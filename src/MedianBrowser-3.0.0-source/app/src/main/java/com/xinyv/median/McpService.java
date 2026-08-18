package com.xinyv.median;

import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    /** 远端 MCP 工具索引：displayName -> 服务器引用（探测成功后重建）。 */
    private final java.util.Map<String, RemoteToolRef> remoteToolIndex = new java.util.concurrent.ConcurrentHashMap<String, RemoteToolRef>();
    /** 远端 MCP 服务器探测结果缓存（displayName -> 描述）。 */
    private volatile String remoteToolsPayload = "[]";
    /** 上次远端索引刷新时间（节流用）。 */
    private volatile long lastRemoteProbe = 0L;
    private static final class RemoteToolRef {
        String display;   // remote.<server>.<tool>
        String server;    // 服务器名（配置中的 name）
        String url;       // 服务器 MCP 端点
        String token;     // 服务器 token（可空）
        String tool;      // 远端真实工具名
        String description;
    }
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
            if ("remote/tools".equals(rpcMethod)) {
                // 内部方法：返回聚合后的远端 MCP 工具描述（供注入块动态合并到 DeepSeek 提示词）
                refreshRemoteIndex();
                return respond(id, new JSONObject().put("tools", new JSONArray(remoteToolsPayload)).put("servers", remoteServersSummary()));
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
                .put("instructions", "Median Browser MCP：用 browser_open 打开网页，browser_panel_open 在小窗打开（不影响当前对话页），" +
                        "browser_screenshot 查看页面，browser_interactive 获取可点击元素，browser_click_at / browser_type 操作页面，" +
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
        tools.put(tool("browser_panel_open", "在小窗中打开指定 URL（浮动小窗浏览器，不占用/不影响当前标签页，如 Kimi 对话页；适合 AI 需要访问网页但不打断对话的场景）", schema(
                new JSONObject().put("url", prop("string", "要打开的网址")), new String[]{"url"})));
        tools.put(tool("browser_state", "获取浏览器与当前页面状态（URL、标题、加载进度、标签数、MCP 端口）", schema(null, null)));
        tools.put(tool("browser_eval", "在当前页面执行 JavaScript 并返回结果", schema(
                new JSONObject().put("expression", prop("string", "要执行的 JS 表达式或语句")), new String[]{"expression"})));
        tools.put(tool("dspp_diag", "DeepSeek++ 运行时诊断（开关状态、内置脚本缓存、assets 读取）", schema(null, null)));
        tools.put(tool("fs_list_dir", "列出目录内容（供 DeepSeek++ 读取本机文件）", schema(
                new JSONObject().put("path", prop("string", "目录路径，如 /sdcard/Download/Median-v3.0.0")), new String[]{"path"})));
        tools.put(tool("fs_read_file", "读取文件内容（文本或 base64，供 DeepSeek++ 读取本机文件）", schema(
                new JSONObject().put("path", prop("string", "文件路径"))
                        .put("maxBytes", prop("number", "最多读取字节数，默认 1048576"))
                        .put("binary", prop("boolean", "true 返回 base64，false 返回 UTF-8 文本，默认 false")),
                new String[]{"path"})));
        tools.put(tool("fs_find_file", "在目录中按文件名模式搜索文件（如 *.apk）", schema(
                new JSONObject().put("dir", prop("string", "搜索目录，默认 /sdcard/Download"))
                        .put("pattern", prop("string", "文件名模式，如 *.apk 或 Median*")),
                new String[]{"pattern"})));
        tools.put(tool("fs_write_file", "写入文件（文本或 base64；相对路径=工作区内，/开头=绝对路径）供 AI 输出内容到本机", schema(
                new JSONObject().put("path", prop("string", "文件路径：相对路径在工作区内，/开头为绝对路径"))
                        .put("content", prop("string", "文件内容（文本）或 base64（binary=true）"))
                        .put("binary", prop("boolean", "true 表示 content 为 base64，默认 false")),
                new String[]{"path", "content"})));
        tools.put(tool("fs_append_file", "追加内容到文件末尾（不存在则自动创建）", schema(
                new JSONObject().put("path", prop("string", "文件路径：相对=工作区内，/开头=绝对"))
                        .put("content", prop("string", "要追加的内容")),
                new String[]{"path", "content"})));
        tools.put(tool("fs_create_dir", "创建目录（自动创建父目录）", schema(
                new JSONObject().put("path", prop("string", "目录路径：相对=工作区内，/开头=绝对")),
                new String[]{"path"})));
        tools.put(tool("fs_delete", "删除文件或目录（目录递归删除）", schema(
                new JSONObject().put("path", prop("string", "文件/目录路径")),
                new String[]{"path"})));
        tools.put(tool("fs_copy", "复制文件或目录（递归）", schema(
                new JSONObject().put("src", prop("string", "源路径")).put("dst", prop("string", "目标路径")),
                new String[]{"src", "dst"})));
        tools.put(tool("fs_move", "移动/重命名文件或目录", schema(
                new JSONObject().put("src", prop("string", "源路径")).put("dst", prop("string", "目标路径")),
                new String[]{"src", "dst"})));
        tools.put(tool("fs_info", "文件/目录详细信息（类型、大小、修改时间、父目录、存在性）", schema(
                new JSONObject().put("path", prop("string", "路径")),
                new String[]{"path"})));
        tools.put(tool("workspace_info", "查看当前工作区（路径、是否存在、可写、磁盘空间）", schema(null, null)));
        tools.put(tool("workspace_set", "设置工作区目录（自动创建；AI 文件读写默认基于工作区）", schema(
                new JSONObject().put("path", prop("string", "工作区目录路径，如 /sdcard/Download/Median/Workspace")),
                new String[]{"path"})));
        tools.put(tool("browser_dom", "提取当前页面结构化内容（标题、URL、正文文本、链接、可交互元素）", schema(null, null)));
        tools.put(tool("browser_interactive", "获取页面全部可交互元素（链接/按钮/输入框/下拉框，含唯一 selector 与屏幕坐标，可直接用于 browser_click / browser_type）", schema(null, null)));
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
        tools.put(tool("browser_keyboard", "模拟按键（Enter/Backspace/Tab/Escape/Delete/Home/End/方向键或普通字符，支持 Ctrl/Shift/Alt 组合）", schema(
                new JSONObject().put("keys", prop("string", "按键序列，如 Enter、Tab")), new String[]{"keys"})));
        tools.put(tool("browser_tabs", "列出全部标签页（标题、URL、是否激活）", schema(null, null)));
        tools.put(tool("browser_new_tab", "新建标签页并激活", schema(
                new JSONObject().put("url", prop("string", "新标签打开的网址，默认主页")), null)));
        tools.put(tool("browser_close", "关闭当前标签页", schema(null, null)));
        tools.put(tool("browser_back", "后退：等价点击底部工具栏后退按钮", schema(null, null)));
        tools.put(tool("browser_forward", "前进：等价点击底部工具栏前进按钮", schema(null, null)));
        tools.put(tool("browser_reload", "刷新当前页面（主页时重新加载主页）", schema(null, null)));
        tools.put(tool("browser_home", "返回浏览器主页（Median 主页）", schema(null, null)));
        tools.put(tool("browser_console", "读取页面 Console 日志（error/warn/log 等）", schema(null, null)));
        tools.put(tool("browser_network", "读取页面网络请求记录（URL、方法、主框架、时间、状态/响应体快照），body=true 时附带响应体", schema(
                new JSONObject().put("body", prop("boolean", "true 时返回已捕获的响应体快照（网络规则拉取路径）")), null)));
        tools.put(tool("net_rule_add", "添加网络规则：block=拦截请求返回空响应; redirect=将请求重写到目标URL; inject=在主框架HTML的</head>前注入片段; replace=替换主框架HTML中的文本; rewrite=页面侧改写请求体/请求头（fetch+XHR双通道 hook 注入）", schema(
                new JSONObject().put("type", prop("string", "block|redirect|inject|replace|rewrite"))
                        .put("pattern", prop("string", "URL 子串（不区分大小写）"))
                        .put("match", prop("string", "可选，replace 类型专用：响应体中要替换的文本（缺省用 pattern）"))
                        .put("target", prop("string", "redirect: 目标URL; inject: 注入的HTML/JS片段; replace: 替换后的文本; rewrite: 改写配置JSON，形如 {\"match\":\"/api/v0/chat/completion\",\"method\":\"POST\",\"body\":{\"set\":{\"parent_message_id\":null},\"delete\":[\"x\"]},\"bodyRaw\":[{\"find\":\"a\",\"replace\":\"b\"}],\"headers\":{\"set\":{\"X-H\":\"1\"},\"delete\":[\"X-F\"]}}"))
                        .put("enabled", prop("boolean", "是否启用，默认 true")),
                new String[]{"type", "pattern"})));
        tools.put(tool("net_rule_list", "列出全部网络规则（id/type/pattern/target/enabled/hits 命中次数）", schema(null, null)));
        tools.put(tool("net_rule_remove", "按 id 删除网络规则", schema(
                new JSONObject().put("id", prop("string", "规则 id（net_rule_list 获取）")), new String[]{"id"})));
        tools.put(tool("net_rule_clear", "清空全部网络规则", schema(null, null)));
        tools.put(tool("browser_har", "导出抓包数据为 HAR 1.2 标准格式（entries: 请求URL/方法/时间/状态/响应体），save=true 时落盘到 Download/Median/", schema(
                new JSONObject().put("limit", prop("number", "最多导出条数，默认 200"))
                        .put("save", prop("boolean", "true 时保存 HAR 文件到 Download/Median/ 并返回路径")), null)));
        tools.put(tool("browser_hook", "管理持久 JS 钩子（页面加载后自动注入，可 hook fetch/XHR/console 等）：action=get 列出；action=add 添加脚本；action=remove 删除；action=clear 清空", schema(
                new JSONObject().put("action", prop("string", "get|add|remove|clear"))
                        .put("script", prop("string", "JS 钩子代码（action=add 时必填；会被包在 try{}catch 中执行）"))
                        .put("id", prop("string", "钩子 id（action=remove 时必填）")),
                new String[]{"action"})));
        tools.put(tool("browser_fingerprint", "浏览器指纹伪装：level ∈ off（关闭）| light（UA类属性）| full（含 webdriver/canvas/WebGL 全面伪装）", schema(
                new JSONObject().put("action", prop("string", "get 或 set"))
                        .put("level", prop("string", "off/light/full（action=set 时必填）")),
                new String[]{"action"})));
        tools.put(tool("browser_packet_analyze", "抓包 AI 启发式分析：汇总请求数/域名分布/方法分布/主框架/敏感路径/可疑外联/时间跨度；llm=true 时调用外部 LLM（需先 ai_configure）生成深度结论", schema(
                new JSONObject().put("limit", prop("number", "参与分析的最近请求数，默认全部"))
                        .put("llm", prop("boolean", "true 时把分析结果发送给已配置的外部 LLM 生成 AI 结论")), null)));
        tools.put(tool("proxy_ctl", "MITM 代理控制：action=get 查状态/统计/CA指纹；action=on 开启；action=off 关闭；action=export_ca 导出 CA 证书 PEM 到 Download/Median/ 供安装信任", schema(
                new JSONObject().put("action", prop("string", "get|on|off|export_ca")),
                new String[]{"action"})));
        tools.put(tool("ai_configure", "外部 LLM 配置（供 packet_analyze llm=true 使用）：action=get 查配置；action=set 设置 endpoint/model/apiKey（apiKey 传 clear 清除）；action=test 发送测试请求验证连通", schema(
                new JSONObject().put("action", prop("string", "get|set|test"))
                        .put("endpoint", prop("string", "OpenAI 兼容 /chat/completions 端点，默认 https://api.openai.com/v1/chat/completions"))
                        .put("model", prop("string", "模型名，默认 gpt-4o-mini"))
                        .put("apiKey", prop("string", "API Key（action=set 时可选；clear 清除）")),
                new String[]{"action"})));
        tools.put(tool("browser_perf", "读取页面性能指标（FCP、DOM 大小、资源数量等）", schema(null, null)));
        tools.put(tool("browser_report", "生成综合诊断报告（页面、Console、Network、性能汇总）", schema(null, null)));
        tools.put(tool("browser_history", "读取浏览历史", schema(null, null)));
        tools.put(tool("browser_bookmarks", "读取书签", schema(null, null)));
        tools.put(tool("browser_logs", "读取浏览器运行日志（应用自身事件，含页面加载/错误/MCP 状态）", schema(
                new JSONObject().put("limit", prop("number", "最多返回条数，默认 200")), null)));
        tools.put(tool("browser_info", "浏览器与设备诊断信息（版本、UA、屏幕、MCP、设置快照）", schema(null, null)));
        tools.put(tool("browser_settings", "读取/修改浏览器设置：action=get 读取全部；action=set 时 key ∈ night|adBlock|performance|searchEngine", schema(
                new JSONObject().put("action", prop("string", "get 或 set")).put("key", prop("string", "设置键：night/adBlock/performance/searchEngine"))
                        .put("value", prop("string", "设置值：true/false / standard|performance|powersave / google|baidu|bing|custom")),
                new String[]{"action"})));
        tools.put(tool("browser_scroll", "滚动页面：direction ∈ top|bottom|up|down，pixels 可选（默认 80% 屏高）", schema(
                new JSONObject().put("direction", prop("string", "top/bottom/up/down")).put("pixels", prop("number", "滚动像素，可选")),
                new String[]{"direction"})));
        tools.put(tool("browser_cookies", "读取当前站点或指定 URL 的 Cookie", schema(
                new JSONObject().put("url", prop("string", "目标 URL，默认当前页面")), null)));
        tools.put(tool("browser_storage", "读取当前页面 localStorage/sessionStorage（调试网站用）", schema(null, null)));
        tools.put(tool("browser_bookmark_add", "添加书签（已存在则忽略）", schema(
                new JSONObject().put("url", prop("string", "书签 URL")).put("title", prop("string", "书签标题，默认用 URL")),
                new String[]{"url"})));
        tools.put(tool("browser_history_clear", "清空浏览历史", schema(null, null)));
        tools.put(tool("browser_http", "从服务端发起 HTTP GET 请求（带大小限制）", schema(
                new JSONObject().put("url", prop("string", "目标 URL（http/https）")).put("maxBytes", prop("number", "响应最大字节数，默认 65536")),
                new String[]{"url"})));
        tools.put(tool("browser_clear", "清空 Console 与 Network 记录", schema(null, null)));
        tools.put(tool("mcp_config", "远端 MCP 服务器配置管理（多 MCP 支持）：action=list 列出全部服务器；action=add 添加服务器(name/url/token，url 为本机或局域网 MCP 地址，如 http://192.168.1.100:8788/mcp)；action=remove 删除(name)；action=set 修改(enabled=true/false 启用禁用、url、token)；action=discover 探测 name 指定服务器（空=全部）并缓存其工具", schema(
                new JSONObject().put("action", prop("string", "list|add|remove|set|discover"))
                        .put("name", prop("string", "服务器名称（add/remove/set/discover 时必填）"))
                        .put("url", prop("string", "服务器 MCP 地址，如 http://192.168.1.100:8788/mcp（add/set 时必填）"))
                        .put("token", prop("string", "服务器访问 token（可选）"))
                        .put("enabled", prop("boolean", "是否启用（set 时可选）")),
                new String[]{"action"})));
        tools.put(tool("mcp_discover", "探测远端 MCP 服务器并缓存其工具列表：name 指定服务器（空=全部已启用）。探测成功后远端工具以 remote.<服务器名>.<工具名> 形式加入可用工具集，DeepSeek 可直接调用", schema(
                new JSONObject().put("name", prop("string", "服务器名称，空=全部")), null)));
        refreshRemoteIndexIfNeeded();
        for (java.util.Map.Entry<String, RemoteToolRef> e : remoteToolIndex.entrySet()) {
            RemoteToolRef r = e.getValue();
            tools.put(tool(r.display, r.description, schema(null, null)));
        }
        JSONArray gt = GithubTools.toolDefinitions();
        for (int i = 0; i < gt.length(); i++) tools.put(gt.getJSONObject(i));
        return tools;
    }
    /** 启动/工具列表时的远端索引懒刷新：索引为空立即探测（远端服务器可能后启动），非空则 60s 节流刷新。 */
    private void refreshRemoteIndexIfNeeded() {
        long now = System.currentTimeMillis();
        if (remoteToolIndex.isEmpty() || now - lastRemoteProbe > 60000L) {
            lastRemoteProbe = now;
            refreshRemoteIndex();
        }
    }
    /** 启动时自动探测远端 MCP 服务器（静默）。返回 true=已注册远端工具，false=探测失败可重试。 */
    public boolean autoDiscoverRemote() {
        lastRemoteProbe = System.currentTimeMillis();
        refreshRemoteIndex();
        return !remoteToolIndex.isEmpty();
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
            if ("browser_panel_open".equals(name)) return panelOpen(args);
            if ("browser_state".equals(name)) return state();
            if ("browser_eval".equals(name)) return eval(args);
            if ("dspp_diag".equals(name)) return dsppDiag();
            if ("fs_list_dir".equals(name)) return fsListDir(args);
            if ("fs_read_file".equals(name)) return fsReadFile(args);
            if ("fs_find_file".equals(name)) return fsFindFile(args);
            if ("fs_write_file".equals(name)) return fsWriteFile(args);
            if ("fs_append_file".equals(name)) return fsAppendFile(args);
            if ("fs_create_dir".equals(name)) return fsCreateDir(args);
            if ("fs_delete".equals(name)) return fsDelete(args);
            if ("fs_copy".equals(name)) return fsCopyMove(args, false);
            if ("fs_move".equals(name)) return fsCopyMove(args, true);
            if ("fs_info".equals(name)) return fsInfo(args);
            if ("workspace_info".equals(name)) return workspaceInfo(args);
            if ("workspace_set".equals(name)) return workspaceSet(args);
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
            if ("browser_back".equals(name)) return nav("back");
            if ("browser_forward".equals(name)) return nav("forward");
            if ("browser_reload".equals(name)) return nav("reload");
            if ("browser_home".equals(name)) return nav("home");
            if ("browser_console".equals(name)) return console(args);
            if ("browser_network".equals(name)) return network(args);
            if ("net_rule_add".equals(name)) return netRuleAdd(args);
            if ("net_rule_list".equals(name)) return netRuleList();
            if ("net_rule_remove".equals(name)) return netRuleRemove(args);
            if ("net_rule_clear".equals(name)) return netRuleClear();
            if ("browser_har".equals(name)) return har(args);
            if ("browser_hook".equals(name)) return hook(args);
            if ("browser_fingerprint".equals(name)) return fingerprint(args);
            if ("browser_packet_analyze".equals(name)) return packetAnalyze(args);
            if ("proxy_ctl".equals(name)) return proxyCtl(args);
            if ("ai_configure".equals(name)) return aiConfigure(args);
            if ("browser_perf".equals(name)) return perf();
            if ("browser_report".equals(name)) return report();
            if ("browser_history".equals(name)) return history(args);
            if ("browser_bookmarks".equals(name)) return bookmarks(args);
            if ("browser_logs".equals(name)) return logs(args);
            if ("browser_info".equals(name)) return info();
            if ("browser_settings".equals(name)) return settings(args);
            if ("browser_scroll".equals(name)) return scroll(args);
            if ("browser_cookies".equals(name)) return cookies(args);
            if ("browser_storage".equals(name)) return storage();
            if ("browser_bookmark_add".equals(name)) return bookmarkAdd(args);
            if ("browser_history_clear".equals(name)) return historyClear();
            if ("browser_http".equals(name)) return httpRequest(args);
            if ("browser_clear".equals(name)) return clear();
            // 隐藏别名（兼容 BrowserDiag 习惯，坐标由 browser_click_at 使用）
            if ("browser_interactive".equals(name)) return interactive();
            if ("mcp_config".equals(name)) return mcpConfig(args);
            if ("mcp_discover".equals(name)) return mcpDiscover(args);
            // GitHub 内置工具（Token 在 MCP 面板配置）
            if (name != null && name.startsWith("github_")) {
                return GithubTools.call(ctl.context(), name, args);
            }
            // 远端 MCP 工具转发（remote.<server>.<tool>）
            RemoteToolRef ref = remoteToolIndex.get(name);
            if (ref != null) return forwardRemoteCall(ref, args);
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
    // ==================== 远端 MCP 服务器（多 MCP 支持） ====================
    /** mcp_config：list/add/remove/set/discover。 */
    private JSONObject mcpConfig(JSONObject args) throws Exception {
        android.content.Context ctx = ctl.context();
        if (ctx == null) return error("context not ready");
        String action = args.optString("action", "list");
        String name = args.optString("name", "");
        if ("add".equals(action)) {
            JSONObject r = ctl.remoteMcpAdd(ctx, name, args.optString("url", ""), args.optString("token", ""));
            if (r.has("ok")) refreshRemoteIndex(); // 新增后立即探测并注册其工具（无需手动 discover）
            return r;
        }
        if ("remove".equals(action)) {
            if (name.isEmpty()) return error("name required");
            JSONObject r = ctl.remoteMcpRemove(ctx, name);
            if (r.has("ok")) refreshRemoteIndex();
            return r;
        }
        if ("set".equals(action)) {
            if (name.isEmpty()) return error("name required");
            String url = args.has("url") ? args.optString("url", "") : null;
            String token = args.has("token") ? args.optString("token", "") : null;
            Boolean enabled = args.has("enabled") ? Boolean.valueOf(args.optBoolean("enabled", false)) : null;
            JSONObject r = ctl.remoteMcpUpdate(ctx, name, url, token, enabled);
            if (r.has("ok")) refreshRemoteIndex();
            return r;
        }
        if ("discover".equals(action)) {
            return mcpDiscover(args);
        }
        return new JSONObject().put("ok", true).put("servers", ctl.remoteMcpList(ctx));
    }
    /** mcp_discover：探测远端服务器工具列表并缓存。 */
    private JSONObject mcpDiscover(JSONObject args) throws Exception {
        android.content.Context ctx = ctl.context();
        if (ctx == null) return error("context not ready");
        String only = args.optString("name", "");
        JSONArray servers = ctl.remoteMcpList(ctx);
        JSONArray results = new JSONArray();
        int probed = 0;
        for (int i = 0; i < servers.length(); i++) {
            JSONObject s = servers.optJSONObject(i);
            if (s == null) continue;
            String sn = s.optString("name", "");
            if (!only.isEmpty() && !only.equals(sn)) continue;
            if (!s.optBoolean("enabled", true)) {
                results.put(new JSONObject().put("server", sn).put("status", "disabled"));
                continue;
            }
            probed++;
            JSONObject r = probeMcpServer(s);
            if (r.has("error")) {
                results.put(new JSONObject().put("server", sn).put("status", "error").put("error", r.optString("error")));
            } else {
                JSONArray tools = r.optJSONArray("tools");
                results.put(new JSONObject().put("server", sn).put("status", "ok").put("tools", tools == null ? new JSONArray() : tools));
            }
        }
        if (probed == 0 && servers.length() == 0) {
            return new JSONObject().put("ok", true).put("note", "no remote MCP servers configured - use mcp_config action=add first")
                    .put("servers", new JSONArray()).put("tools", new JSONArray());
        }
        refreshRemoteIndex();
        JSONObject out = new JSONObject().put("ok", true).put("results", results);
        out.put("remoteTools", new JSONArray(remoteToolsPayload));
        out.put("servers", remoteServersSummary());
        return out;
    }
    /** 探测单个远端 MCP 服务器（尝试 /mcp 端点，失败则回退裸地址）。 */
    private JSONObject probeMcpServer(JSONObject server) {
        String base = server.optString("url", "");
        String token = server.optString("token", "");
        String[] candidates;
        if (base.endsWith("/mcp")) {
            candidates = new String[]{ base };
        } else {
            candidates = new String[]{ base + "/mcp", base };
        }
        for (String endpoint : candidates) {
            JSONObject r = mcpPost(endpoint, token, "tools/list", new JSONObject());
            if (!r.has("error")) return r;
        }
        return error("cannot reach " + base + " (tried /mcp and root): " + candidates[0]);
    }
    /** 向远端 MCP 服务器发送 JSON-RPC 请求。 */
    private JSONObject mcpPost(String endpoint, String token, String rpcMethod, JSONObject rpcParams) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("jsonrpc", "2.0").put("id", System.currentTimeMillis() % 100000).put("method", rpcMethod);
            payload.put("params", rpcParams);
            java.net.URL url = new java.net.URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setDoOutput(true);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            java.io.OutputStream os = conn.getOutputStream();
            try { os.write(body); } finally { os.close(); }
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            if (in != null) {
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
            }
            conn.disconnect();
            String raw = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (code >= 400) {
                return error("remote HTTP " + code + ": " + (raw.length() > 300 ? raw.substring(0, 300) : raw));
            }
            // 兼容 streamable-http（可能返回 SSE 行），提取 JSON
            if (raw.startsWith("event:") || raw.startsWith("data:")) {
                StringBuilder sb = new StringBuilder();
                for (String line : raw.split("\n")) {
                    if (line.startsWith("data:")) {
                        String d = line.substring(5).trim();
                        if (d.startsWith("{")) sb.append(d);
                    }
                }
                raw = sb.toString();
                if (raw.isEmpty()) return error("empty remote SSE response");
            }
            JSONObject resp = new JSONObject(raw);
            if (resp.has("error")) {
                JSONObject e = resp.optJSONObject("error");
                return error("remote rpc error: " + (e == null ? "unknown" : e.optString("message", String.valueOf(e))));
            }
            JSONObject result = resp.optJSONObject("result");
            if (result == null) return error("remote result missing");
            return result;
        } catch (Exception e) {
            return error("remote request failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }
    /** 重建远端工具索引（读取配置 + 探测启用服务器）。 */
    private void refreshRemoteIndex() {
        try {
            android.content.Context ctx = ctl.context();
            if (ctx == null) return;
            remoteToolIndex.clear();
            JSONArray servers = ctl.remoteMcpList(ctx);
            JSONArray tools = new JSONArray();
            for (int i = 0; i < servers.length(); i++) {
                JSONObject s = servers.optJSONObject(i);
                if (s == null || !s.optBoolean("enabled", true)) continue;
                String sn = s.optString("name", "");
                JSONObject r = probeMcpServer(s);
                JSONArray list = r.optJSONArray("tools");
                if (list == null || list.length() == 0) continue;
                for (int j = 0; j < list.length(); j++) {
                    JSONObject t = list.optJSONObject(j);
                    if (t == null) continue;
                    String realName = t.optString("name", "");
                    if (realName.isEmpty()) continue;
                    String display = "remote." + sn + "." + realName;
                    RemoteToolRef ref = new RemoteToolRef();
                    ref.display = display;
                    ref.server = sn;
                    ref.url = s.optString("url", "");
                    ref.token = s.optString("token", "");
                    ref.tool = realName;
                    String desc = t.optString("description", "");
                    ref.description = "[远端 MCP: " + sn + "] " + (desc.isEmpty() ? realName : desc);
                    remoteToolIndex.put(display, ref);
                    JSONObject to = new JSONObject();
                    to.put("name", display).put("description", ref.description);
                    tools.put(to);
                }
            }
            remoteToolsPayload = tools.toString();
        } catch (Exception ignored) { }
    }
    /** 服务器摘要（供注入块/工具结果展示）。 */
    private JSONArray remoteServersSummary() {
        JSONArray out = new JSONArray();
        try {
            android.content.Context ctx = ctl.context();
            if (ctx == null) return out;
            JSONArray servers = ctl.remoteMcpList(ctx);
            for (int i = 0; i < servers.length(); i++) {
                JSONObject s = servers.optJSONObject(i);
                if (s == null) continue;
                int n = 0;
                for (RemoteToolRef r : remoteToolIndex.values()) {
                    if (r.server.equals(s.optString("name"))) n++;
                }
                JSONObject o = new JSONObject();
                o.put("name", s.optString("name")).put("url", s.optString("url"))
                        .put("enabled", s.optBoolean("enabled", true)).put("tools", n);
                out.put(o);
            }
        } catch (Exception ignored) { }
        return out;
    }
    /** 转发工具调用到远端 MCP 服务器。 */
    private JSONObject forwardRemoteCall(RemoteToolRef ref, JSONObject args) throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", ref.tool);
        params.put("arguments", args);
        JSONObject r = mcpPost(ref.url, ref.token, "tools/call", params);
        if (r.has("error")) return r;
        // MCP 标准结果：content 数组（text/image），拼接为文本
        JSONArray content = r.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject c = content.optJSONObject(i);
                if (c == null) continue;
                String type = c.optString("type", "");
                if ("text".equals(type)) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(c.optString("text", ""));
                } else if ("image".equals(type)) {
                    if (sb.length() > 0) sb.append("\n");
                    String mime = c.optString("mimeType", "image/png");
                    String data = c.optString("data", "");
                    sb.append("[image ").append(mime).append(" ").append(data.length()).append(" chars]");
                } else {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(c.toString());
                }
            }
        }
        JSONObject out = new JSONObject();
        out.put("remote", ref.server).put("tool", ref.tool).put("ok", true);
        out.put("result", sb.length() == 0 ? r.toString() : sb.toString());
        if (r.has("isError") && r.optBoolean("isError", false)) out.put("remoteError", true);
        return out;
    }
    /** UI 面板探测入口（返回 JSON 字符串，供 MainActivity 直接展示）。 */
    public String discoverForUi(String serverName) {
        try {
            JSONObject args = new JSONObject();
            if (serverName != null && !serverName.isEmpty()) args.put("name", serverName);
            return mcpDiscover(args).toString();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return "{\"error\":\"" + msg.replace("\"", "'").replace("\n", " ") + "\"}";
        }
    }

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

    /** 小窗打开 URL：启动浮动小窗 Activity，不影响当前标签页。 */
    private JSONObject panelOpen(JSONObject args) throws Exception {
        String url = args.optString("url", "").trim();
        if (!isHttpUrl(url)) return error("valid http(s) url required");
        try {
            android.content.Context ctx = ctl.context();
            if (ctx == null) return error("app context not ready");
            android.content.Intent intent = new android.content.Intent(ctx, PanelBrowserActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(PanelBrowserActivity.EXTRA_URL, url);
            ctx.startActivity(intent);
            return new JSONObject()
                    .put("ok", true)
                    .put("openedIn", "panel")
                    .put("url", url)
                    .put("note", "目标页面已在浮动小窗中打开，当前对话页不受影响");
        } catch (Exception e) {
            return error("panel open failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
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

    /** DeepSeek++ 运行时诊断：开关状态、内置脚本缓存、assets 读取结果。 */
    private JSONObject dsppDiag() throws Exception {
        JSONObject out = new JSONObject();
        android.content.Context ctx = ctl.context();
        out.put("contextReady", ctx != null);
        if (ctx != null) {
            out.put("enabled", DeepSeekPP.isEnabled(ctx));
            JSONObject asset = new JSONObject();
            try {
                asset.put("mainworld", assetLen(ctx, "dspp/dspp_mainworld.js"));
                asset.put("content", assetLen(ctx, "dspp/dspp_content.js"));
            } catch (Exception e) {
                asset.put("error", String.valueOf(e));
            }
            out.put("assets", asset);
        }
        out.put("bindingsReady", ctl.hasUi());
        JSONObject app = ctl.dsppDiagnostics();
        if (app != null) out.put("app", app);
        return new JSONObject().put("result", out);
    }
    private int assetLen(android.content.Context ctx, String path) throws Exception {
        java.io.InputStream in = ctx.getAssets().open(path);
        int total = 0;
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) total += n;
        in.close();
        return total;
    }

    /** 列出目录内容（DeepSeek++ 文件桥）。 */
    private JSONObject fsListDir(JSONObject args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return new JSONObject().put("result", new JSONObject()
                .put("ok", false).put("error", "缺少\u201c所有文件访问\u201d权限：请在系统设置-应用-Median-权限中开启"));
        String path = args.optString("path", "/sdcard/Download");
        java.io.File dir = new java.io.File(path);
        if (!dir.exists() || !dir.isDirectory()) return new JSONObject().put("result", new JSONObject()
                .put("ok", false).put("error", "目录不存在: " + path));
        java.io.File[] files = dir.listFiles();
        JSONArray items = new JSONArray();
        if (files != null) {
            java.util.Arrays.sort(files, new java.util.Comparator<java.io.File>() {
                public int compare(java.io.File a, java.io.File b) {
                    boolean ad = a.isDirectory(), bd = b.isDirectory();
                    if (ad != bd) return ad ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (java.io.File f : files) {
                items.put(new JSONObject()
                        .put("name", f.getName())
                        .put("isDir", f.isDirectory())
                        .put("size", f.isFile() ? f.length() : 0)
                        .put("modified", f.lastModified()));
            }
        }
        return new JSONObject().put("result", new JSONObject()
                .put("ok", true).put("path", path).put("items", items));
    }
    /** 读取文件（DeepSeek++ 文件桥）。文本默认，二进制 base64。 */
    private JSONObject fsReadFile(JSONObject args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return new JSONObject().put("result", new JSONObject()
                .put("ok", false).put("error", "缺少\u201c所有文件访问\u201d权限：请在系统设置-应用-Median-权限中开启"));
        String path = args.optString("path", "");
        int maxBytes = args.optInt("maxBytes", 1048576);
        boolean binary = args.optBoolean("binary", false);
        java.io.File f = new java.io.File(path);
        if (path.isEmpty() || !f.exists() || !f.isFile()) return new JSONObject().put("result", new JSONObject()
                .put("ok", false).put("error", "文件不存在: " + path));
        if (f.length() > 64L * 1024 * 1024) return new JSONObject().put("result", new JSONObject()
                .put("ok", false).put("error", "文件过大 (>64MB): " + f.length()));
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int len = (int) Math.min(f.length(), maxBytes);
            byte[] data = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(data, off, len - off);
                if (r < 0) break;
                off += r;
            }
            if (binary) {
                String b64 = android.util.Base64.encodeToString(data, 0, off, android.util.Base64.NO_WRAP);
                return new JSONObject().put("result", new JSONObject()
                        .put("ok", true).put("path", path).put("size", off)
                        .put("truncated", off < f.length()).put("encoding", "base64").put("content", b64));
            }
            return new JSONObject().put("result", new JSONObject()
                    .put("ok", true).put("path", path).put("size", off)
                    .put("truncated", off < f.length()).put("encoding", "utf-8")
                    .put("content", new String(data, 0, off, java.nio.charset.StandardCharsets.UTF_8)));
        } finally {
            in.close();
        }
    }
    /** 按文件名模式搜索（DeepSeek++ 文件桥）。 */
    private JSONObject fsFindFile(JSONObject args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return new JSONObject().put("result", new JSONObject()
                .put("ok", false).put("error", "缺少\u201c所有文件访问\u201d权限：请在系统设置-应用-Median-权限中开启"));
        String dirPath = args.optString("dir", "/sdcard/Download");
        String pattern = args.optString("pattern", "*.apk");
        java.io.File dir = new java.io.File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) return new JSONObject().put("result", new JSONObject()
                .put("ok", false).put("error", "目录不存在: " + dirPath));
        String regex = pattern.replaceAll("([.\\\\+()\\\\[\\\\]{}^$|])", "\\\\$1").replace("*", ".*").replace("?", ".");
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
        JSONArray matches = new JSONArray();
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isFile() && p.matcher(f.getName()).matches()) {
                    matches.put(new JSONObject()
                            .put("name", f.getName())
                            .put("path", f.getAbsolutePath())
                            .put("size", f.length())
                            .put("modified", f.lastModified()));
                }
            }
        }
        return new JSONObject().put("result", new JSONObject()
                .put("ok", true).put("dir", dirPath).put("pattern", pattern).put("matches", matches));
    }


    // ==================== 工作区 + 文件读写（DeepSeek++ 文件桥扩展） ====================
    private String workspaceDir() {
        android.content.Context ctx = ctl.context();
        if (ctx == null) return "/sdcard/Download/Median/Workspace";
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("median_mcp_v1", android.content.Context.MODE_PRIVATE);
        return prefs.getString("workspace_dir", "/sdcard/Download/Median/Workspace");
    }
    private String resolvePath(String p) {
        if (p == null || p.isEmpty()) return null;
        if (p.startsWith("/")) return p;
        return workspaceDir() + "/" + p;
    }
    private JSONObject storageErr() throws Exception {
        return new JSONObject().put("result", new JSONObject()
.put("ok", false).put("error", "缺少\u201c所有文件访问\u201d权限：请在系统设置-应用-Median-权限中开启"));
    }
    private JSONObject workspaceInfo(JSONObject args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return storageErr();
        String ws = workspaceDir();
        java.io.File d = new java.io.File(ws);
        JSONObject o = new JSONObject().put("ok", true).put("workspace", ws)
                .put("exists", d.exists()).put("writable", d.isDirectory() && d.canWrite());
        if (d.exists()) {
            o.put("isDir", d.isDirectory());
            o.put("freeBytes", d.getFreeSpace());
            o.put("totalBytes", d.getTotalSpace());
        }
        return new JSONObject().put("result", o);
    }
    private JSONObject workspaceSet(JSONObject args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return storageErr();
        String p = args.optString("path", "");
        if (p.isEmpty()) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "path 必填"));
        java.io.File d = new java.io.File(p);
        if (!d.exists() && !d.mkdirs()) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "无法创建目录: " + p));
        if (!d.isDirectory()) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "不是目录: " + p));
        if (!d.canWrite()) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "目录不可写: " + p));
        android.content.Context ctx = ctl.context();
        if (ctx != null) {
            ctx.getSharedPreferences("median_mcp_v1", android.content.Context.MODE_PRIVATE)
                    .edit().putString("workspace_dir", p).apply();
        }
        return new JSONObject().put("result", new JSONObject().put("ok", true).put("workspace", p));
    }
    private JSONObject fsWriteFile(JSONObject args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return storageErr();
        String path = resolvePath(args.optString("path", ""));
        if (path == null) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "path 必填"));
        String content = args.optString("content", "");
        boolean binary = args.optBoolean("binary", false);
        byte[] data;
        try {
            if (binary) data = android.util.Base64.decode(content, android.util.Base64.DEFAULT);
            else data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "base64 解码失败"));
        }
        if (data.length > 64L * 1024 * 1024) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "内容过大 (>64MB)"));
        java.io.File f = new java.io.File(path);
        java.io.File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "无法创建父目录: " + parent.getAbsolutePath()));
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try { out.write(data); } finally { out.close(); }
        return new JSONObject().put("result", new JSONObject().put("ok", true).put("path", path).put("size", data.length).put("mode", binary ? "base64" : "utf-8"));
    }
    private JSONObject fsAppendFile(JSONObject args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return storageErr();
        String path = resolvePath(args.optString("path", ""));
        if (path == null) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "path 必填"));
        String content = args.optString("content", "");
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (data.length > 64L * 1024 * 1024) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "内容过大 (>64MB)"));
        java.io.File f = new java.io.File(path);
        java.io.File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "无法创建父目录"));
        java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
        try { out.write(data); } finally { out.close(); }
        return new JSONObject().put("result", new JSONObject().put("ok", true).put("path", path).put("size", f.length()));
    }
    private JSONObject fsCreateDir(JSONObject args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return storageErr();
        String path = resolvePath(args.optString("path", ""));
        if (path == null) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "path 必填"));
        java.io.File d = new java.io.File(path);
        if (d.exists()) return new JSONObject().put("result", new JSONObject().put("ok", true).put("path", path).put("existed", true));
        if (!d.mkdirs()) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "创建失败: " + path));
        return new JSONObject().put("result", new JSONObject().put("ok", true).put("path", path).put("existed", false));
    }
    private JSONObject fsDelete(JSONObject args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return storageErr();
        String path = resolvePath(args.optString("path", ""));
        if (path == null) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "path 必填"));
        java.io.File f = new java.io.File(path);
        if (!f.exists()) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "不存在: " + path));
        boolean ok = deleteRecursive(f);
        return new JSONObject().put("result", new JSONObject().put("ok", ok).put("path", path).put("deleted", ok));
    }
    private boolean deleteRecursive(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) for (java.io.File k : kids) deleteRecursive(k);
        }
        return f.delete();
    }
    private JSONObject fsCopyMove(JSONObject args, boolean move) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return storageErr();
        String src = resolvePath(args.optString("src", ""));
        String dst = resolvePath(args.optString("dst", ""));
        if (src == null || dst == null) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "src/dst 必填"));
        java.io.File s = new java.io.File(src), d = new java.io.File(dst);
        if (!s.exists()) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "源不存在: " + src));
        if (s.getAbsolutePath().equals(d.getAbsolutePath())) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "源与目标相同"));
        if (move) {
            if (d.exists()) deleteRecursive(d);
            boolean ok = s.renameTo(d);
            if (!ok) { copyRecursive(s, d); ok = d.exists(); deleteRecursive(s); }
            return new JSONObject().put("result", new JSONObject().put("ok", ok).put("op", "move").put("src", src).put("dst", dst));
        }
        if (d.exists()) deleteRecursive(d);
        boolean ok = copyRecursive(s, d);
        return new JSONObject().put("result", new JSONObject().put("ok", ok).put("op", "copy").put("src", src).put("dst", dst));
    }
    private boolean copyRecursive(java.io.File src, java.io.File dst) {
        try {
            if (src.isDirectory()) {
                if (!dst.exists() && !dst.mkdirs()) return false;
                java.io.File[] kids = src.listFiles();
                if (kids != null) for (java.io.File k : kids) if (!copyRecursive(k, new java.io.File(dst, k.getName()))) return false;
                return true;
            }
            java.io.File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            } finally { in.close(); out.close(); }
            return true;
        } catch (Exception e) { return false; }
    }
    private JSONObject fsInfo(JSONObject args) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return storageErr();
        String path = resolvePath(args.optString("path", ""));
        if (path == null) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "path 必填"));
        java.io.File f = new java.io.File(path);
        if (!f.exists()) return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", "不存在: " + path));
        return new JSONObject().put("result", new JSONObject().put("ok", true)
                .put("path", path).put("isDir", f.isDirectory()).put("size", f.length())
                .put("modified", f.lastModified()).put("parent", f.getParent()));
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
        // 实测结论：Chromium WebView 不处理外部合成的 MotionEvent（dispatchTouchEvent 无效，
        // 页面监听器不触发、链接不导航）；JS 派发 pointer/mouse 完整序列可正常触发
        // 监听器、默认行为与真实导航。故改为 JS 定位 + JS 事件序列派发。
        String script = "(function(){var el=document.elementFromPoint(" + x + "," + y + ");"
                + "if(!el)return JSON.stringify({ok:false,error:'no element at point'});"
                + "var opts={bubbles:true,cancelable:true,view:window,clientX:" + x + ",clientY:" + y + ",button:0,buttons:1};"
                + "function fire(t,ctor,extra){try{el.dispatchEvent(new ctor(t,Object.assign({},opts,extra)));}"
                + "catch(e){try{el.dispatchEvent(new MouseEvent(t,opts));}catch(e2){}}}"
                + "fire('pointerdown',PointerEvent,{pointerId:1,pointerType:'touch',isPrimary:true});"
                + "fire('mousedown',MouseEvent,{});"
                + "fire('pointerup',PointerEvent,{pointerId:1,pointerType:'touch',isPrimary:true});"
                + "fire('mouseup',MouseEvent,{});"
                + "fire('click',MouseEvent,{});"
                + "return JSON.stringify({ok:true,tag:el.tagName.toLowerCase(),x:" + x + ",y:" + y + "})})()";
        String raw = ctl.evalJs(wv, script, 5000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected tap result");
        JSONObject out = (JSONObject) decoded;
        if (!out.optBoolean("ok", false)) return error(out.optString("error", "tap failed"));
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
        return new JSONObject().put("ok", true).put("pressed", keys).put("handled", info.optBoolean("handled", false));
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
        else if ("delete".equals(lower) || "del".equals(lower)) { key = "Delete"; code = "'Delete'"; keyCode = 46; }
        else if ("home".equals(lower)) { key = "Home"; code = "'Home'"; keyCode = 36; }
        else if ("end".equals(lower)) { key = "End"; code = "'End'"; keyCode = 35; }
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
        String charJson = key.length() == 1 ? keyJson : "''";
        String script = """
            (function(){
            var el=document.activeElement;
            if(!el) return JSON.stringify({ok:false,error:'no focused element'});
            var opts={key:__KEY__,code:__CODE__,keyCode:__KC__,which:__KC__,bubbles:true,cancelable:true,ctrlKey:__CTRL__,shiftKey:__SHIFT__,altKey:__ALT__};
            var k=opts.key, sh=opts.shiftKey;
            var cancelled=!el.dispatchEvent(new KeyboardEvent('keydown',opts));
            if(k!=='Enter'&&k!=='Tab'&&k!=='Escape'&&k!=='Backspace'&&k!=='Delete'&&k!=='Home'&&k!=='End'&&k!=='ArrowLeft'&&k!=='ArrowRight'&&k!=='ArrowUp'&&k!=='ArrowDown') el.dispatchEvent(new KeyboardEvent('keypress',opts));
            var handled=false;
            if(!cancelled){
            if(k==='Enter'){
            var f=el.tagName==='FORM'?el:el.form;
            if(f){f.submit();handled=true;}
            }
            else if(k==='Backspace'||k==='Delete'){
            if(el.selectionStart!==undefined&&el.selectionStart!==null){
            var s=el.selectionStart,e=el.selectionEnd,v=el.value||'';
            if(k==='Backspace'){if(s!==e){el.value=v.slice(0,s)+v.slice(e);el.setSelectionRange(s,s);}
            else if(s>0){el.value=v.slice(0,s-1)+v.slice(s);el.setSelectionRange(s-1,s-1);}}
            else{if(s!==e){el.value=v.slice(0,s)+v.slice(e);el.setSelectionRange(s,s);}
            else if(s<v.length){el.value=v.slice(0,s)+v.slice(s+1);el.setSelectionRange(s,s);}}
            el.dispatchEvent(new Event('input',{bubbles:true}));
            handled=true;
            }
            }
            else if(k==='Tab'){
            var fs=Array.prototype.slice.call(document.querySelectorAll(\"a[href],button,input,select,textarea,[tabindex]:not([tabindex='-1'])\")).filter(function(x){return !x.disabled&&x.offsetParent!==null;});
            var idx=fs.indexOf(el);
            var next;
            if(sh){next=idx<=0?fs[fs.length-1]:fs[idx-1];}
            else{next=idx<0?fs[0]:fs[(idx+1)%fs.length];}
            if(next){next.focus();handled=true;}
            }
            else if(k==='Escape'){
            if(el.blur)el.blur();
            handled=true;
            }
            else if(k==='Home'||k==='End'){
            if(el.selectionStart!==undefined&&el.selectionStart!==null){var vv=el.value||'';var pos=k==='End'?vv.length:0;el.setSelectionRange(pos,pos);handled=true;}
            }
            else if(k==='ArrowLeft'){
            if(el.selectionStart!==undefined&&el.selectionStart!==null){var cs=el.selectionStart;if(cs>0){el.setSelectionRange(cs-1,cs-1);handled=true;}}
            }
            else if(k==='ArrowRight'){
            if(el.selectionStart!==undefined&&el.selectionStart!==null){var cs2=el.selectionStart,v2=el.value||'';if(cs2<v2.length){el.setSelectionRange(cs2+1,cs2+1);handled=true;}}
            }
            else if(k==='ArrowUp'){
            if(el.selectionStart!==undefined&&el.selectionStart!==null){el.setSelectionRange(0,0);handled=true;}
            else{window.scrollBy(0,-120);handled=true;}
            }
            else if(k==='ArrowDown'){
            if(el.selectionStart!==undefined&&el.selectionStart!==null){var v3=el.value||'';el.setSelectionRange(v3.length,v3.length);handled=true;}
            else{window.scrollBy(0,120);handled=true;}
            }
            else if((k==='a'||k==='A')&&opts.ctrlKey){
            if(el.select)el.select();
            handled=true;
            }
            else if(k.length===1&&!opts.ctrlKey&&!opts.altKey){
            var start=el.selectionStart!==undefined&&el.selectionStart!==null?el.selectionStart:el.value.length;
            var end=el.selectionEnd!==undefined&&el.selectionEnd!==null?el.selectionEnd:el.value.length;
            var v4=el.value||'';el.value=v4.slice(0,start)+__KEYCHAR__+v4.slice(end);
            el.setSelectionRange(start+1,start+1);
            el.dispatchEvent(new Event('input',{bubbles:true}));
            handled=true;
            }
            }
            el.dispatchEvent(new KeyboardEvent('keyup',opts));
            return JSON.stringify({ok:true,handled:handled});
            })()
            """;
        return script.replace("__KEY__", keyJson).replace("__CODE__", code)
                .replace("__KC__", String.valueOf(keyCode))
                .replace("__CTRL__", String.valueOf(ctrl)).replace("__SHIFT__", String.valueOf(shift))
                .replace("__ALT__", String.valueOf(alt)).replace("__KEYCHAR__", charJson);
    }

    // ==================== 标签管理 ====================

    private JSONObject tabs() throws Exception {
        List<?> list = ctl.liveTabs();
        if (list == null) return error("tabs not ready");
        int cur = ctl.currentTabIndex();
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof MainActivity.BrowserTab) {
                MainActivity.BrowserTab t = (MainActivity.BrowserTab) item;
                arr.put(new JSONObject()
                        .put("id", i)
                        .put("title", t.title == null ? "" : t.title)
                        .put("url", t.url == null ? "" : t.url)
                        .put("pinned", t.pinned)
                        .put("active", i == cur));
            } else if (item instanceof JSONObject) {
                JSONObject t = (JSONObject) item;
                arr.put(new JSONObject()
                        .put("id", i)
                        .put("title", t.optString("title", ""))
                        .put("url", t.optString("url", ""))
                        .put("pinned", t.optBoolean("pinned", false))
                        .put("active", i == cur));
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
    /** 原生导航：back/forward/reload/home —— 等价点击底部工具栏按钮（非网页 DOM）。 */
    private JSONObject nav(String action) throws Exception {
        final WebView wv = requireWebView();
        Boolean done = ctl.onUi(new McpController.BlockingCall<Boolean>() {
            @Override public Boolean run() {
                try {
                    if ("back".equals(action)) {
                        if (!wv.canGoBack()) return false;
                        wv.goBack();
                    } else if ("forward".equals(action)) {
                        if (!wv.canGoForward()) return false;
                        wv.goForward();
                    } else if ("reload".equals(action)) {
                        wv.reload();
                    } else if ("home".equals(action)) {
                        ctl.showHome();
                    } else {
                        return false;
                    }
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        }, 3000);
        if (done == null) return error("nav timeout");
        if (!done) return error("unavailable: " + action);
        return new JSONObject().put("ok", true).put("action", action);
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

    private JSONObject netRuleAdd(JSONObject args) throws Exception {
        String type = args.optString("type", "");
        String pattern = args.optString("pattern", "");
        String match = args.has("match") ? args.optString("match", "") : null;
        String target = args.has("target") ? args.optString("target", "") : null;
        boolean enabled = args.optBoolean("enabled", true);
        JSONObject rule = ctl.addNetRule(type, pattern, match, target, enabled);
        if (rule == null) return error("invalid rule: type must be block|redirect|inject|replace, pattern required");
        return rule;
    }

    private JSONObject netRuleList() throws Exception {
        return new JSONObject().put("count", ctl.netRuleSnapshot().length()).put("rules", ctl.netRuleSnapshot());
    }

    private JSONObject netRuleRemove(JSONObject args) throws Exception {
        String id = args.optString("id", "");
        if (id.isEmpty()) return error("id required");
        return new JSONObject().put("removed", ctl.removeNetRule(id));
    }

    private JSONObject netRuleClear() throws Exception {
        return new JSONObject().put("cleared", ctl.clearNetRules());
    }

    private JSONObject har(JSONObject args) throws Exception {
        int limit = Math.max(1, Math.min(args.optInt("limit", 200), 500));
        List<JSONObject> all = ctl.networkSnapshot();
        int from = Math.max(0, all.size() - limit);
        JSONObject har = new JSONObject();
        har.put("log", new JSONObject().put("version", "1.2").put("creator",
                new JSONObject().put("name", "MedianBrowser MCP").put("version", "3.0"))
                .put("entries", entriesJson(all, from)));
        if (args.optBoolean("save", false)) {
            return saveHarFile(har);
        }
        return har;
    }

    /** 将 HAR JSON 保存到 Download/Median/（API29+ 用 MediaStore；低版本回退应用目录），返回路径信息。 */
    private JSONObject saveHarFile(JSONObject har) throws Exception {
        String fileName = "median-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new java.util.Date()) + ".har";
        byte[] data = har.toString().getBytes(StandardCharsets.UTF_8);
        android.content.Context ctx = ctl.context();
        if (ctx == null) return error("context not ready");
        String location = "";
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Median");
            android.net.Uri uri = ctx.getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                if (os != null) {
                    try { os.write(data); } finally { os.close(); }
                }
                location = uri.toString();
            }
        } else {
            java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "Median");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, fileName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            try { fos.write(data); } finally { fos.close(); }
            location = f.getAbsolutePath();
        }
        ctl.recordRunLog("info", "har", "saved " + fileName + " -> " + location);
        return new JSONObject().put("saved", true).put("fileName", fileName)
                .put("location", location).put("entries", har.optJSONObject("log") == null ? 0
                        : har.optJSONObject("log").optJSONArray("entries") == null ? 0
                        : har.optJSONObject("log").optJSONArray("entries").length());
    }

    private JSONArray entriesJson(List<JSONObject> logs, int from) throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = from; i < logs.size(); i++) {
            JSONObject e = logs.get(i);
            JSONObject entry = new JSONObject();
            entry.put("startedDateTime", java.time.Instant.ofEpochMilli(e.optLong("time"))
                    .toString().replace("Z", "+00:00"));
            entry.put("time", 0);
            JSONObject req = new JSONObject();
            req.put("method", e.optString("method", "GET"));
            req.put("url", e.optString("url", ""));
            req.put("httpVersion", "HTTP/2");
            JSONObject headers = new JSONObject();
            headers.put("mainFrame", e.optBoolean("mainFrame", false));
            req.put("headers", headers);
            entry.put("request", req);
            JSONObject resp = new JSONObject().put("status", e.optInt("status", 0))
                    .put("statusText", "").put("httpVersion", "")
                    .put("headers", new JSONObject()).put("mimeType", e.optString("mimeType", ""));
            JSONObject content = new JSONObject().put("size", e.optLong("size", 0));
            String body = e.optString("body", "");
            if (!body.isEmpty()) content.put("text", body);
            resp.put("content", content);
            entry.put("response", resp);
            entry.put("cache", new JSONObject());
            entry.put("timings", new JSONObject().put("send", 0).put("wait", 0).put("receive", 0));
            arr.put(entry);
        }
        return arr;
    }

    private JSONObject hook(JSONObject args) throws Exception {
        String action = args.optString("action", "get");
        if ("add".equals(action)) {
            String script = args.optString("script", "");
            JSONObject h = ctl.addJsHook(script, true);
            if (h == null) return error("script required");
            return h;
        } else if ("remove".equals(action)) {
            String id = args.optString("id", "");
            if (id.isEmpty()) return error("id required");
            return new JSONObject().put("removed", ctl.removeJsHook(id));
        } else if ("clear".equals(action)) {
            return new JSONObject().put("cleared", ctl.clearJsHooks());
        }
        return new JSONObject().put("count", ctl.jsHookSnapshot().length()).put("hooks", ctl.jsHookSnapshot());
    }

    private JSONObject fingerprint(JSONObject args) throws Exception {
        String action = args.optString("action", "get");
        if ("set".equals(action)) {
            String level = args.optString("level", "off");
            ctl.setFingerprintLevel(level);
            return new JSONObject().put("level", ctl.fingerprintLevel()).put("applied", true);
        }
        return new JSONObject().put("level", ctl.fingerprintLevel())
                .put("script", ctl.fingerprintScript().length() > 400 ? ctl.fingerprintScript().substring(0, 400) + "..." : ctl.fingerprintScript());
    }

    private JSONObject packetAnalyze(JSONObject args) throws Exception {
        int limit = Math.max(1, Math.min(args.optInt("limit", 1000), 2000));
        List<JSONObject> all = ctl.networkSnapshot();
        int from = Math.max(0, all.size() - limit);
        java.util.Map<String, Integer> hosts = new java.util.LinkedHashMap<String, Integer>();
        java.util.Map<String, Integer> methods = new java.util.LinkedHashMap<String, Integer>();
        java.util.Map<String, Integer> frames = new java.util.LinkedHashMap<String, Integer>();
        java.util.ArrayList<String> sensitive = new java.util.ArrayList<String>();
        java.util.ArrayList<String> suspicious = new java.util.ArrayList<String>();
        long first = 0, last = 0;
        int total = 0;
        java.util.Set<String> urlSet = new java.util.LinkedHashSet<String>();
        for (int i = from; i < all.size(); i++) {
            JSONObject e = all.get(i);
            total++;
            String url = e.optString("url", "");
            String host = hostOfUrl(url);
            inc(hosts, host.isEmpty() ? "(unknown)" : host);
            inc(methods, e.optString("method", "GET"));
            inc(frames, e.optBoolean("mainFrame", false) ? "main" : "sub");
            urlSet.add(url);
            long t = e.optLong("time");
            if (first == 0 || t < first) first = t;
            if (t > last) last = t;
            String low = url.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("login") || low.contains("password") || low.contains("token")
                    || low.contains("auth") || low.contains("secret") || low.contains("session")
                    || low.contains("admin") || low.contains("upload") || low.contains("api/")) {
                sensitive.add(url.length() > 180 ? url.substring(0, 180) : url);
            }
            if (isSuspiciousUrl(url)) suspicious.add(url.length() > 180 ? url.substring(0, 180) : url);
        }
        JSONObject out = new JSONObject();
        out.put("summary", new JSONObject().put("totalRequests", total).put("uniqueUrls", urlSet.size())
                .put("spanMs", last - first));
        JSONObject hostObj = new JSONObject();
        for (java.util.Map.Entry<String, Integer> en : hosts.entrySet()) hostObj.put(en.getKey(), en.getValue());
        out.put("hosts", hostObj);
        JSONObject methodObj = new JSONObject();
        for (java.util.Map.Entry<String, Integer> en : methods.entrySet()) methodObj.put(en.getKey(), en.getValue());
        out.put("methods", methodObj);
        JSONObject frameObj = new JSONObject();
        for (java.util.Map.Entry<String, Integer> en : frames.entrySet()) frameObj.put(en.getKey(), en.getValue());
        out.put("frames", frameObj);
        JSONArray sens = new JSONArray();
        for (String s : sensitive) sens.put(s);
        out.put("sensitiveUrls", sens);
        JSONArray susp = new JSONArray();
        for (String s : suspicious) susp.put(s);
        out.put("suspiciousUrls", susp);
        out.put("note", "sensitiveUrls=含 login/password/token/auth/api 等敏感路径; suspiciousUrls=可疑外联（IP直连/非常规端口/可疑域名特征），供 AI 分析。");
        if (args.optBoolean("llm", false)) {
            JSONObject ai = llmAnalyze(out);
            out.put("llm", ai);
        }
        return out;
    }

    /** 调用已配置的外部 LLM（OpenAI 兼容 /chat/completions）对抓包分析做深度结论。 */
    private JSONObject llmAnalyze(JSONObject analysis) throws Exception {
        android.content.Context ctx = ctl.context();
        if (ctx == null) return error("context not ready");
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("median_mcp_v1", android.content.Context.MODE_PRIVATE);
        String endpoint = prefs.getString("mcp_llm_endpoint", "https://api.openai.com/v1/chat/completions");
        String model = prefs.getString("mcp_llm_model", "gpt-4o-mini");
        String key = prefs.getString("mcp_llm_key", "");
        if (key.isEmpty()) {
            return new JSONObject().put("error", "LLM not configured - use ai_configure action=set first");
        }
        String prompt = "你是网络安全与流量分析专家。以下是浏览器抓包的启发式分析数据（JSON），请用中文给出简要结论："
                + "1) 流量规模与主要域名；2) 存在的敏感请求及风险点；3) 可疑外联行为；4) 改进建议。不要编造数据。\n" + analysis.toString();
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", prompt)));
        payload.put("temperature", 0.3);
        payload.put("max_tokens", 800);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setDoOutput(true);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            java.io.OutputStream os = conn.getOutputStream();
            try { os.write(body); } finally { os.close(); }
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            if (in != null) {
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
            }
            conn.disconnect();
            String raw = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (code >= 400) {
                return new JSONObject().put("error", "LLM HTTP " + code + ": " + (raw.length() > 300 ? raw.substring(0, 300) : raw));
            }
            JSONObject resp = new JSONObject(raw);
            JSONArray choices = resp.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject msg = choices.optJSONObject(0);
                if (msg != null && msg.optJSONObject("message") != null) {
                    String content = msg.optJSONObject("message").optString("content", "");
                    return new JSONObject().put("model", model).put("endpoint", endpoint)
                            .put("conclusion", content).put("status", "ok");
                }
            }
            return new JSONObject().put("error", "unexpected LLM response").put("raw", raw.length() > 300 ? raw.substring(0, 300) : raw);
        } catch (Exception e) {
            return new JSONObject().put("error", "LLM call failed: " + e.getMessage());
        }
    }

    private void inc(java.util.Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    private String hostOfUrl(String url) {
        try {
            java.net.URI u = new java.net.URI(url);
            String h = u.getHost();
            return h == null ? "" : h;
        } catch (Exception e) { return ""; }
    }

    private boolean isSuspiciousUrl(String url) {
        String low = url.toLowerCase(java.util.Locale.ROOT);
        try {
            java.net.URI u = new java.net.URI(url);
            String host = u.getHost();
            if (host == null) return false;
            String h = host.toLowerCase(java.util.Locale.ROOT);
            int port = u.getPort();
            if (port > 0 && port != 80 && port != 443) return true;
            if (h.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) return true;
            if (h.endsWith(".xyz") || h.endsWith(".top") || h.endsWith(".click")
                    || h.endsWith(".tk") || h.endsWith(".ml") || h.endsWith(".ga") || h.endsWith(".cf")) return true;
        } catch (Exception e) { return false; }
        return low.contains("phpinfo") || low.contains("eval(") || low.contains("base64");
    }

    private JSONObject network(JSONObject args) throws Exception {
        boolean withBody = args != null && args.optBoolean("body", false);
        List<JSONObject> all = ctl.networkSnapshot();
        JSONArray logs = new JSONArray();
        JSONArray failures = new JSONArray();
        for (int i = Math.max(0, all.size() - 100); i < all.size(); i++) {
            JSONObject item = all.get(i);
            if (!withBody) {
                JSONObject slim = new JSONObject();
                slim.put("url", item.optString("url", ""));
                slim.put("mainFrame", item.optBoolean("mainFrame", false));
                slim.put("method", item.optString("method", "GET"));
                slim.put("time", item.optLong("time"));
                slim.put("status", item.optInt("status", 0));
                slim.put("mimeType", item.optString("mimeType", ""));
                slim.put("size", item.optLong("size", 0));
                if (item.has("body")) slim.put("hasBody", true);
                logs.put(slim);
            } else {
                logs.put(item);
            }
            int status = item.optInt("status", 0);
            if (status == 0 || status >= 400) failures.put(item);
        }
        return new JSONObject()
                .put("total", all.size())
                .put("shown", logs.length())
                .put("failures", failures)
                .put("logs", logs);
    }

    // ==================== MITM 代理 / LLM 配置 ====================

    /** MITM 代理控制：get / on / off / export_ca。 */
    private JSONObject proxyCtl(JSONObject args) throws Exception {
        android.content.Context ctx = ctl.context();
        if (ctx == null) return error("context not ready");
        String action = args.optString("action", "get");
        MitmProxy proxy = ctl.proxy();
        if (proxy == null) return error("proxy not initialized");
        if ("on".equals(action)) {
            boolean ok = ctl.proxySet(ctx, true);
            return new JSONObject().put("enabled", true).put("caReady", ok)
                    .put("port", ctl.port()).put("caFingerprint", proxy.caFingerprint());
        }
        if ("off".equals(action)) {
            ctl.proxySet(ctx, false);
            return new JSONObject().put("enabled", false);
        }
        if ("export_ca".equals(action)) {
            String pem = proxy.caPem();
            if (pem == null || pem.isEmpty()) return error("CA not ready");
            String fileName = "median-mitm-ca.crt";
            String location = writePublicFile(ctx, fileName, "application/x-x509-ca-cert", pem.getBytes(StandardCharsets.UTF_8));
            if (location == null || location.isEmpty()) return error("failed to write CA file");
            return new JSONObject().put("exported", true).put("fileName", fileName)
                    .put("location", location).put("fingerprint", proxy.caFingerprint())
                    .put("usage", "将证书安装到设备信任库（设置-安全-加密与凭据-安装证书-CA证书），安装后客户端即可信任本代理签发的全部域名证书。");
        }
        return new JSONObject().put("enabled", proxy.isEnabled())
                .put("port", ctl.port())
                .put("caReady", proxy.caPem() != null && !proxy.caPem().isEmpty())
                .put("caFingerprint", proxy.caFingerprint())
                .put("tunnels", proxy.tunnelCount())
                .put("bytesUp", proxy.bytesUp())
                .put("bytesDown", proxy.bytesDown());
    }

    /** 写入公开文件（Download/Median/），返回 location（uri 或路径）。 */
    private String writePublicFile(android.content.Context ctx, String fileName, String mime, byte[] data) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Median");
                android.net.Uri uri = ctx.getContentResolver()
                        .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        try { os.write(data); } finally { os.close(); }
                    }
                    return uri.toString();
                }
                return "";
            } else {
                java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "Median");
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, fileName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                try { fos.write(data); } finally { fos.close(); }
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 外部 LLM 配置：get / set / test。 */
    private JSONObject aiConfigure(JSONObject args) throws Exception {
        android.content.Context ctx = ctl.context();
        if (ctx == null) return error("context not ready");
        String action = args.optString("action", "get");
        if ("set".equals(action)) {
            String endpoint = args.has("endpoint") ? args.optString("endpoint", "") : null;
            String model = args.has("model") ? args.optString("model", "") : null;
            String apiKey = args.has("apiKey") ? args.optString("apiKey", "") : null;
            ctl.llmSet(ctx, endpoint, model, apiKey);
            return new JSONObject().put("configured", true).put("config", ctl.llmConfig(ctx));
        }
        if ("test".equals(action)) {
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("median_mcp_v1", android.content.Context.MODE_PRIVATE);
            String key = prefs.getString("mcp_llm_key", "");
            if (key.isEmpty()) return new JSONObject().put("error", "LLM not configured - set apiKey first");
            return llmAnalyze(new JSONObject().put("probe", "connectivity test").put("note", "此消息用于验证 LLM 配置连通性"));
        }
        return ctl.llmConfig(ctx);
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
        JSONObject net = network(new JSONObject());
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
        int failCount = 0;
        if (failArr != null) {
            for (int i = 0; i < failArr.length(); i++) {
                JSONObject f = failArr.optJSONObject(i);
                if (f != null && f.optInt("status", 0) >= 400) failCount++;  // 仅确认失败（4xx/5xx）计入健康判定
            }
        }
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
    /** 浏览器运行日志（应用自身事件）。 */
    private JSONObject logs(JSONObject args) throws Exception {
        List<JSONObject> all = ctl.runLogSnapshot();
        int limit = args.optInt("limit", 200);
        if (limit > 0 && all.size() > limit) all = new ArrayList<JSONObject>(all.subList(all.size() - limit, all.size()));
        JSONArray arr = new JSONArray();
        for (JSONObject e : all) arr.put(e);
        return new JSONObject().put("count", arr.length()).put("items", arr);
    }
    /** 浏览器与设备诊断信息。 */
    private JSONObject info() throws Exception {
        JSONObject out = new JSONObject();
        out.put("app", "Median " + VERSION);
        out.put("android", android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        out.put("model", android.os.Build.MODEL);
        WebView wv = null;
        try { wv = requireWebView(); } catch (Exception ignored) { }
        if (wv != null) {
            final WebView fwv = wv;
            JSONObject wi = ctl.onUi(new McpController.BlockingCall<JSONObject>() {
                @Override public JSONObject run() {
                    try {
                        JSONObject j = new JSONObject();
                        j.put("url", fwv.getUrl());
                        j.put("title", fwv.getTitle());
                        String ua = fwv.getSettings().getUserAgentString();
                        j.put("ua", ua);
                        String chromeVer = "";
                        int ci = ua.indexOf("Chrome/");
                        if (ci >= 0) {
                            int end = ua.indexOf(' ', ci);
                            chromeVer = ua.substring(ci + 7, end < 0 ? ua.length() : end);
                        }
                        j.put("webViewChrome", chromeVer);
                        j.put("jsEnabled", fwv.getSettings().getJavaScriptEnabled());
                        android.content.res.Resources res = fwv.getContext().getResources();
                        j.put("screen", res.getDisplayMetrics().widthPixels + "x" + res.getDisplayMetrics().heightPixels
                                + "@" + res.getDisplayMetrics().densityDpi + "dpi");
                        return j;
                    } catch (Exception e) {
                        return null;
                    }
                }
            }, 3000);
            if (wi != null) {
                for (java.util.Iterator<String> it = wi.keys(); it.hasNext(); ) {
                    String k = it.next();
                    out.put(k, wi.get(k));
                }
            }
        }
        out.put("mcpPort", ctl.port());
        out.put("mcpToken", ctl.token());
        out.put("tabCount", tabCount());
        out.put("consoleCount", ctl.consoleSnapshot().size());
        out.put("networkCount", ctl.networkSnapshot().size());
        out.put("runLogCount", ctl.runLogSnapshot().size());
        JSONObject s = ctl.settingsSnapshot();
        if (s != null) out.put("settings", s);
        return out;
    }
    /** 读取/修改浏览器设置。 */
    private JSONObject settings(JSONObject args) throws Exception {
        String action = args.optString("action", "get");
        if ("get".equals(action)) {
            JSONObject s = ctl.settingsSnapshot();
            if (s == null) return error("mcp not attached");
            return s;
        }
        if ("set".equals(action)) {
            String key = args.optString("key", "");
            String value = args.optString("value", "");
            if (key.isEmpty()) return error("key required");
            String err = ctl.applySetting(key, value);
            if (err != null) return error(err);
            JSONObject out = new JSONObject().put("ok", true).put("key", key).put("value", value);
            JSONObject s = ctl.settingsSnapshot();
            if (s != null) out.put("settings", s);
            return out;
        }
        return error("action must be get|set");
    }
    /** 页面滚动。 */
    private JSONObject scroll(JSONObject args) throws Exception {
        final WebView wv = requireWebView();
        String direction = args.optString("direction", "down");
        if (!"up".equals(direction) && !"down".equals(direction) && !"top".equals(direction) && !"bottom".equals(direction)) {
            return error("direction must be up|down|top|bottom");
        }
        final int pixels = args.optInt("pixels", 0);
        String script = "(function(){var d=" + jsonString(direction) + ",p=" + pixels + ";"
                + "var max=Math.max(document.documentElement.scrollHeight-document.documentElement.clientHeight,"
                + "document.body.scrollHeight-window.innerHeight);if(max<0)max=0;"
                + "var y;if(d==='top')y=0;else if(d==='bottom')y=max;"
                + "else if(d==='up')y=Math.max(0,window.scrollY-(p||Math.round(window.innerHeight*0.8)));"
                + "else y=Math.min(max,window.scrollY+(p||Math.round(window.innerHeight*0.8)));"
                + "window.scrollTo(0,y);return JSON.stringify({y:window.scrollY,max:max,dir:d})})()";
        String raw = ctl.evalJs(wv, script, 5000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected scroll result");
        return (JSONObject) decoded;
    }
    /** 读取 Cookie。 */
    private JSONObject cookies(JSONObject args) throws Exception {
        final WebView wv = requireWebView();
        final String url = args.optString("url", "").trim();
        String target = url;
        if (target.isEmpty()) {
            final String[] cur = new String[1];
            Boolean got = ctl.onUi(new McpController.BlockingCall<Boolean>() {
                @Override public Boolean run() {
                    try { cur[0] = wv.getUrl(); return true; } catch (Exception e) { return false; }
                }
            }, 3000);
            if (got != null && got) target = cur[0] == null ? "" : cur[0];
        }
        if (target.isEmpty()) return error("no current url, pass url param");
        final String fTarget = target;
        final String[] cookie = new String[1];
        Boolean done = ctl.onUi(new McpController.BlockingCall<Boolean>() {
            @Override public Boolean run() {
                try {
                    cookie[0] = android.webkit.CookieManager.getInstance().getCookie(fTarget);
                    return true;
                } catch (Exception e) {
                    cookie[0] = null;
                    return false;
                }
            }
        }, 3000);
        if (done == null) return error("cookie timeout");
        if (!done) return error("cookie read failed");
        JSONObject out = new JSONObject().put("url", fTarget);
        if (cookie[0] == null || cookie[0].isEmpty()) {
            out.put("count", 0).put("cookies", new JSONObject());
            return out;
        }
        JSONObject map = new JSONObject();
        for (String pair : cookie[0].split(";")) {
            String p = pair.trim();
            int eq = p.indexOf('=');
            if (eq > 0) map.put(p.substring(0, eq), p.substring(eq + 1));
        }
        return out.put("count", map.length()).put("cookies", map);
    }
    /** 读取 localStorage/sessionStorage（当前源）。 */
    private JSONObject storage() throws Exception {
        final WebView wv = requireWebView();
        String script = "(function(){function dump(s){var o={};for(var i=0;i<s.length;i++){var k=s.key(i);"
                + "var v=s.getItem(k);if(v!==null&&v.length>200)v=v.slice(0,200)+'...(truncated)';o[k]=v;}return o;}"
                + "return JSON.stringify({origin:location.origin,local:dump(localStorage),session:dump(sessionStorage)})})()";
        String raw = ctl.evalJs(wv, script, 5000);
        if (raw == null) return error("TIMEOUT_OR_NO_PAGE");
        Object decoded = decodeEvalValue(raw);
        if (!(decoded instanceof JSONObject)) return error("unexpected storage result");
        return (JSONObject) decoded;
    }
    /** 添加书签（便捷包装，已存在则忽略）。 */
    private JSONObject bookmarkAdd(JSONObject args) throws Exception {
        String url = args.optString("url", "").trim();
        String title = args.optString("title", "").trim();
        if (url.isEmpty()) return error("url required");
        if (!isHttpUrl(url)) return error("valid http(s) url required");
        ctl.addBookmark(url, title.isEmpty() ? url : title);
        return new JSONObject().put("ok", true).put("url", url).put("title", title.isEmpty() ? url : title);
    }
    /** 清空浏览历史。 */
    private JSONObject historyClear() throws Exception {
        ctl.clearHistory();
        return new JSONObject().put("ok", true);
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
                    + "function esc(c){return (window.CSS&&CSS.escape)?CSS.escape(c):c.replace(/([^a-zA-Z0-9_-])/g,'\\\\$1');}"
                    + "function sel(n){if(!n||n===document.body)return 'body';if(n.id)return '#'+esc(n.id);"
                    + "var parts=[],cur=n;for(var k=0;k<4&&cur&&cur!==document.body;k++){"
                    + "var cls='';if(cur.className&&typeof cur.className==='string'){var m=cur.className.trim().split(/\\s+/)[0];if(m)cls=m;}"
                    + "var tag=cur.tagName.toLowerCase();var p=cur.parentElement;var s=p?Array.from(p.children).indexOf(cur)+1:1;"
                    + "var part=tag+':nth-child('+s+')';"
                    + "if(cls){part=tag+'.'+esc(cls);var same=p?Array.from(p.children).filter(function(c){return c.tagName===cur.tagName&&c.className&&typeof c.className==='string'&&c.className.trim().split(/\\s+/)[0]===cls}).length:1;if(same>1)part+=':nth-child('+s+')';}"
                    + "parts.unshift(part);cur=p;}"
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