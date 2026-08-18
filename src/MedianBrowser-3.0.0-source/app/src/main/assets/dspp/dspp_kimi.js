// ==UserScript==
// @name         Median Kimi 工具桥接
// @namespace    median.kimi-bridge
// @version      1.8.0
// @description  为 www.kimi.com 注入 Median 本地设备工具链（MCP 桥接、工具调用解析、自动续跑）
// @match        *://www.kimi.com/*
// @run-at       document-start
// @grant        none
// ==/UserScript==
// Median Kimi Bridge v1 —— connect-RPC 协议层桥接（不依赖 DOM）
// fetch 拦截 ChatService/Chat -> 注入 system prompt；响应流克隆解析 -> 检测工具标签 -> MCP 执行 -> 协议层自动续跑
(function () {
  'use strict';
  if (window.__medianKimi) return;
  window.__medianKimi = true;

  var CHAT_URL_MARK = '/apiv2/kimi.gateway.chat.v1.ChatService/';
  var CHAT_API = 'https://www.kimi.com/apiv2/kimi.gateway.chat.v1.ChatService/Chat';

  // ---------- 导航劫持（v1.8.0）：AI/前端 JS 导航到非 Kimi 域时改走小窗，不脱离对话页 ----------
  // 场景：Kimi 前端执行 AI 的浏览器工具调用（location.href 赋值 / assign / replace），
  // 直接把当前对话页导航走。此处拦截跨域导航 → browser_panel_open 小窗打开。
  function kimiIsChatHost(u) {
    try {
      var h = new URL(String(u), location.href).hostname || '';
      return /(^|\.)(kimi\.com|moonshot\.cn)$/i.test(h);
    } catch (e) { return false; }
  }
  (function installNavGuard() {
    function guard(url) {
      try {
        var s = String(url);
        if (/^https?:\/\//i.test(s) && !kimiIsChatHost(s) && window.__kimiPanelOpen) {
          window.__kimiPanelOpen(s);
          return true; // 已改道小窗，调用方应放弃原导航
        }
      } catch (e) {}
      return false;
    }
    try {
      var d = Object.getOwnPropertyDescriptor(window.Location.prototype, 'href');
      if (d && d.set) {
        Object.defineProperty(window.Location.prototype, 'href', {
          configurable: true,
          get: d.get,
          set: function (v) { if (!guard(v)) d.set.call(this, v); }
        });
      }
    } catch (e) {}
    ['assign', 'replace'].forEach(function (m) {
      try {
        var orig = window.Location.prototype[m];
        if (typeof orig !== 'function') return;
        window.Location.prototype[m] = function (v) {
          if (!guard(v)) return orig.apply(this, arguments);
        };
      } catch (e) {}
    });
    // window.open 已在尾部覆盖；此处记录面板调用计数供诊断
    window.__kimiPanelCount = 0;
  })();

  // ---------- MCP 连接（8788 固定 + 探测兜底） ----------
  window.__kimiMcu = '';
  window.__kimiMcuBase = function () {
    try {
      if (window.__kimiMcu) return window.__kimiMcu;
      var cand = [];
      if (window.__mcpPort && window.__mcpPort > 0) cand.push(window.__mcpPort);
      for (var q = 8788; q <= 8799; q++) { if (cand.indexOf(q) < 0) cand.push(q); }
      for (var i = 0; i < cand.length; i++) {
        try {
          var x = new XMLHttpRequest();
          x.open('POST', 'http://127.0.0.1:' + cand[i] + '/mcp', false);
          x.setRequestHeader('Content-Type', 'application/json');
          x.send('{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}');
          if (x.status === 200) {
            var d = JSON.parse(x.responseText || '{}');
            if (d && d.result && d.result.tools) {
              window.__kimiMcu = 'http://127.0.0.1:' + cand[i] + '/mcp';
              return window.__kimiMcu;
            }
          }
        } catch (e) {}
      }
      window.__kimiMcu = 'http://127.0.0.1:8788/mcp';
      return window.__kimiMcu;
    } catch (e) {
      window.__kimiMcu = 'http://127.0.0.1:8788/mcp';
      return window.__kimiMcu;
    }
  };
  window.__kimiRemoteTools = [];
  window.__kimiRemoteTs = 0;
  function fetchRemoteTools() {
    try {
      var now = Date.now();
      if (window.__kimiRemoteTools.length > 0 && now - window.__kimiRemoteTs < 15000) return window.__kimiRemoteTools;
      var x = new XMLHttpRequest();
      x.open('POST', window.__kimiMcuBase(), false);
      x.setRequestHeader('Content-Type', 'application/json');
      x.send(JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method: 'tools/list', params: {} }));
      var res = JSON.parse(x.responseText || '{}');
      window.__kimiRemoteTools = (res && res.result && res.result.tools) || [];
      window.__kimiRemoteTs = now;
      return window.__kimiRemoteTools;
    } catch (e) { return []; }
  }

  // ---------- system prompt ----------
  function toolSysPrompt() {
    var rt = fetchRemoteTools();
    var t = '[System: You have access to these local device tools via Median Bridge]\n';
    t += rt.map(function (x) { return '- ' + x.name + ': ' + x.description; }).join('\n');
    t += '\n[TOOL PROTOCOL] To call a tool, output EXACTLY this XML at the END of your message:\n';
    t += '<median_name>full_tool_name</median_name> {"arg":"value"}\n';
     t += 'The <median_name> tag MUST contain the FULL exact tool name from the list above (including remote.xxx. prefix). ';
     t += '1) Output ONLY ONE tool call per message, then STOP and WAIT for the result. ';
     t += '2) After a result arrives you MAY call any tool again to continue, unlimited times. ';
     t += '3) On tool error, read the error, fix arguments, retry. ';
     t += '4) Only when the task is COMPLETE output the final answer. ';
     t += '5) When answering ABOUT the tool list, output plain-text names WITHOUT median_name tags (tags ARE executed). ';
      t += '6) To visit/search ANY web page, you MUST use browser_panel_open (opens in a floating mini-window, THIS chat page stays put). NEVER use browser_open/browser_nav for external sites - it navigates THIS conversation away and kills the task. browser_open is ONLY for reloading kimi.com itself. ';
     return t;
   }

  // ---------- 工具名解析 ----------
  function resolveTool(nm) {
    if (!nm || typeof nm !== 'string') return null;
    nm = nm.trim().replace(/^remote\.mtmcp\./i, 'remote.mt_mcp.').replace(/^remote\.mtmcp:/i, 'remote.mt_mcp:').replace(/^mtmcp\./i, 'mt_mcp.');
    if (!nm) return null;
    // 空格/连字符归一化（MCP 返回名可能含空格如 "remote.MT MCP.mt_apk_*"，AI 可能改写为 MT_MCP/MT-MCP 等）
    var nmSquash = nm.toLowerCase().replace(/[\s-]+/g, '_').replace(/_+/g, '_');
    var nmLow = nm.toLowerCase(), best = null;
    try {
      var rt = fetchRemoteTools();
      for (var j = 0; j < rt.length; j++) {
        var rn = rt[j].name || '';
        if (!rn) continue;
        if (rn === nm) return rn;
        var rnLow = rn.toLowerCase();
        if (rnLow === nmLow) return rn;
        var rnSquash = rnLow.replace(/[\s-]+/g, '_').replace(/_+/g, '_');
        if (rnSquash === nmSquash) return rn;
        var st1 = rnLow.replace(/^remote\.[^.]+(\.|$)/, '').replace(/^remote[:.]/, '');
        if (st1 === nmLow) return rn;
        if (rnLow.length > nmLow.length && rnLow.lastIndexOf(nmLow) === rnLow.length - nmLow.length &&
            (rnLow.charAt(rnLow.length - nmLow.length - 1) === '.' || rnLow.charAt(rnLow.length - nmLow.length - 1) === ':')) {
          if (!best || rn.length < best.length) best = rn;
        }
      }
      return best;
    } catch (e) { return null; }
  }
  // ---------- MCP 工具执行 ----------
  function runTool(name, args) {
    return new Promise(function (resolve) {
      var a = args || {};
      var req = { jsonrpc: '2.0', id: Date.now(), method: 'tools/call', params: { name: name, arguments: a } };
      var settled = false;
      var fire = function () {
        if (settled) return;
        try {
          var x = new XMLHttpRequest();
          x.open('POST', window.__kimiMcuBase(), true);
          x.timeout = 180000;
          x.setRequestHeader('Content-Type', 'application/json');
          x.onload = function () {
            settled = true;
            try {
              var res = JSON.parse(x.responseText || '{}');
              var c = res && res.result && res.result.content && res.result.content[0] && res.result.content[0].text;
              var data = c ? JSON.parse(c) : {};
              resolve(data.result !== undefined ? data.result : data);
            } catch (e) { resolve({ ok: false, error: String(e && e.message || e) }); }
          };
          x.onerror = function () {
            if (window.__kimiRetry < 3) {
              window.__kimiRetry++;
              setTimeout(fire, 400);
              return;
            }
            window.__kimiRetry = 0;
            settled = true;
            resolve({ ok: false, error: 'tool network error(3x retried)' });
          };
          x.ontimeout = function () { settled = true; resolve({ ok: false, error: 'tool timeout(180s)' }); };
          x.send(JSON.stringify(req));
        } catch (e) { settled = true; resolve({ ok: false, error: String(e && e.message || e) }); }
      };
      fire();
    });
  }
  window.__kimiRetry = 0;

  // ---------- 工具调用标签解析 ----------
  function parseToolCalls(f) {
    f = String(f).replace(/(^|[^<\/])median_name>/g, '$1<median_name>').replace(/(^|[^<\/])median_call>/g, '$1<median_call>').replace(/(^|[^<\/])median_tool_call>/g, '$1<median_tool_call>');
    var calls = [];
    var re1 = /<median_tool_call>([\s\S]*?)<\/median_tool_call>|<median_call>([\s\S]*?)<\/median_call>/g, m1;
    while ((m1 = re1.exec(f))) {
      var _in = m1[1] || m1[2] || '';
      var _nm = (_in.match(/<median_name>([^<]*)<\/median_name>/) || [])[1] || '';
      var _am = _in.match(/<median_args>([\s\S]*?)<\/median_args>|<arguments>([\s\S]*?)<\/arguments>/);
      var _raw = (_am && (_am[1] || _am[2])) || '{}';
      var _a = {};
      try { _a = JSON.parse(_raw); } catch (e) {}
      if (_nm) calls.push({ nm: _nm, args: _a });
    }
    if (!calls.length) {
      var re2 = /<median_name>\s*([^<][^<]*?)\s*<\/median_name>([\s\S]*?)(?=<median_name>|$)/g, m2;
      while ((m2 = re2.exec(f))) {
        var _nm2 = (m2[1] || '').trim();
        if (!_nm2) continue;
        var _rest = m2[2] || '';
        var _a2 = {};
        var _am2 = _rest.match(/<median_args>([\s\S]*?)<\/median_args>|<arguments>([\s\S]*?)<\/arguments>/);
        if (_am2) { try { _a2 = JSON.parse(_am2[1] || _am2[2]); } catch (e) {} }
        else {
          var _j2 = _rest.match(/\{[\s\S]*?\}/);
          if (_j2) { try { _a2 = JSON.parse(_j2[0]); } catch (e) {} }
          if (!_a2 || Object.keys(_a2).length === 0) {
            // 流截断兜底：JSON 不完整但 url 字段已完整时直接提取（AI 常用 browser_open {"url":"..."}）
            var _um = _rest.match(/"url"\s*:\s*"([^"]+)"/i);
            if (_um && /^https?:\/\//i.test(_um[1])) _a2 = { url: _um[1] };
          }
        }
        calls.push({ nm: _nm2, args: _a2 });
      }
    }
    return calls;
  }

  // ---------- 执行队列 + 自动续跑状态 ----------
  window.__kimiBusy = false;
  window.__kimiPendingResult = null;
  window.__kimiDoneSigs = []; // [{sig, ts}] 带时间戳，去重仅限短时间窗（跨会话不误伤）
  window.__kimiStreaming = false;
  window.__kimiLastReq = null;
  window.__kimiLastResp = '';
  window.__kimiFailCount = 0;
  window.__kimiDiag = { injectedAt: Date.now(), reqs: 0, streams: 0, uiSends: 0, protoSends: 0, capReasons: [] };

  function kimiIsDupSig(sig) {
    var now = Date.now(), alive = [];
    for (var i = 0; i < window.__kimiDoneSigs.length; i++) {
      var it = window.__kimiDoneSigs[i];
      if (now - it.ts < 60000) {
        alive.push(it);
        if (it.sig === sig) { window.__kimiDoneSigs = alive; return true; }
      }
    }
    window.__kimiDoneSigs = alive;
    return false;
  }
  function kimiMarkSig(sig) {
    window.__kimiDoneSigs.push({ sig: sig, ts: Date.now() });
  }
  // 新用户消息到来时清空去重记录（跨会话/跨任务不再误伤相同签名）
  function kimiResetDup() { window.__kimiDoneSigs = []; }

  function toolAndContinue(nm, args) {
    if (window.__kimiBusy) { console.log('[KimiBridge] busy, skip', nm); return; }
    var full = resolveTool(nm);
    if (!full) { console.log('[KimiBridge] skip unknown tool', nm); return; }
    // v1.8.0：browser_open/browser_nav 目标是外部站点时一律改走小窗，保护对话页
    if ((full === 'browser_open' || full === 'browser_nav') && args) {
      var bu = args.url || args.target_url || args.link || args.href || '';
      if (bu && /^https?:\/\//i.test(bu) && !kimiIsChatHost(bu)) {
        full = 'browser_panel_open';
        args = { url: bu };
      }
    }
    window.__kimiBusy = true;
    runTool(full, args).then(function (result) {
      window.__kimiBusy = false;
      window.__kimiPendingResult = JSON.stringify(result);
      console.log('[KimiBridge] tool done', full, JSON.stringify(result).slice(0, 200));
      var sig = full + '|' + JSON.stringify(args || {});
      kimiMarkSig(sig);
      if (result && result.ok === false) window.__kimiFailCount++;
      else window.__kimiFailCount = 0;
      if (window.__kimiFailCount >= 3) { console.log('[KimiBridge] 3 failures, halt'); window.__kimiFailCount = 0; return; }
      if (!window.__kimiStreaming) autoContinue();
    });
  }// ---------- 协议层发送（connect JSON，复用最后一次请求参数） ----------
  function sendChatText(text, onFail) {
    var last = window.__kimiLastReq;
    if (!last) { console.log('[KimiBridge] no last req'); if (onFail) onFail(); return; }
    try {
      var b = JSON.parse(JSON.stringify(last.bodyObj));
      // 关键修复：从当前 URL 提取会话 ID 写入请求体。
      // 新会话首页发送的首条消息 chat_id 为空（服务端才建会话），且协议层请求
      // 会被 fetch hook 二次捕获覆盖 lastReq，导致回传结果无会话归属、AI 收不到。
      var m0 = location.pathname.match(/\/chat\/([^\/?]+)/);
      var curChat = (m0 && m0[1]) || '';
      if (curChat) {
        if ('chat_id' in b) b.chat_id = curChat;
        else if ('chatId' in b) b.chatId = curChat;
        else b.chat_id = curChat;
      } else if (!b.chat_id && !b.chatId) {
        // 无会话归属可写：协议层回传必然失败，直接走 UI 兜底
        console.log('[KimiBridge] no chat id available, use UI fallback');
        if (onFail) onFail();
        return;
      }
      var msg = b.message || {};
      var blocks = msg.blocks || [];
      var injected = false;
      for (var i = 0; i < blocks.length; i++) {
        var blk = blocks[i] || {};
        var c = blk.content || {};
        if (c.case === 'text' && c.value) { c.value.content = text; injected = true; break; }
        if (blk.text && typeof blk.text.content === 'string') { blk.text.content = text; injected = true; break; }
      }
      if (!injected) {
        blocks.unshift({
          $typeName: 'kimi.chat.v1.Block', id: '', messageId: '',
          text: { $typeName: 'kimi.chat.v1.TextBlock', content: text }
        });
        msg.blocks = blocks;
      }
      window.__kimiStreaming = true;
      window.__kimiDiag.protoSends++;
      var body;
      if (last.isBinary) {
        var payload2 = new TextEncoder().encode(JSON.stringify(b));
        body = new Uint8Array(5 + payload2.length);
        body[0] = last.flagsByte || 0;
        body[1] = (payload2.length >> 24) & 255; body[2] = (payload2.length >> 16) & 255;
        body[3] = (payload2.length >> 8) & 255; body[4] = payload2.length & 255;
        body.set(payload2, 5);
      } else {
        body = JSON.stringify(b);
      }
      fetch(last.url, {
        method: 'POST',
        headers: last.headers || {},
        body: body,
        credentials: 'include'
      }).then(function (resp) {
        if (!resp || !resp.ok) { window.__kimiStreaming = false; console.log('[KimiBridge] proto bad resp', resp && resp.status); if (onFail) onFail(); }
      }).catch(function (e) {
        window.__kimiStreaming = false;
        console.log('[KimiBridge] autoContinue err', String(e));
        if (onFail) onFail();
      });
    } catch (e) { console.log('[KimiBridge] sendChatText err', String(e && e.message || e)); }
  }

  // ---------- UI 层发送（Lexical + Enter，已验证可靠） ----------
  function uiSendText(text) {
    try {
      var ed = document.querySelector('.chat-input-editor');
      if (!ed || !ed.__lexicalEditor) { console.log('[KimiBridge] no lexical editor'); return false; }
      var lex = ed.__lexicalEditor;
      var j = lex.getEditorState().toJSON();
      var para = j && j.root && j.root.children && j.root.children[0] &&
        j.root.children[0].children && j.root.children[0].children[0];
      if (!para) { console.log('[KimiBridge] no para'); return false; }
      para.text = text;
      var ns = null;
      try { ns = lex.parseEditorState(JSON.stringify(j)); }
      catch (e1) { try { ns = lex.parseEditorState(j); }
        catch (e2) { console.log('[KimiBridge] parseEditorState err', String(e2)); return false; } }
      try { lex.setEditorState(ns); }
      catch (e3) { console.log('[KimiBridge] setEditorState err', String(e3)); return false; }
      try { lex.focus(); } catch (e4) {}
      var opts = { key: 'Enter', keyCode: 13, which: 13, code: 'Enter', bubbles: true, cancelable: true, composed: true };
      try { ed.dispatchEvent(new KeyboardEvent('keydown', opts)); }
      catch (e5) {
        try { var ev = document.createEvent('KeyboardEvent');
          ev.initKeyboardEvent('keydown', true, true, null, 'Enter', 0, '');
          ed.dispatchEvent(ev); }
        catch (e6) { console.log('[KimiBridge] keydown err', String(e6)); return false; }
      }
      console.log('[KimiBridge] uiSendText ok');
      return true;
    } catch (e) { console.log('[KimiBridge] uiSendText err', String(e && e.message || e)); return false; }
  }

  var __kimiUiRetryCount = 0;
  function autoContinue() {
    if (!window.__kimiPendingResult) return;
    if (window.__kimiStreaming) return;
    var txt = '[System: 工具执行结果]\n' + window.__kimiPendingResult +
      '\n[结果结束。请基于该工具结果回答用户之前的问题并继续任务：需要时请再次调用工具（允许与上次相同，用于继续读取/修改/重试），每次只发一个调用并等待结果；任务完成后直接回答用户。]';
    var pending = window.__kimiPendingResult;
    window.__kimiPendingResult = null;
    var fallback = function () {
      if (uiSendText(txt)) { window.__kimiUiRetryCount = 0; return; }
      if (window.__kimiUiRetryCount < 6) {
        window.__kimiUiRetryCount++;
        window.__kimiPendingResult = pending;
        console.log('[KimiBridge] both channels failed, retry#' + window.__kimiUiRetryCount);
        setTimeout(autoContinue, 2500);
      } else {
        window.__kimiUiRetryCount = 0;
        console.log('[KimiBridge] autoContinue gave up');
      }
    };
    // 通道1：协议层（复用页面真实请求结构避开参数校验）；通道2：UI 兜底
    if (window.__kimiLastReq) sendChatText(txt, fallback);
    else fallback();
  }

  // ---------- 流文本处理（从二进制事件流提取纯文本，增量防重复解析） ----------
  // Kimi 流式响应是 connect-RPC 二进制 envelope：文本被拆成 {"op":"append","mask":"block.text.content",...,"text":{"content":"片段"}} 事件，
  // 标签文本跨事件不连续，必须先从事件流中提取并拼接 text.content/think.content 片段，再做标签解析。
  function extractStreamText(buf) {
    var out = [];
    var re = /"(?:text|think)"\s*:\s*\{\s*"content"\s*:\s*"((?:[^"\\]|\\.)*)"/g, m;
    while ((m = re.exec(buf))) {
      var v = m[1];
      try { v = JSON.parse('"' + v + '"'); } catch (e) {}
      if (v) out.push(v);
    }
    return out.join('');
  }
  var __kimiParsedUpTo = 0;
  function handleStreamText(buf) {
    if (!buf) return;
    var txt = extractStreamText(buf);
    var calls = parseToolCalls(txt);
    if (!calls.length) calls = parseToolCalls(buf); // 兜底：兼容纯文本流
    if (!calls.length) return;
    // 只执行新出现的调用（resolveTool 失败时跳过，继续找下一个真实调用）
    for (var i = 0; i < calls.length; i++) {
      var c = calls[i];
      var fullN = resolveTool(c.nm);
      if (!fullN) continue; // 模板标签(如 full_tool_name)或未知工具，跳过
      var sig = fullN + '|' + JSON.stringify(c.args || {});
      if (kimiIsDupSig(sig)) continue;
      toolAndContinue(c.nm, c.args);
      break; // 一次只执行一个
    }
  }

  // ---------- fetch 拦截 ----------
  var ORIG_FETCH = window.fetch;
  window.fetch = function (input, init) {
    var url = (typeof input === 'string') ? input : (input && input.url) || '';
    // 只对消息发送端点做注入；GetChat/ListMessages 等是 JSON5 请求体，无需处理
    var isChat = url.indexOf(CHAT_URL_MARK) >= 0 && /\/Chat([?#]|$)/.test(url);
    if (isChat) { try { window.__kimiDiag.reqs++; } catch (e) {} }

    function headersToObj(h) {
      var o = {};
      if (!h) return o;
      try {
        if (typeof h.forEach === 'function') { h.forEach(function (v, k) { o[k] = v; }); return o; }
        if (typeof h === 'object') { for (var kk in h) { if (Object.prototype.hasOwnProperty.call(h, kk)) o[kk] = h[kk]; } }
      } catch (e) {}
      return o;
    }
    function injectBlocks(bodyObj) {
      var msg = bodyObj && bodyObj.message;
      if (!msg || !Array.isArray(msg.blocks)) { window.__kimiDiag.capReasons.push('no-blocks'); return false; }
      var found = false;
      for (var i = 0; i < msg.blocks.length; i++) {
        var blk = msg.blocks[i] || {};
        var c = blk.content || {};
        var orig = '';
        if (c.case === 'text' && c.value) { orig = c.value.content || ''; }
        else if (blk.text && typeof blk.text.content === 'string') { orig = blk.text.content; }
        if (orig !== '' || (blk.text && typeof blk.text.content === 'string') || (c.case === 'text' && c.value)) {
          found = true;
          if (orig.indexOf('[System: 工具执行结果]') < 0 && orig.indexOf('[System: You have access') < 0) {
            // 真实用户消息：重置去重窗口（跨会话/新任务不误伤相同签名），并清理残留状态
            if (orig.trim() !== '') {
              kimiResetDup();
              window.__kimiFailCount = 0;
            }
            var inj = '';
            if (window.__kimiPendingResult) {
              inj = '[System: 工具执行结果]\n' + window.__kimiPendingResult +
                '\n[结果结束。请基于该工具结果回答用户之前的问题并继续任务：需要时请再次调用工具（允许与上次相同，用于继续读取/修改/重试），每次只发一个调用并等待结果；任务完成后直接回答用户。]\n';
              window.__kimiPendingResult = null;
            }
            var newTxt = inj + toolSysPrompt() + orig;
            if (c.case === 'text' && c.value) c.value.content = newTxt;
            else blk.text.content = newTxt;
          }
          break;
        }
      }
      if (!found) {
        window.__kimiDiag.capReasons.push('no-text-block');
        try { window.__kimiDiag.lastNoTextBody = JSON.stringify(bodyObj).substring(0, 300); } catch (e) {}
        return false;
      }
      return true;
    }

    if (isChat && init) {
      var capOk = false;
      if (!init.body) { window.__kimiDiag.capReasons.push('no-body'); }
      else {
        try {
          var bodyObj = null, bodyIsBinary = false, flagsByte = 0;
          if (typeof init.body === 'string') {
            bodyObj = JSON.parse(init.body);
          } else if (init.body instanceof Uint8Array) {
            bodyIsBinary = true; flagsByte = init.body[0];
            bodyObj = JSON.parse(new TextDecoder().decode(init.body.subarray(5)));
          } else if (init.body instanceof ArrayBuffer) {
            bodyIsBinary = true; var u8b = new Uint8Array(init.body); flagsByte = u8b[0];
            bodyObj = JSON.parse(new TextDecoder().decode(u8b.subarray(5)));
          } else {
            window.__kimiDiag.capReasons.push('body-type:' + Object.prototype.toString.call(init.body));
          }
          if (bodyObj && injectBlocks(bodyObj)) {
            if (bodyIsBinary) {
              var payload = new TextEncoder().encode(JSON.stringify(bodyObj));
              var nb = new Uint8Array(5 + payload.length);
              nb[0] = flagsByte;
              nb[1] = (payload.length >> 24) & 255; nb[2] = (payload.length >> 16) & 255;
              nb[3] = (payload.length >> 8) & 255; nb[4] = payload.length & 255;
              nb.set(payload, 5);
              init.body = nb;
            } else {
              init.body = JSON.stringify(bodyObj);
            }
            window.__kimiLastReq = {
              url: url,
              headers: headersToObj(init.headers),
              bodyObj: JSON.parse(JSON.stringify(bodyObj)),
              isBinary: bodyIsBinary,
              flagsByte: flagsByte
            };
            window.__kimiLastReqTs = Date.now();
            capOk = true;
          }
        } catch (e) {
          window.__kimiDiag.capReasons.push('err:' + String(e && e.message || e));
        }
      }
      if (!capOk) console.log('[KimiBridge] capture skip', url.slice(-40), window.__kimiDiag.capReasons.slice(-2));
    }

    var p = ORIG_FETCH.apply(this, arguments);

    if (isChat) {
      window.__kimiStreaming = true;
      p.then(function (resp) {
        try {
          var cl = resp.clone();
          var reader = cl.body && cl.body.getReader();
          if (!reader) return;
          var buf = '';
          var td = new TextDecoder(); // 流式解码器，避免多字节 UTF-8 跨 chunk 被切断
          var lastLen = 0;
          function pump() {
            return reader.read().then(function (r) {
              if (r.done) {
                var tail = td.decode();
                buf += tail;
                window.__kimiStreaming = false;
                window.__kimiLastResp = buf;
                window.__kimiLiveResp = buf;
                handleStreamText(buf);
                setTimeout(function () { if (window.__kimiPendingResult && !window.__kimiBusy) autoContinue(); }, 300);
                return;
              }
              buf += td.decode(r.value, { stream: true });
              window.__kimiLiveResp = buf;
              // 增量解析：标签一旦完整出现立即执行，不等流结束
              if (buf.length - lastLen >= 64) { lastLen = buf.length; handleStreamText(buf); }
              return pump();
            });
          }
          pump();
        } catch (e) {}
      }, function () { window.__kimiStreaming = false; });
    }
    return p;
  };

  // ---------- 新标签页打开拦截：改为小窗打开，不脱离对话页 ----------
  window.__kimiPanelOpen = function (url) {
    try {
      window.__kimiPanelCount = (window.__kimiPanelCount || 0) + 1;
      console.log('[KimiBridge] panel open', String(url).slice(0, 120));
      var x = new XMLHttpRequest();
      x.open('POST', window.__kimiMcuBase(), true);
      x.timeout = 5000;
      x.setRequestHeader('Content-Type', 'application/json');
      x.send(JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method: 'tools/call', params: { name: 'browser_panel_open', arguments: { url: String(url) } } }));
      return true;
    } catch (e) { return false; }
  };
  (function () {
    var ORIG_OPEN = window.open;
    try {
      window.open = function (url) {
        try {
          if (url && typeof url === 'string' && /^https?:\/\//i.test(url)) {
            window.__kimiPanelOpen(url);
            return null;
          }
        } catch (e) {}
        return ORIG_OPEN.apply(this, arguments);
      };
    } catch (e) {}
    // 点击 target=_blank / 搜索结果卡片链接 → 小窗打开
    document.addEventListener('click', function (ev) {
      try {
        var el = ev.target;
        while (el && el !== document && !(el.tagName && el.tagName.toLowerCase() === 'a')) el = el.parentElement;
        if (!el || el.tagName === undefined) return;
        var href = el.getAttribute('href') || '';
        if (!/^https?:\/\//i.test(href) && !/^\/(search|link)/.test(href)) return;
        var isBlank = (el.getAttribute('target') || '').toLowerCase() === '_blank';
        var inResult = el.closest && (el.closest('[class*="search-result"]') || el.closest('[class*="result-card"]') || el.closest('[class*="citation"]'));
        if (!isBlank && !inResult) return;
        ev.preventDefault();
        ev.stopPropagation();
        var abs = el.href || href;
        window.__kimiPanelOpen(abs);
      } catch (e) {}
    }, true);
  })();

  // 注：MCP 探测（同步 XHR）延迟执行，避免拖慢 document-start 计时窗口导致脚本被隔离
  setTimeout(function () { try { window.__kimiMcuBase(); } catch (e) {} }, 1500);
  console.log('[KimiBridge] injected at document-start');
})();