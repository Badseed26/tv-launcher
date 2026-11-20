// The Runner's AroFlo reads, run once in the TV's hidden AroFlo page. Read only: searches and page loads, never a save.
// cal: the next few weeks of the calendar (same as the house rules' _calIndex). lookup: task searches and a task's notes.
// Answers through ecRun.done(token, json).
(async function () {
  var TOKEN = '__TOKEN__', JOB = __JOB__;
  var done = function (o) { try { window.ecRun.done(TOKEN, JSON.stringify(o)); } catch (e) {} };
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  var txt = function (d) { return (d.body ? d.body.innerText : '').replace(/\s+/g, ' '); };
  try {
    for (var i = 0; i < 60 && !(document.readyState == 'complete' && document.body); i++) await sleep(250);
    var t = txt(document);
    if (/User Session Limit/i.test(t)) return done({ err: 'AroFlo has the User Session Limit page up (signed in somewhere else)' });
    if (document.querySelector('input[type=password]') || /\/login/i.test(location.pathname)) return done({ err: 'AroFlo is signed out on the TV' });

    // same search as the house rules' _taskSearch
    var search = async function (term, field) {
      var fd = new URLSearchParams({ taskString: term, SearchParam: field, searchtasks: 'Search', searchtasks_btn: 'Search' });
      var h = await (await fetch('/ims/Site/Home/index.cfm?tid=IMS.STK.CUR', { method: 'POST', body: fd })).text();
      var d = new DOMParser().parseFromString(h, 'text/html');
      return [].slice.call(d.querySelectorAll('tr')).filter(function (tr) { return tr.querySelectorAll('td').length >= 6; }).map(function (tr) {
        var c = [].slice.call(tr.querySelectorAll('td')).map(function (x) { return (x.textContent || '').replace(/\s+/g, ' ').trim(); }).filter(Boolean);
        var a = [].slice.call(tr.querySelectorAll('a')).find(function (a) { var h = a.getAttribute('href') || ''; return /workrequest/.test(h) && !/printview/.test(h); });
        return { ref: c[0], jn: c[1], req: c[2], task: c[4], status: c[c.length - 1], href: a ? a.getAttribute('href') : '' };
      });
    };

    if (JOB.kind == 'cal') {
      for (var k = 0; k < 80 && !(window.jQuery && jQuery('.fc').length && jQuery('.fc').first().fullCalendar('clientEvents').length); k++) await sleep(250);
      if (!(window.jQuery && jQuery('.fc').length)) return done({ err: 'the AroFlo calendar never loaded' });
      if (!jQuery('.fc').first().fullCalendar('clientEvents').length) return done({ err: 'the AroFlo calendar showed no bookings after 20 sec (slow or signed out?), page: ' + document.title.slice(0, 60) });
      var $c = jQuery('.fc').first(), fc = function () { return $c.fullCalendar('clientEvents'); }, idx = new Map();
      var grab = function () {
        fc().filter(function (e) { return e.type == 'task'; }).forEach(function (e) {
          var key = e.wrid + '|' + new Date(e.start).getTime();
          if (!idx.has(key)) idx.set(key, { staff: (e.resourcename || '').split(' ')[0], start: new Date(e.start), end: e.end ? new Date(e.end) : null,
            h: (e.headers || []).map(function (h) { return h.headerText; }).filter(Boolean).join(' / '),
            t: (e.title || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 140),
            cancel: /cancel/i.test(JSON.stringify(e.headers) + e.title) });
        });
      };
      var from = new Date(); from.setHours(0, 0, 0, 0);
      var back = JOB.back || 0; from = new Date(from.getTime() - back * 864e5);
      var weeks = JOB.weeks || 5;
      // 20/11: after each jump, wait for the bookings to stop changing (a quick read missed a week). Lines so far go in
      // window.__runPartial, so a timeout still hands back what it got.
      var DN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
      var tm = function (x) { if (!x) return '?'; var h = x.getHours(), m = x.getMinutes(); return (h % 12 || 12) + ':' + (m < 10 ? '0' : '') + m + (h < 12 ? 'am' : 'pm'); };
      var linesNow = function () {
        return Array.from(idx.values()).filter(function (v) { return v.start >= from; }).sort(function (a, b) { return a.start - b.start; }).map(function (v) {
          var s = v.start;
          return DN[s.getDay()] + ' ' + s.getDate() + '/' + (s.getMonth() + 1) + ' ' + tm(s) + '-' + tm(v.end) + ' ' + v.staff + ' | JN ' + v.h + ' | ' + v.t + (v.cancel ? ' | CANCELLED' : '');
        });
      };
      for (var w = 0; w < weeks; w++) {
        var d = new Date(from.getTime() + w * 7 * 864e5);
        $c.fullCalendar('gotoDate', d);
        var last = -1, same = 0;
        for (var n = 0; n < 20 && same < 3; n++) { await sleep(400); var c = fc().length; if (n >= 2 && c == last) same++; else same = 0; last = c; }
        grab();
        window.__runPartial = { ok: true, partial: true, visits: idx.size, from: from.getDate() + '/' + (from.getMonth() + 1), weeks: w + 1, lines: linesNow() };
      }
      var lines = linesNow();
      return done({ ok: true, visits: lines.length, from: from.getDate() + '/' + (from.getMonth() + 1), weeks: weeks, lines: lines });
    }
    if (JOB.kind == 'lookup') {
      // 20/11: a task's notes load after the page opens, so notes lookups open each task page in turn and read it once
      // it's drawn (like the filer). Progress waits in sessionStorage between pages.
      var st = null; try { st = JSON.parse(sessionStorage.getItem('_runLook') || 'null'); } catch (e) {}
      if (!st || st.id != JOB.id) {
        var out = [], queue = [];
        for (var q = 0; q < JOB.terms.length && q < 25; q++) {
          var term = JOB.terms[q], m;
          try {
            if ((m = term.match(/^notes?\s+(\d{4,5})$/i))) { queue.push(m[1]); out.push('@notes ' + m[1]); continue; }
            if (/^diag$/i.test(term)) { out.push('diag: page ' + innerWidth + 'x' + innerHeight + ' css px, dpr ' + devicePixelRatio + ', screen ' + screen.width + 'x' + screen.height); continue; }
            var field = 'Location', s = term;
            if ((m = term.match(/^(jn|job)\s*(\d{4,5})$/i)) || (m = term.match(/^()(\d{4,5})$/))) { field = 'JobNo'; s = m[2]; }
            else if ((m = term.match(/^(po|claim|order)\s+(.+)$/i))) { field = 'CustOn'; s = m[2]; }
            else if ((m = term.match(/^client\s+(.+)$/i))) { field = 'Client'; s = m[1]; }
            else if ((m = term.match(/^desc\s+(.+)$/i))) { field = 'Desc'; s = m[1]; }
            else if ((m = term.match(/^(street|address|addr)\s+(.+)$/i))) { field = 'Location'; s = m[2]; }
            else if (/\d{6,}/.test(term) && !/\s/.test(term)) field = 'CustOn';
            var res = await search(s, field);
            if (field == 'JobNo') res = res.filter(function (x) { return x.jn == s; });
            out.push(field + ' "' + s + '": ' + (res.length ? res.length + ' found' : 'nothing found') + res.slice(0, 12).map(function (x) { return '\n  JN ' + x.jn + ' | ' + x.task + ' | ' + x.status + (x.req ? ' | ' + x.req : ''); }).join(''));
          } catch (e) { out.push(term + ': failed, ' + String(e && e.message || e).slice(0, 80)); }
        }
        st = { id: JOB.id, out: out, queue: queue, cur: null };
      }
      var save = function () { sessionStorage.setItem('_runLook', JSON.stringify(st)); };
      var put = function (jn, text) { var i = st.out.indexOf('@notes ' + jn); if (i >= 0) st.out[i] = text; else st.out.push(text); };
      // on the task page we were heading for: read it
      if (st.cur && new RegExp('\\bJN ' + st.cur + '\\b').test(document.title)) {
        for (var k = 0; k < 40 && txt(document).indexOf('Add Note (Text)') < 0; k++) await sleep(400);
        await sleep(1500);
        var pt = txt(document), a1 = pt.indexOf('Task Notes'), b1 = pt.indexOf('Add Note (Text)'), u = pt.indexOf('Upcoming Schedules');
        // strip AroFlo's button and icon text so the answer is just the facts
        var junk = function (s) { return s.replace(/\b(sticky_note_2|Send SMS|Create Task Schedule|Capture Signature|Save Task|View Schedule)\b/g, ' ').replace(/\s+Reply\s+/g, ': ').replace(/\s+/g, ' ').trim(); };
        var notes = a1 < 0 ? '' : junk(pt.slice(a1 + 10, b1 > a1 ? b1 : a1 + 1500)).replace(/ (?=\d{1,2} [A-Z][a-z]+ 20\d\d \d{1,2}:\d\d[ap]m)/g, '\n    ');
        var sched = u < 0 ? 'no upcoming schedules' : junk(pt.slice(u, u + 260).replace(/\s+All Task Schedules[\s\S]*$/, ''));
        put(st.cur, 'notes ' + document.title.replace(/ - Task - AroFlo.*$/, '').slice(0, 70) + '\n  ' + sched + '\n  Notes: ' + (notes ? notes.slice(-1800) : 'none found on the page'));
        st.cur = null;
      } else if (st.cur) { put(st.cur, 'notes JN ' + st.cur + ': the task page didn\'t open'); st.cur = null; }
      while (st.queue.length) {
        var jn = st.queue.shift(), r = null;
        try { r = (await search(jn, 'JobNo')).find(function (x) { return x.jn == jn; }); } catch (e) {}
        if (!r || !r.href) { put(jn, 'notes ' + jn + ': JN not found'); continue; }
        st.cur = jn; save();
        location.href = r.href; // the app runs this again once the task page loads
        return;
      }
      sessionStorage.removeItem('_runLook');
      return done({ ok: true, text: st.out.join('\n') });
    }
    done({ err: 'unknown job' });
  } catch (e) { done({ err: 'script error: ' + String(e && e.message || e).slice(0, 120) }); }
})();
