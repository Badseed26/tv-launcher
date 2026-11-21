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
    { name: 'ambrose', host: /^tradies\.ambrosebuilding\.com\.au$/i, login: null, out: function () { return false; } },
    { name: 'ambroseadmin', host: /^tradiesadmin\.ambroseconstruct\.com\.au$/i, login: null, out: function () { return false; } }
  ];
  var siteFor = function (h) { return SITES.filter(function (s) { return s.host.test(h); })[0]; };
  var site = siteFor(location.host);
  // a sign in page on some other host (a login server): it's for the site this job came from
  if (site) S.lastSite = site.name;
  else if (A.url && !S.lastSite) { try { var h0 = siteFor(new URL(A.url).host); if (h0) S.lastSite = h0.name; } catch (e) {} }
  if (!site && S.lastSite && pwBoxNow()) site = SITES.filter(function (s) { return s.name == S.lastSite; })[0];
  function pwBoxNow() { return [].slice.call(document.querySelectorAll('input[type=password]')).some(ec.visible); }

  // ---- small helpers
  var flat = function (x) { return String(x == null ? '' : x).replace(/\s+/g, ' ').trim(); };
  var vis = ec.visible;
  var all = function (q, r) { return [].slice.call((r || document).querySelectorAll(q)); };
  var pwBox = function () { return all('input[type=password]').filter(vis)[0]; };
  // Wait for the page to stop changing (half a second quiet, 8 s tops)
  // Wait for the page to stop changing: 400 ms with no new content (sliders and animations moving things about don't
  // count), 3 s tops unless asked, then any spinner gone (4 s tops)
  var settle = async function (ms) {
    var last = Date.now(), t0 = Date.now();
    var mo = new MutationObserver(function (list) {
      for (var i = 0; i < list.length; i++) if (list[i].type != 'childList' || list[i].addedNodes.length || list[i].removedNodes.length) { last = Date.now(); return; }
    });
    try { mo.observe(document.body || document.documentElement, { subtree: true, childList: true, characterData: true }); } catch (e) {}
    while (Date.now() - last < 400 && Date.now() - t0 < (ms || 3000)) await ec.sleep(100);
    mo.disconnect();
    await ec.waitFor(function () { return !all('.spinner,.loading,[aria-busy=true],.mat-progress-spinner,.loader').some(vis); }, 4000, 150);
  };

  // Cloudflare's "are you a bot" page (Tradezone, 21/11): it often clears by itself in a few seconds. If it wants a
  // person to tick it, the job never does that: it says so and leaves the tab open for Ethan (phone page, tap the box).
  var botCheck = function () { return /^Just a moment/i.test(document.title) || /Performing security verification|Verify you are human/i.test(document.body ? document.body.innerText.slice(0, 600) : ''); };
  if (botCheck()) {
    await ec.waitFor(function () { return !botCheck(); }, 15000, 500);
    if (botCheck()) return ec.fail(location.host + ' is showing its bot check and wants a person to tick it. The TV never does that: Ethan can tick it from the phone page (the tab stays open ' + (A.keep || 5) + ' min), then it should stay clear for a while', { look: { url: location.href, title: document.title } });
    ec.log('the bot check cleared by itself');
  }

  // a page that's already been worked on this tab is settled: don't wait again
  if (window.__ecTab) await settle(800); else await settle(6000);
  window.__ecTab = true;

  // Ambrose admin (Blazor) often doesn't come up: spinner, the error bar, or a lost connection. Reload, 4 goes tops.
  if (site && site.name == 'ambroseadmin') {
    var stuck = function () {
      var er = document.getElementById('blazor-error-ui'), rc = document.getElementById('components-reconnect-modal');
      if (er && getComputedStyle(er).display != 'none') return 'Ambrose crashed';
      if (rc && getComputedStyle(rc).display != 'none') return 'Ambrose lost its connection';
      var mn = document.querySelector('main');
      if (!pwBox() && mn && !mn.querySelector('div.widget, table, form, input, h3, h4') && flat(mn.textContent).length < 60) return 'Ambrose never finished loading';
      return '';
    };
    var why = await ec.waitFor(function () { return !stuck() ? 'ok' : null; }, 12000, 400);
    if (!why) {
      S.reloads = (S.reloads || 0) + 1; ec.save();
      if (S.reloads > 4) return ec.fail(stuck() + ', and 4 reloads didn\'t fix it');
      ec.log(stuck() + ', reloading (' + S.reloads + ')');
      ec.leaving = true; location.reload(); return;
    }
    await ec.sleep(600); // a beat for Blazor to wire the page up
  }

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
  var WEB_BLOCK = /\b(submit|send|pay|payment|checkout|check out|place order|confirm order|complete purchase|purchase now|buy now|lodge|finali[sz]e|complete report|mark (as )?complete|delete|archive|reply|forward|unsubscribe)\b/i;
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
      frames: all('iframe').filter(vis).length || undefined,
      text: text.slice(0, chars), more: Math.max(0, text.length - chars), els: list.slice(0, Number(o.max) || 60), elsMore: Math.max(0, list.length - (Number(o.max) || 60)) };
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

  // ---- the actions. Each gives back what it did; the job adds a look at the page
  var ops = {};
  ops.look = async function () { return {}; };
  ops.press = async function (a) {
    var el = target(a), t0 = tapTarget(el);
    var said = flat(t0.innerText || t0.value || t0.getAttribute('aria-label') || t0.title);
    if (WEB_BLOCK.test(said)) throw new Error('not pressing "' + said.slice(0, 60) + '", send, submit, pay and the like are Ethan\'s to press');
    var before = location.href, beforeText = ec.text().length;
    await ec.press(t0, { script: !!a.script, shots: a.shots === true });
    if (a.wait) await ec.waitFor(function () { return ec.text().indexOf(a.wait) >= 0; }, 15000, 300);
    await settle();
    return { did: 'pressed ' + ec.label(t0), moved: location.href != before, changed: ec.text().length != beforeText };
  };
  ops.fill = async function (a) {
    var list = a.fields || [a], did = [];
    for (var i = 0; i < list.length; i++) {
      try { did.push((list[i].ref || list[i].label || list[i].sel) + ': ' + await setOne(list[i])); }
      catch (e) { did.push((list[i].ref || list[i].label || list[i].sel) + ': FAILED ' + e.message); if (!a.keepGoing) { var er = new Error(did.join('; ')); er.did = did; throw er; } }
    }
    await settle(4000);
    return { did: did.join('; ') };
  };
  ops.key = async function (a) {
    var k = String(a.key || 'Enter'), kel = a.ref != null || a.sel || a.label ? target(a) : document.activeElement;
    if (!kel || kel === document.body) throw new Error('nothing has focus, say which with ref');
    if (/^enter$/i.test(k) && kel.form && [].slice.call(kel.form.querySelectorAll('button,input[type=submit]')).some(function (b) { return WEB_BLOCK.test(flat(b.innerText || b.value)); }))
      throw new Error('not pressing Enter in a form that has a send/submit button, Ethan does those');
    kel.focus();
    var codes = { Enter: 13, Tab: 9, Escape: 27, ArrowDown: 40, ArrowUp: 38 };
    ['keydown', 'keypress', 'keyup'].forEach(function (t) { kel.dispatchEvent(new KeyboardEvent(t, { key: k, code: k, keyCode: codes[k] || 0, which: codes[k] || 0, bubbles: true })); });
    await settle(5000);
    return { did: 'key ' + k };
  };
  ops.scroll = async function (a) {
    if (a.ref != null || a.sel || a.text) target(a).scrollIntoView({ block: 'center' });
    else window.scrollBy(0, Number(a.y) || 600);
    await settle(2000);
    return { did: 'scrolled' };
  };
  ops.wait = async function (a) {
    var got = await ec.waitFor(function () { return ec.text().indexOf(a.text) >= 0; }, Math.min(Number(a.ms) || 15000, 60000), 300);
    return { did: got ? 'saw "' + a.text + '"' : 'never saw "' + a.text + '"', found: !!got };
  };
  ops.sleep = async function (a) { await ec.sleep(Math.min(Number(a.ms) || 1000, 20000)); return { did: 'waited' }; };
  ops.shot = async function (a) {
    if (a.ref != null || a.sel) target(a).scrollIntoView({ block: 'center' });
    await ec.shot(a.label || 'page');
    S.pics = (S.pics || 0) + 1;
    return { did: 'picture', pictures: S.pics };
  };

  // A single press that moved to a new page: the job runs again here, answer with the new page
  if (S.acted && what != 'web.steps') { await settle(); return answer({ moved: true, did: S.acted }); }

  if (what == 'web.open' || what == 'web.look') return answer(S.signedIn ? { signedIn: true } : {});

  if (what == 'web.text') {
    var root = A.sel ? ec.one(A.sel) : document.body, tx = flat(root.innerText), from = Number(A.from) || 0, n = Math.min(Number(A.chars) || 6000, 30000);
    return { ok: true, url: location.href.replace(/[?#].*$/, ''), from: from, text: tx.slice(from, from + n), more: Math.max(0, tx.length - from - n) };
  }

  // web.steps {steps: [{do: 'press', ref: 4}, {do: 'fill', fields: [...]}, {do: 'wait', text: 'Saved'}, ...]}
  // Several actions in one request (each step can be any of the actions above). A step that moves to another page
  // carries on from the next step there. Stops at the first one that fails, with a look at the page.
  if (what == 'web.steps') {
    var steps = A.steps || [], did = S.did || [];
    for (var i = S.si || 0; i < steps.length; i++) {
      var st = steps[i], op = String(st.do || '').replace(/^web\./, '');
      if (!ops[op]) return ec.fail('step ' + (i + 1) + ': can\'t do "' + st.do + '" in steps (look, press, fill, key, scroll, wait, sleep, shot)', { did: did });
      S.si = i + 1; S.did = did.concat([(i + 1) + ' ' + op + ' (moved page)']); ec.save();
      try { var r = await ops[op](st); did.push((i + 1) + ' ' + op + ': ' + (r.did || 'ok')); }
      catch (e) { did.push((i + 1) + ' ' + op + ': FAILED ' + e.message); var o = { did: did, look: look({ chars: 800, max: 40 }) }; if (S.pics) o.pictures = S.pics; return ec.fail('step ' + (i + 1) + ' failed: ' + e.message, o); }
      S.did = did; ec.save();
    }
    return answer({ did: did, pictures: S.pics || 0 });
  }

  if (what == 'web.ambrose') return ambrose();

  var one = { 'web.press': 'press', 'web.fill': 'fill', 'web.key': 'key', 'web.scroll': 'scroll', 'web.wait': 'wait', 'web.shot': 'shot' }[what];
  if (one) {
    if (one == 'press') { S.acted = 'press'; ec.save(); }
    try { var res = await ops[one](A); }
    catch (e) { return ec.fail(e.message, { did: e.did, look: look({ chars: 600, max: 40 }) }); }
    S.acted = null; ec.save();
    return answer(res);
  }

  // ---- web.ambrose {po}: from the Ambrose admin portal to that PO's report on Tradies Web (Ethan, 21/11).
  // PO list, search the PO, open it, Bookings, then the report link. Stops with a look when there's a choice to make.
  // Start it with args.url https://tradiesadmin.ambroseconstruct.com.au/PurchaseOrders
  async function ambrose() {
    var po = String(A.po || '').trim(), base = po.slice(0, 8);
    if (!/^\d{8}(-\d{2,3})?$/.test(po)) return ec.fail('po wants the Ambrose PO number, like 20332055 or 20332055-07');
    if (/tradies\.ambrosebuilding/.test(location.host)) return answer({ did: 'on Tradies Web for PO ' + po, steps: S.amb });
    if (!/tradiesadmin/.test(location.host)) return ec.fail('not on Ambrose admin (' + location.host + ')');
    S.amb = S.amb || [];
    var note = function (x) { S.amb.push(x); ec.log(x); ec.save(); };
    var rowText = function (r) { return flat(r.innerText); };
    if (/^\/PurchaseOrders\/?$/i.test(location.pathname)) {
      var search = await ec.waitFor(function () { return all('input[type=search]').filter(vis)[0]; }, 20000, 200);
      if (!search) return ec.fail('no PO search box on the PO list', { look: look({ chars: 600 }) });
      await ec.sleep(600);
      var showAll = all('div.widget').filter(function (e) { return /Show All PO/i.test(e.textContent); })[0];
      if (showAll) { var n0 = all('tbody tr').length; showAll.click(); await ec.waitFor(function () { return all('tbody tr').length != n0; }, 4000, 150); }
      ec.fill(search, base);
      search.dispatchEvent(new KeyboardEvent('keyup', { key: base.slice(-1), bubbles: true }));
      var rows = await ec.waitFor(function () { var r = all('tbody tr').filter(function (r) { return rowText(r).indexOf(base) == 0; }); return r.length ? r : null; }, 8000, 150) || [];
      var exact = rows.filter(function (r) { return po.length > 8 && rowText(r).indexOf(po) == 0; });
      var pick = exact.length == 1 ? exact[0] : rows.length == 1 ? rows[0] : null;
      if (!pick) return answer({ did: rows.length ? rows.length + ' POs start with ' + base + ', pick one (web.press its ref)' : 'PO ' + po + ' isn\'t on the PO list (try Invoices or Paid Invoices)', steps: S.amb });
      note('found PO ' + rowText(pick).slice(0, 80));
      var cell = pick.querySelector('.pointer-enabled') || pick.querySelector('td');
      await ec.press(cell, { expect: function () { return /^\/PurchaseOrders\/[a-f0-9]{20,}/i.test(location.pathname); }, what: 'the PO page to open', timeout: 20000, shots: false });
      await settle();
    }
    if (!/^\/PurchaseOrders\/[a-f0-9]{20,}/i.test(location.pathname)) return answer({ did: 'not on a PO page, here\'s where it is', steps: S.amb });
    note('on the PO page');
    var tab = ec.find({ text: 'bookings', exact: false, sel: 'a, button, [role=tab], li, .nav-link, div.tab, span' }).filter(function (e) { return flat(e.innerText).length < 30; });
    if (tab.length != 1) return answer({ did: 'found ' + tab.length + ' Bookings tabs on the PO page, have a look', steps: S.amb });
    await ec.press(tab[0], { shots: false });
    await settle();
    note('opened Bookings');
    var reps = ec.find({ text: 'report', exact: false }).filter(function (e) { return flat(e.innerText).length < 60 && !WEB_BLOCK.test(flat(e.innerText)); });
    if (reps.length != 1) return answer({ did: reps.length ? reps.length + ' report links under Bookings, pick one (web.press its ref)' : 'no report link under Bookings, have a look', steps: S.amb });
    note('pressing ' + flat(reps[0].innerText));
    S.acted = 'report'; ec.save();
    await ec.press(reps[0], { shots: false });
    await settle();
    // it goes on to Tradies Web (this runs again there), or opened in place
    if (/tradies\.ambrosebuilding/.test(location.host)) return answer({ did: 'on Tradies Web for PO ' + po, steps: S.amb });
    return answer({ did: 'pressed the report link but it didn\'t go to Tradies Web, have a look', steps: S.amb });
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
