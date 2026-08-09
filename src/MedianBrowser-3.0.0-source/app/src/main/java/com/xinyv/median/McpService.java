package com.xinyv.median;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP（Model Context Protocol）服务：JSON-RPC over Streamable HTTP。
 *
 * - 路由：POST /mcp（根 / 兼容别名）、/api/browser_*（HTTP Bridge）、/health；
 * - 协议：2025-06-18 initialize / tools/list / tools/call / ping，
 *   并兼容 2026-07-28 无状态 server/discover / server/tools/call；
 * - 认证：Authorization: Bearer <token> 或 X-Median-Token；
 * - 工具：24 个 browser_*（打开/导航/点击/输入/截图/取 DOM/Console/Network/性能/标签/历史/书签等）。
 */
public final class McpService implements MiniHttpServer.Handler {
    private static final String SERVER_NAME = "Median Browser";
    private static final String VERSION = "3.0.0-mcp";
    private static final String PROTOCOL = "2025-06-18";
    private static final String[] SUPPORTED_PROTOCOLS = {
            "2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"
    };

    private final McpController ctl;
    private final String token;

    public McpService(McpController controller, String token) {
        this.ctl = controller;
        this.token = token;
    }

    // ==================== HTTP 入口 ====================

    @Override
    public MiniHttpServer.Response handle(String method, String path, Map<String, String> headers, byte[] body, String remoteHost) {
        String cleanPath = path;
        int q = cleanPath.indexOf('?');
        if (q >= 0) cleanPath = cleanPath.substring(0, q);
        if ("/health".equals(cleanPath)) {
            return MiniHttpServer.Response.ok("{\"ok\":true,\"name\":\"" + SERVER_NAME + "\",\"version\":\"" + VERSION + "\"}");
        }
        if (!authorized(headers, remoteHost)) {
            return MiniHttpServer.Response.unauthorized();
        }
        if ("/api/".equals(cleanPath) || cleanPath.startsWith("/api/")) {
            // 传原始 path（含 query），由 handleBridgeInner 解析 query 参数
            return handleBridge(method, path, body);
        }
        if ("/mcp".equals(cleanPath) || "/".equals(cleanPath)) {
            return handleMcp(method, headers, body);
        }
        return MiniHttpServer.Response.error(404, "not found: " + cleanPath);
    }

    /**
     * 认证策略（三选一即可完整调用）：
     * 1. 本机地址（127.0.0.1 / ::1 回环）访问 → 免 Token；
     * 2. 局域网/远程地址访问 → 必须携带有效 Token；
     * 3. 携带有效 Token（Authorization: Bearer 或 X-Median-Token）→ 任意地址均可。
     */
    private boolean authorized(Map<String, String> headers, String remoteHost) {
        if (isLoopback(remoteHost)) return true;
        if (token == null || token.isEmpty()) return false;
        String authorization = headers.get("authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return constantTimeEquals(token, authorization.substring(7).trim());
        }
        String direct = headers.get("x-median-token");
        return direct != null && constantTimeEquals(token, direct.trim());
    }

    /** 判断客户端 IP 是否为回环地址（本机访问免认证）。 */
    private static boolean isLoopback(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.trim();
        if ("localhost".equalsIgnoreCase(h)) return true;
        if (h.startsWith("127.")) return true;
        if ("::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h)) return true;
        return h.startsWith("0:0:0:0:0:0:0:1");
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }

    // ==================== MCP JSON-RPC ====================

    private MiniHttpServer.Response handleMcp(String method, Map<String, String> headers, byte[] body) {
        if ("GET".equalsIgnoreCase(method)) {
            return MiniHttpServer.Response.ok("{\"name\":\"" + SERVER_NAME + "\",\"version\":\"" + VERSION
                    + "\",\"protocol\":\"" + PROTOCOL + "\",\"transport\":\"streamable-http\"}");
        }
        if (!"POST".equalsIgnoreCase(method)) {
            return MiniHttpServer.Response.error(405, "method not allowed");
        }
        JSONObject request;
        try {
            request = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return MiniHttpServer.Response.error(400, "invalid JSON-RPC body");
        }
        String id = request.has("id") ? String.valueOf(request.opt("id")) : null;
        String rpcMethod = request.optString("method", "");
        JSONObject params = request.optJSONObject("params");
        if (params == null) params = new JSONObject();

        try {
            if ("initialize".equals(rpcMethod)) {
                return respond(id, initializeResult(params));
            }
            if ("notifications/initialized".equals(rpcMethod) || "initialized".equals(rpcMethod)) {
                return MiniHttpServer.Response.ok("{}");
            }
            if ("ping".equals(rpcMethod)) {
                return respond(id, new JSONObject().put("_meta", serverMeta()));
            }
            if ("server/discover".equals(rpcMethod)) {
                return respond(id, discoverResult());
            }
            if ("tools/list".equals(rpcMethod)) {
                return respond(id, new JSONObject().put("tools", toolDefinitions()).put("_meta", serverMeta()));
            }
            if ("server/tools/list".equals(rpcMethod)) {
                return respond(id, new JSONObject().put("tools", toolDefinitions()).put("_meta", serverMeta()));
            }
            if ("tools/call".equals(rpcMethod) || "server/tools/call".equals(rpcMethod)) {
                return respond(id, callToolResult(params));
            }
            return respondError(id, -32601, "method not found: " + rpcMethod);
        } catch (Exception e) {
            JSONObject errObj = new JSONObject();
            JSONObject outObj = new JSONObject();
            try {
                errObj.put("code", -32603).put("message", "internal error: " + e.getMessage());
                outObj.put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id).put("error", errObj);
            } catch (Exception ignored) { }
            return MiniHttpServer.Response.ok(outObj.toString());
        }
    }

