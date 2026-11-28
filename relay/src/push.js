// Phone notifications (28/11): real web push from this relay to Ethan's phone, no other service in between (only
// Apple's or Google's own push service, which can't read them: every message is encrypted for the phone, RFC 8291).
// - Claude makes a one-time code (phone_pair, 15 min, 5 tries). Ethan opens <relay>/push on his phone (added to the
//   home screen on an iPhone), puts the code in and taps Turn on. The phone subscribes and gets its own key back.
// - Claude sends with phone_notify (short text: job numbers and what's needed, never addresses or names). Rate limited.
// - The page lists the last notices (with the phone's own key) and can send itself a test. 5 phones at most.
// The VAPID keys are made here the first time and kept in the Durable Object's storage: nothing secret is in the repo.

const enc = new TextEncoder();
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
const rand = (n) => hex(crypto.getRandomValues(new Uint8Array(n)));
const sha = async (s) => hex(await crypto.subtle.digest('SHA-256', enc.encode(s)));
const b64u = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const unb64u = (s) => {
  const t = String(s).replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(t + '==='.slice((t.length + 3) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
};
const cat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
};
const hmac = async (key, data) => new Uint8Array(await crypto.subtle.sign('HMAC', await crypto.subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']), data));

export const MAX_PHONES = 5;
export const PER_HOUR = 30;
const KEEP = 30;
const KEEP_MS = 3 * 864e5;
// Only the real push services get our messages.
const PUSH_HOSTS = /^(fcm\.googleapis\.com|android\.googleapis\.com|[a-z0-9.-]*\.push\.apple\.com|updates\.push\.services\.mozilla\.com|push\.services\.mozilla\.com|[a-z0-9.-]*\.notify\.windows\.com)$/i;

export function pushEndpointOk(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' && PUSH_HOSTS.test(u.hostname) && !u.username && !u.password;
  } catch {
    return false;
  }
}

// RFC 8291 + RFC 8188 (aes128gcm): the message encrypted for one phone's keys.
export async function encryptFor(sub, payload) {
  const uaPublic = unb64u(sub.p256dh);
  const authSecret = unb64u(sub.auth);
  if (uaPublic.length !== 65 || authSecret.length !== 16) throw new Error('bad phone keys');
  const as = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const asPublic = new Uint8Array(await crypto.subtle.exportKey('raw', as.publicKey));
  const uaKey = await crypto.subtle.importKey('raw', uaPublic, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: uaKey }, as.privateKey, 256));
  const prkKey = await hmac(authSecret, shared);
  const ikm = (await hmac(prkKey, cat(enc.encode('WebPush: info\0'), uaPublic, asPublic, new Uint8Array([1])))).slice(0, 32);
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const prk = await hmac(salt, ikm);
  const cek = (await hmac(prk, cat(enc.encode('Content-Encoding: aes128gcm\0'), new Uint8Array([1])))).slice(0, 16);
  const nonce = (await hmac(prk, cat(enc.encode('Content-Encoding: nonce\0'), new Uint8Array([1])))).slice(0, 12);
  const key = await crypto.subtle.importKey('raw', cek, { name: 'AES-GCM' }, false, ['encrypt']);
  const plain = cat(enc.encode(payload), new Uint8Array([2]));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, tagLength: 128 }, key, plain));
  const rs = new Uint8Array([0, 0, 16, 0]); // 4096
  return cat(salt, rs, new Uint8Array([asPublic.length]), asPublic, ct);
}

// The relay's own VAPID keys (RFC 8292), made once.
export async function vapidKeys(storage) {
  let v = await storage.get('vapid');
  if (!v) {
    const k = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
    v = { priv: await crypto.subtle.exportKey('jwk', k.privateKey), pub: b64u(await crypto.subtle.exportKey('raw', k.publicKey)) };
    await storage.put('vapid', v);
  }
  return v;
}

export async function vapidHeader(v, endpoint, subject) {
  const aud = new URL(endpoint).origin;
  const head = b64u(enc.encode(JSON.stringify({ typ: 'JWT', alg: 'ES256' })));
  const body = b64u(enc.encode(JSON.stringify({ aud, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: subject })));
  const key = await crypto.subtle.importKey('jwk', v.priv, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, enc.encode(head + '.' + body));
  return 'vapid t=' + head + '.' + body + '.' + b64u(sig) + ', k=' + v.pub;
}

const clip = (s, n) => String(s == null ? '' : s).replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, '').replace(/[ \t]+/g, ' ').trim().slice(0, n);

const unb64 = (s) => {
  const bin = atob(String(s).replace(/^data:[^,]*,/, '').replace(/\s+/g, ''));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
};
const ALERT_KEEP_MS = 30 * 864e5;
const ALERT_MAX = 400;
const PHOTO_MAX = 400 * 1024;
const LEVELS = ['major', 'minor', 'hub', 'info'];
// Only AroFlo task links (and the Hub's own) can ride along with an alert.
const LINK_OK = /^https:\/\/office\.aroflo\.com\//;

