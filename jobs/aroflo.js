// AroFlo jobs for the TV's api (21/11). Runs on every AroFlo page a job lands on, after jobs/lib.js.
// Never opens admin or settings areas (lib and the TV both block them).
//
//   aroflo.check                          signed in? which page?
//   aroflo.search   {term, field}         task search. field: JobNo, CustOn (PO or claim), Location, Client, Desc
//   aroflo.task     {jn}                  a task's header, upcoming schedules and notes
//   aroflo.calendar {weeks, from}         bookings, one line a visit (from: YYYY-MM-DD, default today)
//   aroflo.run      {js, url}             runs a bit of code on an AroFlo page and hands back what it returns. For
//                                         looking at a page and fixing a job from anywhere (ec is there to use)
// Writes (every one has a dry run that does all the checks and saves nothing, and re-reads after saving):
//   aroflo.note.add      {jn, note}               Internal Only HTML note. note: one line, or lines with "## " headings
//                                                 and "- " dot points ("  - " one in). Skips it if it's already there.
//   aroflo.note.edit     {jn, match, note, author} rewrites the one note containing match (15+ chars), only if author
//                                                 wrote it. Saves with the notes area's Save Note, checks no stray note.
//   aroflo.siteinfo.add  {jn, line}               one line on top of the task description (open tasks only), checks
//                                                 the old text and every other header field are untouched.
//   aroflo.schedule.add  {jn, staff, date, start, end, note}   new booking (staff full name, date YYYY-MM-DD, times
//                                                 like 7:30am in 15 min steps). Stops if they're already on it that day.
//   aroflo.schedule.move {jn, staff, from, to, start, end, note} moves that one booking to another day.
//                                                 Never deletes a schedule. Send Event Messages is never ticked.
ec.run(async function () {
  var A = ec.args, S = ec.state, what = ec.job.do;
  var HOME = '/ims/Site/Home/index.cfm', CAL = '/ims/Site/Calendar/index.cfm?viewfullcalendar=1&tid=IMS.CAL';

  // ---- AroFlo's own pages first: signed out, or the User Session Limit page.
  // Ethan's PC and phone stay signed in when the TV takes the spot (21/11), so the TV just presses Continue.
  var t = ec.text();
  if (/User Session Limit/i.test(t)) {
    S.continues = (S.continues || 0) + 1; ec.save();
    if (S.continues > 3) return ec.fail('AroFlo kept showing the User Session Limit page after 3 Continues');
    ec.log('User Session Limit page, pressing Continue');
    await ec.press({ text: 'continue', exact: false }, { expect: function () { return !/User Session Limit/i.test(ec.text()); }, what: 'AroFlo to let the TV in', timeout: 20000 });
    return; // the page moves on and the job runs again there
  }
  if ([].slice.call(document.querySelectorAll('input[type=password]')).some(ec.visible) || /\/login/i.test(location.pathname)) {
    // signed out: the TV fills its saved login itself (never seen by this code), at most twice in 30 min
    S.logins = (S.logins || 0) + 1; ec.save();
    if (S.logins > 2) return ec.fail('AroFlo still wants a sign-in after 2 goes with the saved login');
    var r0 = window.ecApi.login('__TOKEN__');
    if (r0 != 'ok') return ec.fail('AroFlo is signed out on the TV and it can\'t sign in: ' + r0);
    ec.log('AroFlo sign-in page, the TV is entering the saved login');
    ec.leaving = true;
    await ec.sleep(25000); // the page moves on and the job runs again there
    if (!ec.finished) ec.fail('AroFlo didn\'t move on after the sign-in (still on ' + location.pathname + ')');
    return;
  }

  var search = async function (term, field) {
    var fd = new URLSearchParams({ taskString: term, SearchParam: field, searchtasks: 'Search', searchtasks_btn: 'Search' });
    var d = await ec.doc(HOME + '?tid=IMS.STK.CUR', { method: 'POST', body: fd, credentials: 'include' });
    return [].slice.call(d.querySelectorAll('tr')).filter(function (tr) { return tr.querySelectorAll('td').length >= 6; }).map(function (tr) {
      var c = [].slice.call(tr.querySelectorAll('td')).map(function (x) { return (x.textContent || '').replace(/\s+/g, ' ').trim(); }).filter(Boolean);
      var a = [].slice.call(tr.querySelectorAll('a')).find(function (a) { var h = a.getAttribute('href') || ''; return /workrequest/.test(h) && !/printview/.test(h); });
      return { ref: c[0], jn: c[1], req: c[2], task: c[4], status: c[c.length - 1], href: a ? a.getAttribute('href') : '' };
    });
  };
  var guessField = function (term) {
    if (/^\d{4,5}$/.test(term)) return 'JobNo';
    if (/^\d{6,}[\w-]*$/.test(term)) return 'CustOn';
    return 'Location';
  };
  var clean = function (s) {
    return String(s || '').replace(/\b(sticky_note_2|Send SMS|Create Task Schedule|Capture Signature|Save Task|View Schedule|Edit Note)\b/g, ' ')
      .replace(/View \d+ Previous Notes( \.\.\.)?/g, ' ').replace(/\s+Reply\s+/g, ': ').replace(/\s+/g, ' ').replace(/ :/g, ':').trim();
  };

  if (what == 'aroflo.check') {
    return { ok: true, signedIn: true, title: document.title.slice(0, 80), page: location.pathname };
  }

  if (what == 'aroflo.search') {
    if (!A.term) return ec.fail('search needs a term');
    var field = A.field || guessField(String(A.term));
    var res = await search(String(A.term), field);
    if (field == 'JobNo') res = res.filter(function (x) { return x.jn == String(A.term); });
    ec.log(field + ' "' + A.term + '": ' + res.length + ' found');
    return { ok: true, field: field, term: A.term, count: res.length, results: res.slice(0, 40).map(function (x) { return { jn: x.jn, ref: x.ref, task: x.task, status: x.status, req: x.req }; }) };
  }

  if (what == 'aroflo.task') {
    var jn = String(A.jn || '');
    if (!/^\d{4,5}$/.test(jn)) return ec.fail('task needs a jn (4 or 5 digits)');
    if (!new RegExp('\\bJN ' + jn + '\\b').test(document.title)) {
      if (S.opened) return ec.fail('opened JN ' + jn + ' but landed on "' + document.title.slice(0, 60) + '"');
      var r = (await search(jn, 'JobNo')).find(function (x) { return x.jn == jn; });
      if (!r || !r.href) return ec.fail('JN ' + jn + ' isn\'t in AroFlo');
      S.opened = true; S.status = r.status; S.task = r.task;
      return ec.go(r.href);
    }
    // notes load after the page does
    await ec.waitFor(function () { return ec.text().indexOf('Add Note (Text)') >= 0; }, 15000, 400);
    await ec.sleep(1200);
    var pt = ec.text(), a1 = pt.indexOf('Task Notes'), b1 = pt.indexOf('Add Note (Text)'), u = pt.indexOf('Upcoming Schedules');
    var notesTxt = a1 < 0 ? '' : clean(pt.slice(a1 + 10, b1 > a1 ? b1 : a1 + 4000));
    var notes = notesTxt ? notesTxt.split(/ (?=\d{1,2} [A-Z][a-z]+ 20\d\d \d{1,2}:\d\d[ap]m)/).map(function (s) { return s.trim(); }).filter(Boolean) : [];
    var sched = u < 0 ? '' : clean(pt.slice(u + 18, u + 400).replace(/\s+All Task Schedules[\s\S]*$/, ''));
    var head = clean(pt.slice(0, a1 > 0 ? Math.min(a1, 2500) : 2500));
    return { ok: true, jn: jn, title: document.title.replace(/ - Task - AroFlo.*$/, '').slice(0, 120), status: S.status || '', task: S.task || '',
      upcoming: sched, notes: notes.slice(-40), header: head };
  }

  if (what == 'aroflo.calendar') {
    if (location.pathname.indexOf('/Calendar/') < 0) {
      if (S.cal) return ec.fail('the calendar page didn\'t open');
      S.cal = true; return ec.go(CAL);
    }
    var ok = await ec.waitFor(function () { return window.jQuery && jQuery('.fc').length && jQuery('.fc').first().fullCalendar('clientEvents').length; }, 25000, 300);
    if (!ok) return ec.fail('the AroFlo calendar showed no bookings after 25 sec');
    var $c = jQuery('.fc').first(), fc = function () { return $c.fullCalendar('clientEvents'); }, idx = new Map();
    var grab = function () {
      fc().filter(function (e) { return e.type == 'task'; }).forEach(function (e) {
        var key = e.wrid + '|' + new Date(e.start).getTime();
        if (!idx.has(key)) idx.set(key, { wrid: e.wrid, staff: e.resourcename || '', start: new Date(e.start), end: e.end ? new Date(e.end) : null,
          h: (e.headers || []).map(function (h) { return h.headerText; }).filter(Boolean),
          t: (e.title || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 160) });
      });
    };
    var from = new Date(); from.setHours(0, 0, 0, 0);
    var m = /^(\d{4})-(\d\d)-(\d\d)$/.exec(A.from || '');
    if (m) from = new Date(+m[1], +m[2] - 1, +m[3]);
    var weeks = Math.min(10, Math.max(1, A.weeks || 2));
    for (var w = 0; w < weeks; w++) {
      $c.fullCalendar('gotoDate', new Date(from.getTime() + w * 7 * 864e5));
      var last = -1, same = 0;
      for (var n = 0; n < 20 && same < 3; n++) { await ec.sleep(400); var cnt = fc().length; if (n >= 2 && cnt == last) same++; else same = 0; last = cnt; }
      grab();
    }
    var pad = function (x) { return (x < 10 ? '0' : '') + x; };
    var iso = function (d) { return d ? d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) : ''; };
    var visits = Array.from(idx.values()).filter(function (v) { return v.start >= from; }).sort(function (a, b) { return a.start - b.start; }).map(function (v) {
      return { start: iso(v.start), end: iso(v.end), staff: v.staff, jn: v.h[0] || '', status: v.h[1] || '', sub: v.h[2] || '', note: v.h.slice(3).join(' / '), info: v.t, wrid: v.wrid };
    });
    return { ok: true, from: iso(from).slice(0, 10), weeks: weeks, count: visits.length, visits: visits };
  }

  // ---- documents and photos on a task (21/11). Same list AroFlo's Documents tab shows.
  // aroflo.files {jn}: every photo and document with who, when, caption and its n (and its path for web.upload)
  // aroflo.file {jn, n: [1,3] | name | all, max: 1600}: sends them to Claude as files on the remote (photos shrunk to
  // max px so they're quick to look at, documents as they are). 8 at a time tops.
  var taskCode = async function (jn) {
    var hit = (await search(String(jn), 'JobNo')).filter(function (x) { return x.jn == String(jn); })[0];
    var m = hit && /wrCoded=([^&]+)/i.exec(hit.href || '');
    if (!m) throw new Error('JN ' + jn + ' not found in AroFlo');
    return m[1];
  };
  var taskFiles = async function (code) {
    var q = 'searchdocumenttype=task&idcoded=' + code + '&customfield_id=0&columns=document,comment,uploadedby,imgdatetimetaken,datetimeinserted' +
      '&category_id=0&searchTaskBUs=childbus&_search=true&nd=' + Date.now() + '&rows=500&page=1&sidx=sortOrder&sord=asc';
    var r = await fetch('/ims/Site/Documents/AJAX_JSON_search_documents.cfm?' + q, { credentials: 'include' });
    var j; try { j = JSON.parse(await r.text()); } catch (e) { throw new Error('AroFlo didn\'t send the document list'); }
    var txt = function (x) { return String(x == null ? '' : x).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim(); };
    return (j.rows || []).map(function (x, i) {
      var name = txt(x.documentname);
      return { n: i + 1, name: name, photo: /\.(jpe?g|png|heic|heif|webp|gif)$/i.test(name), by: txt(x.uploadedby),
        when: txt(x.imgdatetimetaken) || txt(x.datetimeinserted), caption: txt(new DOMParser().parseFromString(String(x.comment || ''), 'text/html').body.textContent).slice(0, 200),
        path: String(x.timedlinkoriginal || '') };
    }).filter(function (x) { return x.path; });
  };
  if (what == 'aroflo.files') {
    if (!A.jn) return ec.fail('files needs jn');
    var fl = await taskFiles(await taskCode(A.jn));
    var docs = fl.filter(function (x) { return !x.photo; }), pics = fl.filter(function (x) { return x.photo; });
    return { ok: true, jn: A.jn, photos: pics.length, documents: docs.length,
      files: fl.map(function (x) { return x.n + ' ' + (x.photo ? 'photo' : 'doc') + ' ' + x.name + ' | ' + x.when + ' | ' + x.by + (x.caption ? ' | ' + x.caption : ''); }),
      paths: A.paths ? fl.map(function (x) { return x.path; }) : undefined };
  }
  if (what == 'aroflo.file') {
    if (!A.jn) return ec.fail('file needs jn');
    var fl2 = await taskFiles(await taskCode(A.jn)), want;
    if (A.all) want = fl2;
    else if (A.n) { var ns = [].concat(A.n).map(Number); want = fl2.filter(function (x) { return ns.indexOf(x.n) >= 0; }); }
    else if (A.name) want = fl2.filter(function (x) { return x.name.toLowerCase().indexOf(String(A.name).toLowerCase()) >= 0; });
    else return ec.fail('say which: n, name or all');
    if (!want.length) return ec.fail('nothing matched');
    var sent = [];
    for (var i = 0; i < want.length && sent.length < 8; i++) {
      var f = want[i];
      var rr = await fetch(f.path, { credentials: 'include' });
      if (!rr.ok) { ec.log('AroFlo said ' + rr.status + ' for ' + f.name); continue; }
      var blob = await rr.blob(), file = new File([blob], f.name, { type: blob.type });
      if (f.photo && A.max !== 0) file = await ec.shrink(file, Number(A.max) || 1600);
      ec.file(file.name, await ec.b64(file), file.type);
      sent.push(f.n + ' ' + f.name + ' (' + Math.round(file.size / 1024) + ' KB)');
    }
    return { ok: true, jn: A.jn, sent: sent, more: Math.max(0, want.length - sent.length) };
  }

  if (what == 'aroflo.run') {
    if (!A.js) return ec.fail('run needs js');
    ec.log('run: ' + String(A.js).slice(0, 400));
    var out;
    if (typeof window.__ecRun == 'function') out = await window.__ecRun(ec, A, S);
    else { var AsyncFn = Object.getPrototypeOf(async function () {}).constructor; out = await new AsyncFn('ec', 'A', 'S', A.js)(ec, A, S); }
    if (ec.finished || ec.leaving) return; // the code went to another page or answered itself
    return { ok: true, result: out === undefined ? null : out };
  }


  // ================================================================ writes (21/11)
  var notesPart = function () { var t = ec.text(), i = t.indexOf('Task Notes'), j = t.indexOf('Add Note (Text)'); return i < 0 ? '' : t.slice(i, j > i ? j : i + 400); };
  var flat = function (x) { return String(x || '').replace(/\s+/g, ' ').trim(); };
  var esc = function (x) { return String(x == null ? '' : x).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); };
  // "## " lines bold, "- " lines dot points ("  - " one in), anything else a plain line. Same as the filer's queue.
  var noteHtml = function (src) {
    var h = '', lvl = 0, close = function (to) { while (lvl > to) { h += '</li></ul>'; lvl--; } };
    String(src || '').replace(/\r/g, '').split('\n').forEach(function (raw) {
      if (!raw.trim()) return;
      var m = raw.match(/^(\s*)[-•]\s+(.*)$/);
      if (m) { var dep = m[1].length >= 2 ? 2 : 1; if (dep > lvl) { while (lvl < dep) { h += '<ul><li>'; lvl++; } h += esc(m[2].trim()); } else { close(dep); h += '</li><li>' + esc(m[2].trim()); } return; }
      close(0); var hd = raw.match(/^\s*##\s+(.*)$/); h += hd ? '<p><b>' + esc(hd[1].trim()) + '</b></p>' : '<p>' + esc(raw.trim()) + '</p>';
    });
    close(0); return h;
  };
  var notePlain = function (src) { return flat(String(src || '').replace(/\r/g, '').split('\n').map(function (l) { return l.replace(/^\s*(##|[-•])\s+/, '').trim(); }).filter(Boolean).join(' ')); };
  // On the task page for jn? If not, off there (true means wait for the next page).
  var toTask = async function (jn, openOnly) {
    if (!/^\d{4,5}$/.test(String(jn || ''))) { ec.fail('needs a jn (4 or 5 digits)'); return true; }
    if (new RegExp('\\bJN ' + jn + '\\b').test(document.title) && !/^Edit - /.test(document.title) && await ec.waitFor(function () { return document.getElementById('btnAddNoteHTML'); }, 10000)) return false;
    if (S.taskOpened) { ec.fail('opened JN ' + jn + ' but landed on "' + document.title.slice(0, 60) + '"'); return true; }
    var r = (await search(String(jn), 'JobNo')).find(function (x) { return x.jn == String(jn); });
    if (!r || !r.href) { ec.fail('JN ' + jn + ' isn\'t in AroFlo'); return true; }
    if (/Archived/i.test(r.status)) { ec.fail('JN ' + jn + ' is archived, AroFlo won\'t take changes on it'); return true; }
    if (openOnly && /Completed|Archived|Cancel|^Quote/i.test(r.status)) { ec.fail('JN ' + jn + ' is ' + r.status + ', this only goes on open tasks'); return true; }
    S.taskOpened = true; S.status = r.status; S.href = r.href;
    var m = r.href.match(/wrCoded=([^&]+)/i); if (m) S.wrcoded = m[1];
    ec.go(r.href); return true;
  };
  var waitNotes = async function () { await ec.waitFor(function () { return ec.text().indexOf('Add Note (Text)') >= 0; }, 15000, 400); await ec.sleep(1000); };

  if (what == 'aroflo.note.add') {
    if (!A.note && !A.text) return ec.fail('note.add needs a note');
    var src = A.note || A.text, multi = /\n/.test(src);
    var html = multi ? noteHtml(src) : '<p>' + esc(src) + '</p>', plain = multi ? notePlain(src) : flat(src);
    if (await toTask(A.jn)) return;
    await waitNotes();
    if (notesPart().indexOf(plain.slice(0, 80)) >= 0 && notesPart().indexOf(plain.slice(-60)) >= 0) return { ok: true, jn: A.jn, already: true };
    var b = document.getElementById('btnAddNoteHTML'); if (!b) return ec.fail('no Add Note (HTML) button on JN ' + A.jn);
    await ec.press(b, { jq: true, expect: function () { return window.CKEDITOR && CKEDITOR.instances.thisnote && CKEDITOR.instances.thisnote.status == 'ready'; }, what: 'the note editor to open' });
    var ed = CKEDITOR.instances.thisnote; ed.setData(html); await ec.sleep(500);
    var f = document.querySelector('select[name=NoteFilter]'), st = document.querySelector('input[name=NoteSticky]'), btn = document.getElementById('btnAddNote');
    var filt = f ? f.options[f.selectedIndex].text : '?';
    if (filt != 'Internal Only' || (st && st.checked) || !btn) { ed.setData(''); return ec.fail('note settings not right (filter ' + filt + ', sticky ' + !!(st && st.checked) + '), nothing saved'); }
    if (ec.dry) { var filled = flat(ed.getData()).length > 0; ed.setData(''); return { ok: true, dry: true, jn: A.jn, checks: 'editor opened, filled ' + filled + ', Internal Only, not sticky, Save Note found, nothing saved' }; }
    await ec.press(btn, { script: true, expect: function () { return notesPart().indexOf(plain.slice(0, 80)) >= 0; }, what: 'the note to show on the task', timeout: 15000 });
    return { ok: true, jn: A.jn, saved: true };
  }

  if (what == 'aroflo.note.edit') {
    var want = flat(A.match).toLowerCase();
    if (want.length < 15) return ec.fail('note.edit needs a match of 15 or more characters');
    if (!A.note) return ec.fail('note.edit needs the new note');
    if (!A.author) return ec.fail('note.edit needs author (whose note it has to be)');
    var src2 = A.note, multi2 = /\n/.test(src2), html2 = multi2 ? noteHtml(src2) : '<p>' + esc(src2) + '</p>', plain2 = multi2 ? notePlain(src2) : flat(src2);
    var linkSel = 'a.editNote, .editNote a, a[class*="editNote"]';
    if (S.fired) { // back after the save reloaded the page
      await waitNotes();
      if (notesPart().indexOf(plain2) < 0) return ec.fail('pressed Save Note but the new text didn\'t show, check the note by hand');
      var n2 = document.querySelectorAll(linkSel).length;
      if (S.count && n2 != S.count) return ec.fail('edit went in but the number of notes went from ' + S.count + ' to ' + n2 + ', check for a stray note');
      return { ok: true, jn: A.jn, edited: true };
    }
    if (await toTask(A.jn)) return;
    await waitNotes();
    if (notesPart().indexOf(plain2) >= 0) return { ok: true, jn: A.jn, already: true };
    var links = [].slice.call(document.querySelectorAll(linkSel)).filter(function (x, i, all) { return all.indexOf(x) == i; });
    if (!links.length) return ec.fail('no edit links on the notes, nothing changed');
    var blockOf = function (a) { var bb = a, k = 0; while (k++ < 8 && bb.parentElement && bb.parentElement != document.body && bb.parentElement.querySelectorAll(linkSel).length <= 1) bb = bb.parentElement; return bb; };
    var hits = links.map(function (a) { return { a: a, b: blockOf(a) }; }).filter(function (x) { return flat(x.b.innerText).toLowerCase().indexOf(want) >= 0; });
    if (hits.length != 1) return ec.fail((hits.length ? hits.length + ' notes' : 'no note') + ' matched "' + A.match.slice(0, 40) + '", nothing changed');
    var a = hits[0].a, blk = hits[0].b;
    if (blk.innerText.indexOf(A.author) < 0) return ec.fail('that note isn\'t ' + A.author + '\'s (or the page doesn\'t show who wrote it), nothing changed');
    var before = Object.keys(window.CKEDITOR ? CKEDITOR.instances : {});
    await ec.press(a, { jq: true, expect: function () { return Object.keys(CKEDITOR.instances).some(function (k) { return /^myeditorNotes/.test(k) && CKEDITOR.instances[k].status == 'ready'; }); }, what: 'the note\'s editor to open' });
    var eds = Object.keys(CKEDITOR.instances).filter(function (k) { return /^myeditorNotes/.test(k) && CKEDITOR.instances[k].status == 'ready'; }).map(function (k) { return CKEDITOR.instances[k]; });
    var inB = eds.filter(function (e) { return e.element && blk.contains(e.element.$); }), ed2 = inB.length == 1 ? inB[0] : eds.length == 1 ? eds[0] : null;
    if (!ed2) return ec.fail('the note\'s editor didn\'t open (or more than one did), nothing changed');
    var old = ed2.getData();
    var unhtml = function (h) { var d = document.createElement('div'); d.innerHTML = String(h).replace(/<\/(p|li|div)>/g, ' </$1>'); return flat(d.textContent); };
    if (unhtml(old).toLowerCase().indexOf(want) < 0) { ed2.setData(old); ed2.resetDirty(); return ec.fail('the editor that opened isn\'t that note, nothing changed'); }
    var tn = CKEDITOR.instances.thisnote;
    if (tn && flat(tn.getData())) { ed2.setData(old); ed2.resetDirty(); return ec.fail('the add note box has text in it, not saving over it'); }
    var save = document.getElementById('btnAddNote');
    if (!save) { ed2.setData(old); ed2.resetDirty(); return ec.fail('no Save Note button, nothing changed'); }
    ed2.setData(html2); await ec.sleep(600);
    var took = unhtml(ed2.getData()).indexOf(plain2.slice(0, 60)) >= 0;
    if (ec.dry) { ed2.setData(old); ed2.resetDirty(); return { ok: true, dry: true, jn: A.jn, checks: 'found the one note, author ok, editor ' + ed2.name + ' opened, new text ' + (took ? 'went in' : 'did NOT go in') + ', Save Note found, old text put back, nothing saved' }; }
    if (!took) { ed2.setData(old); ed2.resetDirty(); return ec.fail('the new text didn\'t take in the editor, nothing changed'); }
    S.fired = true; S.count = links.length; ec.save();
    ec.log('saving the edit with Save Note'); await ec.shot('before save');
    save.click();
    var ok2 = await ec.waitFor(function () { return notesPart().indexOf(plain2) >= 0; }, 15000, 400);
    if (!ok2) { await ec.sleep(5000); if (ec.leaving) return; return ec.fail('pressed Save Note but the new text didn\'t show, check the note by hand'); }
    if (document.querySelectorAll(linkSel).length != S.count) return ec.fail('edit went in but the number of notes changed, check for a stray note');
    return { ok: true, jn: A.jn, edited: true };
  }

  if (what == 'aroflo.siteinfo.add') {
    var line = flat(A.line);
    if (!line) return ec.fail('siteinfo.add needs a line');
    var plainH = function (x) { return flat(String(x).replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&#39;/g, "'").replace(/&quot;/g, '"')); };
    var hdrFields = function () { var f = document.forms.editwr; return f ? [].slice.call(f.elements).filter(function (e) { return e.name && !/^(hidden|button|submit)$/.test(e.type) && !/^(description|thisnote)$/.test(e.name); }).map(function (e) { return e.name + '=' + (/radio|checkbox/.test(e.type) ? e.checked : e.value); }).join('|') : ''; };
    if (/^Edit - JN/.test(document.title)) await ec.waitFor(function () { return window.CKEDITOR && CKEDITOR.instances.description; }, 10000);
    var onHeader = /^Edit - JN/.test(document.title) && window.CKEDITOR && CKEDITOR.instances.description && document.forms.editwr;
    if (S.step == 'saved') { // back on a page after Save: open the header again to check it
      if (!onHeader) { if (S.rechecked) return ec.fail('couldn\'t get back to the header page to check the save'); S.rechecked = true; return ec.go(S.headerHref); }
      await ec.waitFor(function () { return CKEDITOR.instances.description.status == 'ready'; }, 10000);
      var d2 = plainH(CKEDITOR.instances.description.getData());
      var res2 = { lineOnTop: d2.indexOf(plainH(line)) == 0, oldKept: d2.slice(-Math.min(200, S.old.length)) == S.old.slice(-Math.min(200, S.old.length)), otherFieldsSame: hdrFields() == S.f0 };
      if (!res2.lineOnTop || !res2.oldKept || !res2.otherFieldsSame) return ec.fail('saved, but the check after isn\'t right: ' + JSON.stringify(res2) + '. Check JN ' + A.jn + ' by hand', res2);
      return { ok: true, jn: A.jn, saved: true, checks: res2 };
    }
    if (!onHeader) {
      if (S.step == 'header') return ec.fail('the Edit Task Header page didn\'t open');
      if (await toTask(A.jn, true)) return;
      var hl = [].slice.call(document.querySelectorAll('a')).find(function (e) { return /^\s*Edit Task Header\s*$/i.test(e.textContent || ''); });
      if (!hl) return ec.fail('no Edit Task Header link on JN ' + A.jn);
      S.step = 'header'; S.headerHref = hl.getAttribute('href'); return ec.go(S.headerHref);
    }
    var ed3 = CKEDITOR.instances.description, upd = document.getElementById('updateheader__btn');
    await ec.waitFor(function () { return ed3.status == 'ready'; }, 10000);
    if (!upd) return ec.fail('no Save on the header page');
    var old3 = ed3.getData();
    if (plainH(old3).toLowerCase().indexOf(plainH(line).slice(0, 40).toLowerCase()) >= 0) return { ok: true, jn: A.jn, already: true };
    var f0 = hdrFields(), lineHtml = '<p>' + esc(line) + '</p>';
    ed3.setData(lineHtml + old3); await ec.sleep(1200);
    var now3 = plainH(ed3.getData()), top3 = now3.indexOf(plainH(line)) == 0, kept3 = now3.slice(-Math.min(200, plainH(old3).length)) == plainH(old3).slice(-Math.min(200, plainH(old3).length)), same3 = hdrFields() == f0;
    if (!top3 || !kept3 || !same3) { ed3.setData(old3); ed3.resetDirty(); return ec.fail('checks failed, nothing saved: line on top ' + top3 + ', old text kept ' + kept3 + ', other fields unchanged ' + same3); }
    if (ec.dry) { ed3.setData(old3); await ec.sleep(800); ed3.resetDirty(); return { ok: true, dry: true, jn: A.jn, checks: 'line went on top, old text kept, other fields unchanged, put back, nothing saved' }; }
    S.step = 'saved'; S.f0 = f0; S.old = plainH(old3); ec.save();
    ed3.updateElement();
    ec.log('saving the header'); await ec.shot('before header save');
    ec.leaving = true; jQuery(upd).trigger('click');
    await ec.sleep(20000);
    if (!ec.finished) return ec.fail('pressed Save on the header but the page didn\'t move on');
    return;
  }

  // ---- calendar bookings (same steps as the house rules' _schedule and _move, which were proven in Chrome)
  var schedEvents = function (jn, staff, day) { return jQuery('.fc').first().fullCalendar('clientEvents').filter(function (e) { return e.type == 'task' && (e.headers || []).map(function (h) { return h.headerText; }).filter(Boolean)[0] == String(jn) && (e.resourcename || '') == staff && new Date(e.start).toDateString() == day.toDateString(); }); };
  var tfmt = function (t) { var h = t.getHours(), m = t.getMinutes(); return (h % 12 || 12) + ':' + (m < 10 ? '0' : '') + m + (h < 12 ? 'am' : 'pm'); };
  var ymdDate = function (x) { var m = /^(\d{4})-(\d\d)-(\d\d)$/.exec(x || ''); return m ? new Date(+m[1], +m[2] - 1, +m[3]) : null; };
  var setv = function (el, v) { el.focus(); el.value = v; ['input', 'keyup', 'change'].forEach(function (t) { el.dispatchEvent(new Event(t, { bubbles: true })); }); jQuery(el).trigger('change'); el.blur(); };

  if (what == 'aroflo.schedule.add') {
    var day = ymdDate(A.date);
    if (!day || !A.staff || !A.jn) return ec.fail('schedule.add needs jn, staff (full name) and date (YYYY-MM-DD)');
    if (!/newtasksched=1/.test(location.search)) {
      if (S.sched) return ec.fail('the new schedule window didn\'t open');
      var r5 = (await search(String(A.jn), 'JobNo')).find(function (x) { return x.jn == String(A.jn); });
      if (!r5 || !r5.href) return ec.fail('JN ' + A.jn + ' isn\'t in AroFlo');
      if (/Completed|Archived|Cancel|Quote/i.test(r5.status)) return ec.fail('JN ' + A.jn + ' is ' + r5.status + ', not booking it');
      var mm = r5.href.match(/wrCoded=([^&]+)/i); if (!mm) return ec.fail('no task code for JN ' + A.jn);
      S.sched = true;
      return ec.go('/ims/Site/Calendar/index.cfm?viewfullcalendar=1&tid=IMS.CAL&wrcoded=' + mm[1] + '&initdate=' + day.getDate() + '/' + (day.getMonth() + 1) + '/' + day.getFullYear() + '&newtasksched=1');
    }
    if (S.fired) return ec.fail('the page reloaded after Done, check the booking by hand');
    var dm = day.getDate() + '/' + (day.getMonth() + 1), dlg = null;
    await ec.waitFor(function () { dlg = [].slice.call(document.querySelectorAll('.ui-dialog')).find(function (x) { return x.offsetParent && /Task Schedules/.test(x.innerText); }); return dlg; }, 15000);
    if (!dlg) return ec.fail('schedule window didn\'t open');
    if (!new RegExp('Ref / JN: [^:]*?/ ' + A.jn + ' ').test(dlg.innerText.replace(/\s+/g, ' '))) return ec.fail('the window isn\'t for JN ' + A.jn);
    var res = function (g) { return +((g.innerText.match(/Resources:\s*(\d+)/) || [0, -1])[1]); };
    var gs = [].slice.call(dlg.querySelectorAll('.imsMultiSchedule__schedule')).filter(function (x) { return x.offsetParent; });
    var empty = gs.filter(function (g) { return res(g) == 0 && !g.querySelector('input.schedStartTime'); });
    if (empty.length != 1) return ec.fail('expected one new empty schedule group, found ' + empty.length);
    var others = gs.filter(function (x) { return x !== empty[0]; });
    var snap = function () { return others.map(function (x) { return [].slice.call(x.querySelectorAll('input')).map(function (i) { return i.type == 'checkbox' ? i.checked : i.value; }).join('|') + x.innerText.replace(/\s+/g, ' '); }).join('#'); }, snap0 = snap();
    var g = empty[0], dates = function () { return ((g.innerText.match(/Dates:\s*([^|\n]*)/) || [0, ''])[1]).trim(); };
    if (dates() != dm) return ec.fail('window date is ' + dates() + ', wanted ' + dm);
    var cbx = [].slice.call(g.querySelectorAll('input[id^=cbxEvent],input[id^=cbxHidden]'));
    if (cbx.some(function (c) { return c.checked; })) return ec.fail('Send Event Messages or Hidden is ticked, stopped');
    await ec.waitFor(function () { return jQuery('.fc').first().fullCalendar('clientEvents').some(function (e) { return new Date(e.start).toDateString() == day.toDateString(); }); }, 6000);
    var before5 = schedEvents(A.jn, A.staff, day).length;
    if (before5) return ec.fail(A.staff + ' is already on JN ' + A.jn + ' on ' + dm);
    g.querySelector('button[title="Add resources to schedule"]').click(); await ec.sleep(400);
    var us = g.querySelector('span[title="Users"]'); if (!us) return ec.fail('no Users option in the window');
    us.click();
    var parts = String(A.staff).split(' '), fn = parts[0], ln = parts.slice(1).join(' '), ud = null, row = null;
    await ec.waitFor(function () { ud = [].slice.call(document.querySelectorAll('.ui-dialog')).find(function (x) { return x.offsetParent && /Search Users/.test(x.innerText); }); row = ud && [].slice.call(ud.querySelectorAll('tr')).find(function (t) { var c = [].slice.call(t.querySelectorAll('td')).map(function (x) { return x.innerText.trim(); }); return c.indexOf(fn) >= 0 && c.indexOf(ln) >= 0; }); return row; }, 9000);
    var closeUd = function () { var c = ud && [].slice.call(ud.querySelectorAll('a,button,span')).find(function (e) { return /^\s*close\s*$/i.test(e.innerText || '') || /titlebar-close/.test(e.className); }); if (c) c.click(); };
    if (!row) { closeUd(); return ec.fail(A.staff + ' isn\'t in the user list, nothing saved'); }
    jQuery(row.closest('table')).jqGrid('setSelection', row.id, true);
    await ec.waitFor(function () { return res(g) == 1 && g.querySelectorAll('input.schedStartTime').length == 1; }, 4000);
    closeUd(); await ec.sleep(400);
    if (res(g) != 1 || g.innerText.indexOf(A.staff) < 0) return ec.fail(A.staff + ' didn\'t go on the booking, nothing saved');
    var tr = g.querySelector('input.schedStartTime').closest('tr'), Sx = tr.querySelector('input.schedStartTime'), Ex = tr.querySelector('input.schedEndTime'), Nx = tr.querySelector('input.schedNote');
    if (A.start) { setv(Sx, A.start); await ec.sleep(300); } if (A.end) { setv(Ex, A.end); await ec.sleep(300); } if (A.note) { setv(Nx, A.note); await ec.sleep(200); }
    document.querySelectorAll('.ui-timepicker-wrapper').forEach(function (x) { x.style.display = 'none'; });
    var got = { staff: A.staff, date: dates(), start: Sx.value, end: Ex.value, note: Nx.value, othersSame: snap() == snap0 };
    var good = got.date == dm && (!A.start || Sx.value == A.start) && (!A.end || Ex.value == A.end) && Nx.value == (A.note || '') && res(g) == 1 && !cbx.some(function (c) { return c.checked; }) && got.othersSame;
    await ec.shot('booking filled');
    if (!good) return ec.fail('checks failed, nothing saved: ' + JSON.stringify(got));
    if (ec.dry) return { ok: true, dry: true, jn: A.jn, filled: got, checks: 'window right, staff on, times and note in, ticks off, other groups untouched, nothing saved' };
    var done5 = [].slice.call(dlg.querySelectorAll('button,a')).find(function (b) { return /^\s*(save\s*)?Done\s*$/i.test(b.innerText || ''); });
    if (!done5) return ec.fail('no Done button, nothing saved');
    S.fired = true; ec.save();
    done5.click();
    await ec.waitFor(function () { return !dlg.offsetParent; }, 8000);
    var $c5 = jQuery('.fc').first(); $c5.fullCalendar('gotoDate', day); $c5.fullCalendar('refetchEvents'); await ec.sleep(3500);
    var hit5 = schedEvents(A.jn, A.staff, day), last5 = hit5[hit5.length - 1];
    var chk = { count: hit5.length, start: last5 ? tfmt(new Date(last5.start)) : '', end: last5 && last5.end ? tfmt(new Date(last5.end)) : '', header: last5 ? (last5.headers || []).map(function (x) { return x.headerText; }).filter(Boolean).join(' / ').slice(0, 80) : '' };
    await ec.shot('after booking');
    var ok5 = hit5.length == before5 + 1 && (!A.start || chk.start == A.start) && (!A.end || chk.end == A.end) && (!A.note || chk.header.indexOf(A.note) >= 0);
    if (!ok5) return ec.fail('Done pressed but the calendar check isn\'t right: ' + JSON.stringify(chk) + '. Check it by hand');
    return { ok: true, jn: A.jn, booked: chk };
  }

  if (what == 'aroflo.schedule.move') {
    var from = ymdDate(A.from), to = ymdDate(A.to);
    if (!from || !to || !A.staff || !A.jn) return ec.fail('schedule.move needs jn, staff, from and to (YYYY-MM-DD)');
    if (location.pathname.indexOf('/Calendar/') < 0) { if (S.cal) return ec.fail('the calendar didn\'t open'); S.cal = true; return ec.go(CAL); }
    if (S.fired) return ec.fail('the page reloaded after Done, check the booking by hand');
    var $c6 = jQuery('#calBox'); if (!$c6.length || !window.fullcalEventClick) return ec.fail('not the calendar page');
    $c6.fullCalendar('gotoDate', new Date(from.getFullYear(), from.getMonth(), from.getDate(), 8)); $c6.fullCalendar('refetchEvents'); await ec.sleep(3500);
    var hit6 = schedEvents(A.jn, A.staff, from);
    if (hit6.length != 1) return ec.fail('found ' + hit6.length + ' bookings for ' + A.staff + ' on JN ' + A.jn + ' on ' + from.getDate() + '/' + (from.getMonth() + 1));
    var e6 = hit6[0]; if (e6.TotalNumInGroup > 1) return ec.fail('that booking is in a group of ' + e6.TotalNumInGroup + ', not moving it');
    var before6 = { start: tfmt(new Date(e6.start)), end: e6.end ? tfmt(new Date(e6.end)) : '', h: (e6.headers || []).map(function (x) { return x.headerText; }).filter(Boolean).join(' / ').slice(0, 80) };
    fullcalEventClick(e6);
    var dlg6 = null; await ec.waitFor(function () { dlg6 = [].slice.call(document.querySelectorAll('.ui-dialog')).find(function (x) { return x.offsetParent && /Task Schedules/.test(x.innerText); }); return dlg6; }, 10000);
    if (!dlg6) return ec.fail('schedule window didn\'t open');
    await ec.sleep(800);
    var close6 = function () { var c = dlg6.querySelector('.ui-dialog-titlebar-close'); if (c) c.click(); };
    if (!new RegExp('Ref / JN: [^:]*?/ ' + A.jn + ' ').test(dlg6.innerText.replace(/\s+/g, ' '))) { close6(); return ec.fail('window isn\'t for JN ' + A.jn); }
    var dates6 = function (x) { return ((x.innerText.match(/Dates:\s*([^|\n]*)/) || [0, ''])[1]).trim(); };
    var gs6 = [].slice.call(dlg6.querySelectorAll('.imsMultiSchedule__schedule')).filter(function (x) { return x.offsetParent; });
    var mine = gs6.filter(function (x) { return dates6(x) == from.getDate() + '/' + (from.getMonth() + 1) && x.innerText.indexOf(A.staff) >= 0; });
    if (mine.length != 1) { close6(); return ec.fail('expected one group for ' + A.staff + ' on that day, found ' + mine.length); }
    var g6 = mine[0], oth6 = gs6.filter(function (x) { return x !== g6; });
    var snap6 = function () { return oth6.map(function (x) { return [].slice.call(x.querySelectorAll('input')).map(function (i) { return i.type == 'checkbox' ? i.checked : i.value; }).join('|') + dates6(x); }).join('#'); }, snap60 = snap6();
    var pick = g6.querySelector('[title="Show/Hide Calendar"]'); if (!pick) { close6(); return ec.fail('no date picker'); }
    if (!g6.querySelector('table.ui-datepicker-calendar')) pick.click(); await ec.sleep(800);
    var MN = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
    var want6 = MN[to.getMonth()] + ' ' + to.getFullYear(), mIdx = function (x) { var p = x.split(' '); return +p[1] * 12 + MN.indexOf(p[0]); };
    var titles = function () { return [].slice.call(g6.querySelectorAll('.ui-datepicker-title')).map(function (x) { return x.textContent.replace(/\s+/g, ' ').trim(); }); };
    for (var i6 = 0; i6 < 14 && titles().indexOf(want6) < 0; i6++) { var t6 = titles(); if (!t6.length) break; var nav = g6.querySelector(mIdx(want6) > mIdx(t6[0]) ? '.ui-datepicker-next' : '.ui-datepicker-prev'); if (!nav) break; jQuery(nav).trigger('click'); await ec.sleep(400); }
    var k6 = titles().indexOf(want6); if (k6 < 0) { close6(); return ec.fail('the date picker wouldn\'t show ' + want6); }
    var cell = [].slice.call(g6.querySelectorAll('table.ui-datepicker-calendar')[k6].querySelectorAll('td')).find(function (x) { return x.textContent.trim() == String(to.getDate()) && x.querySelector('.dpday'); });
    if (!cell) { close6(); return ec.fail('no date cell for ' + to.getDate() + '/' + (to.getMonth() + 1)); }
    // the date cells only take a real tap (jQuery UI selectable)
    await ec.press(cell, { expect: function () { return dates6(g6) == to.getDate() + '/' + (to.getMonth() + 1); }, what: 'Dates to show ' + to.getDate() + '/' + (to.getMonth() + 1) + ' only' });
    var rows6 = g6.querySelectorAll('input.schedStartTime');
    if (g6.innerText.indexOf(A.staff) < 0 || rows6.length != 1) { close6(); return ec.fail('the group doesn\'t have just ' + A.staff + ' on it, nothing saved'); }
    if ([].slice.call(g6.querySelectorAll('input[type=checkbox]')).some(function (c) { return c.checked; })) { close6(); return ec.fail('a tick box is ticked (Hidden, Send Event Messages or Single), nothing saved'); }
    var tr6 = rows6[0].closest('tr'), S6 = tr6.querySelector('input.schedStartTime'), E6 = tr6.querySelector('input.schedEndTime'), N6 = tr6.querySelector('input.schedNote');
    if (A.start) { setv(S6, A.start); await ec.sleep(300); } if (A.end) { setv(E6, A.end); await ec.sleep(300); } if (A.note != null) { setv(N6, A.note); await ec.sleep(200); }
    document.querySelectorAll('.ui-timepicker-wrapper').forEach(function (x) { x.style.display = 'none'; });
    var got6 = { date: dates6(g6), start: S6.value, end: E6.value, note: N6.value, othersSame: snap6() == snap60, before: before6 };
    await ec.shot('move filled');
    var good6 = (!A.start || S6.value == A.start) && (!A.end || E6.value == A.end) && (A.note == null || N6.value == A.note) && got6.othersSame;
    if (!good6) { close6(); return ec.fail('checks failed, nothing saved (window closed): ' + JSON.stringify(got6)); }
    if (ec.dry) { close6(); return { ok: true, dry: true, jn: A.jn, filled: got6, checks: 'found the one booking, date picked, times and note in, ticks off, other groups untouched, window closed, nothing saved' }; }
    var done6 = [].slice.call(dlg6.querySelectorAll('button,a')).find(function (b) { return /^\s*(save\s*)?Done\s*$/i.test(b.innerText || ''); });
    if (!done6) { close6(); return ec.fail('no Done button, nothing saved'); }
    S.fired = true; ec.save();
    done6.click();
    await ec.waitFor(function () { return !dlg6.offsetParent; }, 8000);
    $c6.fullCalendar('gotoDate', from); $c6.fullCalendar('refetchEvents'); await ec.sleep(3500);
    var left6 = schedEvents(A.jn, A.staff, from).length;
    $c6.fullCalendar('gotoDate', to); $c6.fullCalendar('refetchEvents'); await ec.sleep(3500);
    var hitTo = schedEvents(A.jn, A.staff, to), eT = hitTo[0];
    var chk6 = { onNewDay: hitTo.length, leftOnOldDay: left6, start: eT ? tfmt(new Date(eT.start)) : '', end: eT && eT.end ? tfmt(new Date(eT.end)) : '' };
    await ec.shot('after move');
    var ok6 = hitTo.length == 1 && (from.toDateString() == to.toDateString() || left6 == 0) && (!A.start || chk6.start == A.start) && (!A.end || chk6.end == A.end);
    if (!ok6) return ec.fail('Done pressed but the calendar check isn\'t right: ' + JSON.stringify(chk6) + '. Check it by hand');
    return { ok: true, jn: A.jn, moved: chk6 };
  }

  return ec.fail('unknown aroflo job ' + what + ' (check, search, task, calendar, run, note.add, note.edit, siteinfo.add, schedule.add, schedule.move)');
});
