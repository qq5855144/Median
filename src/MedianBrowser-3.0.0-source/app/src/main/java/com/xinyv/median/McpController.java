package com.xinyv.median;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 进程级 MCP 运行时（保持轻量、零第三方依赖）。
 *
 * 职责：
 * 1. 持有当前 WebView 与标签集合的只读访问器；
 * 2. 收集 Console 日志与网络请求记录（供 MCP 工具与开发者面板读取）；
 * 3. 管理 MiniHttpServer / McpService 生命周期、稳定端口与持久化 Token；
 * 4. 提供在主线程执行 WebView 操作的阻塞封装（带超时）。
 */
public final class McpController {
    private static final String PREFS = "median_mcp_v1";
    private static final String KEY_TOKEN = "mcp_token";
    private static final String KEY_PORT = "mcp_port";
    private static final String KEY_LAN = "mcp_lan_enabled";
    private static final String KEY_ENABLED = "mcp_enabled";
    private static final String KEY_PROXY = "mcp_proxy_enabled";
    private static final String KEY_LLM_ENDPOINT = "mcp_llm_endpoint";
    private static final String KEY_LLM_MODEL = "mcp_llm_model";
    private static final String KEY_LLM_KEY = "mcp_llm_key";
    private static final String KEY_MCP_SERVERS = "mcp_remote_servers";
    private static final int DEFAULT_PORT = 8788;
    private static final int MAX_CONSOLE = 500;
    private static final int MAX_NETWORK = 400;
    private static final int MAX_RUNLOG = 400;
    /** 界面绑定：MainActivity 在 onResume 挂接、onPause 解绑。 */
    public interface UiBindings {
        WebView currentWebView();
        List<?> liveTabs();          // List<MainActivity.BrowserTab> 只读
        Object dataStore();          // BrowserDataStore（书签/历史）
        boolean isPrivateMode();     // 隐私窗口不暴露页面内容
        void newTab(String url);     // 新建标签并激活
        void closeCurrentTab();      // 关闭当前标签
        void switchTab(int index);   // 切换标签
        void showHome();             // 返回浏览器主页（内部渲染，不走网络）
        int currentTabIndex();       // 当前激活标签索引
        JSONObject settingsSnapshot();   // 浏览器设置快照（夜间/拦截/性能/引擎/UA）
        String applySetting(String key, String value); // 修改设置，null=成功
        void addBookmark(String url, String title);    // 添加书签（已存在则忽略）
        void clearHistory();         // 清空浏览历史
        JSONObject dsppDiagnostics();  // DeepSeek++ 运行时诊断（开关/脚本缓存/内置资产读取）
    }

    private static volatile McpController instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> consoleLogs = Collections.synchronizedList(new ArrayList<JSONObject>());
    private final List<JSONObject> networkLogs = Collections.synchronizedList(new ArrayList<JSONObject>());
    private final List<JSONObject> runLogs = Collections.synchronizedList(new ArrayList<JSONObject>());
    private final Map<String, Long> counters = Collections.synchronizedMap(new LinkedHashMap<String, Long>());

    private volatile UiBindings bindings;
    private volatile MiniHttpServer server;
    private volatile McpService service;
    private volatile MitmProxy mitm;
    private volatile String token;
    private volatile int port = DEFAULT_PORT;
    private volatile String lanHost;
    private volatile android.content.Context appContext;

    private McpController() { }

    public static McpController get() {
        if (instance == null) {
            synchronized (McpController.class) {
                if (instance == null) instance = new McpController();
            }
        }
        return instance;
    }