// Business hours, Brisbane (no daylight saving): Monday to Friday, 7am to 5:30pm.
const BNE = 10 * 3600000;
export function inHours(ms) {
  const d = new Date(ms + BNE);
  const day = d.getUTCDay(), mins = d.getUTCHours() * 60 + d.getUTCMinutes();
  return day >= 1 && day <= 5 && mins >= 7 * 60 && mins < 17 * 60 + 30;
}
// The next roundup: 7am, 12pm or 4pm on a weekday, Brisbane time.
export function nextSlot(ms) {
  for (let i = 0; i < 8 * 24; i++) {
    const base = new Date(ms + BNE);
    base.setUTCMinutes(0, 0, 0);
    const t = new Date(base.getTime() + (i + 1) * 3600000);
    const day = t.getUTCDay(), h = t.getUTCHours();
    if (day >= 1 && day <= 5 && (h === 7 || h === 12 || h === 16)) return t.getTime() - BNE;
  }
  return ms + 3600000;
}

function cleanAlert(raw, src) {
  if (!raw || typeof raw !== 'object') return null;
  const id = String(raw.id || '').trim();
  if (!/^[A-Za-z0-9_-]{4,64}$/.test(id)) return null;
  const level = LEVELS.includes(raw.level) ? raw.level : 'minor';
  const body = clip(raw.body, 240);
  if (!body) return null;
  const url = typeof raw.url === 'string' && LINK_OK.test(raw.url) ? raw.url.slice(0, 600) : undefined;
  const at = raw.at && !isNaN(Date.parse(raw.at)) ? new Date(raw.at).toISOString() : new Date().toISOString();
  return { id, at, level, title: clip(raw.title, 60) || 'Hub', body, jn: clip(raw.jn, 12).replace(/[^0-9]/g, '') || undefined, url,
    staff: clip(raw.staff, 30) || undefined, photo: false, done: false, doneAt: null, src: src || 'hub' };
}

export class Push {
  constructor(storage, fetcher) {
    this.storage = storage;
    this.fetch = fetcher || ((u, o) => fetch(u, o));
  }

  async phones() { return (await this.storage.get('phones')) || []; }
  async savePhones(l) { await this.storage.put('phones', l); }

  async makeCode() {
    const code = rand(4).toUpperCase();
    await this.storage.put('pushpair', { code, exp: Date.now() + 15 * 60000, tries: 0 });
    return code;
  }

  async subscribe(b) {
    const p = await this.storage.get('pushpair');
    if (!p || p.exp < Date.now() || p.tries >= 5) return { status: 403, error: 'No code waiting. Ask Claude for a new phone code.' };
    if (String(b.code || '').trim().toUpperCase() !== p.code) {
      p.tries = (p.tries || 0) + 1;
      await this.storage.put('pushpair', p);
      return { status: 403, error: 'Wrong code' };
    }
    const s = b.sub || {};
    const keys = s.keys || {};
    if (!pushEndpointOk(s.endpoint)) return { status: 400, error: "That phone's push address isn't one this relay sends to" };
    try {
      if (unb64u(keys.p256dh).length !== 65 || unb64u(keys.auth).length !== 16) throw new Error();
    } catch {
      return { status: 400, error: 'Bad push keys from the phone' };
    }
    await this.storage.delete('pushpair');
    const token = rand(32);
    const phone = { id: 'p' + rand(4), name: clip(b.name, 40) || 'Phone', endpoint: s.endpoint, p256dh: keys.p256dh, auth: keys.auth,
      token: await sha(token), added: new Date().toISOString(), last: null, fails: 0 };
    let list = (await this.phones()).filter((x) => x.endpoint !== s.endpoint);
    list.push(phone);
    while (list.length > MAX_PHONES) list.shift();
    await this.savePhones(list);
    return { status: 200, token, id: phone.id };
  }

  async phoneByToken(req) {
    const h = req.headers.get('authorization') || '';
    if (!/^Bearer /i.test(h)) return null;
    const t = await sha(h.slice(7).trim());
    return (await this.phones()).find((x) => x.token === t) || null;
  }