    private JSONObject serverMeta() throws Exception {
        return new JSONObject().put("server", SERVER_NAME).put("version", VERSION);
    }

    private MiniHttpServer.Response respond(String id, JSONObject result) throws Exception {
        JSONObject out = new JSONObject().put("jsonrpc", "2.0")
                .put("id", id == null ? JSONObject.NULL : id)
                .put("result", result);
        return MiniHttpServer.Response.ok(out.toString());
    }

    private MiniHttpServer.Response respondError(String id, int code, String message) throws Exception {
        JSONObject error = new JSONObject().put("code", code).put("message", message);
        JSONObject out = new JSONObject().put("jsonrpc", "2.0")
                .put("id", id == null ? JSONObject.NULL : id)
                .put("error", error);
        return MiniHttpServer.Response.ok(out.toString());
    }

    private JSONObject initializeResult(JSONObject params) throws Exception {
        String requested = params.optString("protocolVersion", PROTOCOL);
        String chosen = PROTOCOL;
        for (String supported : SUPPORTED_PROTOCOLS) {
            if (supported.equals(requested)) { chosen = requested; break; }
        }
        JSONObject capabilities = new JSONObject()
                .put("tools", new JSONObject().put("listChanged", false))
                .put("logging", new JSONObject());
        JSONObject serverInfo = new JSONObject()
                .put("name", SERVER_NAME)
                .put("version", VERSION);
        return new JSONObject()
                .put("protocolVersion", chosen)
                .put("capabilities", capabilities)
                .put("serverInfo", serverInfo)
                .put("instructions", "Median Browser MCP：用 browser_open 打开网页，browser_screenshot 查看页面，" +
                        "browser_interactive 获取可点击元素，browser_click_at / browser_type 操作页面，" +
                        "browser_dom / browser_text 读取内容，browser_console / browser_network 诊断。")
                .put("_meta", serverMeta());
    }

    private JSONObject discoverResult() throws Exception {
        return new JSONObject()
                .put("protocolVersion", PROTOCOL)
                .put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", false)))
                .put("serverInfo", new JSONObject().put("name", SERVER_NAME).put("version", VERSION))
                .put("tools", toolDefinitions())
                .put("resultType", "tools")
                .put("_meta", serverMeta());
    }

    // ==================== 工具定义 ====================

    private static JSONObject tool(String name, String description, JSONObject schema) throws Exception {
        return new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", schema);
    }

    private static JSONObject schema(JSONObject properties, String[] required) throws Exception {
        JSONObject s = new JSONObject().put("type", "object")
                .put("properties", properties == null ? new JSONObject() : properties);
        if (required != null && required.length > 0) {
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            s.put("required", req);
        }
        return s;
    }

    private static JSONObject prop(String type, String description) throws Exception {
        return new JSONObject().put("type", type).put("description", description);
    }