    // ---------- 生命周期 ----------
    public void attach(UiBindings b) { bindings = b; }
    public void detach() { bindings = null; }
    public boolean hasUi() { return bindings != null; }
    public WebView webView() { return bindings == null ? null : bindings.currentWebView(); }
    public Object dataStore() { return bindings == null ? null : bindings.dataStore(); }
    public boolean privateMode() { return bindings != null && bindings.isPrivateMode(); }
    public List<?> liveTabs() { return bindings == null ? null : bindings.liveTabs(); }
    public int currentTabIndex() { return bindings == null ? -1 : bindings.currentTabIndex(); }
    public void newTab(String url) { if (bindings != null) bindings.newTab(url); }
    public JSONObject dsppDiagnostics() { return bindings == null ? null : bindings.dsppDiagnostics(); }
    public void closeCurrentTab() { if (bindings != null) bindings.closeCurrentTab(); }
    public void switchTab(int index) { if (bindings != null) bindings.switchTab(index); }
    public void showHome() { if (bindings != null) bindings.showHome(); }
    public JSONObject settingsSnapshot() { return bindings == null ? null : bindings.settingsSnapshot(); }
    public String applySetting(final String key, final String value) {
        if (bindings == null) return "mcp not attached";
        final String[] err = new String[1];
        Boolean done = onUi(new BlockingCall<Boolean>() {
            @Override public Boolean run() {
                err[0] = bindings.applySetting(key, value);
                return true;
            }
        }, 5000);
        if (done == null || !done) return "apply setting timeout";
        return err[0];
    }
    public void addBookmark(String url, String title) { if (bindings != null) bindings.addBookmark(url, title); }
    public void clearHistory() { if (bindings != null) bindings.clearHistory(); }
    /** 记录浏览器运行日志（环形缓冲）。level: info/warn/error。 */
    public void recordRunLog(String level, String source, String message) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("ts", System.currentTimeMillis());
            entry.put("level", level == null ? "info" : level);
            entry.put("src", source == null ? "app" : source);
            entry.put("msg", message == null ? "" : message);
        } catch (Exception ignored) { }
        synchronized (runLogs) {
            runLogs.add(entry);
            while (runLogs.size() > MAX_RUNLOG) runLogs.remove(0);
        }
    }
    public List<JSONObject> runLogSnapshot() {
        synchronized (runLogs) { return new ArrayList<JSONObject>(runLogs); }
    }
    public synchronized void start(Context context) {
        Context app = context.getApplicationContext();
        appContext = app;
        SharedPreferences prefs = prefs(app);
        token = prefs.getString(KEY_TOKEN, null);
        if (token == null || token.length() < 24) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            token = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            prefs.edit().putString(KEY_TOKEN, token).apply();
        }
        lanHost = prefs.getBoolean(KEY_LAN, false) ? "0.0.0.0" : "127.0.0.1";
        int preferred = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        if (preferred < 1 || preferred > 65535) preferred = DEFAULT_PORT;
        if (mitm == null) mitm = new MitmProxy(app.getFilesDir());
        mitm.setEnabled(prefs.getBoolean(KEY_PROXY, false));
        if (server != null && service != null && port == preferred) return;
        stopLocked();
        int bound = bind(preferred, app);
        if (bound > 0) {
            port = bound;
            if (bound != preferred) prefs.edit().putInt(KEY_PORT, bound).apply();
            // 自动探测远端 MCP 服务器（MTmcp 等可能后启动）：延迟 3s/6s/6s/6s 重试 4 次。
            final McpService svc = service;
            new Thread(new Runnable() {
                @Override public void run() {
                    for (int attempt = 1; attempt <= 4; attempt++) {
                        try {
                            Thread.sleep(attempt == 1 ? 3000L : 6000L);
                            if (svc != service) return; // 服务已重启/停止，放弃
                            if (svc.autoDiscoverRemote()) return; // 探测成功即停止
                        } catch (Exception ignored) { }
                    }
                }
            }, "mcp-auto-discover").start();
        }
    }

    private int bind(int preferred, Context app) {
        for (int p = preferred; p < preferred + 12; p++) {
            try {
                McpService s = new McpService(this, token);
                final MitmProxy proxy = mitm;
                MiniHttpServer srv = new MiniHttpServer(lanHost, p, s,
                        new MiniHttpServer.ConnectHandler() {
                            @Override public boolean handleConnect(String hostPort, java.net.Socket socket) {
                                return proxy != null && proxy.isEnabled() && proxy.handleConnect(hostPort, socket);
                            }
                        });
                srv.start();
                service = s;
                server = srv;
                recordRunLog("info", "mcp", "MCP server started, port=" + p);
                return p;
            } catch (Exception ignored) {
                // 端口被占用，尝试下一个
            }
        }
        return 0;
    }

    public synchronized void restart(Context context) {
        stopLocked();
        start(context);
    }

    public synchronized void stop() { stopLocked(); }

    private void stopLocked() {
        try { if (server != null) server.stop(); } catch (Exception ignored) { }
        server = null;
        service = null;
    }

    public boolean isRunning() { return server != null && service != null; }
    public int port() { return port; }
    public String token() { return token; }
    public String listenHost() { return lanHost; }
    public android.content.Context context() { return appContext; }

    /** 局域网开关：true=0.0.0.0，false=仅本机。 */
    public void setLanEnabled(Context context, boolean enabled) {
        prefs(context.getApplicationContext()).edit().putBoolean(KEY_LAN, enabled).apply();
        lanHost = enabled ? "0.0.0.0" : "127.0.0.1";
    }
    public boolean lanEnabled(Context context) {
        return prefs(context.getApplicationContext()).getBoolean(KEY_LAN, false);
    }
    public void setEnabled(Context context, boolean enabled) {
        prefs(context.getApplicationContext()).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }
    public boolean enabled(Context context) {
        return prefs(context.getApplicationContext()).getBoolean(KEY_ENABLED, true);
    }
    public void setPort(Context context, int port) {
        prefs(context.getApplicationContext()).edit().putInt(KEY_PORT, port).apply();
    }

    /** 本机 IPv4 地址（优先 Wi-Fi 接口）。 */
    public static String localIp() {
        try {
            List<NetworkInterface> ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface n : ifaces) {
                String name = n.getName() == null ? "" : n.getName().toLowerCase();
                if (!name.startsWith("wlan") && !name.startsWith("wifi")) continue;
                List<Inet4Address> addrs = Collections.list(n.getInetAddresses()).stream()
                        .filter(a -> a instanceof Inet4Address)
                        .map(a -> (Inet4Address) a)
                        .collect(java.util.stream.Collectors.toList());
                for (Inet4Address a : addrs) {
                    if (a.isLoopbackAddress()) continue;
                    String host = a.getHostAddress();
                    if (host != null && !host.startsWith("127.")) return host;
                }
            }
            for (NetworkInterface n : ifaces) {
                List<Inet4Address> addrs = Collections.list(n.getInetAddresses()).stream()
                        .filter(a -> a instanceof Inet4Address)
                        .map(a -> (Inet4Address) a)
                        .collect(java.util.stream.Collectors.toList());
                for (Inet4Address a : addrs) {
                    if (a.isLoopbackAddress()) continue;
                    String host = a.getHostAddress();
                    if (host != null && !host.startsWith("127.")) return host;
                }
            }
        } catch (Exception ignored) { }
        return "127.0.0.1";
    }

    public String endpointUrl(boolean lan) {
        String host = lan ? localIp() : "127.0.0.1";
        return "http://" + host + ":" + port + "/mcp";
    }

    // ---------- 诊断数据 ----------
    public void recordConsole(JSONObject entry) {
        synchronized (consoleLogs) {
            consoleLogs.add(entry);
            while (consoleLogs.size() > MAX_CONSOLE) consoleLogs.remove(0);
        }
        bump("console");
    }
    public List<JSONObject> consoleSnapshot() {
        synchronized (consoleLogs) { return new ArrayList<JSONObject>(consoleLogs); }
    }
    public void recordNetwork(JSONObject entry) {
        synchronized (networkLogs) {
            networkLogs.add(entry);
            while (networkLogs.size() > MAX_NETWORK) networkLogs.remove(0);
        }
        bump("network");
    }
    public List<JSONObject> networkSnapshot() {
        synchronized (networkLogs) { return new ArrayList<JSONObject>(networkLogs); }
    }
    public void clearNetwork() { synchronized (networkLogs) { networkLogs.clear(); } }

    // ==================== MITM 代理（v2） ====================
    /** 开启/关闭 MITM 代理，返回是否成功。 */
    public synchronized boolean proxySet(Context context, boolean on) {
        if (mitm == null) mitm = new MitmProxy(context.getApplicationContext().getFilesDir());
        boolean ok = mitm.ensureCa();
        mitm.setEnabled(on);
        prefs(context.getApplicationContext()).edit().putBoolean(KEY_PROXY, on).apply();
        recordRunLog("info", "proxy", on ? "MITM proxy enabled" : "MITM proxy disabled");
        return ok;
    }
    public MitmProxy proxy() { return mitm; }

    // ==================== 响应体捕获 ====================
    /** 将网络规则 fetch 路径拉到的响应体快照回填到最后一条匹配 url 的网络记录。 */
    public void attachNetworkBody(String url, int status, String mimeType, long size, String bodySnippet) {
        if (url == null) return;
        synchronized (networkLogs) {
            for (int i = networkLogs.size() - 1; i >= 0; i--) {
                JSONObject e = networkLogs.get(i);
                if (url.equals(e.optString("url"))) {
                    try {
                        e.put("status", status);
                        e.put("mimeType", mimeType == null ? "" : mimeType);
                        e.put("size", size);
                        if (bodySnippet != null) e.put("body", bodySnippet);
                    } catch (Exception ignored) { }
                    break;
                }
            }
        }
    }

    // ==================== 外部 LLM 配置（AI 分析增强） ====================
    /** 读取 LLM 配置（apiKey 打码）。 */
    public JSONObject llmConfig(Context context) {
        SharedPreferences p = prefs(context.getApplicationContext());
        JSONObject o = new JSONObject();
        try {
            String key = p.getString(KEY_LLM_KEY, "");
            o.put("enabled", !key.isEmpty());
            o.put("endpoint", p.getString(KEY_LLM_ENDPOINT, "https://api.openai.com/v1/chat/completions"));
            o.put("model", p.getString(KEY_LLM_MODEL, "gpt-4o-mini"));
            o.put("apiKey", key.isEmpty() ? "" : key.substring(0, Math.min(6, key.length())) + "***");
        } catch (Exception ignored) { }
        return o;
    }
    /** 写入 LLM 配置。apiKey 传 "clear" 清除；传 null/空 表示保留原值。 */
    public void llmSet(Context context, String endpoint, String model, String apiKey) {
        SharedPreferences.Editor ed = prefs(context.getApplicationContext()).edit();
        if (endpoint != null && !endpoint.trim().isEmpty()) ed.putString(KEY_LLM_ENDPOINT, endpoint.trim());
        if (model != null && !model.trim().isEmpty()) ed.putString(KEY_LLM_MODEL, model.trim());
        if (apiKey != null) {
            String k = apiKey.trim();
            if ("clear".equals(k)) ed.remove(KEY_LLM_KEY);
            else if (!k.isEmpty()) ed.putString(KEY_LLM_KEY, k);
        }
        ed.apply();
    }
    public void llmClearKey(Context context) {
        prefs(context.getApplicationContext()).edit().remove(KEY_LLM_KEY).apply();
    }
    // ==================== 远端 MCP 服务器配置（多 MCP 支持） ====================
    /** 读取全部远端 MCP 服务器配置（JSON 数组：name/url/token/enabled）。 */
    public JSONArray remoteMcpList(Context context) throws Exception {
        SharedPreferences p = prefs(context.getApplicationContext());
        String raw = p.getString(KEY_MCP_SERVERS, "");
        JSONArray arr = new JSONArray();
        if (!raw.isEmpty()) {
            try { arr = new JSONArray(raw); } catch (Exception ignored) { }
        }
        return arr;
    }
    /** 添加远端 MCP 服务器。url 支持 http://192.168.x.x:port 或 http://192.168.x.x:port/mcp。 */
    public JSONObject remoteMcpAdd(Context context, String name, String url, String token) throws Exception {
        String n = name == null ? "" : name.trim();
        String u = url == null ? "" : url.trim();
        if (n.isEmpty()) return new JSONObject().put("error", "name required");
        if (u.isEmpty()) return new JSONObject().put("error", "url required");
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        JSONArray arr = remoteMcpList(context);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s != null && n.equals(s.optString("name"))) {
                return new JSONObject().put("error", "server already exists: " + n);
            }
        }
        JSONObject s = new JSONObject();
        s.put("name", n).put("url", u)
                .put("token", token == null ? "" : token.trim())
                .put("enabled", true);
        arr.put(s);
        prefs(context.getApplicationContext()).edit().putString(KEY_MCP_SERVERS, arr.toString()).apply();
        return new JSONObject().put("ok", true).put("servers", remoteMcpList(context));
    }
    /** 删除远端 MCP 服务器。 */
    public JSONObject remoteMcpRemove(Context context, String name) throws Exception {
        JSONArray arr = remoteMcpList(context);
        JSONArray out = new JSONArray();
        boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s != null && name != null && name.equals(s.optString("name"))) { found = true; continue; }
            if (s != null) out.put(s);
        }
        if (!found) return new JSONObject().put("error", "server not found: " + name);
        prefs(context.getApplicationContext()).edit().putString(KEY_MCP_SERVERS, out.toString()).apply();
        return new JSONObject().put("ok", true).put("servers", remoteMcpList(context));
    }
    /** 更新远端 MCP 服务器（url/token/enabled，传 null 表示不变）。 */
    public JSONObject remoteMcpUpdate(Context context, String name, String url, String token, Boolean enabled) throws Exception {
        JSONArray arr = remoteMcpList(context);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s != null && name != null && name.equals(s.optString("name"))) {
                if (url != null && !url.trim().isEmpty()) {
                    String u = url.trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
                    while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                    s.put("url", u);
                }
                if (token != null) s.put("token", token.trim());
                if (enabled != null) s.put("enabled", enabled.booleanValue());
                prefs(context.getApplicationContext()).edit().putString(KEY_MCP_SERVERS, arr.toString()).apply();
                return new JSONObject().put("ok", true).put("servers", remoteMcpList(context));
            }
        }
        return new JSONObject().put("error", "server not found: " + name);
    }
    /** UI 面板探测入口：探测指定远端 MCP 服务器并返回 JSON 字符串结果。MCP 服务未运行时自动启动。 */
    public String remoteDiscoverForUi(String serverName) {
        if (service == null && appContext != null) {
            try { start(appContext); } catch (Exception ignored) { }
        }
        if (service == null) return "{\"error\":\"MCP service not running\"}";
        return service.discoverForUi(serverName);
    }

    // ==================== 网络规则引擎（block / redirect / inject / replace） ====================
    /** 网络拦截/重写/注入规则。 */
    public static final class NetRule {
        public final String id;
        public final String type;      // block | redirect | inject | replace
        public final String pattern;   // URL 子串匹配（不区分大小写）
        public final String match;     // replace: 响应体中的被替换文本（null=用 pattern）
        public final String target;    // redirect: 目标URL; inject: 注入HTML; replace: 替换后的文本
        public final boolean enabled;
        public volatile long hits;
        public final long createdAt;
        NetRule(String id, String type, String pattern, String match, String target, boolean enabled) {
            this.id = id; this.type = type; this.pattern = pattern;
            this.match = match; this.target = target; this.enabled = enabled;
            this.hits = 0L; this.createdAt = System.currentTimeMillis();
        }
    }
    private final List<NetRule> netRules = Collections.synchronizedList(new ArrayList<NetRule>());
    private long netRuleSeq = 0;

    /** 添加网络规则，返回规则 JSON（含 id）。type ∈ block|redirect|inject|replace。 */
    public JSONObject addNetRule(String type, String pattern, String match, String target, boolean enabled) {
        String t = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        if (!("block".equals(t) || "redirect".equals(t) || "inject".equals(t) || "replace".equals(t)))
            return null;
        if (pattern == null || pattern.trim().isEmpty()) return null;
        if (("redirect".equals(t) || "inject".equals(t) || "replace".equals(t)) && target == null)
            return null;
        String matchTrim = (match == null || match.trim().isEmpty()) ? null : match;
        String id;
        synchronized (netRules) {
            netRuleSeq++;
            id = "r" + netRuleSeq + "_" + Long.toHexString(System.currentTimeMillis() & 0xffffff);
            NetRule rule = new NetRule(id, t, pattern.trim(), matchTrim, target == null ? "" : target, enabled);
            netRules.add(rule);
            bump("netrule");
        }
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("type", t).put("pattern", pattern.trim())
             .put("match", matchTrim == null ? "" : matchTrim)
             .put("target", target == null ? "" : target).put("enabled", enabled).put("hits", 0L);
        } catch (Exception ignored) {}
        return o;
    }

    /** 按 id 删除规则，返回是否删除成功。 */
    public boolean removeNetRule(String id) {
        if (id == null) return false;
        synchronized (netRules) {
            java.util.Iterator<NetRule> it = netRules.iterator();
            while (it.hasNext()) { if (id.equals(it.next().id)) { it.remove(); return true; } }
        }
        return false;
    }

    /** 清空全部规则，返回清除条数。 */
    public int clearNetRules() {
        synchronized (netRules) {
            int n = netRules.size();
            netRules.clear();
            return n;
        }
    }

    /** 规则快照（JSONArray：id/type/pattern/target/enabled/hits）。 */
    public org.json.JSONArray netRuleSnapshot() {
        org.json.JSONArray arr = new org.json.JSONArray();
        synchronized (netRules) {
            for (NetRule r : netRules) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", r.id).put("type", r.type).put("pattern", r.pattern)
                     .put("match", r.match == null ? "" : r.match)
                     .put("target", r.target).put("enabled", r.enabled).put("hits", r.hits);
                } catch (Exception ignored) {}
                arr.put(o);
            }
        }
        return arr;
    }

    /** 匹配所有启用的规则（URL 子串，不区分大小写），按添加顺序返回；无命中返回空列表。
     *  多条规则可同时命中：block 优先拦截，redirect 独立重定向，inject/replace 可叠加变换。 */
    public java.util.List<NetRule> matchNetRules(String url) {
        if (url == null) return java.util.Collections.emptyList();
        String u = url.toLowerCase(java.util.Locale.ROOT);
        java.util.ArrayList<NetRule> out = new java.util.ArrayList<NetRule>();
        synchronized (netRules) {
            for (NetRule r : netRules) {
                if (r.enabled && r.pattern != null && !r.pattern.isEmpty() &&
                        u.contains(r.pattern.toLowerCase(java.util.Locale.ROOT))) out.add(r);
            }
        }
        return out;
    }

    // ==================== 持久 JS 钩子（页面加载后自动注入） ====================
    public static final class JsHook {
        public final String id;
        public final String script;
        public final boolean enabled;
        public volatile long hits;
        public final long createdAt;
        JsHook(String id, String script, boolean enabled) {
            this.id = id; this.script = script; this.enabled = enabled;
            this.hits = 0L; this.createdAt = System.currentTimeMillis();
        }
    }
    private final List<JsHook> jsHooks = Collections.synchronizedList(new ArrayList<JsHook>());
    private long hookSeq = 0;

    /** 添加 JS 钩子，返回规则 JSON（含 id）。 */
    public JSONObject addJsHook(String script, boolean enabled) {
        if (script == null || script.trim().isEmpty()) return null;
        String id;
        synchronized (jsHooks) {
            hookSeq++;
            id = "h" + hookSeq + "_" + Long.toHexString(System.currentTimeMillis() & 0xffffff);
            jsHooks.add(new JsHook(id, script, enabled));
            bump("hook");
        }
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("enabled", enabled).put("hits", 0L).put("script", script.trim());
        } catch (Exception ignored) {}
        return o;
    }

    public boolean removeJsHook(String id) {
        if (id == null) return false;
        synchronized (jsHooks) {
            java.util.Iterator<JsHook> it = jsHooks.iterator();
            while (it.hasNext()) { if (id.equals(it.next().id)) { it.remove(); return true; } }
        }
        return false;
    }

    public int clearJsHooks() {
        synchronized (jsHooks) {
            int n = jsHooks.size();
            jsHooks.clear();
            return n;
        }
    }

    public org.json.JSONArray jsHookSnapshot() {
        org.json.JSONArray arr = new org.json.JSONArray();
        synchronized (jsHooks) {
            for (JsHook h : jsHooks) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", h.id).put("enabled", h.enabled).put("hits", h.hits)
                     .put("script", h.script.length() > 200 ? h.script.substring(0, 200) + "..." : h.script);
                } catch (Exception ignored) {}
                arr.put(o);
            }
        }
        return arr;
    }

    /** 拼接全部启用的钩子脚本为一段（页面加载后注入）。 */
    public String activeHookScript() {
        StringBuilder sb = new StringBuilder();
        synchronized (jsHooks) {
            for (JsHook h : jsHooks) {
                if (h.enabled) {
                    sb.append("(function(){try{").append(h.script)
                      .append(";window.__mcpHook__hits=(window.__mcpHook__hits||0)+1;}catch(e){}})();");
                }
            }
        }
        return sb.toString();
    }

    // ==================== 指纹伪装（off/light/full） ====================
    private volatile String fingerprintLevel = "off";

    public void setFingerprintLevel(String level) {
        String l = level == null ? "off" : level.trim().toLowerCase(java.util.Locale.ROOT);
        if (!("off".equals(l) || "light".equals(l) || "full".equals(l))) l = "off";
        fingerprintLevel = l;
    }

    public String fingerprintLevel() { return fingerprintLevel; }

    /** 生成指纹伪装脚本（页面加载后注入）。 */
    public String fingerprintScript() {
        String level = fingerprintLevel;
        if ("off".equals(level)) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){try{");
        if ("light".equals(level) || "full".equals(level)) {
            sb.append("Object.defineProperty(navigator,'hardwareConcurrency',{get:function(){return 8;},configurable:true});");
            sb.append("Object.defineProperty(navigator,'deviceMemory',{get:function(){return 8;},configurable:true});");
            sb.append("Object.defineProperty(navigator,'platform',{get:function(){return 'MacIntel';},configurable:true});");
            sb.append("Object.defineProperty(navigator,'maxTouchPoints',{get:function(){return 1;},configurable:true});");
        }
        if ("full".equals(level)) {
            sb.append("Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});");
            sb.append("Object.defineProperty(navigator,'languages',{get:function(){return ['zh-CN','zh','en-US'];},configurable:true});");
            sb.append("Object.defineProperty(navigator,'language',{get:function(){return 'zh-CN';},configurable:true});");
            sb.append("(function(){var _oc=HTMLCanvasElement.prototype.toDataURL;HTMLCanvasElement.prototype.toDataURL=function(){try{var r=_oc.apply(this,arguments);var c=document.createElement('canvas');c.width=2;c.height=2;var x=c.getContext('2d');x.fillStyle='rgb(1,2,3)';x.fillRect(0,0,2,2);var noise=x.getImageData(0,0,2,2).data;if(noise[0]%2===0){}return r;}catch(e){return _oc.apply(this,arguments);}};})();");
            sb.append("(function(){var _gl=WebGLRenderingContext.prototype.getParameter;WebGLRenderingContext.prototype.getParameter=function(p){try{if(p===37445)return 'Intel Inc.';if(p===37446)return 'Intel(R) UHD Graphics 630';}catch(e){}return _gl.apply(this,arguments);};})();");
        }
        sb.append("window.__mcpFp=(window.__mcpFp||0)+1;") ;
        sb.append("}catch(e){}})();");
        return sb.toString();
    }
    public void clearConsole() { synchronized (consoleLogs) { consoleLogs.clear(); } }
    public void bump(String key) {
        synchronized (counters) {
            Long v = counters.get(key);
            counters.put(key, v == null ? 1L : v + 1L);
        }
    }
    public long counter(String key) {
        synchronized (counters) { Long v = counters.get(key); return v == null ? 0L : v; }
    }

    // ---------- 主线程阻塞执行 ----------
    public interface BlockingCall<T> { T run() throws Exception; }

    /** 在主线程执行并等待结果（默认 5s 超时），超时返回 null。 */
    public <T> T onUi(BlockingCall<T> call, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return call.run(); } catch (Exception e) { return null; }
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] holder = new Object[1];
        final Throwable[] error = new Throwable[1];
        main.post(new Runnable() {
            @Override public void run() {
                try { holder[0] = call.run(); }
                catch (Throwable t) { error[0] = t; }
                finally { latch.countDown(); }
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (error[0] != null) return null;
        @SuppressWarnings("unchecked")
        T result = (T) holder[0];
        return result;
    }

    // ---------- WebView 辅助（主线程执行） ----------
    /** evaluateJavascript 同步版：返回 JS 求值结果字符串。 */
    public String evalJs(WebView wv, String script, long timeoutMs) {
        if (wv == null) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] holder = new Object[1];
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    wv.evaluateJavascript(script, new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            holder[0] = value;
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return holder[0] == null ? null : String.valueOf(holder[0]);
    }

    /** 截图：主线程 draw 到 Bitmap，返回 JPEG base64。 */
    public String screenshot(WebView wv, int quality) {
        if (wv == null) return null;
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    int w = wv.getWidth();
                    int h = wv.getHeight();
                    if (w <= 0 || h <= 0) { latch.countDown(); return; }
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    wv.draw(new Canvas(bmp));
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, Math.max(10, Math.min(100, quality)), out);
                    holder[0] = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    bmp.recycle();
                } catch (Throwable t) { }
                latch.countDown();
            }
        });
        try {
            if (!latch.await(6000, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return holder[0] == null ? null : String.valueOf(holder[0]);
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** JSON 工具（供 McpService 使用）。 */
    static JSONObject obj() { return new JSONObject(); }
    static JSONArray arr() { return new JSONArray(); }
}
