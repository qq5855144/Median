// ==UserScript==
// @name         DeepSeek 解除对话长度上限 v6
// @namespace    median.dspp-unlimit
// @version      6.0.0
// @description  解除 DeepSeek 对话长度上限（v6：剥离注入内容，防历史膨胀）
// @match        *://chat.deepseek.com/*
// @run-at       document-start
// @grant        none
// ==/UserScript==
// ============================================================
// DeepSeek 网页版「解除对话长度上限」注入脚本 v6（fetch+XHR 双通道，剥离注入内容）
// 修复：sid 从 URL 兜底（页面请求体不带 chat_session_id）
// ============================================================
(function () {
  if (window.__dsUnlimitV6) return;
  window.__dsUnlimitV6 = true;
  window.__dsDiag = { injectAt: Date.now(), histHookHit: false, completionHookHit: false, xhrCompletionHit: false, urlHits: [] };
  var KEY = 'dsul_hist_';
  var ORIG = window.fetch;
  var MAX_LEN = 150000;
  var MAX_ITEMS = 200;

  function urlSid() {
    var m = location.pathname.match(/\/s\/([0-9a-f-]+)/);
    return m ? m[1] : '';
  }

  function cleanMsg(p) {
    var s = String(p || '');
    // v2: 不再剥离 [System: 工具执行结果] 与工具系统提示（由 mainworld 注入，必须透传给模型）
    // 仅清理零宽字符与多余空白，避免破坏工具结果回传
    s = s.replace(/[\u200b\u200c\u200d]/g, '');
    return s.trim();
  }

  function extractSse(txt) {
    var acc = '';
    var re = /data:\s*(\{.*?\})\s*(?:\n|$)/g, m;
    while ((m = re.exec(txt))) {
      try {
        var o = JSON.parse(m[1]), v = o.v;
        if (typeof v === 'string') acc += v;
        else if (v && v.response && Array.isArray(v.response.fragments)) {
          for (var j = 0; j < v.response.fragments.length; j++) {
            var f = v.response.fragments[j];
            if (f.type === 'RESPONSE' && f.content) acc += f.content;
          }
        }
      } catch (e) {}
    }
    return acc;
  }
  function rewriteCompletion(body) {
    var o;
    try { o = typeof body === 'string' ? JSON.parse(body) : body; } catch (e) { return null; }
    if (!o) return null;
    var sid = String(o.chat_session_id || '');
    if (!sid) sid = urlSid();
    var h = histGet(sid);
    var _rawP = String(o.prompt || '');
    var _tr = _rawP.match(/\[System: 工具执行结果\][\s\S]*?\[结果结束。请基于该工具结果回答用户之前的问题，不要重复调用相同工具。\]\s*/);
    var msg = cleanMsg(_rawP);
    var nb = Object.assign({}, o, { parent_message_id: null, prompt: (_tr ? _tr[0] : '') + buildPrompt(h, msg) });
    if (msg && msg.replace(/[\u200b\u200c\u200d\s]/g, '')) h.push({ r: 'u', c: msg });
    histSet(sid, h);
    window.__dsLastRewrite = { sid: sid, histLen: h.length, body: nb, rawBody: o };
    return { sid: sid, body: JSON.stringify(nb), h: h };
  }
  function histGet(sid) {
    try { return JSON.parse(sessionStorage.getItem(KEY + sid) || '[]'); } catch (e) { return []; }
  }
  function histSet(sid, h) {
    try { sessionStorage.setItem(KEY + sid, JSON.stringify(h.slice(-MAX_ITEMS))); } catch (e) {}
  }
  function buildPrompt(h, msg) {
    var lines = [];
    for (var i = 0; i < h.length; i++) {
      var cc = String(h[i].c || '');
      if (!cc.replace(/[\u200b\u200c\u200d\s]/g, '')) continue;
      lines.push((h[i].r === 'u' ? '[用户] ' : '[助手] ') + (h[i].r === 'u' ? cleanMsg(cc) : cc));
    }
    if (msg && msg.replace(/[\u200b\u200c\u200d\s]/g, '')) lines.push('[用户] ' + msg);
    var s = lines.join('\n');
    if (s.length > MAX_LEN) s = s.slice(-MAX_LEN);
    return s;
  }
  function wrapStream(resp, cb) {
    try {
      var reader = resp.body.getReader();
      var dec = new TextDecoder();
      var buf = '', acc = '';
      var stream = new ReadableStream({
        start: function (ctl) {
          function pump() {
            reader.read().then(function (r) {
              if (r.done) { ctl.close(); cb(acc); return; }
              buf += dec.decode(r.value, { stream: true });
              var i;
              while ((i = buf.indexOf('\n')) >= 0) {
                var line = buf.slice(0, i); buf = buf.slice(i + 1);
                if (line.indexOf('data:') === 0) {
                  var d = line.slice(5).trim();
                  if (!d) continue;
                  try {
                    var o = JSON.parse(d), v = o.v;
                    if (typeof v === 'string') acc += v;
                    else if (v && v.response && Array.isArray(v.response.fragments)) {
                      for (var j = 0; j < v.response.fragments.length; j++) {
                        var f = v.response.fragments[j];
                        if (f.type === 'RESPONSE' && f.content) acc += f.content;
                      }
                    }
                  } catch (e) {}
                }
              }
              ctl.enqueue(r.value);
              pump();
            }).catch(function (e) { ctl.error(e); });
          }
          pump();
        }
      });
      return new Response(stream, { status: resp.status, statusText: resp.statusText, headers: resp.headers });
    } catch (e) { return resp; }
  }

  window.fetch = function (input, init) {
    init = init || {};
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    if (url.indexOf('/api/v0/chat/') >= 0) {
      window.__dsDiag.urlHits.push(url.slice(0, 100));
    }
    if (url.indexOf('/api/v0/chat/history_messages') >= 0) {
      window.__dsDiag.histHookHit = true;
      return ORIG.apply(this, arguments).then(function (resp) {
        try {
          var c = resp.clone();
          c.json().then(function (j) {
            var bd = (j && j.data && j.data.biz_data) || {};
            var msgs = bd.messages || bd.chat_messages || [];
            if (msgs.length) {
              var sid = '';
              try { sid = String(JSON.parse(init.body || '{}').chat_session_id || ''); } catch (e) {}
              if (!sid) sid = urlSid();
              var h = [];
              for (var i = 0; i < msgs.length; i++) {
                var m = msgs[i];
                if (m && m.content) { var mc = typeof m.content === 'string' ? m.content : JSON.stringify(m.content); if (m.role === 'user' || m.role === 'USER') mc = cleanMsg(mc); h.push({ r: (m.role === 'user' || m.role === 'USER') ? 'u' : 'a', c: mc }); }
              }
              if (h.length) histSet(sid, h);
            }
          }).catch(function () {});
        } catch (e) {}
        return resp;
      });
    }
    if (url.indexOf('/api/v0/chat/completion') >= 0 && init.method === 'POST') {
      window.__dsDiag.completionHookHit = true;
      var body;
      try { body = JSON.parse(init.body); } catch (e) { return ORIG.apply(this, arguments); }
      var sid = String(body.chat_session_id || '');
      if (!sid) sid = urlSid();
      var h = histGet(sid);
      var _rawP2 = String(body.prompt || '');
      var _tr2 = _rawP2.match(/\[System: 工具执行结果\][\s\S]*?\[结果结束。请基于该工具结果回答用户之前的问题，不要重复调用相同工具。\]\s*/);
      var msg = cleanMsg(_rawP2);
      var rewritten = Object.assign({}, body, { parent_message_id: null, prompt: (_tr2 ? _tr2[0] : '') + buildPrompt(h, msg) });
      if (msg && msg.replace(/[\u200b\u200c\u200d\s]/g, '')) h.push({ r: 'u', c: msg });
      histSet(sid, h);
      window.__dsLastRewrite = { sid: sid, histLen: h.length, body: rewritten, rawBody: body };
      return ORIG.call(this, input, Object.assign({}, init, { body: JSON.stringify(rewritten) })).then(function (resp) {
        var h2 = histGet(sid);
        return wrapStream(resp, function (ai) {
          if (ai) { h2.push({ r: 'a', c: ai }); histSet(sid, h2); }
        });
      });
    }
    return ORIG.apply(this, arguments);
  };

  // ---- XHR hook（页面可能走 XHR 而非 fetch）----
  var XO = XMLHttpRequest.prototype.open;
  var XS = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__dsU = url || '';
    return XO.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    var u = this.__dsU || '';
    if (u.indexOf('/api/v0/chat/completion') >= 0) {
      window.__dsDiag.xhrCompletionHit = true;
      var rw = rewriteCompletion(body);
      if (rw) {
        var self = this;
        this.addEventListener('loadend', function () {
          try {
            var txt = self.responseText || '';
            var acc = extractSse(txt);
            if (acc) { var h2 = histGet(rw.sid); h2.push({ r: 'a', c: acc }); histSet(rw.sid, h2); }
          } catch (e) {}
        });
        return XS.call(this, rw.body);
      }
    }
    return XS.apply(this, arguments);
  };
  // DOM 兜底历史提取（hydration 后）
  setTimeout(function () {
    try {
      var sid = urlSid();
      if (sid && histGet(sid).length === 0) {
        var h = [];
        var seen = [];
        document.querySelectorAll('.ds-message, .ds-markdown').forEach(function (el) {
          if (seen.indexOf(el) >= 0) return;
          seen.push(el);
          var isAsst = el.className.indexOf('assistant') >= 0 || el.querySelector('.ds-markdown') || el.className.indexOf('ds-markdown') >= 0;
          var txt = (el.innerText || '').trim();
          if (txt && txt.indexOf('已思考（') !== 0) h.push({ r: isAsst ? 'a' : 'u', c: txt });
        });
        if (h.length) histSet(sid, h);
      }
    } catch (e) {}
  }, 5000);

  console.log('[DS-Unlimit] v6 注入成功');
})();