  // ---------------------------------------------------------------- the alert list (28/11, Hub Link)
  // One list for the phone and the Hub: { id, at, level, title, body, jn, url, staff, photo, done, doneAt, src }.
  // Levels: major and hub push straight away in business hours, minor waits for the 12pm and 4pm roundups, info
  // (Claude's phone_notify) always goes straight away. Anything held outside business hours goes in the 7am summary.
  // Only a short gist is kept here; the full note stays in the Hub.
  async alerts() {
    const now = Date.now();
    let l = await this.storage.get('alerts');
    if (!l) {
      const old = (await this.storage.get('notices')) || [];
      l = old.map((n, i) => ({ id: 'n' + Date.parse(n.at).toString(36) + i, at: n.at, level: 'info', title: n.title, body: n.body, src: 'claude', done: false }));
    }
    const keep = l.filter((x) => now - Date.parse(x.at) < ALERT_KEEP_MS);
    if (keep.length !== l.length) for (const x of l) if (!keep.includes(x) && x.photo) await this.storage.delete('photo:' + x.id);
    return keep;
  }

  async recent() { return this.alerts(); }

  async undone() { return (await this.alerts()).filter((x) => !x.done).length; }

  // Adds or updates alerts. Returns what got pushed now and what's held.
  async addAlerts(items, src, origin) {
    const list = await this.alerts();
    const pending = new Set((await this.storage.get('pending')) || []);
    const now = Date.now();
    const pushNow = [];
    let added = 0, updated = 0;
    for (const raw of (Array.isArray(items) ? items : []).slice(0, 50)) {
      const it = cleanAlert(raw, src);
      if (!it) continue;
      const old = list.find((x) => x.id === it.id);
      if (old) {
        // An edited note: new text. renew (the edit says something new) puts it back on the list and alerts again.
        const changed = old.body !== it.body || old.level !== it.level;
        Object.assign(old, { title: it.title, body: it.body, level: it.level, jn: it.jn, url: it.url, staff: it.staff });
        updated++;
        if (!(changed && raw.renew)) continue;
        old.done = false;
        old.doneAt = null;
        old.at = it.at;
      } else {
        list.unshift(it);
        added++;
      }
      const x = old || it;
      if (raw.quiet) continue; // the first fill-in of past notes: into the list, no push
      if (x.level === 'info' || ((x.level === 'major' || x.level === 'hub') && inHours(now))) pushNow.push(x);
      else pending.add(x.id);
    }
    list.sort((p, q) => String(q.at).localeCompare(String(p.at)));
    await this.storage.put('alerts', list.slice(0, ALERT_MAX));
    await this.storage.put('pending', [...pending]);
    if (pending.size) await this.armDigest();
    let sent = null;
    if (pushNow.length && (await this.phones()).length) {
      const one = pushNow.length === 1 ? pushNow[0] : null;
      try {
        sent = await this.send(one ? { title: one.title, body: one.body, tag: one.id } : { title: pushNow.length + ' things to check', body: pushNow.slice(0, 3).map((x) => x.body).join('; ') + (pushNow.length > 3 ? '; and ' + (pushNow.length - 3) + ' more' : ''), tag: 'batch' }, origin);
      } catch (e) {
        sent = { sent: 0, error: String(e.message || e) };
        for (const x of pushNow) pending.add(x.id);
        await this.storage.put('pending', [...pending]);
        await this.armDigest();
      }
    }
    return { added, updated, pushed: pushNow.map((x) => x.id), held: [...pending].length, sent };
  }

  async tick(id, done) {
    const list = await this.alerts();
    const x = list.find((y) => y.id === String(id));
    if (!x) return false;
    x.done = !!done;
    x.doneAt = done ? new Date().toISOString() : null;
    if (done && x.photo) { await this.storage.delete('photo:' + x.id); x.photo = false; }
    await this.storage.put('alerts', list);
    if (done) {
      const pending = ((await this.storage.get('pending')) || []).filter((p) => p !== x.id);
      await this.storage.put('pending', pending);
    }
    return true;
  }

  // A shrunk photo for an alert (JPEG, 400 KB tops), shown on the phone. Gone when the alert's ticked or after 30 days.
  async putPhoto(id, b64) {
    const list = await this.alerts();
    const x = list.find((y) => y.id === String(id));
    if (!x) return 'no such alert';
    let bytes;
    try { bytes = unb64(b64); } catch { return 'not base64'; }
    if (bytes.length < 200 || bytes.length > PHOTO_MAX) return 'photo must be a JPEG under 400 KB';
    if (!(bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff)) return 'only JPEG photos';
    if (x.done) return 'already ticked off';
    await this.storage.put('photo:' + x.id, bytes);
    x.photo = true;
    await this.storage.put('alerts', list);
    return null;
  }

  async getPhoto(id) { return (await this.storage.get('photo:' + String(id).slice(0, 80))) || null; }

  // The roundups: 7am (everything held overnight), 12pm and 4pm (held minor ones), weekdays, Brisbane time.
  async armDigest() {
    if (!this.storage.setAlarm) return;
    const at = nextSlot(Date.now());
    const cur = this.storage.getAlarm ? await this.storage.getAlarm() : null;
    if (!cur || cur > at) await this.storage.setAlarm(at);
  }

