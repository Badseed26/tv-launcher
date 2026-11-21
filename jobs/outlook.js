// Outlook jobs for the TV api (21/11). Read only, always: search, read an email, grab its attachments.
// Nothing here ever clicks an email open (that would mark it read). Mail comes straight from Outlook's own mail
// service, signed with the headers Outlook itself uses (caught by jobs/outlook-hook.js, which also blocks every write).
// Only Find/Get calls ever go out from here.
//
// outlook.check                       signed in? which headers did we catch
// outlook.search {q, folder, max}     q is Outlook search words (from:lisa, subject:solar, 9364). folder inbox (default),
//                                     sentitems, drafts, deleteditems, or all. max 25 by default, 100 tops
// outlook.read {id} or {q, n}         one email: who, when, to, cc, text body, attachments list. n picks from the search
// outlook.attachment {id|q, name|all} the attachment(s) as files on the remote task (8 MB each tops, 5 at a time)
// outlook.run {js}                    any bit of read-only code on the page, with svc(action, body) and the helpers
ec.run(async function () {
  var A = ec.args, what = ec.job.do;

  if (/login\.microsoftonline\.com|login\.live\.com/.test(location.host)) {
    await ec.shot('outlook signed out');
    return ec.fail('Outlook on the TV is signed out. Ethan needs to sign in once on the Filer (the TV never types the Microsoft login).');
  }
  if (!/outlook\./.test(location.host)) return ec.fail('not an Outlook page: ' + location.host);
  if (!window.__ecApiHook) return ec.fail('the read-only hook isn\'t on this page, stopped');

  var READ = /^(Find|Get|Search|ExecuteSearch)/;
  var TZ = 'E. Australia Standard Time';

  // ---- signing the calls the way Outlook does
  async function auth() {
    var ol = window.__ecOl;
    var got = await ec.waitFor(function () { return ol.hdr && ol.base; }, 45000, 500);
    if (got) return { hdr: ol.hdr, base: ol.base, how: 'outlook' };
    // Outlook didn't make a mail call we could see in time: its own saved sign-in in this page's storage
    var best = null;
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (!/accesstoken/i.test(k)) continue;
        var v = JSON.parse(localStorage.getItem(k));
        if (!v || !v.secret || !/outlook\.office(365)?\.com/i.test(v.target || k)) continue;
        if (Number(v.expiresOn) * 1000 < Date.now() + 60000) continue;
        if (!best || Number(v.expiresOn) > Number(best.expiresOn)) best = v;
      }
    } catch (e) {}
    if (!best) throw new Error('Outlook never made a mail call to copy, and there\'s no saved sign-in (seen: ' + ol.seen.join(', ') + ')');
    return { hdr: { authorization: 'Bearer ' + best.secret }, base: location.origin + '/owa/service.svc', how: 'saved sign-in' };
  }
  var AUTH = null, N = 0;
  async function svc(action, body) {
    if (!READ.test(action)) throw new Error(action + ' isn\'t a read, not sending it');
    if (!AUTH) { AUTH = await auth(); ec.log('signed with ' + AUTH.how); }
    var h = { 'content-type': 'application/json; charset=utf-8', action: action, 'x-req-source': 'Mail' };
    for (var k in AUTH.hdr) h[k] = AUTH.hdr[k];
    var full = {
      __type: action + 'JsonRequest:#Exchange',
      Header: { __type: 'JsonRequestHeaders:#Exchange', RequestServerVersion: 'V2018_01_08',
        TimeZoneContext: { __type: 'TimeZoneContext:#Exchange', TimeZoneDefinition: { __type: 'TimeZoneDefinitionType:#Exchange', Id: TZ } } },
      Body: body
    };
    var r = await fetch(AUTH.base + '?action=' + action + '&app=Mail&n=' + (++N), { method: 'POST', headers: h, body: JSON.stringify(full), credentials: 'include' });
    var t = await r.text(), j = null;
    try { j = JSON.parse(t); } catch (e) {}
    if (!r.ok || !j) throw new Error(action + ' said ' + r.status + ': ' + t.replace(/\s+/g, ' ').slice(0, 200));
    var m = j.Body && j.Body.ResponseMessages && j.Body.ResponseMessages.Items && j.Body.ResponseMessages.Items[0];
    if (m && m.ResponseClass == 'Error') throw new Error(action + ': ' + (m.MessageText || m.ResponseCode));
    return { raw: j, msg: m };
  }

  function who(x) { var m = x && (x.Mailbox || x); return m ? [m.Name, m.EmailAddress].filter(Boolean).join(' ') : ''; }
  function whoList(a) { return (a || []).map(who).filter(Boolean).join('; '); }
  var FOLDERS = { inbox: 1, sentitems: 1, drafts: 1, deleteditems: 1, junkemail: 1, archive: 1, msgfolderroot: 1 };

  async function search(q, folder, max) {
    folder = String(folder || 'inbox').toLowerCase().replace(/\s+/g, '');
    if (folder == 'sent') folder = 'sentitems';
    if (folder == 'all') folder = 'msgfolderroot';
    if (!FOLDERS[folder]) throw new Error('unknown folder ' + folder);
    max = Math.min(Number(max) || 25, 100);
    var body = {
      __type: 'FindItemRequest:#Exchange',
      ItemShape: { __type: 'ItemResponseShape:#Exchange', BaseShape: 'Default' },
      ParentFolderIds: [{ __type: 'DistinguishedFolderId:#Exchange', Id: folder }],
      Traversal: folder == 'msgfolderroot' ? 'Deep' : 'Shallow',
      Paging: { __type: 'IndexedPageView:#Exchange', BasePoint: 'Beginning', Offset: 0, MaxEntriesReturned: max },
      SortOrder: [{ __type: 'SortResults:#Exchange', Order: 'Descending', Path: { __type: 'PropertyUri:#Exchange', FieldURI: 'DateTimeReceived' } }]
    };
    if (q) body.QueryString = { __type: 'QueryStringType:#Exchange', Value: String(q) };
    var res;
    try { res = await svc('FindItem', body); }
    catch (e) {
      if (!q) throw e;
      ec.log('search as an object failed (' + e.message.slice(0, 120) + '), trying it as plain text');
      body.QueryString = String(q);
      res = await svc('FindItem', body);
    }
    var items = (res.msg && res.msg.RootFolder && res.msg.RootFolder.Items) || [];
    return items.map(function (x) {
      return { id: x.ItemId && x.ItemId.Id, when: x.DateTimeReceived || x.DateTimeSent, from: who(x.From), subj: x.Subject || '',
        unread: x.IsRead === false, att: !!x.HasAttachments, prev: String(x.Preview || '').slice(0, 160) };
    });
  }

  async function getItem(id) {
    var res = await svc('GetItem', {
      __type: 'GetItemRequest:#Exchange',
      ItemShape: { __type: 'ItemResponseShape:#Exchange', BaseShape: 'AllProperties', BodyType: 'Text' },
      ItemIds: [{ __type: 'ItemId:#Exchange', Id: id }]
    });
    var x = res.msg && res.msg.Items && res.msg.Items[0];
    if (!x) throw new Error('that email wasn\'t found');
    return x;
  }

  async function pick() {
    if (A.id) return String(A.id);
    if (!A.q) throw new Error('needs id, or q to search for it');
    var list = await search(A.q, A.folder, Math.max(5, (Number(A.n) || 0) + 1));
    var it = list[Number(A.n) || 0];
    if (!it) throw new Error('nothing found for "' + A.q + '"');
    ec.log('picked "' + it.subj.slice(0, 60) + '" ' + it.when);
    return it.id;
  }

  function atts(x) {
    return (x.Attachments || []).map(function (a) {
      return { id: a.AttachmentId && a.AttachmentId.Id, name: a.Name, type: a.ContentType, kb: Math.round((a.Size || 0) / 1024), inline: !!a.IsInline };
    });
  }

  if (what == 'outlook.check') {
    var au = await auth();
    var r0 = await search('', 'inbox', 1);
    return { ok: true, signedIn: true, via: au.how, headers: Object.keys(au.hdr), newest: r0[0] ? { when: r0[0].when, subj: r0[0].subj } : null,
      seen: window.__ecOl.seen.slice(0, 20), blocked: window.__ecOl.blocked };
  }

  if (what == 'outlook.search') {
    var list = await search(A.q || '', A.folder, A.max);
    ec.log('search "' + (A.q || '') + '" in ' + (A.folder || 'inbox') + ': ' + list.length);
    return { ok: true, q: A.q || '', folder: A.folder || 'inbox', count: list.length, items: list, blocked: window.__ecOl.blocked };
  }

  if (what == 'outlook.read') {
    var x = await getItem(await pick());
    var body = String((x.Body && x.Body.Value) || '').replace(/\r/g, '').replace(/\n{3,}/g, '\n\n');
    var cap = Math.min(Number(A.chars) || 12000, 40000);
    return { ok: true, id: x.ItemId && x.ItemId.Id, subj: x.Subject, from: who(x.From), sent: x.DateTimeSent, received: x.DateTimeReceived,
      to: whoList(x.ToRecipients), cc: whoList(x.CcRecipients), unread: x.IsRead === false,
      attachments: atts(x), body: body.slice(0, cap), cut: body.length > cap, blocked: window.__ecOl.blocked };
  }

  if (what == 'outlook.attachment') {
    var it = await getItem(await pick());
    var list2 = atts(it).filter(function (a) { return A.inline || !a.inline; });
    if (A.name) list2 = list2.filter(function (a) { return String(a.name || '').toLowerCase().indexOf(String(A.name).toLowerCase()) >= 0; });
    else if (!A.all) list2 = list2.slice(0, 1);
    if (!list2.length) return ec.fail('no attachment matched' + (A.name ? ' "' + A.name + '"' : '') + ' (has: ' + atts(it).map(function (a) { return a.name; }).join(', ') + ')');
    var sent = [];
    for (var i = 0; i < list2.length && sent.length < 5; i++) {
      var a = list2[i];
      if (a.kb > 8192) { ec.log('skipped ' + a.name + ', ' + a.kb + ' KB is too big'); continue; }
      var ga = await svc('GetAttachment', { __type: 'GetAttachmentRequest:#Exchange', AttachmentIds: [{ __type: 'AttachmentId:#Exchange', Id: a.id }] });
      var f = ga.msg && ga.msg.Attachments && ga.msg.Attachments[0];
      if (!f || !f.Content) { ec.log('no content came back for ' + a.name); continue; }
      ec.file(a.name || 'attachment', f.Content, a.type);
      sent.push({ name: a.name, kb: a.kb });
    }
    if (!sent.length) return ec.fail('couldn\'t get any of the attachments');
    return { ok: true, subj: it.Subject, files: sent };
  }

  if (what == 'outlook.run') {
    if (!A.js) return ec.fail('run needs js');
    ec.log('run: ' + String(A.js).slice(0, 400));
    // the TV puts the code on the page as window.__ecRun (build 68 on); Outlook blocks turning text into code
    if (typeof window.__ecRun != 'function') return ec.fail('the js didn\'t parse (or the TV is older than build 68)');
    var out = await window.__ecRun(ec, A, ec.state, svc, search, getItem);
    if (ec.finished || ec.leaving) return;
    return { ok: true, result: out === undefined ? null : out, blocked: window.__ecOl.blocked };
  }

  return ec.fail('unknown outlook job ' + what);
});
