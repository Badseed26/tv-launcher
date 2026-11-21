// Web tabs for Claude on the TV (21/11): drive CNW, Tradezone, Ambrose Tradies Web and AroFlo pages one step at a
// time, like a Chrome tab. The page stays open between requests (a few minutes, args.keep up to 15), so each request
// carries on from where the last one left it. Outlook has its own read only jobs (outlook.*). BridgeSelect is off.
//
// web.open {url}                     open a page (signs itself in with the login saved on the phone page if it has to)
// web.look {find, chars}             what's on the page: text, and every box, tick box, list and button with a ref number
// web.text {sel, from, chars}        more of the page's text (or one part of it)
// web.press {ref | text | sel}       press one thing (exactly one visible match). Never send, submit, pay, delete...
// web.fill {ref | label | sel, value} or {fields: [{ref, value}, ...]}
//                                    type into boxes, pick from lists, tick or untick boxes (value true/false), radios
// web.key {key, ref}                 Enter, Tab, Escape, ArrowDown on the box that has focus (or ref)
// web.scroll {y | ref}               scroll the page (px) or bring one thing into view
// web.wait {text, ms}                wait for some text to show up
// web.shot {label}                   a picture of the page, posted after the answer
// web.run {js}                       any bit of code on the page (ec, A, S), for anything the rest can't do
// web.close                          close the tab now (the TV goes back how it was)
// Every answer except web.text and web.run carries a fresh look at the page (a.look:false to skip it).
ec.run(async function () {
  var A = ec.args, S = ec.state, what = ec.job.do;
  if (/bridgeselect|rectraders/i.test(location.host)) return ec.fail('BridgeSelect is off on the TV (Ethan, 21/11)');

  var SITES = [
    { name: 'cnw', host: /(^|\.)cnw\.com\.au$/i, login: 'https://shop.cnw.com.au/login', out: function () { return document.body && document.body.classList.contains('logged-out'); } },
    { name: 'tradezone', host: /(^|\.)tradezone\.com\.au$/i, login: 'https://www.tradezone.com.au/customer/account/login/', out: function () { return false; } },
    { name: 'ambrose', host: /^tradies\.ambrosebuilding\.com\.au$/i, login: null, out: function () { return false; } }
  ];
  var site = SITES.filter(function (s) { return s.host.test(location.host); })[0];

  // ---- small helpers
  var flat = function (x) { return String(x == null ? '' : x).replace(/\s+/g, ' ').trim(); };
  var vis = ec.visible;
  var all = function (q, r) { return [].slice.call((r || document).querySelectorAll(q)); };
  var pwBox = function () { return all('input[type=password]').filter(vis)[0]; };
  // Wait for the page to stop changing (half a second quiet, 8 s tops)
  var settle = async function (ms) {
    var last = Date.now(), t0 = Date.now();
    var mo = new MutationObserver(function () { last = Date.now(); });
    try { mo.observe(document.documentElement, { subtree: true, childList: true, attributes: true, characterData: true }); } catch (e) {}
    while (Date.now() - last < 500 && Date.now() - t0 < (ms || 8000)) await ec.sleep(120);
    mo.disconnect();
    await ec.waitFor(function () { return !all('.spinner,.loading,[aria-busy=true],.mat-progress-spinner,.loader').some(vis); }, 4000, 200);
  };

  await settle(10000);

  // ---- AroFlo's own pages: session limit and sign in, same as the aroflo jobs
  if (/office\.aroflo\.com$/.test(location.host)) {
    if (/User Session Limit/i.test(ec.text())) {
      S.continues = (S.continues || 0) + 1; ec.save();
      if (S.continues > 3) return ec.fail('AroFlo kept showing the User Session Limit page');
      await ec.press({ text: 'continue', exact: false }, { expect: function () { return !/User Session Limit/i.test(ec.text()); }, what: 'AroFlo to let the TV in', timeout: 20000 });
      return;
    }
    if (pwBox() || /\/login/i.test(location.pathname)) {
      S.logins = (S.logins || 0) + 1; ec.save();
      if (S.logins > 2) return ec.fail('AroFlo still wants a sign-in after 2 goes with the saved login');
      var ra = window.ecApi.login('__TOKEN__');
      if (ra != 'ok') return ec.fail('AroFlo is signed out on the TV and it can\'t sign in: ' + ra);
      ec.leaving = true; await ec.sleep(25000);
      if (!ec.finished) ec.fail('AroFlo didn\'t move on after the sign-in');
      return;
    }
  }

  // ---- supplier and Ambrose sign in: the TV fills the login Ethan saved on the phone page
  var onLoginPage = !!pwBox() || (site && site.login && location.href.indexOf(site.login) == 0) ||
    (S.loginStep && all('input[type=email],input[type=text]').some(vis) && /sign ?in|log ?in/i.test(ec.text().slice(0, 3000)));
  if (site && !onLoginPage && site.out() && site.login) {
    S.logins = (S.logins || 0) + 1;
    if (S.logins > 3) return ec.fail(site.name + ' keeps saying signed out after 3 goes');
    if (!S.want) S.want = location.href;
    ec.log(site.name + ' is signed out, going to its sign in page');
    ec.go(site.login);
    return;
  }
  if (site && onLoginPage) {
    S.logins = (S.logins || 0) + 1; ec.save();
    if (S.logins > 3) return ec.fail('still on the ' + site.name + ' sign in page after 3 goes, check the saved login on the phone page');
    if (!S.want && what == 'web.open' && A.url && A.url.indexOf(site.login || '\u0000') != 0) S.want = A.url;
    var r = ec.siteLogin(site.name);
    if (r != 'ok') return ec.fail(site.name + ' sign in: ' + r);
    S.loginStep = !pwBox(); // email first, password on the next step
    ec.log('signing in to ' + site.name + (S.loginStep ? ' (email step)' : ''));
    ec.save();
    // it either moves to another page (this runs again there) or changes in place
    await ec.waitFor(function () { return !pwBox(); }, 20000, 500);
    await ec.sleep(1500);
    if (ec.finished) return;
    if (S.loginStep && pwBox()) { // two step sign in: now the password
      var r2 = ec.siteLogin(site.name);
      if (r2 != 'ok') return ec.fail(site.name + ' sign in: ' + r2);
      S.loginStep = false; ec.save();
      await ec.waitFor(function () { return !pwBox(); }, 20000, 500);
      await ec.sleep(1500);
    }
    if (pwBox()) { await ec.shot('sign in didn\'t take'); return ec.fail('the saved ' + site.name + ' login didn\'t get in (still asking for the password). Check it on the phone page', { pictures: 1 }); }
    await settle();
  }
  if (S.want && !pwBox() && location.href != S.want) {
    var w = S.want; S.want = null; S.signedIn = true;
    ec.go(w);
    return;
  }

  // ---- what's on the page
  var labelOf = function (el) {
    var t = el.getAttribute('aria-label') || '';
    if (!t && el.id) { var l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]'); if (l) t = l.innerText; }
    if (!t) { var lc = el.closest('label'); if (lc) t = lc.innerText; }
    if (!t && el.getAttribute('aria-labelledby')) t = el.getAttribute('aria-labelledby').split(/\s+/).map(function (i) { var x = document.getElementById(i); return x ? x.innerText : ''; }).join(' ');
    if (!t) t = el.placeholder || el.title || '';
    if (!t) { // the nearest text before it (form rows usually put the question first)
      var p = el.parentElement, n = 0;
      while (p && n < 4 && !t) { var tx = flat(p.innerText); if (tx && tx.length < 200) t = tx; p = p.parentElement; n++; }
    }
    if (!t) t = el.name || '';
    return flat(t).slice(0, 90);
  };
  var SEL = 'input:not([type=hidden]),select,textarea,button,a[href],[role=button],[role=checkbox],[role=radio],[role=combobox],[role=option],[role=tab],[role=menuitem],[contenteditable=true],mat-checkbox,mat-radio-button,mat-select';
  // ref numbers live on the page itself, so they stay the same across requests while the tab's open
  var nextRef = function () {
    if (!window.__ecRef) window.__ecRef = all('[data-ec]').reduce(function (m, x) { return Math.max(m, Number(x.getAttribute('data-ec')) || 0); }, 0);
    return ++window.__ecRef;
  };
  // Web pages get a wider block than lib's: anything that sends, submits, pays or finalises is Ethan's to press
  var WEB_BLOCK = /\b(submit|send|pay|payment|checkout|check out|place order|confirm order|purchase|buy now|lodge|finali[sz]e|complete report|mark (as )?complete|delete|archive|reply|forward|unsubscribe)\b/i;
  var shown = function (el) {
    if (vis(el)) return true;
    // a styled tick box: the real input is hidden behind its label
    if (/^(checkbox|radio)$/.test(el.type)) { var l = el.closest('label') || (el.id && document.querySelector('label[for="' + CSS.escape(el.id) + '"]')); return !!(l && vis(l)); }
    return false;
  };
  var els = function () {
    var out = [];
    all(SEL).forEach(function (el) {
      if (!shown(el) || el.closest('[aria-hidden=true]')) return;
      if (out.some(function (o) { return o.el.contains(el) && /^(BUTTON|A)$/.test(o.el.tagName); })) return;
      if (!el.getAttribute('data-ec')) el.setAttribute('data-ec', nextRef());
      out.push({ el: el, ref: el.getAttribute('data-ec') });
    });
    ec.save();
    return out;
  };
  var describe = function (o) {
    var el = o.el, tag = el.tagName.toLowerCase(), type = (el.type || el.getAttribute('role') || '').toLowerCase(), s;
    if (tag == 'select') {
      var opts = [].slice.call(el.options).map(function (x) { return flat(x.text); }).filter(Boolean);
      s = 'list "' + labelOf(el) + '" = "' + flat(el.options[el.selectedIndex] && el.options[el.selectedIndex].text) + '" [' + opts.slice(0, 12).join(' | ') + (opts.length > 12 ? ' | +' + (opts.length - 12) : '') + ']';
    } else if (type == 'checkbox' || tag == 'mat-checkbox' || el.getAttribute('role') == 'checkbox') {
      var on = el.checked !== undefined ? el.checked : /true|checked/.test((el.getAttribute('aria-checked') || '') + el.className);
      s = 'tick ' + (on ? '[x]' : '[ ]') + ' "' + labelOf(el) + '"';
    } else if (type == 'radio' || tag == 'mat-radio-button' || el.getAttribute('role') == 'radio') {
      var on2 = el.checked !== undefined ? el.checked : /true|checked/.test((el.getAttribute('aria-checked') || '') + el.className);
      s = 'radio ' + (on2 ? '(o)' : '( )') + ' "' + labelOf(el) + '"' + (el.name ? ' group ' + el.name : '');
    } else if (tag == 'input' || tag == 'textarea' || el.isContentEditable) {
      var v = el.isContentEditable ? el.innerText : el.value;
      s = (tag == 'textarea' || el.isContentEditable ? 'text area' : 'box' + (type && type != 'text' ? ' (' + type + ')' : '')) + ' "' + labelOf(el) + '"' +
        (type == 'password' ? (v ? ' = (filled)' : '') : v ? ' = "' + flat(v).slice(0, 120) + '"' : '') + (el.required ? ' *' : '') + (el.disabled || el.readOnly ? ' (locked)' : '');
    } else {
      var t = flat(el.innerText || el.value || el.getAttribute('aria-label') || el.title).slice(0, 70);
      s = (tag == 'a' ? 'link' : 'button') + ' "' + t + '"' + (el.disabled ? ' (off)' : '') + (WEB_BLOCK.test(t) ? ' (Ethan presses this)' : '');
    }
    return o.ref + ' ' + s;
  };
  var look = function (o) {
    o = o || {};
    var list = els().map(describe);
    if (o.find) { var f = String(o.find).toLowerCase(); list = list.filter(function (x) { return x.toLowerCase().indexOf(f) >= 0; }); }
    var text = ec.text(), chars = Math.min(Number(o.chars) || 1500, 20000);
    return { url: location.href.replace(/[?#].*$/, '') + (location.hash && location.hash.length < 80 ? location.hash : ''), title: document.title.slice(0, 90),
      text: text.slice(0, chars), more: Math.max(0, text.length - chars), els: list.slice(0, Number(o.max) || 150), elsMore: Math.max(0, list.length - (Number(o.max) || 150)) };
  };
  var target = function (a) {
    if (a.ref != null) {
      var el = document.querySelector('[data-ec="' + a.ref + '"]');
      if (!el) throw new Error('ref ' + a.ref + ' isn\'t on the page any more (the page changed), do a web.look');
      return el;
    }
    if (a.sel) return ec.one(a.sel);
    if (a.label) {
      var want = String(a.label).toLowerCase();
      var m = els().filter(function (o) { return /^(INPUT|SELECT|TEXTAREA)$/.test(o.el.tagName) || o.el.isContentEditable; }).filter(function (o) { return labelOf(o.el).toLowerCase().indexOf(want) >= 0; });
      if (m.length != 1) throw new Error('label "' + a.label + '" matched ' + m.length + ' boxes' + (m.length ? ': ' + m.slice(0, 4).map(describe).join('; ') : ''));
      return m[0].el;
    }
    if (a.text) return ec.one({ text: a.text, exact: a.exact === true, sel: a.within });
    throw new Error('say which: ref, label, text or sel');
  };
  // The thing to tap for an element: a hidden styled tick box taps its label
  var tapTarget = function (el) {
    if (vis(el)) return el;
    var l = el.closest('label') || (el.id && document.querySelector('label[for="' + CSS.escape(el.id) + '"]'));
    if (l && vis(l)) return l;
    throw new Error(ec.label(el) + ' can\'t be seen on the page');
  };
  var answer = function (extra) {
    var o = { ok: true };
    for (var k in (extra || {})) o[k] = extra[k];
    if (A.look !== false) o.look = look({ find: A.find, chars: A.chars || 1200, max: A.max });
    return o;
  };

  // A press or fill that moved to a new page: the job runs again here, answer with the new page
  if (S.acted) { await settle(); return answer({ moved: true, did: S.acted }); }

  if (what == 'web.open' || what == 'web.look') return answer(S.signedIn ? { signedIn: true } : {});

  if (what == 'web.text') {
    var root = A.sel ? ec.one(A.sel) : document.body, tx = flat(root.innerText), from = Number(A.from) || 0, n = Math.min(Number(A.chars) || 6000, 30000);
    return { ok: true, url: location.href.replace(/[?#].*$/, ''), from: from, text: tx.slice(from, from + n), more: Math.max(0, tx.length - from - n) };
  }

  if (what == 'web.press') {
    var el = target(A), t0 = tapTarget(el);
    var said = flat(t0.innerText || t0.value || t0.getAttribute('aria-label') || t0.title);
    if (WEB_BLOCK.test(said)) return ec.fail('not pressing "' + said.slice(0, 60) + '", send, submit, pay and the like are Ethan\'s to press');
    var before = location.href, beforeText = ec.text().length;
    S.acted = 'press ' + ec.label(t0); ec.save();
    await ec.press(t0, { script: !!A.script, shots: A.shots !== false });
    if (A.wait) await ec.waitFor(function () { return ec.text().indexOf(A.wait) >= 0; }, 15000, 300);
    await settle();
    S.acted = null; ec.save();
    return answer({ did: 'pressed ' + ec.label(t0), moved: location.href != before, changed: ec.text().length != beforeText });
  }

  var setOne = async function (a) {
    var el = target(a), v = a.value, tag = el.tagName, type = (el.type || '').toLowerCase();
    var role = el.getAttribute('role') || '';
    if (tag == 'SELECT') {
      var want = String(v).toLowerCase(), opts = [].slice.call(el.options);
      var opt = opts.filter(function (x) { return flat(x.text).toLowerCase() == want || x.value.toLowerCase() == want; })[0] ||
        opts.filter(function (x) { return flat(x.text).toLowerCase().indexOf(want) >= 0; })[0];
      if (!opt) throw new Error('"' + v + '" isn\'t in ' + describe({ el: el, ref: el.getAttribute('data-ec') }));
      var set = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set;
      el.focus(); set.call(el, opt.value);
      ['input', 'change'].forEach(function (t) { el.dispatchEvent(new Event(t, { bubbles: true })); });
      if (window.jQuery) try { jQuery(el).trigger('change'); } catch (e) {}
      el.blur();
      ec.log('pick ' + ec.label(el) + ' = ' + flat(opt.text));
      return 'picked "' + flat(opt.text) + '"';
    }
    if (type == 'checkbox' || type == 'radio' || /checkbox|radio/.test(role) || /^MAT-(CHECKBOX|RADIO-BUTTON)$/.test(tag)) {
      var on = function () { return el.checked !== undefined ? el.checked : /true/.test(el.getAttribute('aria-checked') || '') || /checked/.test(el.className); };
      var wantOn = !(v === false || v === 'false' || v === 0 || v === 'no' || v === 'off');
      if (on() == wantOn) return 'already ' + (wantOn ? 'ticked' : 'clear');
      await ec.press(tapTarget(el), { expect: function () { return on() == wantOn; }, what: 'the tick to change', shots: false });
      return wantOn ? 'ticked' : 'cleared';
    }
    if (el.isContentEditable) {
      el.focus(); el.innerText = String(v);
      el.dispatchEvent(new InputEvent('input', { bubbles: true })); el.blur();
      return 'typed';
    }
    if (type == 'password') throw new Error('passwords are never typed by a job, the TV fills its saved logins itself');
    ec.fill(el, String(v));
    // pickers that open a list as you type (suburbs, addresses): settle so the next step sees the list
    await ec.sleep(300);
    return 'typed';
  };

  if (what == 'web.fill') {
    var list = A.fields || [A], did = [];
    for (var i = 0; i < list.length; i++) {
      try { did.push((list[i].ref || list[i].label || list[i].sel) + ': ' + await setOne(list[i])); }
      catch (e) { did.push((list[i].ref || list[i].label || list[i].sel) + ': FAILED ' + e.message); if (!A.keepGoing) return ec.fail(did.join('; '), { done: did }); }
    }
    await settle(4000);
    if (A.shot) await ec.shot('after fill');
    return answer({ did: did, pictures: A.shot ? 1 : 0 });
  }

  if (what == 'web.key') {
    var k = String(A.key || 'Enter'), kel = A.ref != null || A.sel || A.label ? target(A) : document.activeElement;
    if (!kel || kel === document.body) return ec.fail('nothing has focus, say which with ref');
    if (/^enter$/i.test(k) && kel.form && [].slice.call(kel.form.querySelectorAll('button,input[type=submit]')).some(function (b) { return WEB_BLOCK.test(flat(b.innerText || b.value)); }))
      return ec.fail('not pressing Enter in a form that has a send/submit button, Ethan does those');
    kel.focus();
    var codes = { Enter: 13, Tab: 9, Escape: 27, ArrowDown: 40, ArrowUp: 38 };
    ['keydown', 'keypress', 'keyup'].forEach(function (t) { kel.dispatchEvent(new KeyboardEvent(t, { key: k, code: k, keyCode: codes[k] || 0, which: codes[k] || 0, bubbles: true })); });
    S.acted = 'key ' + k; ec.save();
    await settle(5000);
    S.acted = null; ec.save();
    return answer({ did: 'key ' + k });
  }

  if (what == 'web.scroll') {
    if (A.ref != null || A.sel || A.text) target(A).scrollIntoView({ block: 'center' });
    else window.scrollBy(0, Number(A.y) || 600);
    await settle(2000);
    return answer({ did: 'scrolled' });
  }

  if (what == 'web.wait') {
    var got = await ec.waitFor(function () { return ec.text().indexOf(A.text) >= 0; }, Math.min(Number(A.ms) || 15000, 60000), 300);
    return answer({ found: !!got });
  }

  if (what == 'web.shot') {
    if (A.ref != null || A.sel) target(A).scrollIntoView({ block: 'center' });
    await ec.shot(A.label || 'page');
    return answer({ pictures: 1 });
  }

  if (what == 'web.run') {
    if (typeof window.__ecRun != 'function') return ec.fail('the js didn\'t parse');
    var out = await window.__ecRun(ec, A, S);
    if (ec.finished || ec.leaving) return;
    return { ok: true, result: out === undefined ? null : out };
  }

  if (what == 'web.close') return { ok: true, closed: true };

  return ec.fail('unknown web job ' + what);
});