  async digest(origin) {
    const list = await this.alerts();
    const ids = new Set((await this.storage.get('pending')) || []);
    const due = list.filter((x) => ids.has(x.id) && !x.done);
    await this.storage.put('pending', []);
    if (!due.length || !(await this.phones()).length) return { sent: 0, held: 0 };
    const majors = due.filter((x) => x.level === 'major').length;
    const title = due.length + (due.length === 1 ? ' thing' : ' things') + ' to check' + (majors ? ' (' + majors + ' major)' : '');
    const body = due.slice(0, 3).map((x) => x.body).join('; ') + (due.length > 3 ? '; and ' + (due.length - 3) + ' more' : '');
    try { return await this.send({ title, body, tag: 'roundup' }, origin); }
    catch (e) { await this.storage.put('pending', [...ids]); await this.armDigest(); return { sent: 0, error: String(e.message || e) }; }
  }

  async limited() {
    const now = Date.now();
    const sent = ((await this.storage.get('sentTimes')) || []).filter((t) => now - t < 3600000);
    if (sent.length >= PER_HOUR) return true;
    sent.push(now);
    await this.storage.put('sentTimes', sent);
    return false;
  }

  // Pushes one message to every phone (or one). Gone phones (404/410 from their push service) are dropped.
  async send({ title, body, tag }, origin, onlyId) {
    const notice = { title: clip(title, 60) || 'Hub', body: clip(body, 240), tag: clip(tag, 64) || undefined, at: new Date().toISOString() };
    if (!notice.body) throw new Error('Nothing to send');
    let list = await this.phones();
    const targets = list.filter((x) => !onlyId || x.id === onlyId);
    if (!targets.length) return { sent: 0, phones: 0, note: 'No phone is set up yet: make a code with phone_pair and open ' + origin + '/push on the phone' };
    if (await this.limited()) throw new Error('Too many notifications this hour (' + PER_HOUR + ' tops), not sent');
    notice.badge = await this.undone();
    const v = await vapidKeys(this.storage);
    const payload = JSON.stringify(notice);
    const results = await Promise.all(targets.map(async (ph) => {
      try {
        const r = await this.fetch(ph.endpoint, { method: 'POST', headers: {
          authorization: await vapidHeader(v, ph.endpoint, origin), 'content-encoding': 'aes128gcm', 'content-type': 'application/octet-stream',
          ttl: '86400', urgency: 'high', ...(notice.tag ? { topic: notice.tag.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32) || 'hub' } : {}) },
          body: await encryptFor(ph, payload) });
        return { id: ph.id, status: r.status };
      } catch (e) {
        return { id: ph.id, status: 0, error: String(e && e.message || e).slice(0, 80) };
      }
    }));
    const gone = new Set(results.filter((r) => r.status === 404 || r.status === 410).map((r) => r.id));
    const now = new Date().toISOString();
    list = list.filter((x) => !gone.has(x.id)).map((x) => {
      const r = results.find((y) => y.id === x.id);
      if (!r) return x;
      return r.status >= 200 && r.status < 300 ? { ...x, last: now, fails: 0 } : { ...x, fails: (x.fails || 0) + 1 };
    });
    await this.savePhones(list);
    const ok = results.filter((r) => r.status >= 200 && r.status < 300).length;
    return { sent: ok, phones: targets.length, dropped: gone.size || undefined, failed: results.filter((r) => !(r.status >= 200 && r.status < 300) && !gone.has(r.id)).map((r) => r.status || r.error) };
  }

  async list() {
    return (await this.phones()).map((x) => ({ id: x.id, name: x.name, added: x.added, last: x.last, fails: x.fails || 0 }));
  }

  async remove(id) {
    const l = await this.phones();
    const next = l.filter((x) => x.id !== id);
    await this.savePhones(next);
    return l.length - next.length;
  }
}

// ---------------------------------------------------------------- the phone's own page, worker and manifest
const CSS = 'body{margin:0;min-height:100vh;background:radial-gradient(900px 500px at 10% -10%,#1d2b55,transparent 60%),radial-gradient(700px 400px at 110% 0,#3a1f5c,transparent 55%),#0b0f17;color:#e8ecf4;font:16px -apple-system,Segoe UI,sans-serif}' +
  'main{max-width:460px;margin:0 auto;padding:calc(18px + env(safe-area-inset-top,0px)) 16px 32px}h1{font-size:21px;margin:6px 0 4px}p{color:#93a0b8;font-size:14px;margin:6px 0}' +
  '.box{background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.1);border-radius:16px;padding:16px;margin-top:14px}' +
  'input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid rgba(255,255,255,.15);background:rgba(0,0,0,.3);color:#fff;font-size:18px;letter-spacing:.15em;text-transform:uppercase;margin:8px 0 12px}' +
  'button{width:100%;padding:12px;border:0;border-radius:10px;background:linear-gradient(135deg,#6f8cff,#8a5bff);color:#fff;font-weight:600;font-size:16px}button.ghost{background:rgba(255,255,255,.08);margin-top:8px}' +
  '.err{color:#ff7a7a}.ok{color:#7be0a2}ul{list-style:none;margin:0;padding:0}li{padding:10px 0;border-top:1px solid rgba(255,255,255,.08)}li:first-child{border-top:0}' +
  'li b{display:block;font-size:15px}li span{display:block;color:#c4cad5;font-size:14px;margin-top:2px;white-space:pre-wrap;overflow-wrap:anywhere}li small{color:#8b93a3;font-size:12px}';

