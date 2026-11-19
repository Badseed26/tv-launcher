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
      var out = [];
      for (var q = 0; q < JOB.terms.length && q < 25; q++) {
        var term = JOB.terms[q], m;
        try {
          if ((m = term.match(/^notes?\s+(\d{4,5})$/i))) {
            var r = (await search(m[1], 'JobNo')).find(function (x) { return x.jn == m[1]; });
            if (!r || !r.href) { out.push('notes ' + m[1] + ': JN not found'); continue; }
            var pg = new DOMParser().parseFromString(await (await fetch(r.href)).text(), 'text/html'), pt = txt(pg);
            var a1 = pt.indexOf('Task Notes'), b1 = pt.indexOf('Add Note (Text)'), u = pt.indexOf('Upcoming Schedules');
            var notes = a1 < 0 ? '' : pt.slice(a1, b1 > a1 ? b1 : a1 + 1500);
            out.push('notes JN ' + r.jn + ' ' + r.task + ' (' + r.status + ')\n  ' + (u < 0 ? 'no upcoming schedules shown' : pt.slice(u, u + 200)) + '\n  ' + (notes ? notes.slice(-1500) : 'notes list not found on the page'));
            continue;
          }
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
      return done({ ok: true, text: out.join('\n') });
    }
    done({ err: 'unknown job' });
  } catch (e) { done({ err: 'script error: ' + String(e && e.message || e).slice(0, 120) }); }
})();
