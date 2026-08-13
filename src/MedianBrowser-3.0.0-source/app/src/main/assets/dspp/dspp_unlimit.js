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
    s = s.replace(/\[System: 工具执行结果\][\s\S]*?\[结果结束。请基于该工具结果回答用户之前的问题，不要重复调用相同工具。\]\s*/g, '');
    s = s.replace(/\[System: You have access to these local device tools via Median Bridge\][\s\S]*?Then answer the user based on the result\.\s*/g, '');
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
    var msg = cleanMsg(o.prompt || '');
    var nb = Object.assign({}, o, { parent_message_id: null, prompt: buildPrompt(h, msg) });
    h.push({ r: 'u', c: msg });
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
    for (var i = 0; i < h.length; i++) lines.push((h[i].r === 'u' ? '[用户] ' : '[助手] ') + (h[i].r === 'u' ? cleanMsg(h[i].c) : h[i].c));
    lines.push('[用户] ' + msg);
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
      var msg = cleanMsg(body.prompt || '');
      var rewritten = Object.assign({}, body, { parent_message_id: null, prompt: buildPrompt(h, msg) });
      h.push({ r: 'u', c: msg });
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