const CSS2 = '.top{display:flex;align-items:center;gap:10px}.top h1{flex:1}.count{min-width:26px;text-align:center;padding:4px 9px;border-radius:999px;background:#ff6b6b;color:#1a0b0b;font-weight:700;font-size:14px}.count.zero{background:rgba(255,255,255,.08);color:#93a0b8}' +
  '.card{background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.1);border-radius:16px;padding:14px;margin-top:10px}.card.major{border-color:rgba(255,107,107,.55)}' +
  '.pill{display:inline-block;font-size:11px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;padding:3px 7px;border-radius:6px;margin-right:6px;vertical-align:1px}' +
  '.pill.major{background:#3a1a1d;color:#ff8b8b}.pill.minor{background:#33291a;color:#f2b65e}.pill.hub{background:#1a273a;color:#7db9ff}.pill.info{background:#2a2142;color:#b9a2ff}' +
  '.card b{font-size:15.5px}.card .txt{color:#d6dbe4;font-size:14.5px;margin:6px 0 0;white-space:pre-wrap;overflow-wrap:anywhere}.card small{display:block;color:#8b93a3;font-size:12.5px;margin-top:6px}' +
  '.card img{display:block;width:100%;max-height:320px;object-fit:cover;border-radius:10px;margin-top:10px;background:#111}' +
  '.acts{display:flex;gap:8px;margin-top:10px}.acts a,.acts button{flex:1;text-align:center;text-decoration:none;padding:10px;border-radius:10px;font-weight:600;font-size:14.5px;border:0;width:auto}' +
  '.acts a{background:rgba(255,255,255,.08);color:#e8ecf4}.acts .tick{background:linear-gradient(135deg,#2f9e6a,#2c7a8f);color:#fff}.acts .undo{background:rgba(255,255,255,.08);color:#c4cad5}' +
  '.hubtop{display:flex;align-items:center;gap:8px}.hubtop b{flex:1;font-size:16px}.hubtop button{width:auto;padding:7px 12px;font-size:13px}' +
  '.go{background:#2f9e6a !important}.danger{background:linear-gradient(135deg,#d9534f,#b3261e) !important}.row2{display:flex;gap:8px}.row2 input{margin:8px 0 0;text-transform:none;letter-spacing:0;font-size:15px}' +
  '#hubbox details{margin-top:10px}#hublog li{font-size:13px;color:#c4cad5;padding:6px 0}#hublog small{display:inline;margin:0 6px 0 0}' +
  'details{margin-top:16px}summary{color:#93a0b8;font-size:14px;cursor:pointer;padding:6px 0}.empty{color:#8b93a3;font-size:14px;margin:12px 2px}.done .txt,.done b{color:#8b93a3}';

export const PAGE = '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">' +
  '<meta name="apple-mobile-web-app-capable" content="yes"><meta name="apple-mobile-web-app-title" content="Hub Link"><meta name="theme-color" content="#0b0f17">' +
  '<link rel="manifest" href="/push/manifest.json"><link rel="icon" href="/push/icon.svg"><link rel="apple-touch-icon" href="/push/icon.svg">' +
  '<title>Hub Link</title><style>' + CSS + CSS2 + '</style></head><body><main>' +
  '<div class="top"><h1>Hub Link</h1><span class="count zero" id="count" hidden>0</span></div>' +
  '<div class="box" id="setup" hidden><p id="ios" hidden>On an iPhone: tap Share, then <b>Add to Home Screen</b>, and open Hub Link from there first. Notifications only work from the home screen.</p>' +
  '<p>Ask Claude for a phone code, put it in and tap Turn on.</p><input id="code" autocomplete="one-time-code" maxlength="8" placeholder="CODE">' +
  '<button id="on">Turn on notifications</button><p id="msg"></p></div>' +
  '<div class="box" id="hubbox" hidden><div class="hubtop"><b id="hubstate">Hub</b><button class="ghost" id="hubrefresh">Refresh</button></div><p id="hubsub"></p>' +
  '<button class="danger" id="hubstop">Stop all</button>' +
  '<div id="hubresume" hidden><input id="hubpin" type="password" inputmode="numeric" autocomplete="off" maxlength="12" placeholder="Hub PIN"><button class="go" id="hubgo">Resume</button></div>' +
  '<details><summary>Leave mode</summary><div class="row2"><input id="lfrom" type="date"><input id="lto" type="date"></div><button id="lset" style="margin-top:8px">Set leave</button><button class="ghost" id="lend">End leave now</button></details>' +
  '<details><summary>Activity</summary><ul id="hublog"></ul></details><p id="hubmsg"></p></div>' +
  '<div id="todo"></div><details id="donebox" hidden><summary id="donesum">Done</summary><div id="done"></div></details>' +
  '<div class="box" id="live" hidden><p class="ok" id="state">Notifications are on for this phone.</p><button class="ghost" id="test">Send a test</button><p id="tmsg"></p></div>' +
  '<p style="text-align:center;font-size:11px;color:#4b5363;margin-top:18px">Hub Link v5</p></main><script src="/push/app.js?v=5"></script></body></html>';

