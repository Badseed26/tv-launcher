// AroFlo jobs for the TV's api (21/11). Runs on every AroFlo page a job lands on, after jobs/lib.js.
// Never opens admin or settings areas (lib and the TV both block them).
//
//   aroflo.check                          signed in? which page?
//   aroflo.search   {term, field}         task search. field: JobNo, CustOn (PO or claim), Location, Client, Desc
//   aroflo.task     {jn}                  a task's header, upcoming schedules and notes
//   aroflo.calendar {weeks, from}         bookings, one line a visit (from: YYYY-MM-DD, default today)
//   aroflo.run      {js, url}             runs a bit of code on an AroFlo page and hands back what it returns. For
//                                         looking at a page and fixing a job from anywhere (ec is there to use)
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
  if (document.querySelector('input[type=password]') || /\/login/i.test(location.pathname))
    return ec.fail('AroFlo is signed out on the TV. Open the Filer once so it signs back in, then send this again.');

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

  if (what == 'aroflo.run') {
    if (!A.js) return ec.fail('run needs js');
    ec.log('run: ' + String(A.js).slice(0, 400));
    var AsyncFn = Object.getPrototypeOf(async function () {}).constructor;
    var out = await new AsyncFn('ec', 'A', 'S', A.js)(ec, A, S);
    if (ec.finished || ec.leaving) return; // the code went to another page or answered itself
    return { ok: true, result: out === undefined ? null : out };
  }

  return ec.fail('unknown aroflo job ' + what + ' (check, search, task, calendar, run)');
});
