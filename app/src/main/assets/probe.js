// Runs inside the Outlook / AroFlo pages every few seconds.
// Reports where the page is up to and, on sign-in pages only, fills the login in.
// __CREDS__ is swapped for null, or for the login when the page is a sign-in page.
(function (c) {
  function vis(e) {
    if (!e) return false;
    var r = e.getBoundingClientRect();
    var st = getComputedStyle(e);
    return r.width > 0 && r.height > 0 && st.visibility !== 'hidden' && st.display !== 'none';
  }
  function q(sel) {
    var l = document.querySelectorAll(sel);
    for (var i = 0; i < l.length; i++) if (vis(l[i])) return l[i];
    return null;
  }
  function setVal(e, v) {
    var d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e), 'value');
    if (d && d.set) d.set.call(e, v); else e.value = v;
    e.dispatchEvent(new Event('input', { bubbles: true }));
    e.dispatchEvent(new Event('change', { bubbles: true }));
  }
  function later(fn, ms) { setTimeout(fn, ms); }
  var text = (document.body && document.body.innerText) || '';

  var s = { host: location.host, path: location.pathname, ready: document.readyState, act: '' };
  var pwd = q('input[type=password]');
  s.pwd = !!pwd;

  var codeIn = null;
  if (!pwd) {
    var ins = document.querySelectorAll('input');
    for (var i = 0; i < ins.length; i++) {
      var e = ins[i];
      if (!vis(e) || e.type === 'hidden' || e.type === 'checkbox') continue;
      var k = ((e.name || '') + ' ' + (e.id || '') + ' ' + (e.placeholder || '') + ' ' +
               (e.getAttribute('aria-label') || '') + ' ' + (e.autocomplete || '')).toLowerCase();
      if (/code|otp|token|verif|one-time|2fa|mfa/.test(k)) { codeIn = e; break; }
    }
  }
  s.code = !!codeIn;

  var num = q('#idRichContext_DisplaySign, .displaySign');
  if (num) s.number = num.textContent.trim();

  if (c && c.kind === 'ms') {
    var u = q('input[name=loginfmt]');
    var p = q('input[name=passwd]');
    var next = q('#idSIButton9');
    if (u && c.user && !u.value) {
      setVal(u, c.user); s.act = 'entered Microsoft email';
      later(function () { var b = q('#idSIButton9'); if (b) b.click(); }, 500);
    } else if (p && c.pass && !p.value) {
      setVal(p, c.pass); s.act = 'entered Microsoft password';
      later(function () { var b = q('#idSIButton9'); if (b) b.click(); }, 500);
    } else if (/stay signed in/i.test(text) && next) {
      next.click(); s.act = 'ticked stay signed in';
    } else if (/pick an account/i.test(text) && c.user) {
      var tiles = document.querySelectorAll('[data-test-id], .table, .tile, div[role=button], div[role=listitem]');
      for (var j = 0; j < tiles.length; j++) {
        if (vis(tiles[j]) && tiles[j].textContent.toLowerCase().indexOf(c.user.toLowerCase()) >= 0) {
          tiles[j].click(); s.act = 'picked Microsoft account'; break;
        }
      }
    }
  } else if (c && c.kind === 'af') {
    if (pwd && c.pass && !pwd.value) {
      var form = pwd.form || document;
      var us = form.querySelectorAll('input[type=text], input[type=email], input:not([type])');
      var uf = null;
      for (var m = 0; m < us.length; m++) { if (vis(us[m])) { uf = us[m]; break; } }
      if (uf && c.user && !uf.value) setVal(uf, c.user);
      setVal(pwd, c.pass);
      s.act = 'entered AroFlo password';
      later(function () {
        var b = form.querySelector('button[type=submit], input[type=submit], button');
        if (b) b.click(); else if (pwd.form) pwd.form.submit();
      }, 600);
    }
  }
  return JSON.stringify(s);
})(__CREDS__);
