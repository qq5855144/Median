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
        int currentTabIndex();       // 当前激活标签索引
        JSONObject settingsSnapshot();   // 浏览器设置快照（夜间/拦截/性能/引擎/UA）
        String applySetting(String key, String value); // 修改设置，null=成功
        void addBookmark(String url, String title);    // 添加书签（已存在则忽略）
        void clearHistory();         // 清空浏览历史
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
    private volatile String token;
    private volatile int port = DEFAULT_PORT;
    private volatile String lanHost;

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
    public void closeCurrentTab() { if (bindings != null) bindings.closeCurrentTab(); }
    public void switchTab(int index) { if (bindings != null) bindings.switchTab(index); }
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
        if (server != null && service != null && port == preferred) return;
        stopLocked();
        int bound = bind(preferred, app);
        if (bound > 0) {
            port = bound;
            if (bound != preferred) prefs.edit().putInt(KEY_PORT, bound).apply();
        }
    }

    private int bind(int preferred, Context app) {
        for (int p = preferred; p < preferred + 12; p++) {
            try {
                McpService s = new McpService(this, token);
                MiniHttpServer srv = new MiniHttpServer(lanHost, p, s);
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
