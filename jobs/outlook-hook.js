// ecApiHook: runs in an api job's Outlook page before Outlook's own code (21/11).
// Outlook is read only for the TV: every mailbox write gets blocked here (read status included, Lisa tracks her work
// with unread), whether it comes from the page, a background worker or Outlook's graphql. It also notes the headers
// Outlook signs its own mail calls with, so the job can ask for mail the same way (they never leave the page).
// Loaded fresh from GitHub on every Outlook job, like the job code.
(function () {
  if (window.__ecApiHook) return;
  window.__ecApiHook = 1;
  var T = '__TOKEN__', A = window.ecApi;
  var WRITE = /^(Update|MarkAs|MarkAll|Mark|SetRead|Delete|Move|Copy|Send|Create|Apply|Flag|Categori|Archive|Empty|Ignore|Report|Respond|Snooze|SaveItem|Upload|Remove|AddItem|Undo)/i;
  var WORKER_WRITE = /\b(MarkAsRead|MarkItemsAsRead|MarkAllItemsAsRead|SetReadFlag|UpdateItem|UpdateConversation|SendItem|CreateItem|MoveItem|CopyItem|DeleteItem|ArchiveItem|ApplyConversationAction|MarkAsJunk|EmptyFolder)\b|\bmutation\s+\w*(Mark|Update|Delete|Move|Send|Create|Archive|Flag|Set|Snooze|Pin|Categor)/;
  var ol = window.__ecOl = { hdr: null, base: null, seen: [], blocked: [] };

  function log(s) { try { A.log(T, 'hook: ' + s); } catch (e) {} }
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return String(u); } }
  function ours(u) { return /^https:\/\/(outlook\.(cloud\.microsoft|office\.com|office365\.com|live\.com)|substrate\.office\.com)\//.test(u); }
  function action(u, h) {
    var m = /[?&]action=([A-Za-z]+)/.exec(u) || /\/service\.svc\/s\/([A-Za-z]+)/.exec(u) || /\/([a-z]+data)\.ashx/i.exec(u) || /\/(graphql)\b/i.exec(u);
    return m ? m[1] : (h && h.action) || '';
  }
  function isWrite(a, body) { return WRITE.test(a) || (/graphql/i.test(a) && typeof body == 'string' && /\bmutation\b/.test(body)); }
  function block(where, a) { if (ol.blocked.length < 50) ol.blocked.push(a); log('BLOCKED ' + where + ' ' + a); }
  function lower(h) {
    var o = {};
    try { if (h) new Headers(h).forEach(function (v, k) { o[k] = v; }); } catch (e) {}
    return o;
  }
  // Keep the headers Outlook signs a mail call with (the newest one wins)
  function keep(u, h, a) {
    if (a && ol.seen.indexOf(a) < 0 && ol.seen.length < 40) ol.seen.push(a);
    if (!/service\.svc/.test(u)) return;
    if (!h.authorization && !h['x-owa-canary']) return;
    var k = {};
    ['authorization', 'x-owa-canary', 'x-anchormailbox', 'x-owa-sessionid', 'x-req-source', 'x-owa-hosted-ux', 'prefer', 'x-owa-correlationid'].forEach(function (n) { if (h[n]) k[n] = h[n]; });
    ol.hdr = k;
    ol.base = u.replace(/service\.svc.*$/, 'service.svc');
    ol.at = Date.now();
  }

  var of = window.fetch;
  if (of) window.fetch = function (input, init) {
    var isReq = input && typeof input == 'object' && 'url' in input;
    var u = abs(isReq ? input.url : input);
    if (ours(u)) {
      var h = isReq ? lower(input.headers) : {};
      var h2 = lower(init && init.headers); for (var k in h2) h[k] = h2[k];
      var a = action(u, h), body = init && typeof init.body == 'string' ? init.body : null;
      if (a && isWrite(a, body)) { block('fetch', a); return Promise.reject(new TypeError('blocked by the TV (read only)')); }
      keep(u, h, a);
    }
    return of.apply(this, arguments);
  };

  var XP = XMLHttpRequest.prototype, XO = XP.open, XS = XP.send, XH = XP.setRequestHeader;
  XP.open = function (m, u) { this.__ec = { url: abs(u), h: {} }; return XO.apply(this, arguments); };
  XP.setRequestHeader = function (k, v) { if (this.__ec) this.__ec.h[String(k).toLowerCase()] = v; return XH.apply(this, arguments); };
  XP.send = function (b) {
    var e = this.__ec;
    if (e && ours(e.url)) {
      var a = action(e.url, e.h);
      if (a && isWrite(a, b)) { block('xhr', a); try { this.abort(); } catch (x) {} return; }
      keep(e.url, e.h, a);
    }
    return XS.apply(this, arguments);
  };

  // sendBeacon can carry writes too
  if (navigator.sendBeacon) {
    var sb = navigator.sendBeacon.bind(navigator);
    navigator.sendBeacon = function (u, d) {
      var a = action(abs(u));
      if (ours(abs(u)) && a && WRITE.test(a)) { block('beacon', a); return false; }
      return sb(u, d);
    };
  }

  // The new Outlook does a lot of its mailbox calls in a background worker. Messages to it that ask for a write
  // never get there.
  function wrapPost(target, where) {
    var pm = target.postMessage;
    target.postMessage = function (m) {
      try {
        var s = typeof m == 'string' ? m : JSON.stringify(m);
        if (s && s.length < 2e6) { var w = WORKER_WRITE.exec(s); if (w) { block(where, w[0].slice(0, 60)); return; } }
      } catch (e) {} // can't be read as text (transfer objects): let it through
      return pm.apply(this, arguments);
    };
  }
  var WK = window.Worker;
  if (WK) {
    var W2 = function (u, o) {
      var w = o === undefined ? new WK(u) : new WK(u, o);
      try { wrapPost(w, 'worker'); } catch (e) {}
      return w;
    };
    W2.prototype = WK.prototype;
    window.Worker = W2;
  }
  var SWK = window.SharedWorker;
  if (SWK) {
    var S2 = function (u, o) {
      var w = o === undefined ? new SWK(u) : new SWK(u, o);
      try { wrapPost(w.port, 'shared worker'); } catch (e) {}
      return w;
    };
    S2.prototype = SWK.prototype;
    window.SharedWorker = S2;
  }
  var MP = window.MessagePort && MessagePort.prototype;
  if (MP && MP.postMessage) wrapPost(MP, 'port');
  try {
    var SC = navigator.serviceWorker && window.ServiceWorker && ServiceWorker.prototype;
    if (SC && SC.postMessage) wrapPost(SC, 'service worker');
  } catch (e) {}
})();
