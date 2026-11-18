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
  if (!pwd && !codeIn && /code|verif|authenticat|two.?factor|2fa/i.test(text)) {
    // Fallback: a page talking about a code with a single text box on it.
    var boxes1 = [];
    var all = document.querySelectorAll('input[type=text], input[type=number], input[type=tel], input:not([type])');
    for (var a = 0; a < all.length; a++) if (vis(all[a])) boxes1.push(all[a]);
    if (boxes1.length === 1) codeIn = boxes1[0];
  }
  s.code = !!codeIn;

  s.limit = /maximum number of device sessions|user session limit/i.test(text);

  var num = q('#idRichContext_DisplaySign, .displaySign');
  if (num) s.number = num.textContent.trim();

  if (c && c.kind === 'press' && c.label) {
    // Button press sent from AirHQ, e.g. CONTINUE on AroFlo's session limit screen.
    var want = c.label.trim().toLowerCase();
    var els = document.querySelectorAll('button, a, input[type=submit], input[type=button], [role=button], div, span');
    var hit = null;
    for (var e2 = 0; e2 < els.length; e2++) {
      var el = els[e2];
      var tx = (el.value || el.textContent || '').trim().toLowerCase();
      if (tx === want && vis(el)) { hit = el; if (/^(button|a|input)$/i.test(el.tagName) || el.getAttribute('role') === 'button') break; }
    }
    if (hit) { hit.click(); s.act = 'pressed ' + c.label; } else { s.act = 'no "' + c.label + '" button on screen'; }
  } else if (c && c.kind === 'code' && c.code) {
    // Code pushed from AirHQ: type it in, tick any "remember this device / 30 days" box, then submit.
    if (codeIn) {
      setVal(codeIn, c.code);
      var notes = ['code entered'];
      var boxes = document.querySelectorAll('input[type=checkbox]');
      for (var b = 0; b < boxes.length; b++) {
        var bx = boxes[b];
        var lab = '';
        if (bx.id) { var l1 = document.querySelector('label[for="' + bx.id + '"]'); if (l1) lab += l1.textContent; }
        var l2 = bx.closest('label'); if (l2) lab += ' ' + l2.textContent;
        if (bx.parentElement) lab += ' ' + bx.parentElement.textContent;
        lab += ' ' + (bx.name || '') + ' ' + (bx.id || '');
        if (/remember|trust|30|don.?t ask|days/i.test(lab)) {
          if (!bx.checked) { bx.click(); if (!bx.checked) { bx.checked = true; bx.dispatchEvent(new Event('change', { bubbles: true })); } }
          notes.push('remember ticked');
          break;
        }
      }
      var cform = codeIn.form || document;
      later(function () {
        var btn = null;
        var cands = cform.querySelectorAll('button, input[type=submit], input[type=button], a[role=button]');
        for (var x = 0; x < cands.length; x++) {
          var t = (cands[x].textContent || cands[x].value || '').trim();
          if (vis(cands[x]) && /verify|submit|continue|sign in|log ?in|confirm|next|ok/i.test(t)) { btn = cands[x]; break; }
        }
        if (!btn) btn = cform.querySelector('button[type=submit], input[type=submit]');
        if (btn) btn.click(); else if (codeIn.form) codeIn.form.submit();
      }, 700);
      notes.push('submitted');
      s.act = notes.join(', ');
    } else {
      s.act = 'no code box on screen';
    }
  } else if (c && c.kind === 'ms') {
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
        // Prefer AroFlo's OFFICE LOGIN button (not Field Login, not the show-password eye).
        var b = null, pats = [/office\s*login/i, /^\s*(log\s?in|sign\s?in)\s*$/i];
        var cands = document.querySelectorAll('button, input[type=submit], a[role=button], [role=button]');
        for (var pi = 0; pi < pats.length && !b; pi++) {
          for (var ci = 0; ci < cands.length; ci++) {
            var tt = (cands[ci].textContent || cands[ci].value || '').trim();
            if (vis(cands[ci]) && pats[pi].test(tt)) { b = cands[ci]; break; }
          }
        }
        if (!b) b = form.querySelector('button[type=submit], input[type=submit]');
        if (b) b.click(); else if (pwd.form) pwd.form.submit();
      }, 600);
    }
  }
  return JSON.stringify(s);
})(__CREDS__);
