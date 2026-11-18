// Screen-off mail check. Runs in an invisible Outlook page: catches the mail list Outlook downloads as it starts,
// hands the newest emails to the TV app, and blocks anything that would change the mailbox (read status included).
(function () {
  if (window.__ecPeek) return;
  window.__ecPeek = 1;
  var T = '@@PEEKTOKEN@@', B = window.ecPeekBridge;
  if (!B) return;
  var sent = false, seen = {};
  var WRITE = /^(Update|MarkAs|MarkAll|SetRead|Delete|Move|Send|Create|Apply|Flag|Categori|Archive|Empty|Ignore|Report)/i;

  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return String(u); } }
  function action(u) { var m = /[?&]action=([A-Za-z]+)/.exec(u) || /\/([a-z]+data)\.ashx/i.exec(u) || /\/(graphql)\b/i.exec(u); return m ? m[1] : ''; }
  function ours(u) { return /^https:\/\/outlook\.(cloud\.microsoft|office\.com|office365\.com)\//.test(u); }
  function note(u) { var a = action(u); if (a && !seen[a]) { seen[a] = 1; try { B.seen(T, a); } catch (e) {} } return a; }
  function isWrite(a, body) { return WRITE.test(a) || (a == 'graphql' && typeof body == 'string' && /\bmutation\b/.test(body)); }
  function block(a) { try { B.blocked(T, a); } catch (e) {} }

  function str(v) { return typeof v == 'string' ? v : ''; }
  function who(o) {
    var m = (o.From && o.From.Mailbox) || (o.Sender && o.Sender.Mailbox) || o.From || o.Sender;
    var s = m && typeof m == 'object' ? [str(m.Name), str(m.EmailAddress)].filter(Boolean).join(' ') : '';
    if (!s && o.UniqueSenders && o.UniqueSenders.join) s = o.UniqueSenders.join(', ');
    if (!s && o.LastSender && o.LastSender.Mailbox) s = [str(o.LastSender.Mailbox.Name), str(o.LastSender.Mailbox.EmailAddress)].filter(Boolean).join(' ');
    return s;
  }
  // Anything that looks like an email: a subject and a received time.
  function grab(j) {
    var out = [], keys = {}, n = 0;
    (function walk(o, d) {
      if (!o || typeof o != 'object' || d > 14 || n > 4000) return;
      n++;
      if (o.length !== undefined && o.forEach) { for (var i = 0; i < o.length; i++) walk(o[i], d + 1); return; }
      var subj = str(o.ConversationTopic) || str(o.Subject);
      var when = str(o.LastDeliveryTime) || str(o.DateTimeReceived) || str(o.ReceivedOrRenewTime) || str(o.LastDeliveryOrRenewTime);
      var t = when ? Date.parse(when) : NaN;
      if (subj && !isNaN(t)) {
        var k = subj + '|' + t;
        if (!keys[k]) {
          keys[k] = 1;
          out.push({ t: t, from: who(o), subj: subj.slice(0, 200), prev: str(o.Preview).slice(0, 200),
            unread: o.UnreadCount > 0 || o.IsRead === false, cls: str(o.ItemClass) });
        }
        return;
      }
      for (var key in o) if (Object.prototype.hasOwnProperty.call(o, key)) walk(o[key], d + 1);
    })(j, 0);
    out.sort(function (a, b) { return b.t - a.t; });
    return out;
  }
  function handle(u, req, text) {
    if (sent || !text || text.length > 12e6) return;
    var j; try { j = JSON.parse(text); } catch (e) { return; }
    var it = grab(j);
    if (!it.length) return;
    sent = true;
    try { B.items(T, JSON.stringify({ action: action(u), items: it.slice(0, 40), req: req })); } catch (e) {}
  }
  function headersOf(h) {
    var o = {};
    try { if (h) new Headers(h).forEach(function (v, k) { o[k] = v; }); } catch (e) {}
    return o;
  }

  var of = window.fetch;
  if (of) window.fetch = function (input, init) {
    var isReq = input && typeof input == 'object' && 'url' in input;
    var u = abs(isReq ? input.url : input), a = ours(u) ? note(u) : '';
    var body = init && typeof init.body == 'string' ? init.body : null;
    if (a && isWrite(a, body)) { block(a); return Promise.reject(new TypeError('blocked by the TV mail check')); }
    var p = of.apply(this, arguments);
    if (!sent && ours(u)) {
      var req = { url: u, method: ((init && init.method) || (isReq && input.method) || 'GET').toUpperCase(),
        headers: isReq ? headersOf(input.headers) : headersOf(init && init.headers), body: body };
      if (init && init.headers && isReq) { var extra = headersOf(init.headers); for (var k in extra) req.headers[k] = extra[k]; }
      p.then(function (r) {
        try {
          if (!/json/i.test(r.headers.get('content-type') || '')) return;
          r.clone().text().then(function (t) { handle(u, req, t); }, function () {});
        } catch (e) {}
      }, function () {});
    }
    return p;
  };

  var XP = XMLHttpRequest.prototype, XO = XP.open, XS = XP.send, XH = XP.setRequestHeader;
  XP.open = function (m, u) { this.__ec = { url: abs(u), method: String(m || 'GET').toUpperCase(), headers: {} }; return XO.apply(this, arguments); };
  XP.setRequestHeader = function (k, v) { if (this.__ec) this.__ec.headers[String(k).toLowerCase()] = v; return XH.apply(this, arguments); };
  XP.send = function (b) {
    var x = this, e = x.__ec;
    if (e && ours(e.url)) {
      var a = note(e.url);
      if (a && isWrite(a, b)) { block(a); return; }
      e.body = typeof b == 'string' ? b : null;
      if (!sent) x.addEventListener('load', function () {
        try {
          var t = x.responseType === '' || x.responseType === 'text' ? x.responseText : x.responseType === 'json' ? JSON.stringify(x.response) : null;
          if (t) handle(e.url, e, t);
        } catch (er) {}
      });
    }
    return XS.apply(this, arguments);
  };
})();
