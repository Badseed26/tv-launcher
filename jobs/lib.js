// Shared helpers for the TV's api jobs (21/11). The TV runs this, then jobs/<area>.js, on every page a job lands on.
// It's loaded fresh from GitHub on each request, so a fix here is live on the next one with no new build.
//
// Rules built in, whatever a job asks:
// - Presses go by element, never by screen spot: exactly one visible match, scrolled into view, checked that nothing is
//   sitting on top of it, a real finger tap in its middle, then a check that the page changed the way it should.
//   A picture before and after every press goes in the job's log.
// - Send, submit, pay, delete and the like are never pressed. Admin and settings pages are never opened.
// - Writes check ec.dry first. Everything worth knowing goes through ec.log so a failed job explains itself.
window.ec = (function () {
  var TOKEN = '__TOKEN__', JOB = __JOB__, api = window.ecApi;
  var ec = { job: JOB, args: JOB.args || {}, dry: !!JOB.dry, state: JOB.state || {}, n: JOB.n, finished: false };

  ec.log = function (s) { try { api.log(TOKEN, typeof s == 'string' ? s : JSON.stringify(s)); } catch (e) {} };
  ec.save = function () { try { api.save(TOKEN, JSON.stringify(ec.state)); } catch (e) {} };
  ec.done = function (o) {
    if (ec.finished) return; ec.finished = true;
    o = o || {}; if (o.ok === undefined && !o.err) o.ok = true;
    api.done(TOKEN, JSON.stringify(o));
  };
  ec.fail = function (msg, extra) {
    if (ec.finished) return; ec.finished = true;
    var o = { ok: false, err: String(msg) }; for (var k in (extra || {})) o[k] = extra[k];
    ec.log('FAIL ' + o.err); api.done(TOKEN, JSON.stringify(o));
  };
  // A file for the answer (an attachment, say): goes on the remote task as its own comment once the job's done
  ec.file = function (name, b64, type) { api.file(TOKEN, String(name), String(b64), String(type || '')); };
  // A supplier or Ambrose sign in page: the TV fills the login Ethan saved on the phone page (the job never sees it)
  ec.siteLogin = function (name) { try { return api.siteLogin(TOKEN, String(name)); } catch (e) { return 'this TV build can\'t sign in to sites yet'; } };
  // A file off AroFlo (photo or document path from aroflo.files), fetched by the app with the TV's AroFlo sign in.
  // Works on any site's page, so a report on Ambrose can take AroFlo's photos. Gives back a File.
  var got = {}, gotN = 0;
  window.__ecGot = function (id, b64, type, err) { var f = got[id]; if (f) { delete got[id]; f(b64, type, err); } };
  ec.grab = function (path, name) {
    return new Promise(function (res, rej) {
      var id = 'g' + (++gotN) + '_' + Date.now();
      var timer = setTimeout(function () { delete got[id]; rej(new Error('AroFlo file took over 90 s: ' + name)); }, 90000);
      got[id] = function (b64, type, err) {
        clearTimeout(timer);
        if (err || !b64) return rej(new Error('couldn\'t get ' + (name || 'the file') + ' off AroFlo: ' + (err || 'empty')));
        var bin = atob(b64), u = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
        res(new File([u], name || 'file', { type: type || 'application/octet-stream' }));
      };
      api.grab(TOKEN, id, String(path));
    });
  };
  // A photo shrunk to max px on its longest side (JPEG), for sending or for slow upload boxes
  ec.shrink = async function (file, max) {
    if (!/^image\//.test(file.type)) return file;
    var bm = await createImageBitmap(file), k = Math.min(1, max / Math.max(bm.width, bm.height));
    if (k === 1 && /jpe?g/.test(file.type)) return file;
    var c = document.createElement('canvas'); c.width = Math.round(bm.width * k); c.height = Math.round(bm.height * k);
    c.getContext('2d').drawImage(bm, 0, 0, c.width, c.height);
    var blob = await new Promise(function (r) { c.toBlob(r, 'image/jpeg', 0.88); });
    return new File([blob], String(file.name).replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg' });
  };
  ec.b64 = function (blob) { return new Promise(function (res, rej) { var r = new FileReader(); r.onload = function () { res(String(r.result).split(',')[1]); }; r.onerror = rej; r.readAsDataURL(blob); }); };
  ec.sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  ec.waitFor = async function (fn, ms, every) {
    var t0 = Date.now(); ms = ms || 10000;
    while (Date.now() - t0 < ms) { try { var v = fn(); if (v) return v; } catch (e) {} await ec.sleep(every || 250); }
    try { return fn(); } catch (e) { return null; }
  };
  ec.text = function (root) { return ((root || document).body ? (root || document).body.innerText : (root && root.innerText) || '').replace(/\s+/g, ' ').trim(); };
  ec.shot = async function (label) { try { api.shot(TOKEN, String(label || '')); } catch (e) {} await ec.sleep(450); };
  // Off to another page. The TV runs the job again there with ec.state as it was saved.
  ec.go = function (url) { ec.log('go ' + String(url).replace(/[?#].*$/, '')); ec.save(); ec.leaving = true; location.href = url; };
  ec.ready = async function (ms) {
    await ec.waitFor(function () { return document.readyState == 'complete' && document.body; }, ms || 15000);
  };

  var label = function (el) {
    if (!el) return 'nothing';
    var t = (el.innerText || el.value || el.title || el.getAttribute && el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
    return '<' + el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + '> "' + t.slice(0, 40) + '"';
  };
  ec.label = label;
  ec.visible = function (el) {
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect(), st = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && st.visibility != 'hidden' && st.display != 'none' && st.opacity != '0';
  };
  var textOf = function (el) { return (el.innerText || el.value || el.title || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().toLowerCase(); };
  // target: a CSS selector, or { text: 'Save Note', sel: 'button', exact: true, within: element }
  ec.find = function (target, root) {
    root = root || (target && target.within) || document;
    if (typeof target == 'string') return [].slice.call(root.querySelectorAll(target)).filter(ec.visible);
    var sel = target.sel || 'button, a, input[type=button], input[type=submit], [role=button], [onclick]';
    var want = String(target.text || '').replace(/\s+/g, ' ').trim().toLowerCase();
    var all = [].slice.call(root.querySelectorAll(sel)).filter(ec.visible).filter(function (el) {
      var t = textOf(el); return target.exact === false ? t.indexOf(want) >= 0 : t == want;
    });
    // a match sitting inside another match counts once (the outer one)
    return all.filter(function (el) { return !all.some(function (o) { return o !== el && o.contains(el); }); });
  };
  ec.one = function (target, root) {
    var m = ec.find(target, root);
    if (m.length != 1) throw new Error('wanted exactly 1 of ' + JSON.stringify(typeof target == 'string' ? target : { text: target.text, sel: target.sel }) + ', found ' + m.length + (m.length ? ': ' + m.slice(0, 4).map(label).join(', ') : ''));
    return m[0];
  };

  // Never pressed by a job, on any site (Ethan's rule: he presses those himself)
  ec.BLOCK = /^(send|send now|submit|submit job|pay|pay now|place order|confirm order|delete|delete note|remove|archive|unsubscribe|reply|reply all|forward)$/i;

  // The checked press. opt: { expect: fn that's true once it worked, what: 'the note editor to open', timeout,
  // script: true to use a script click instead of a finger tap, shots: false to skip the pictures, root }
  ec.press = async function (target, opt) {
    opt = opt || {};
    var el = target && target.nodeType ? target : ec.one(target, opt.root);
    var name = label(el);
    if (ec.BLOCK.test(textOf(el))) throw new Error('not pressing ' + name + ', send/submit/delete type buttons are off limits');
    el.scrollIntoView({ block: 'center', inline: 'center' });
    await ec.sleep(350);
    var r = el.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) throw new Error(name + ' is off the page after scrolling (' + Math.round(cx) + ',' + Math.round(cy) + ')');
    var hit = document.elementFromPoint(cx, cy);
    if (!opt.script && !opt.jq && !(hit === el || el.contains(hit) || (hit && hit.contains(el) && hit.tagName == 'LABEL')))
      throw new Error('something is sitting on top of ' + name + ': ' + label(hit));
    ec.log('press ' + name + ' at ' + Math.round(cx) + ',' + Math.round(cy) + (opt.jq ? ' (jQuery click)' : opt.script ? ' (script click)' : ''));
    if (opt.shots !== false) await ec.shot('before ' + name);
    // jq: AroFlo buttons wired through jQuery (Add Note (HTML) ignores plain clicks). script: a plain script click.
    if (opt.jq && window.jQuery) jQuery(el).trigger('click'); else if (opt.script) el.click(); else api.tap(TOKEN, cx, cy, innerWidth);
    if (opt.expect) {
      var ok = await ec.waitFor(opt.expect, opt.timeout || 12000);
      if (opt.shots !== false) await ec.shot('after ' + name);
      if (!ok) throw new Error('pressed ' + name + ' but ' + (opt.what || 'the page') + ' didn\'t happen');
      ec.log('ok: ' + (opt.what || 'pressed'));
    } else {
      await ec.sleep(600);
      if (opt.shots !== false) await ec.shot('after ' + name);
    }
    return el;
  };

  // Typing into a box the way a person would, so the page's own checks see it
  ec.fill = function (el, value) {
    el = el && el.nodeType ? el : ec.one(el);
    if (el.disabled || el.readOnly) throw new Error(label(el) + ' is locked');
    var proto = el.tagName == 'TEXTAREA' ? HTMLTextAreaElement.prototype : el.tagName == 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    var set = Object.getOwnPropertyDescriptor(proto, 'value').set;
    el.focus(); set.call(el, value);
    ['input', 'keyup', 'change'].forEach(function (t) { el.dispatchEvent(new Event(t, { bubbles: true })); });
    if (window.jQuery) try { jQuery(el).trigger('change'); } catch (e) {}
    el.blur();
    if (String(el.value) != String(value)) throw new Error(label(el) + ' didn\'t take the value');
    ec.log('fill ' + label(el) + ' = ' + String(value).slice(0, 60));
    return el;
  };

  // Another page's HTML as a document, without leaving this one (reads only)
  ec.doc = async function (url, opts) {
    var res = await fetch(url, opts || { credentials: 'include' });
    if (!res.ok) throw new Error(url.replace(/[?#].*$/, '') + ' said ' + res.status);
    return new DOMParser().parseFromString(await res.text(), 'text/html');
  };

  // Runs a job body. Anything it throws becomes a clean failure with the page and a picture, so nothing hangs.
  ec.run = async function (fn) {
    try {
      await ec.ready();
      if (/(siteadmin|site_admin|\/admin\/|\/administration|usermanagement)/i.test(location.href)) return ec.fail('landed on an admin page, stopped');
      var out = await fn();
      if (out !== undefined && !ec.finished && !ec.leaving) ec.done(out);
    } catch (e) {
      ec.log('ERROR ' + (e && e.stack || e));
      await ec.shot('error');
      // a script mistake (not a page problem) gets one more go with the last copy that worked
      var crash = e instanceof TypeError || e instanceof ReferenceError || e instanceof SyntaxError;
      ec.fail(String(e && e.message || e).slice(0, 300), { page: location.href.replace(/[?#].*$/, ''), title: document.title.slice(0, 80), crash: crash });
    }
  };
  return ec;
})();
