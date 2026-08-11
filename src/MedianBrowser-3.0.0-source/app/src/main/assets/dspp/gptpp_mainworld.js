// ==UserScript==
// @name         Median ChatGPT++ MCP Bridge
// @namespace    median.gptpp
// @version      1.2.0
// @description  将 Median 本地 MCP 工具（fs/github/browser/remote）注入 chatgpt.com，AI 可直接调用操作设备与 GitHub
// @match        *://chatgpt.com/*
// @run-at       document-start
// @grant        GM_xmlhttpRequest
// @connect      http://127.0.0.1:8788
// ==/UserScript==
/* Median ChatGPT++ MCP Bridge: chatgpt.com <-> Median MCP (127.0.0.1:8788) */
(function () {
    'use strict';
    var API = 'http://127.0.0.1:8788/mcp';
    var TOOL_CACHE_TTL = 15000;
    var __toolsCache = null;
    var __toolsTs = 0;
    var __pendingResult = null;
    var __toolsWaiters = [];
    var __toolsLoading = false;
    var buf = '';

    /* ---------- 工具列表（优先 GM 原生桥，绕过 CSP；同步 XHR 兜底） ---------- */
    function gmFetchTools(cb) {
        try {
            if (typeof GM_xmlhttpRequest === 'function') {
                if (__toolsLoading) {
                    if (cb) __toolsWaiters.push(cb);
                    return true;
                }
                __toolsLoading = true;
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: API,
                    headers: { 'Content-Type': 'application/json' },
                    data: JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method: 'tools/list', params: {} }),
                    timeout: 10000,
                    onload: function (r) {
                        __toolsLoading = false;
                        try {
                            var res = JSON.parse(r.responseText || '{}');
                            var arr = (res && res.result && res.result.tools) || [];
                            __toolsCache = arr;
                            __toolsTs = Date.now();
                            var w = __toolsWaiters; __toolsWaiters = [];
                            for (var i = 0; i < w.length; i++) { try { w[i](arr); } catch (e) { /* ignore */ } }
                            if (cb) cb(arr);
                        } catch (e) {
                            var w2 = __toolsWaiters; __toolsWaiters = [];
                            for (var j = 0; j < w2.length; j++) { try { w2[j]([]); } catch (e2) { /* ignore */ } }
                            if (cb) cb([]);
                        }
                    },
                    onerror: function () {
                        __toolsLoading = false;
                        try { console.warn('[MedianGPT++] tools fetch error (bridge)'); } catch (e) { /* ignore */ }
                        var w = __toolsWaiters; __toolsWaiters = [];
                        for (var i = 0; i < w.length; i++) { try { w[i]([]); } catch (e) { /* ignore */ } }
                        if (cb) cb([]);
                    },
                    ontimeout: function () {
                        __toolsLoading = false;
                        try { console.warn('[MedianGPT++] tools fetch timeout (bridge)'); } catch (e) { /* ignore */ }
                        var w = __toolsWaiters; __toolsWaiters = [];
                        for (var i = 0; i < w.length; i++) { try { w[i]([]); } catch (e) { /* ignore */ } }
                        if (cb) cb([]);
                    }
                });
                return true;
            }
        } catch (e) { /* ignore */ }
        return false;
    }
    /* 等待工具列表就绪（GM 预取可能未完成时阻塞至完成；返回 Promise） */
    function waitToolsReady() {
        return new Promise(function (resolve) {
            try {
                var now = Date.now();
                if (__toolsCache && __toolsCache.length > 0 && now - __toolsTs < TOOL_CACHE_TTL) { resolve(__toolsCache); return; }
                var done = false;
                var finish = function (arr) { if (!done) { done = true; resolve(arr || []); } };
                if (!gmFetchTools(finish)) {
                    /* GM 不可用：同步兜底 */
                    var arr = [];
                    try {
                        var x = new XMLHttpRequest();
                        x.open('POST', API, false);
                        x.setRequestHeader('Content-Type', 'application/json');
                        x.send(JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method: 'tools/list', params: {} }));
                        var res = JSON.parse(x.responseText || '{}');
                        arr = (res && res.result && res.result.tools) || [];
                        __toolsCache = arr;
                        __toolsTs = now;
                    } catch (e) { /* ignore */ }
                    finish(arr);
                }
                /* 兜底：3 秒后无论如何 resolve */
                setTimeout(function () { finish(__toolsCache || []); }, 3000);
            } catch (e) { resolve(__toolsCache || []); }
        });
    }
    function fetchTools() {
        try {
            var now = Date.now();
            if (__toolsCache && __toolsCache.length > 0 && now - __toolsTs < TOOL_CACHE_TTL) return __toolsCache;
            var x = new XMLHttpRequest();
            x.open('POST', API, false);
            x.setRequestHeader('Content-Type', 'application/json');
            x.send(JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method: 'tools/list', params: {} }));
            var res = JSON.parse(x.responseText || '{}');
            var arr = (res && res.result && res.result.tools) || [];
            __toolsCache = arr;
            __toolsTs = now;
            return arr;
        } catch (e) {
            return __toolsCache || [];
        }
    }

    /* ---------- 系统提示词（工具列表 + 调用协议） ---------- */
    function sysPrompt() {
        var tools = fetchTools();
        var lines = [];
        for (var i = 0; i < tools.length; i++) {
            var t = tools[i];
            if (!t || !t.name) continue;
            lines.push('- ' + t.name + ': ' + (t.description || ''));
        }
        return '[System: You have access to these local device tools via Median Bridge]\n'
            + lines.join('\n')
            + '\n[File Workspace] You can read/write files on this Android device. Use median_workspace_info to see the current workspace; write generated content to files with median_write_file (relative path = inside workspace).\n'
            + 'To call a tool, reply with EXACTLY this XML (nothing else):\n'
            + '<median_tool_call><median_name>INVOCATION_NAME</median_name><median_args>{"arg1":"value1"}</median_args></median_tool_call>\n'
            + 'After the tool runs, its result will be sent to you automatically. Then answer the user based on the result.\n';
    }

    /* ---------- 调用工具（优先 GM 原生桥绕过 CSP；同步 XHR 兜底） ---------- */
    function gmRunTool(name, args, cb) {
        try {
            if (typeof GM_xmlhttpRequest === 'function') {
                var iv = name;
                if (iv.indexOf('median_') === 0) iv = 'fs_' + iv.substring(7);
                var a = args || {};
                if (a.directory !== undefined && a.dir === undefined) a.dir = a.directory;
                if (iv === 'fs_find_file' && a.path !== undefined && a.dir === undefined) a.dir = a.path;
                GM_xmlhttpRequest({
                    method: 'POST',
                    url: API,
                    headers: { 'Content-Type': 'application/json' },
                    data: JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method: 'tools/call', params: { name: iv, arguments: a } }),
                    timeout: 180000,
                    onload: function (r) {
                        try {
                            var res = JSON.parse(r.responseText || '{}');
                            var c = res && res.result && res.result.content && res.result.content[0] && res.result.content[0].text;
                            var data = c ? JSON.parse(c) : {};
                            var val = data.result !== undefined ? data.result : data;
                            if (cb) cb(JSON.stringify(val).substring(0, 20000));
                        } catch (e) { if (cb) cb(JSON.stringify({ ok: false, error: String((e && e.message) || e) })); }
                    },
                    onerror: function () { if (cb) cb(JSON.stringify({ ok: false, error: 'tool network error' })); },
                    ontimeout: function () { if (cb) cb(JSON.stringify({ ok: false, error: 'tool timeout(180s)' })); }
                });
                return true;
            }
        } catch (e) { /* ignore */ }
        return false;
    }
    function runTool(name, args) {
        try {
            var iv = name;
            if (iv.indexOf('median_') === 0) iv = 'fs_' + iv.substring(7);
            var a = args || {};
            if (a.directory !== undefined && a.dir === undefined) a.dir = a.directory;
            if (iv === 'fs_find_file' && a.path !== undefined && a.dir === undefined) a.dir = a.path;
            var x = new XMLHttpRequest();
            x.open('POST', API, false);
            x.setRequestHeader('Content-Type', 'application/json');
            x.send(JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method: 'tools/call', params: { name: iv, arguments: a } }));
            var res = JSON.parse(x.responseText || '{}');
            var c = res && res.result && res.result.content && res.result.content[0] && res.result.content[0].text;
            var data = c ? JSON.parse(c) : {};
            var val = data.result !== undefined ? data.result : data;
            return JSON.stringify(val).substring(0, 20000);
        } catch (e) {
            return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
        }
    }

    /* ---------- 从文本解析工具调用 XML ---------- */
    function parseToolCalls(text) {
        var out = [];
        var re = /<median_tool_call>([\s\S]*?)<\/median_tool_call>/g;
        var m;
        while ((m = re.exec(text)) !== null) {
            var body = m[1];
            var nm = (body.match(/<median_name>([^<]*)<\/median_name>/) || [])[1] || '';
            var argsRaw = (body.match(/<median_args>([\s\S]*?)<\/median_args>/) || [])[1] || '{}';
            var args = {};
            try { args = JSON.parse(argsRaw); } catch (e) { /* ignore */ }
            if (nm) out.push({ name: nm, args: args });
        }
        return out;
    }

    /* ---------- 处理一帧 ChatGPT SSE 数据（提取文本累积） ---------- */
    function sseTextFromFrame(frame) {
        var texts = [];
        try {
            var obj = JSON.parse(frame);
            var msg = obj && (obj.message || obj.delta || null);
            /* 新版 Responses API: {"type":"response.output_text.delta","delta":"..."} */
            if (obj && obj.type && (obj.type === 'response.output_text.delta' || obj.type === 'response.output_text.done')) {
                if (typeof obj.delta === 'string') texts.push(obj.delta);
                if (Array.isArray(obj.delta)) {
                    for (var k = 0; k < obj.delta.length; k++) {
                        var dk = obj.delta[k];
                        if (dk && typeof dk.text === 'string') texts.push(dk.text);
                    }
                }
                return texts.join('');
            }
            if (!msg) return '';
            var content = msg.content;
            if (typeof content === 'string') {
                texts.push(content);
            } else if (content && Array.isArray(content.parts)) {
                for (var i = 0; i < content.parts.length; i++) {
                    if (typeof content.parts[i] === 'string') texts.push(content.parts[i]);
                }
            } else if (content && content.content_type && typeof content.text === 'string') {
                texts.push(content.text);
            } else if (content && Array.isArray(content)) {
                /* 新版: content 为数组 [{type:'output_text',text:'...'}] */
                for (var m = 0; m < content.length; m++) {
                    var ci = content[m];
                    if (!ci) continue;
                    if (typeof ci.text === 'string') texts.push(ci.text);
                    else if (typeof ci.content === 'string') texts.push(ci.content);
                    else if (ci.content && Array.isArray(ci.content)) {
                        for (var n = 0; n < ci.content.length; n++) {
                            var cn = ci.content[n];
                            if (cn && typeof cn.text === 'string') texts.push(cn.text);
                        }
                    }
                }
            }
            if (Array.isArray(msg.parts)) {
                for (var j = 0; j < msg.parts.length; j++) {
                    if (typeof msg.parts[j] === 'string') texts.push(msg.parts[j]);
                }
            }
            if (typeof msg.text === 'string') texts.push(msg.text);
        } catch (e) { /* ignore */ }
        return texts.join('');
    }

    /* ---------- 注入请求体：messages / input 前插 system 消息 ---------- */
    function augmentBody(rawBody) {
        try {
            var req = JSON.parse(rawBody);
            if (!req) return null;
            var inj = '';
            if (__pendingResult) {
                inj = '[System: 工具执行结果]\n' + __pendingResult + '\n[结果结束。请基于该工具结果回答用户之前的问题，不要重复调用相同工具。]\n';
                __pendingResult = null;
            }
            var sysText = inj + sysPrompt();
            /* 旧版格式: {author:{role:'system'}, content:{content_type:'text', parts:[...]}} */
            var sysMsgOld = {
                author: { role: 'system' },
                content: { content_type: 'text', parts: [sysText] }
            };
            /* 新版格式: {role:'system', content:[{type:'input_text', text:'...'}]} */
            var sysMsgNew = { role: 'system', content: [{ type: 'input_text', text: sysText }] };
            if (req.messages && Array.isArray(req.messages)) {
                var hasNewFormat = false;
                for (var i = 0; i < req.messages.length; i++) {
                    var m = req.messages[i];
                    if (m && typeof m.role === 'string' && Array.isArray(m.content)) { hasNewFormat = true; break; }
                }
                req.messages.unshift(hasNewFormat ? sysMsgNew : sysMsgOld);
                return JSON.stringify(req);
            }
            /* 新版 Responses-API 请求体: {model, instructions, input:[...]} */
            if (req.input && Array.isArray(req.input)) {
                req.input.unshift({ role: 'system', content: [{ type: 'input_text', text: sysText }] });
                if (typeof req.instructions === 'string') req.instructions = sysText + '\n' + req.instructions;
                else req.instructions = sysText;
                return JSON.stringify(req);
            }
            return null;
        } catch (e) {
            return null;
        }
    }

    /* ---------- 流式响应包装（fetch） ---------- */
    function wrapFetchResponse(originalResponse) {
        if (!originalResponse || !originalResponse.body || !originalResponse.body.getReader) return originalResponse;
        var reader = originalResponse.body.getReader();
        var decoder = new TextDecoder();
        var stream = new ReadableStream({
            pull: function (controller) {
                return reader.read().then(function (res) {
                    if (res.done) {
                        controller.close();
                        return;
                    }
                    var chunk = decoder.decode(res.value, { stream: true });
                    buf += chunk;
                    /* 尝试从已累积文本中解析工具调用（跨帧） */
                    tryMaybeRunTool();
                    controller.enqueue(res.value);
                }).catch(function (err) {
                    controller.error(err);
                });
            },
            cancel: function () {
                try { reader.cancel(); } catch (e) { /* ignore */ }
            }
        });
        var headers = new Headers(originalResponse.headers);
        headers.delete('content-length');
        headers.delete('content-encoding');
        var resp = new Response(stream, { headers: headers, status: originalResponse.status, statusText: originalResponse.statusText });
        try { Object.defineProperty(resp, 'url', { value: originalResponse.url, enumerable: true, configurable: true }); } catch (e) { /* ignore */ }
        return resp;
    }

    /* ---------- 从累积 SSE 文本中提取并执行工具调用 ---------- */
    function tryMaybeRunTool() {
        if (__pendingResult) return;
        var calls = parseToolCalls(buf);
        if (calls.length === 0) return;
        var call = calls[calls.length - 1];
        try {
            var done = function (result) {
                __pendingResult = result;
                console.log('[MedianGPT++] tool', call.name, '->', result.substring(0, 200));
            };
            if (!gmRunTool(call.name, call.args, done)) {
                done(runTool(call.name, call.args));
            }
        } catch (e) {
            __pendingResult = JSON.stringify({ ok: false, error: String((e && e.message) || e) });
        }
    }

    /* ---------- fetch 拦截 ---------- */
    var origFetch = window.fetch;
    if (origFetch && !window.__medianGptppInstalled) {
        window.__medianGptppInstalled = true;
        /* 页面加载即预拉工具列表（GM 桥，绕过 CSP）——延迟发起避开导航早期 URL 未就绪窗口，失败自动重试 */
        var __prefetchAttempts = 0;
        function __prefetchTools() {
            if (__toolsCache && __toolsCache.length > 0) return;
            if (__prefetchAttempts >= 4) return;
            __prefetchAttempts++;
            try { gmFetchTools(function (arr) { console.log('[MedianGPT++] tools prefetched:', arr ? arr.length : 0); }); } catch (e) { /* ignore */ }
            setTimeout(__prefetchTools, 2500);
        }
        setTimeout(__prefetchTools, 800);
        /* 将 body（可能为 ReadableStream）读取为字符串；无法读取返回 null */
        function readBodyAsText(input) {
            try {
                if (typeof input === 'string') return input;
                if (input instanceof ReadableStream) {
                    return input.getReader().read().then(function (r) {
                        if (r.done) return '';
                        return new TextDecoder().decode(r.value, { stream: false });
                    });
                }
                if (input && typeof input.getReader === 'function') {
                    return input.getReader().read().then(function (r) {
                        if (r.done) return '';
                        return new TextDecoder().decode(r.value, { stream: false });
                    });
                }
            } catch (e) { /* ignore */ }
            return null;
        }
        window.fetch = function (input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) ? input.url : null;
            var method = (init && init.method) ? init.method : (input && input.method) ? input.method : 'GET';
            var body = (init && init.body) ? init.body : null;
            var isConv = url && url.indexOf('chatgpt.com') >= 0 && method === 'POST'
                && (url.indexOf('/backend-api/') >= 0)
                && body && (typeof body === 'string' || typeof body.getReader === 'function' || body instanceof ReadableStream);
            if (isConv && method === 'POST' && body) {
                var textP = readBodyAsText(body);
                if (textP !== null) {
                    var p0 = Promise.resolve(textP);
                    return p0.then(function (text) {
                        if (typeof text !== 'string') return origFetch.call(this, input, init);
                        /* 确保工具列表已就绪（GM 预取完成），再注入 */
                        return waitToolsReady().then(function () {
                            var augmented = augmentBody(text);
                            /* 无论注入是否成功，都用读取到的完整文本重建 body（ReadableStream 已被消费，不能原样传递） */
                            init = Object.assign({}, init, { body: augmented !== null ? augmented : text });
                            var p = origFetch.call(this, input, init);
                            return p.then(function (r) { return wrapFetchResponse(r); });
                        });
                    });
                }
            }
            var p = origFetch.call(this, input, init);
            if (isConv) {
                p = p.then(function (r) { return wrapFetchResponse(r); });
            }
            return p;
        };
    }

    /* ---------- XHR 拦截（兜底） ---------- */
    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) {
        this.__medianUrl = typeof u === 'string' ? u : (u && u.href) ? u.href : null;
        this.__medianMethod = typeof m === 'string' ? m.toUpperCase() : m;
        return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
        var self = this;
        if (self.__medianMethod === 'POST' && self.__medianUrl
            && self.__medianUrl.indexOf('/backend-api/conversation') >= 0
            && self.__medianUrl.indexOf('chatgpt.com') >= 0
            && typeof body === 'string') {
            var augmented = augmentBody(body);
            if (augmented !== null) body = augmented;
        }
        this.addEventListener('readystatechange', function () {
            if (self.readyState === 4 && self.status === 200
                && self.__medianUrl && self.__medianUrl.indexOf('/backend-api/conversation') >= 0) {
                var txt = '';
                try { txt = self.responseText || ''; } catch (e) { /* ignore */ }
                var lines = txt.split('\n');
                buf = '';
                for (var i = 0; i < lines.length; i++) {
                    var line = lines[i];
                    if (line.indexOf('data:') === 0) {
                        var payload = line.substring(5).trim();
                        if (payload && payload !== '[DONE]') buf += sseTextFromFrame(payload);
                    }
                }
                tryMaybeRunTool();
            }
        });
        return origSend.apply(this, arguments);
    };
})();