    private JSONArray toolDefinitions() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("browser_open", "在浏览器中打开指定 URL（当前标签页导航）", schema(
                new JSONObject().put("url", prop("string", "要打开的网址")), new String[]{"url"})));
        tools.put(tool("browser_nav", "导航到指定 URL（browser_open 的别名）", schema(
                new JSONObject().put("url", prop("string", "目标网址")), new String[]{"url"})));
        tools.put(tool("browser_state", "获取浏览器与当前页面状态（URL、标题、加载进度、标签数、MCP 端口）", schema(null, null)));
        tools.put(tool("browser_eval", "在当前页面执行 JavaScript 并返回结果", schema(
                new JSONObject().put("expression", prop("string", "要执行的 JS 表达式或语句")), new String[]{"expression"})));
        tools.put(tool("browser_dom", "提取当前页面结构化内容（标题、URL、正文文本、链接、可交互元素）", schema(null, null)));
        tools.put(tool("browser_text", "提取当前页面可见文本", schema(null, null)));
        tools.put(tool("browser_links", "列出当前页面全部链接（href + 文本）", schema(null, null)));
        tools.put(tool("browser_source", "获取当前页面 HTML 源码（限制长度）", schema(
                new JSONObject().put("maxLen", prop("number", "最大字符数，默认 20000")), null)));
        tools.put(tool("browser_screenshot", "截取当前页面可见区域，返回 JPEG base64", schema(
                new JSONObject().put("quality", prop("number", "JPEG 质量 10-100，默认 80")), null)));
        tools.put(tool("browser_click", "按 CSS 选择器点击页面元素", schema(
                new JSONObject().put("selector", prop("string", "CSS 选择器")), new String[]{"selector"})));
        tools.put(tool("browser_click_at", "按屏幕坐标点击（x, y 为像素，可用 browser_interactive 获取）", schema(
                new JSONObject().put("x", prop("number", "横坐标")).put("y", prop("number", "纵坐标")),
                new String[]{"x", "y"})));
        tools.put(tool("browser_type", "向输入框输入文本（先聚焦再填值，触发 input/change 事件）", schema(
                new JSONObject().put("selector", prop("string", "CSS 选择器")).put("text", prop("string", "要输入的文本")),
                new String[]{"selector", "text"})));
        tools.put(tool("browser_keyboard", "模拟按键（Enter/Backspace/Tab/Escape 或普通字符）", schema(
                new JSONObject().put("keys", prop("string", "按键序列，如 Enter、Tab")), new String[]{"keys"})));
        tools.put(tool("browser_tabs", "列出全部标签页（标题、URL、是否激活）", schema(null, null)));
        tools.put(tool("browser_new_tab", "新建标签页并激活", schema(
                new JSONObject().put("url", prop("string", "新标签打开的网址，默认主页")), null)));
        tools.put(tool("browser_close", "关闭当前标签页", schema(null, null)));
        tools.put(tool("browser_console", "读取页面 Console 日志（error/warn/log 等）", schema(null, null)));
        tools.put(tool("browser_network", "读取页面网络请求记录（URL、类型、主框架、时间）与资源性能条目", schema(null, null)));
        tools.put(tool("browser_perf", "读取页面性能指标（FCP、DOM 大小、资源数量等）", schema(null, null)));
        tools.put(tool("browser_report", "生成综合诊断报告（页面、Console、Network、性能汇总）", schema(null, null)));
        tools.put(tool("browser_history", "读取浏览历史", schema(null, null)));
        tools.put(tool("browser_bookmarks", "读取书签", schema(null, null)));
        tools.put(tool("browser_http", "从服务端发起 HTTP GET 请求（带大小限制）", schema(
                new JSONObject().put("url", prop("string", "目标 URL（http/https）")).put("maxBytes", prop("number", "响应最大字节数，默认 65536")),
                new String[]{"url"})));
        tools.put(tool("browser_clear", "清空 Console 与 Network 记录", schema(null, null)));
        return tools;
    }

    // ==================== 工具分发 ====================

    /** tools/call：包装为标准 MCP 结果（content 数组）。截图时附 image 内容块。 */
    private JSONObject callToolResult(JSONObject params) throws Exception {
        String name = params.optString("name", "");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        JSONObject toolResult = callTool(name, args);
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        String screenshot = toolResult.optString("_screenshot", "");
        if (!screenshot.isEmpty()) {
            toolResult.remove("_screenshot");
            content.put(new JSONObject()
                    .put("type", "image")
                    .put("mimeType", toolResult.optString("mimeType", "image/jpeg"))
                    .put("data", screenshot));
        }
        content.put(new JSONObject().put("type", "text").put("text", toolResult.toString()));
        result.put("content", content);
        result.put("isError", toolResult.has("error"));
        return result;
    }

    private JSONObject callTool(String name, JSONObject args) {
        if (name == null || name.isEmpty()) return error("tool name required");
        try {
            if ("browser_open".equals(name) || "browser_nav".equals(name)) return open(args);
            if ("browser_state".equals(name)) return state();
            if ("browser_eval".equals(name)) return eval(args);
            if ("browser_dom".equals(name)) return dom();
            if ("browser_text".equals(name)) return text();
            if ("browser_links".equals(name)) return links();
            if ("browser_source".equals(name)) return source(args);
            if ("browser_screenshot".equals(name)) return screenshot(args);
            if ("browser_click".equals(name)) return click(args);
            if ("browser_click_at".equals(name)) return clickAt(args);
            if ("browser_type".equals(name)) return type(args);
            if ("browser_keyboard".equals(name)) return keyboard(args);
            if ("browser_tabs".equals(name)) return tabs();
            if ("browser_new_tab".equals(name)) return newTab(args);
            if ("browser_close".equals(name)) return closeTab();
            if ("browser_console".equals(name)) return console(args);
            if ("browser_network".equals(name)) return network();
            if ("browser_perf".equals(name)) return perf();
            if ("browser_report".equals(name)) return report();
            if ("browser_history".equals(name)) return history(args);
            if ("browser_bookmarks".equals(name)) return bookmarks(args);
            if ("browser_http".equals(name)) return httpRequest(args);
            if ("browser_clear".equals(name)) return clear();
            // 隐藏别名（兼容 BrowserDiag 习惯，坐标由 browser_click_at 使用）
            if ("browser_interactive".equals(name)) return interactive();
            return error("unknown tool: " + name);
        } catch (Exception e) {
            return error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try { o.put("error", message); } catch (Exception ignored) { }
        return o;
    }
    private static JSONObject obj() { return new JSONObject(); }
    private static JSONArray arr() { return new JSONArray(); }

    // ==================== 页面与导航 ====================

    /** 打开 URL 并等待加载，返回加载后的页面状态。 */
    private JSONObject open(JSONObject args) throws Exception {
        String url = args.optString("url", "").trim();
        if (!isHttpUrl(url)) return error("valid http(s) url required");
        final WebView wv = requireWebView();
        Boolean loaded = ctl.onUi(new McpController.BlockingCall<Boolean>() {
            @Override public Boolean run() {
                wv.loadUrl(url);
                return true;
            }
        }, 3000);
        if (loaded == null || !loaded) return error("webview not ready");
        long waitMs = args.optLong("waitMs", 2500);
        try { Thread.sleep(Math.max(500, Math.min(waitMs, 8000))); } catch (InterruptedException ignored) { }
        return state();
    }

    private JSONObject state() throws Exception {
        final WebView wv = requireWebView();
        JSONObject page = ctl.onUi(new McpController.BlockingCall<JSONObject>() {
            @Override public JSONObject run() throws Exception {
                JSONObject p = new JSONObject();
                p.put("url", wv.getUrl() == null ? "" : wv.getUrl());
                p.put("title", wv.getTitle() == null ? "" : wv.getTitle());
                p.put("progress", wv.getProgress());
                return p;
            }
        }, 3000);
        if (page == null) page = new JSONObject();
        String raw = ctl.evalJs(wv, PAGE_STATE_JS, 3000);
        Object decoded = decodeEvalValue(raw);
        if (decoded instanceof JSONObject) {
            JSONObject js = (JSONObject) decoded;
            page.put("documentReady", js.optString("ready", ""));
            page.put("bodyPreview", js.optString("body", ""));
        }
        List<JSONObject> logs = ctl.consoleSnapshot();
        int errs = 0;
        for (JSONObject l : logs) if ("error".equals(l.optString("type"))) errs++;
        page.put("consoleCount", logs.size());
        page.put("consoleErrors", errs);
        page.put("mcp", new JSONObject()
                .put("port", ctl.port())
                .put("token", ctl.token())
                .put("endpoint", ctl.endpointUrl(false))
                .put("lanEndpoint", ctl.endpointUrl(true)));
        page.put("tabCount", tabCount());
        return page;
    }

    private JSONObject eval(JSONObject args) throws Exception {
        String expression = args.optString("expression", "");
        if (expression.isEmpty()) return error("expression required");
        final WebView wv = requireWebView();
        String raw = ctl.evalJs(wv, expression, 8000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object value = decodeEvalValue(raw);
        return new JSONObject().put("result", value == null ? JSONObject.NULL : value);
    }

    // ==================== DOM / 文本 / 链接 / 源码 ====================

    private JSONObject dom() throws Exception {
        final WebView wv = requireWebView();
        String raw = ctl.evalJs(wv, PAGE_DOM_JS, 6000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected dom result");
        return (JSONObject) decoded;
    }

    private JSONObject text() throws Exception {
        final WebView wv = requireWebView();
        String raw = ctl.evalJs(wv, PAGE_TEXT_JS, 6000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected text result");
        return (JSONObject) decoded;
    }

    private JSONObject links() throws Exception {
        final WebView wv = requireWebView();
        String raw = ctl.evalJs(wv, PAGE_LINKS_JS, 6000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected links result");
        return (JSONObject) decoded;
    }

    private JSONObject source(JSONObject args) throws Exception {
        final WebView wv = requireWebView();
        int maxLen = args.optInt("maxLen", 20000);
        final int cap = Math.max(1000, Math.min(maxLen, 200000));
        String script = "(function(){var h=document.documentElement?document.documentElement.outerHTML:'';"
                + "return JSON.stringify({url:location.href,htmlLength:h.length,truncated:h.length>" + cap
                + ",html:h.slice(0," + cap + ")})})()";
        String raw = ctl.evalJs(wv, script, 8000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected source result");
        return (JSONObject) decoded;
    }

    private JSONObject screenshot(JSONObject args) throws Exception {
        final WebView wv = requireWebView();
        int quality = args.optInt("quality", 80);
        String b64 = ctl.screenshot(wv, quality);
        if (b64 == null) return error("screenshot failed");
        return new JSONObject()
                .put("mimeType", "image/jpeg")
                .put("bytes", (long) (b64.length() * 3L / 4L))
                .put("_screenshot", b64);
    }

    // ==================== AI 操作：点击 / 输入 / 键盘 ====================

    /** 按 CSS 选择器点击：JS 定位元素中心坐标，再派发真实触摸事件。 */
    private JSONObject click(JSONObject args) throws Exception {
        String selector = args.optString("selector", "").trim();
        if (selector.isEmpty()) return error("selector required");
        final WebView wv = requireWebView();
        String script = "(function(){var el=document.querySelector(" + jsonString(selector) + ");"
                + "if(!el)return JSON.stringify({ok:false,error:'element not found'});"
                + "el.scrollIntoView({block:'center'});"
                + "var r=el.getBoundingClientRect();if(r.width<=0||r.height<=0)return JSON.stringify({ok:false,error:'element not visible'});"
                + "return JSON.stringify({ok:true,x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2),"
                + "tag:el.tagName.toLowerCase(),text:(el.innerText||el.value||'').trim().slice(0,80)})})()";
        String raw = ctl.evalJs(wv, script, 5000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected click result");
        JSONObject info = (JSONObject) decoded;
        if (!info.optBoolean("ok", false)) return error(info.optString("error", "click failed"));
        return tapAt(wv, info.optInt("x", 0), info.optInt("y", 0), info);
    }

    /** 按屏幕坐标点击（像素）。 */
    private JSONObject clickAt(JSONObject args) throws Exception {
        final WebView wv = requireWebView();
        int x = args.optInt("x", -1);
        int y = args.optInt("y", -1);
        if (x < 0 || y < 0) return error("x/y required");
        return tapAt(wv, x, y, null);
    }

    private JSONObject tapAt(final WebView wv, final int x, final int y, final JSONObject info) throws Exception {
        Boolean done = ctl.onUi(new McpController.BlockingCall<Boolean>() {
            @Override public Boolean run() {
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
                wv.dispatchTouchEvent(down);
                down.recycle();
                try { Thread.sleep(60); } catch (InterruptedException ignored) { }
                long now2 = SystemClock.uptimeMillis();
                MotionEvent up = MotionEvent.obtain(now2, now2, MotionEvent.ACTION_UP, x, y, 0);
                wv.dispatchTouchEvent(up);
                up.recycle();
                return true;
            }
        }, 3000);
        if (done == null || !done) return error("tap failed");
        JSONObject out = new JSONObject().put("ok", true).put("x", x).put("y", y);
        if (info != null) out.put("target", info);
        return out;
    }

    /** 向输入框输入文本：JS 聚焦 + 原生 setter 赋值 + 触发 input/change。 */
    private JSONObject type(JSONObject args) throws Exception {
        String selector = args.optString("selector", "").trim();
        String text = args.optString("text", "");
        if (selector.isEmpty()) return error("selector required");
        final WebView wv = requireWebView();
        String script = "(function(){var el=document.querySelector(" + jsonString(selector) + ");"
                + "if(!el)return JSON.stringify({ok:false,error:'element not found'});"
                + "el.focus();"
                + "var setter=Object.getOwnPropertyDescriptor(el instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:"
                + "(el instanceof HTMLInputElement?HTMLInputElement.prototype:HTMLSelectElement.prototype),'value').set;"
                + "setter.call(el," + jsonString(text) + ");"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return JSON.stringify({ok:true,tag:el.tagName.toLowerCase(),value:el.value.slice(0,100)})})()";
        String raw = ctl.evalJs(wv, script, 5000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected type result");
        JSONObject info = (JSONObject) decoded;
        if (!info.optBoolean("ok", false)) return error(info.optString("error", "type failed"));
        return new JSONObject().put("ok", true).put("typed", text.length()).put("target", info);
    }

    /** 模拟按键：Enter/Backspace/Tab/Escape/Arrow* 或普通字符（JS 派发键盘事件）。 */
    private JSONObject keyboard(JSONObject args) throws Exception {
        String keys = args.optString("keys", "").trim();
        if (keys.isEmpty()) return error("keys required");
        final WebView wv = requireWebView();
        String script = buildKeyScript(keys);
        String raw = ctl.evalJs(wv, script, 5000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected keyboard result");
        JSONObject info = (JSONObject) decoded;
        if (!info.optBoolean("ok", false)) return error(info.optString("error", "keyboard failed"));
        return new JSONObject().put("ok", true).put("pressed", keys);
    }

    private String buildKeyScript(String keys) {
        String[] parts = keys.split("\\+");
        String key = parts[parts.length - 1].trim();
        String lower = key.toLowerCase(Locale.ROOT);
        String code = "''";
        int keyCode = 0;
        if ("enter".equals(lower)) { key = "Enter"; code = "'Enter'"; keyCode = 13; }
        else if ("backspace".equals(lower)) { key = "Backspace"; code = "'Backspace'"; keyCode = 8; }
        else if ("tab".equals(lower)) { key = "Tab"; code = "'Tab'"; keyCode = 9; }
        else if ("escape".equals(lower) || "esc".equals(lower)) { key = "Escape"; code = "'Escape'"; keyCode = 27; }
        else if ("space".equals(lower)) { key = " "; code = "' '"; keyCode = 32; }
        else if ("arrowup".equals(lower)) { key = "ArrowUp"; code = "'ArrowUp'"; keyCode = 38; }
        else if ("arrowdown".equals(lower)) { key = "ArrowDown"; code = "'ArrowDown'"; keyCode = 40; }
        else if ("arrowleft".equals(lower)) { key = "ArrowLeft"; code = "'ArrowLeft'"; keyCode = 37; }
        else if ("arrowright".equals(lower)) { key = "ArrowRight"; code = "'ArrowRight'"; keyCode = 39; }
        else if (key.length() == 1) { code = jsonString(key); keyCode = key.charAt(0); }
        boolean ctrl = false, shift = false, alt = false;
        for (int i = 0; i < parts.length - 1; i++) {
            String m = parts[i].trim().toLowerCase(Locale.ROOT);
            if ("ctrl".equals(m) || "control".equals(m)) ctrl = true;
            if ("shift".equals(m)) shift = true;
            if ("alt".equals(m)) alt = true;
        }
        String keyJson = jsonString(key);
        return "(function(){var el=document.activeElement;if(!el)return JSON.stringify({ok:false,error:'no focused element'});"
                + "var opts={key:" + keyJson + ",code:" + code + ",keyCode:" + keyCode + ",which:" + keyCode
                + ",bubbles:true,cancelable:true,ctrlKey:" + ctrl + ",shiftKey:" + shift + ",altKey:" + alt + "};"
                + "el.dispatchEvent(new KeyboardEvent('keydown',opts));"
                + "if(" + keyCode + "!==13)el.dispatchEvent(new KeyboardEvent('keypress',opts));"
                + "el.dispatchEvent(new KeyboardEvent('keyup',opts));"
                + "if(" + keyCode + "===13){if(el.tagName==='FORM'){el.submit();}else{var f=el.form;if(f)f.submit();}}"
                + "if(" + keyCode + "===32||(" + keyCode + ">=48&&" + keyCode + "<=90)||(" + keyCode + ">=96&&" + keyCode + "<=111)){"
                + "var start=el.selectionStart!=null?el.selectionStart:el.value.length;"
                + "var end=el.selectionEnd!=null?el.selectionEnd:el.value.length;"
                + "var v=el.value||'';el.value=v.slice(0,start)+" + keyJson + "+v.slice(end);"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));}"
                + "return JSON.stringify({ok:true})})()";
    }

    // ==================== 标签管理 ====================

    private JSONObject tabs() throws Exception {
        List<?> list = ctl.liveTabs();
        if (list == null) return error("tabs not ready");
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof JSONObject) {
                JSONObject t = (JSONObject) item;
                arr.put(new JSONObject()
                        .put("id", i)
                        .put("title", t.optString("title", ""))
                        .put("url", t.optString("url", ""))
                        .put("pinned", t.optBoolean("pinned", false))
                        .put("active", t.optBoolean("active", false)));
            }
        }
        return new JSONObject().put("count", arr.length()).put("tabs", arr);
    }

    private JSONObject newTab(JSONObject args) throws Exception {
        final String url = args.optString("url", "").trim();
        Boolean done = ctl.onUi(new McpController.BlockingCall<Boolean>() {
            @Override public Boolean run() {
                ctl.newTab(url.isEmpty() ? null : url);
                return true;
            }
        }, 3000);
        if (done == null || !done) return error("new tab failed");
        return new JSONObject().put("ok", true).put("opened", url.isEmpty() ? "home" : url);
    }

    private JSONObject closeTab() throws Exception {
        Boolean done = ctl.onUi(new McpController.BlockingCall<Boolean>() {
            @Override public Boolean run() {
                ctl.closeCurrentTab();
                return true;
            }
        }, 3000);
        if (done == null || !done) return error("close tab failed");
        return new JSONObject().put("ok", true);
    }

    private int tabCount() {
        List<?> list = ctl.liveTabs();
        return list == null ? 0 : list.size();
    }

    // ==================== 开发者诊断 ====================

    private JSONObject console(JSONObject args) throws Exception {
        String type = args.optString("type", "");
        int limit = Math.max(1, Math.min(args.optInt("limit", 50), 200));
        List<JSONObject> all = ctl.consoleSnapshot();
        java.util.ArrayList<JSONObject> filtered = new java.util.ArrayList<JSONObject>();
        for (JSONObject log : all) {
            if (type.isEmpty() || type.equals(log.optString("type"))) filtered.add(log);
        }
        JSONArray logs = new JSONArray();
        int from = Math.max(0, filtered.size() - limit);
        for (int i = filtered.size() - 1; i >= from; i--) logs.put(filtered.get(i));
        int errs = 0;
        for (JSONObject log : all) if ("error".equals(log.optString("type"))) errs++;
        return new JSONObject().put("total", filtered.size()).put("errorCount", errs).put("logs", logs);
    }

    private JSONObject network() throws Exception {
        List<JSONObject> all = ctl.networkSnapshot();
        JSONArray logs = new JSONArray();
        JSONArray failures = new JSONArray();
        for (int i = Math.max(0, all.size() - 100); i < all.size(); i++) {
            JSONObject item = all.get(i);
            logs.put(item);
            int status = item.optInt("status", 0);
            if (status == 0 || status >= 400) failures.put(item);
        }
        return new JSONObject()
                .put("total", all.size())
                .put("shown", logs.length())
                .put("failures", failures)
                .put("logs", logs);
    }

    private JSONObject perf() throws Exception {
        final WebView wv = requireWebView();
        String raw = ctl.evalJs(wv, PAGE_PERF_JS, 6000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected perf result");
        return (JSONObject) decoded;
    }

    /** 综合诊断报告：页面 + Console 错误 + Network 失败 + 性能 + 健康度。 */
    private JSONObject report() throws Exception {
        JSONObject state = state();
        JSONObject perf = perf();
        JSONObject net = network();
        List<JSONObject> logs = ctl.consoleSnapshot();
        JSONArray errs = new JSONArray();
        int errCount = 0;
        for (JSONObject log : logs) {
            if ("error".equals(log.optString("type"))) {
                errCount++;
                if (errs.length() < 20) errs.put(log);
            }
        }
        JSONArray issues = new JSONArray();
        if (errCount > 0) issues.put("console errors: " + errCount);
        JSONArray failArr = net.optJSONArray("failures");
        int failCount = failArr == null ? 0 : failArr.length();
        if (failCount > 0) issues.put("failed/4xx/5xx requests: " + failCount);
        JSONObject timing = perf.optJSONObject("timing");
        if (timing != null) {
            if (timing.optInt("ttfb", 0) > 2000) issues.put("slow TTFB: " + timing.optInt("ttfb", 0) + "ms");
            if (timing.optInt("load", 0) > 10000) issues.put("slow load: " + timing.optInt("load", 0) + "ms");
        }
        return new JSONObject()
                .put("generatedAt", System.currentTimeMillis())
                .put("server", SERVER_NAME + " " + VERSION)
                .put("page", new JSONObject()
                        .put("url", state.optString("url", ""))
                        .put("title", state.optString("title", ""))
                        .put("progress", state.optInt("progress", 0))
                        .put("documentReady", state.optString("documentReady", "")))
                .put("issues", issues)
                .put("healthy", issues.length() == 0)
                .put("console", new JSONObject().put("total", logs.size()).put("errors", errCount)
                        .put("errorSample", errs))
                .put("network", new JSONObject()
                        .put("total", net.optInt("total", 0))
                        .put("failures", failCount)
                        .put("failureSample", failArr == null ? new JSONArray() : failArr))
                .put("perf", timing == null ? JSONObject.NULL : timing)
                .put("resources", perf.optJSONObject("resources") == null ? JSONObject.NULL : perf.optJSONObject("resources"))
                .put("tabs", state.optInt("tabCount", 0));
    }

    /** 查找可点击元素（隐藏别名，供 AI 获取坐标）。脚本返回 JSON 数组，包装为 {count, items}。 */
    private JSONObject interactive() throws Exception {
        final WebView wv = requireWebView();
        String raw = ctl.evalJs(wv, PAGE_INTERACTIVE_JS, 6000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (decoded instanceof JSONArray) {
            return new JSONObject().put("count", ((JSONArray) decoded).length()).put("items", decoded);
        }
        if (!(decoded instanceof JSONObject)) return error("unexpected interactive result");
        return (JSONObject) decoded;
    }

    // ==================== 历史 / 书签 / HTTP / 清理 ====================

    private JSONObject history(JSONObject args) throws Exception {
        Object store = ctl.dataStore();
        if (!(store instanceof BrowserDataStore)) return error("data store not ready");
        String keyword = args.optString("keyword", "");
        int limit = Math.max(1, Math.min(args.optInt("limit", 20), 100));
        List<BrowserDataStore.HistoryItem> items = ((BrowserDataStore) store).recentHistory(limit, keyword);
        JSONArray arr = new JSONArray();
        for (BrowserDataStore.HistoryItem item : items) {
            arr.put(new JSONObject()
                    .put("url", item.url)
                    .put("title", item.title)
                    .put("visitedAt", item.visitedAt)
                    .put("visits", item.visits));
        }
        return new JSONObject().put("count", arr.length()).put("items", arr);
    }

    private JSONObject bookmarks(JSONObject args) throws Exception {
        Object store = ctl.dataStore();
        if (!(store instanceof BrowserDataStore)) return error("data store not ready");
        BrowserDataStore ds = (BrowserDataStore) store;
        String action = args.optString("action", "search");
        if ("add".equals(action)) {
            String name = args.optString("name", args.optString("title", "书签"));
            String url = args.optString("url", "").trim();
            if (!isHttpUrl(url)) return error("valid http(s) url required");
            boolean added = ds.toggleBookmark(name, url);
            return new JSONObject().put("added", added).put("name", name).put("url", url);
        }
        if ("delete".equals(action)) {
            String url = args.optString("url", "").trim();
            if (url.isEmpty()) return error("url required");
            ds.removeBookmark(url);
            return new JSONObject().put("deleted", true).put("url", url);
        }
        String keyword = args.optString("keyword", "").trim().toLowerCase(Locale.ROOT);
        List<BrowserDataStore.Bookmark> all = ds.bookmarks();
        JSONArray arr = new JSONArray();
        for (BrowserDataStore.Bookmark item : all) {
            if (!keyword.isEmpty() && !item.title.toLowerCase(Locale.ROOT).contains(keyword)
                    && !item.url.toLowerCase(Locale.ROOT).contains(keyword)) continue;
            arr.put(new JSONObject().put("name", item.title).put("url", item.url).put("createdAt", item.createdAt));
        }
        return new JSONObject().put("count", arr.length()).put("items", arr);
    }

    /** 服务端 HTTP GET（带大小限制），用于无法用 JS 直接请求的场景。 */
    private JSONObject httpRequest(JSONObject args) {
        String urlStr = args.optString("url", "").trim();
        if (!isHttpUrl(urlStr)) return error("valid http(s) url required");
        int maxBytes = Math.max(1024, Math.min(args.optInt("maxBytes", 65536), 1048576));
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            int timeout = Math.max(1000, Math.min(args.optInt("timeoutMs", 10000), 30000));
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("User-Agent", "Median/" + VERSION);
            conn.setRequestProperty("Accept", "*/*");
            JSONObject headers = args.optJSONObject("headers");
            if (headers != null) {
                java.util.Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    conn.setRequestProperty(k, headers.optString(k));
                }
            }
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            byte[] bytes = new byte[0];
            boolean truncated = false;
            if (stream != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int n;
                while ((n = stream.read(buffer)) > 0) {
                    if (total + n > maxBytes) {
                        out.write(buffer, 0, maxBytes - total);
                        truncated = true;
                        break;
                    }
                    out.write(buffer, 0, n);
                    total += n;
                }
                bytes = out.toByteArray();
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            JSONObject respHeaders = new JSONObject();
            Map<String, List<String>> headerFields = conn.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    respHeaders.put(entry.getKey(), entry.getValue().get(0));
                }
            }
            return new JSONObject()
                    .put("status", code)
                    .put("url", urlStr)
                    .put("bodyBytes", bytes.length)
                    .put("truncated", truncated)
                    .put("body", body)
                    .put("headers", respHeaders);
        } catch (Exception e) {
            return error(e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject clear() throws Exception {
        ctl.clearConsole();
        ctl.clearNetwork();
        return new JSONObject().put("ok", true).put("cleared", "console,network");
    }

    // ==================== HTTP Bridge（/api/browser_*） ====================

    /** 兼容旧式 HTTP Bridge：GET/POST /api/browser_<tool>，参数来自 query 或 JSON body。 */
    private MiniHttpServer.Response handleBridge(String method, String path, byte[] body) {
        try {
            return handleBridgeInner(method, path, body);
        } catch (Exception e) {
            return MiniHttpServer.Response.error(500,
                    "bridge error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private MiniHttpServer.Response handleBridgeInner(String method, String path, byte[] body) throws Exception {
        String tool = path;
        int qq = tool.indexOf('?');
        if (qq >= 0) tool = tool.substring(0, qq); // 工具名不含 query（query 作为参数在下方解析）
        if (tool.startsWith("/api/")) tool = tool.substring(5);
        if (tool.endsWith("/")) tool = tool.substring(0, tool.length() - 1);
        if (tool.isEmpty()) {
            return MiniHttpServer.Response.ok(new JSONObject()
                    .put("name", SERVER_NAME)
                    .put("version", VERSION)
                    .put("tools", bridgeTools()).toString());
        }
        if (!tool.startsWith("browser_")) tool = "browser_" + tool;
        JSONObject args = new JSONObject();
        if (body != null && body.length > 0) {
            try { args = new JSONObject(new String(body, StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            String query = path.substring(q + 1);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq);
                    String v = pair.substring(eq + 1);
                    try { v = URLDecoder.decode(v, "UTF-8"); } catch (Exception ignored) { }
                    if (!args.has(k)) args.put(k, v);
                }
            }
        }
        JSONObject result = callTool(tool, args);
        return MiniHttpServer.Response.ok(result.toString());
    }

    private JSONArray bridgeTools() {
        JSONArray arr = new JSONArray();
        JSONArray defs;
        try {
            defs = toolDefinitions();
        } catch (Exception e) {
            return arr;
        }
        for (int i = 0; i < defs.length(); i++) {
            JSONObject t = defs.optJSONObject(i);
            if (t != null) arr.put(t.optString("name", ""));
        }
        return arr;
    }

    // ==================== 辅助 ====================

    private WebView requireWebView() {
        WebView wv = ctl.webView();
        if (wv == null) throw new IllegalStateException("webview not ready");
        return wv;
    }

    private static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * evaluateJavascript 返回值是 JSON 编码的字符串，这里解码为 Java 对象。
     * 注意：evaluateJavascript 会对 JS 返回值做 JSON 编码——若脚本返回对象，回调为 {"a":1}；
     * 若脚本返回字符串（如 JSON.stringify 的结果），回调会变成带引号与转义的字符串字面量
     * （双重编码）。此处对字符串结果再做一次 JSON 解析以还原对象/数组，兼容两种写法。
     */
    private static Object decodeEvalValue(String raw) {
        if (raw == null) return null;
        try {
            Object value = new org.json.JSONTokener(raw).nextValue();
            if (value instanceof String) {
                String s = (String) value;
                if ("undefined".equals(s) || "null".equals(s) || "NaN".equals(s)) return null;
                String t = s.trim();
                // 字符串内容本身是 JSON 结构（脚本用 JSON.stringify 包装的返回值）→ 二次解析还原
                if (t.startsWith("{") || t.startsWith("[")) {
                    try {
                        Object inner = new org.json.JSONTokener(s).nextValue();
                        if (inner != null && !(inner instanceof String)) return inner;
                    } catch (Exception ignored) { }
                }
                return s;
            }
            return value;
        } catch (Exception e) {
            return null;
        }
    }

    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ==================== 页面脚本常量 ====================

    private static final String PAGE_STATE_JS =
            "(function(){return JSON.stringify({ready:document.readyState,"
                    + "body:(document.body?document.body.innerText:'').slice(0,2000)})})()";

    private static final String PAGE_TEXT_JS =
            "(function(){var a=document.querySelector('article')||document.body;"
                    + "var t=(a?a.innerText:'').replace(/\\n{3,}/g,'\\n\\n');"
                    + "return JSON.stringify({title:document.title,url:location.href,text:t.slice(0,20000),length:t.length})})()";

    private static final String PAGE_LINKS_JS =
            "(function(){var out=[];var seen={};"
                    + "Array.from(document.querySelectorAll('a[href]')).forEach(function(a){"
                    + "var h=a.getAttribute('href')||'';if(!h||seen[h])return;seen[h]=1;if(out.length>=200)return;"
                    + "out.push({href:h,text:(a.innerText||a.getAttribute('title')||'').trim().slice(0,120)})});"
                    + "return JSON.stringify({count:out.length,links:out})})()";

    private static final String PAGE_DOM_JS =
            "(function(){var txt=(document.body?document.body.innerText:'').replace(/\\n{3,}/g,'\\n\\n');"
                    + "var links=Array.from(document.querySelectorAll('a[href]')).length;"
                    + "var interactive=Array.from(document.querySelectorAll('a[href],button,input,select,textarea,[role=button],[onclick],[tabindex]'))"
                    + ".filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0}).length;"
                    + "return JSON.stringify({title:document.title,url:location.href,text:txt.slice(0,20000),"
                    + "textLength:txt.length,linkCount:links,interactiveCount:interactive,ready:document.readyState})})()";

    private static final String PAGE_INTERACTIVE_JS =
            "(function(){var els=Array.from(document.querySelectorAll('a[href],button,input,select,textarea,[role=button],[onclick],[tabindex]'))"
                    + ".filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0}).slice(0,60);"
                    + "return JSON.stringify(els.map(function(e,i){"
                    + "function sel(n){if(!n||n===document.body)return 'body';if(n.id)return '#'+n.id;"
                    + "var parts=[],cur=n;for(var k=0;k<4&&cur&&cur!==document.body;k++){"
                    + "var cls='';if(cur.className&&typeof cur.className==='string'){var m=cur.className.trim().split(/\\s+/)[0];if(m)cls=m;}"
                    + "var tag=cur.tagName.toLowerCase();var p=cur.parentElement;var s=p?Array.from(p.children).indexOf(cur)+1:1;"
                    + "parts.unshift(cls?tag+'.'+cls:tag+':nth-child('+s+')');cur=p;}"
                    + "return parts.join(' > ')}"
                    + "var r=e.getBoundingClientRect();"
                    + "return{tag:e.tagName.toLowerCase(),text:(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().slice(0,80),"
                    + "href:e.getAttribute('href')||'',type:e.getAttribute('type')||'',selector:sel(e).slice(0,300),"
                    + "x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)}}))})()";

    private static final String PAGE_PERF_JS =
            "(function(){var nav=performance.getEntriesByType('navigation')[0];"
                    + "var res=performance.getEntriesByType('resource');"
                    + "var total=res.reduce(function(s,e){return s+(e.transferSize||0)},0);"
                    + "var slow=res.filter(function(e){return e.duration>2000}).sort(function(a,b){return b.duration-a.duration})"
                    + ".slice(0,10).map(function(e){return {name:e.name.slice(0,120),dur:Math.round(e.duration)}});"
                    + "return JSON.stringify({url:location.href,"
                    + "timing:nav?{ttfb:Math.round(nav.responseStart-nav.requestStart),"
                    + "domContentLoaded:Math.round(nav.domContentLoadedEventEnd-nav.startTime),"
                    + "load:Math.round(nav.loadEventEnd-nav.startTime),protocol:nav.nextHopProtocol}:null,"
                    + "resources:{count:res.length,totalBytes:total},slowResources:slow})})()";
}