export const APP = `(function(){
'use strict';
var $=function(i){return document.getElementById(i);};
var tok=null;try{tok=localStorage.getItem('hubPushToken');}catch(e){}
var ios=/iphone|ipad|ipod/i.test(navigator.userAgent),standalone=window.navigator.standalone||matchMedia('(display-mode: standalone)').matches;
var LV={major:'Major',minor:'Minor',hub:'Hub',info:'Claude'};
var photos={};
function say(el,t,bad){el.textContent=t;el.className=bad?'err':'';}
function el(tag,cls,text){var e=document.createElement(tag);if(cls)e.className=cls;if(text!=null)e.textContent=text;return e;}
function u8(s){var t=s.replace(/-/g,'+').replace(/_/g,'/');var b=atob(t+'==='.slice((t.length+3)%4));var o=new Uint8Array(b.length);for(var i=0;i<b.length;i++)o[i]=b.charCodeAt(i);return o;}
function when(s){var d=new Date(s);return d.toLocaleString('en-AU',{weekday:'short',hour:'numeric',minute:'2-digit',day:'numeric',month:'numeric'});}
function auth(){return {authorization:'Bearer '+tok};}
function gone(){tok=null;try{localStorage.removeItem('hubPushToken');}catch(e){}show();}
function photo(card,id){
  if(photos[id]){var im=el('img');im.src=photos[id];im.alt='Photo for this alert';card.appendChild(im);return;}
  fetch('/push/photo?id='+encodeURIComponent(id),{headers:auth()}).then(function(r){return r.ok?r.blob():null;}).then(function(b){
    if(!b)return;photos[id]=URL.createObjectURL(b);var im=el('img');im.src=photos[id];im.alt='Photo for this alert';var a=card.querySelector('.acts');card.insertBefore(im,a);
  }).catch(function(){});
}
function tick(id,done,btn){
  btn.disabled=true;
  fetch('/push/tick',{method:'POST',headers:Object.assign({'content-type':'application/json'},auth()),body:JSON.stringify({id:id,done:done})})
  .then(function(r){if(r.status===401)return gone();load();}).catch(function(){btn.disabled=false;});
}
function card(x){
  var c=el('div','card '+(x.done?'done':x.level));
  var h=el('div');h.appendChild(el('span','pill '+x.level,LV[x.level]||x.level));h.appendChild(el('b',null,x.title));c.appendChild(h);
  c.appendChild(el('p','txt',x.body));
  c.appendChild(el('small',null,[x.staff,x.jn?'JN '+x.jn:'',when(x.at)].filter(Boolean).join(' \\u00b7 ')));
  var acts=el('div','acts');
  if(x.url){var a=el('a',null,'Open in AroFlo');a.href=x.url;a.target='_blank';a.rel='noopener';acts.appendChild(a);}
  var t=el('button',x.done?'undo':'tick',x.done?'Not done':'Done');t.addEventListener('click',function(){tick(x.id,!x.done,t);});acts.appendChild(t);
  c.appendChild(acts);
  if(x.photo&&!x.done)photo(c,x.id);
  return c;
}
function draw(items){
  var R={major:0,hub:1,info:2,minor:3};
  var todo=items.filter(function(x){return !x.done;}).sort(function(p,q){return (R[p.level]-R[q.level])||String(q.at).localeCompare(String(p.at));}),done=items.filter(function(x){return x.done;});
  var T=$('todo'),D=$('done');T.textContent='';D.textContent='';
  if(!todo.length)T.appendChild(el('p','empty','Nothing to check. All clear.'));
  todo.forEach(function(x){T.appendChild(card(x));});
  done.slice(0,60).forEach(function(x){D.appendChild(card(x));});
  $('donebox').hidden=!done.length;$('donesum').textContent='Done ('+done.length+')';
  var n=$('count');n.hidden=false;n.textContent=todo.length;n.className='count'+(todo.length?'':' zero');
  try{if(navigator.setAppBadge){todo.length?navigator.setAppBadge(todo.length):navigator.clearAppBadge();}}catch(e){}
}
function load(){
  if(!tok)return;
  fetch('/push/alerts',{headers:auth(),cache:'no-store'}).then(function(r){if(r.status===401){gone();return null;}return r.json();})
  .then(function(j){if(j)draw(j.items||[]);}).catch(function(){});
}
// ---------- the Hub card: status, Stop all, Resume with the PIN, leave mode, activity ----------
var hubSt=null;
function ymd(d){return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');}
function hubDraw(st){
  hubSt=st;$('hubbox').hidden=false;
  var s=st.status||{},mode=s.mode||'?',lv=s.leave;
  $('hubstate').textContent=mode==='running'?'Hub running':mode==='leave'?'Hub on leave':mode==='stopped'?'Hub stopped':'Hub';
  $('hubsub').textContent=(lv?'Leave '+lv.from+' to '+lv.to+'. ':'')+(st.locked?'Locked on the PC. ':'')+(st.scripts&&st.scripts.length?'Scripts on: '+st.scripts.join(', ')+'.':'');
  $('hubstop').hidden=mode!=='running';$('hubresume').hidden=mode!=='stopped';
  $('lend').hidden=!lv;
  if(!$('lfrom').value){$('lfrom').value=ymd(new Date());var t=new Date();t.setDate(t.getDate()+7);$('lto').value=ymd(t);}
  var L=$('hublog');L.textContent='';(st.log||[]).slice(0,15).forEach(function(e){var li=el('li');li.appendChild(el('small',null,when(e.ts)));li.appendChild(document.createTextNode([e.actor,e.action,e.site,e.detail].filter(Boolean).join(' ')));L.appendChild(li);});
}
function hubLoad(){
  if(!tok)return;
  fetch('/push/hub',{headers:auth(),cache:'no-store'}).then(function(r){if(r.status===401){gone();return null;}return r.json().then(function(j){return {ok:r.ok,j:j};});})
  .then(function(x){if(!x)return;if(!x.ok){$('hubbox').hidden=false;$('hubstate').textContent='Hub';$('hubsub').textContent=x.j.error||'The Hub isn\\'t reachable right now.';$('hubstop').hidden=true;$('hubresume').hidden=true;return;}hubDraw(x.j);}).catch(function(){});
}
function hubDo(body,okText){
  var m=$('hubmsg');say(m,'Asking the Hub...');
  fetch('/push/hub',{method:'POST',headers:Object.assign({'content-type':'application/json'},auth()),body:JSON.stringify(body)})
  .then(function(r){return r.json().then(function(j){if(!r.ok||j.error)throw new Error(j.error||r.status);return j;});})
  .then(function(j){say(m,okText);$('hubpin').value='';hubDraw(j);}).catch(function(e){say(m,String(e&&e.message||e),true);});
}
$('hubrefresh').addEventListener('click',hubLoad);
$('hubstop').addEventListener('click',function(){hubDo({do:'stop'},'Stopped. Everything on the Hub is paused.');});
$('hubgo').addEventListener('click',function(){var p=$('hubpin').value.trim();if(!p){say($('hubmsg'),'Put your Hub PIN in first.',true);return;}hubDo({do:'resume',pin:p},'Running again.');});
$('lset').addEventListener('click',function(){hubDo({do:'leave',from:$('lfrom').value,to:$('lto').value},'Leave mode set.');});
$('lend').addEventListener('click',function(){hubDo({do:'leave-end'},'Leave mode ended.');});
function show(){
  var has=!!tok,on=('Notification' in window)&&Notification.permission==='granted';
  $('setup').hidden=has;$('live').hidden=!has;$('ios').hidden=!(ios&&!standalone);
  say($('state'),on?'Notifications are on for this phone.':'Notifications are off for this phone. Turn them on for Hub Link in Settings, Notifications.',!on);
  if(on)$('state').className='ok';
  if(!has){$('todo').textContent='';$('donebox').hidden=true;$('count').hidden=true;$('hubbox').hidden=true;return;}
  load();hubLoad();
}
$('on').addEventListener('click',function(){
  var code=$('code').value.trim(),msg=$('msg');
  if(!code){say(msg,'Put the code from Claude in first.',true);return;}
  if(!('serviceWorker' in navigator)||!('PushManager' in window)||!('Notification' in window)){say(msg,ios&&!standalone?'Add this page to your Home Screen first, then open it from there.':'This browser can\\'t do notifications.',true);return;}
  // Each step shows on screen and has its own time limit, so nothing can hang without saying where.
  var n=0;
  function step(label,p,ms){n++;var me=n;say(msg,'Step '+me+' of 5: '+label+'...');
    return new Promise(function(res,rej){var t=setTimeout(function(){rej(new Error('Stuck at step '+me+' ('+label+') for '+Math.round(ms/1000)+' s.'));},ms);
      Promise.resolve(p).then(function(v){clearTimeout(t);res(v);},function(e){clearTimeout(t);rej(e);});});}
  // On an iPhone the permission has to be asked right inside the tap, so it goes first, straight away.
  var ask;try{ask=Notification.permission==='granted'?Promise.resolve('granted'):Notification.requestPermission();}catch(e){ask=Promise.reject(e);}
  step('asking to allow notifications',ask,60000).then(function(p){
    if(p!=='granted')throw new Error('Notifications are '+p+'. Turn them on for Hub Link in Settings, Notifications, then try again.');
    return step('starting the helper',navigator.serviceWorker.register('/push/sw.js',{scope:'/push'}).then(function(reg){
      if(reg.active)return reg;
      return new Promise(function(res,rej){var w=reg.installing||reg.waiting;if(!w){res(reg);return;}
        w.addEventListener('statechange',function(){if(w.state==='activated')res(reg);else if(w.state==='redundant')rej(new Error('The helper failed to start.'));});});
    }),20000);
  }).then(function(reg){
    return step('getting the relay key',fetch('/push/key',{cache:'no-store'}).then(function(r){return r.json();}),15000).then(function(k){
      return step('signing up with Apple or Google',reg.pushManager.getSubscription().then(function(old){return old?old.unsubscribe():null;}).then(function(){
        return reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:u8(k.key)});}),30000);
    });
  }).then(function(sub){
    var name=ios?'iPhone':/android/i.test(navigator.userAgent)?'Android phone':'Browser';
    return step('saving this phone',fetch('/push/subscribe',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({code:code,sub:sub.toJSON(),name:name})})
      .then(function(r){return r.json().then(function(j){if(!r.ok)throw new Error(j.error||('The relay said '+r.status));return j;});}),15000);
  }).then(function(j){tok=j.token;try{localStorage.setItem('hubPushToken',tok);}catch(e){}say(msg,'');show();})
  .catch(function(e){say(msg,(e&&e.name&&e.name!=='Error'?e.name+': ':'')+String(e&&e.message||e),true);});
});
$('test').addEventListener('click',function(){
  var m=$('tmsg');say(m,'Sending...');
  fetch('/push/test',{method:'POST',headers:auth()}).then(function(r){return r.json().then(function(j){if(!r.ok)throw new Error(j.error||r.status);return j;});})
  .then(function(j){say(m,j.sent?'Sent. It should pop up in a few seconds.':'Not sent: '+JSON.stringify(j.failed||j.note||''),!j.sent);})
  .catch(function(e){say(m,String(e&&e.message||e),true);});
});
document.addEventListener('visibilitychange',function(){if(document.visibilityState==='visible')show();});
setInterval(function(){if(document.visibilityState==='visible')load();},60000);
show();
})();`;

