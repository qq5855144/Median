// ==UserScript==
// @name         Median ChatGPT++ MCP Bridge
// @namespace    median.gptpp
// @version      1.0.0
// @description  将 Median 本地 MCP 工具（fs/github/browser/remote）注入 chatgpt.com，AI 可直接调用操作设备与 GitHub
// @match        *://chatgpt.com/*
// @run-at       document-start
// ==/UserScript==
/* Median ChatGPT++ MCP Bridge: chatgpt.com <-> Median MCP (127.0.0.1:8788) */
(function () {
    'use strict';
    var API = 'http://127.0.0.1:8788/mcp';
    var TOOL_CACHE_TTL = 15000;
    var __toolsCache = null;
    var __toolsTs = 0;
    var __pendingResult = null;
    var buf = '';

    /* ---------- 工具列表（同步拉取，15s 缓存） ---------- */
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

    /* ---------- 调用工具（同步，供流解析后执行） ---------- */
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
            }
            if (Array.isArray(msg.parts)) {
                for (var j = 0; j < msg.parts.length; j++) {
                    if (typeof msg.parts[j] === 'string') texts.push(msg.parts[j]);
                }
            }
        } catch (e) { /* ignore */ }
        return texts.join('');
    }

    /* ---------- 注入请求体：messages 前插 system 消息 ---------- */
    function augmentBody(rawBody) {
        try {
            var req = JSON.parse(rawBody);
            if (!req || !req.messages || !Array.isArray(req.messages)) return null;
            var inj = '';
            if (__pendingResult) {
                inj = '[System: 工具执行结果]\n' + __pendingResult + '\n[结果结束。请基于该工具结果回答用户之前的问题，不要重复调用相同工具。]\n';
                __pendingResult = null;
            }
            var sysMsg = {
                author: { role: 'system' },
                content: { content_type: 'text', parts: [inj + sysPrompt()] }
            };
            req.messages.unshift(sysMsg);
            return JSON.stringify(req);
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
            var result = runTool(call.name, call.args);
            __pendingResult = result;
            console.log('[MedianGPT++] tool', call.name, '->', result.substring(0, 200));
        } catch (e) {
            __pendingResult = JSON.stringify({ ok: false, error: String((e && e.message) || e) });
        }
    }

    /* ---------- fetch 拦截 ---------- */
    var origFetch = window.fetch;
    if (origFetch && !window.__medianGptppInstalled) {
        window.__medianGptppInstalled = true;
        window.fetch = function (input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) ? input.url : null;
            var method = (init && init.method) ? init.method : (input && input.method) ? input.method : 'GET';
            var body = (init && init.body) ? init.body : null;
            if (url && method === 'POST' && body && typeof body === 'string'
                && url.indexOf('/backend-api/conversation') >= 0
                && url.indexOf('chatgpt.com') >= 0) {
                var augmented = augmentBody(body);
                if (augmented !== null) {
                    init = Object.assign({}, init, { body: augmented });
                }
            }
            var p = origFetch.call(this, input, init);
            if (url && url.indexOf('/backend-api/conversation') >= 0) {
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