export const SW = `self.addEventListener('install',function(e){self.skipWaiting();});
self.addEventListener('activate',function(e){e.waitUntil(self.clients.claim());});
self.addEventListener('push',function(e){
  var n={title:'Hub',body:'Something needs you'};
  try{n=e.data?e.data.json():n;}catch(x){try{n.body=e.data.text();}catch(y){}}
  var badge=typeof n.badge==='number'?n.badge:null;
  e.waitUntil(Promise.all([
    self.registration.showNotification(String(n.title||'Hub').slice(0,60),{body:String(n.body||'').slice(0,240),tag:n.tag||undefined,renotify:!!n.tag,icon:'/push/icon.svg',badge:'/push/icon.svg',data:{url:'/push'}}),
    (badge!=null&&self.navigator&&self.navigator.setAppBadge)?(badge?self.navigator.setAppBadge(badge):self.navigator.clearAppBadge()).catch(function(){}):null
  ]));
});
self.addEventListener('notificationclick',function(e){
  e.notification.close();
  e.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(function(l){
    for(var i=0;i<l.length;i++){if(l[i].url.indexOf('/push')>=0&&'focus' in l[i])return l[i].focus();}
    return self.clients.openWindow('/push');
  }));
});`;

export const MANIFEST = JSON.stringify({ name: 'Hub Link', short_name: 'Hub Link', start_url: '/push', scope: '/push', display: 'standalone',
  background_color: '#0b0f17', theme_color: '#0b0f17', icons: [{ src: '/push/icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' }] });

export const ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#6f8cff"/><stop offset="1" stop-color="#8a5bff"/></linearGradient></defs>' +
  '<rect width="96" height="96" rx="22" fill="#0b0f17"/><path d="M48 20c-11 0-19 8-19 19v13l-6 9h50l-6-9V39c0-11-8-19-19-19zm-7 45a7 7 0 0 0 14 0z" fill="url(#g)"/></svg>';
