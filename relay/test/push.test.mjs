// Phone alerts and Hub Link (push.js, index.js). Run: node --test relay/test/*.test.mjs
// Plays the phone itself (decrypts what the relay sends, checks the VAPID signature) and the Hub (its alert routes).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Push, encryptFor, vapidKeys, pushEndpointOk, inHours, nextSlot } from '../src/push.js';
import { Hub } from '../src/index.js';

const subtle = crypto.subtle;
const b64u = (b) => Buffer.from(b).toString('base64url');
const hm = async (k, d) => new Uint8Array(await subtle.sign('HMAC', await subtle.importKey('raw', k, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']), d));
const cat = (...p) => Buffer.concat(p.map((x) => Buffer.from(x)));
const BNE = (iso) => Date.parse(iso + '+10:00');

function memStorage() {
  const mem = new Map();
  let alarm = null;
  return {
    mem,
    get alarm() { return alarm; },
    get: async (k) => structuredClone(mem.get(k)),
    put: async (k, v) => { mem.set(k, structuredClone(v)); },
    delete: async (k) => mem.delete(k),
    setAlarm: async (t) => { alarm = t; },
    getAlarm: async () => alarm,
  };
}

async function phone() {
  const ua = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const pub = new Uint8Array(await subtle.exportKey('raw', ua.publicKey));
  const auth = crypto.getRandomValues(new Uint8Array(16));
  const keys = { p256dh: b64u(pub), auth: b64u(auth) };
  const decrypt = async (body) => {
    body = new Uint8Array(body);
    const salt = body.slice(0, 16), idlen = body[20], asPub = body.slice(21, 21 + idlen), ct = body.slice(21 + idlen);
    const asKey = await subtle.importKey('raw', asPub, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
    const shared = new Uint8Array(await subtle.deriveBits({ name: 'ECDH', public: asKey }, ua.privateKey, 256));
    const ikm = (await hm(await hm(auth, shared), cat(Buffer.from('WebPush: info\0'), pub, asPub, [1]))).slice(0, 32);
    const prk = await hm(salt, ikm);
    const cek = (await hm(prk, cat(Buffer.from('Content-Encoding: aes128gcm\0'), [1]))).slice(0, 16);
    const nonce = (await hm(prk, cat(Buffer.from('Content-Encoding: nonce\0'), [1]))).slice(0, 12);
    const k = await subtle.importKey('raw', cek, 'AES-GCM', false, ['decrypt']);
    const pt = new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: nonce }, k, ct));
    assert.equal(pt[pt.length - 1], 2);
    return JSON.parse(Buffer.from(pt.slice(0, -1)).toString());
  };
  return { keys, decrypt };
}

async function setup() {
  const storage = memStorage();
  const sent = [];
  let reply = 201;
  const p = new Push(storage, async (u, o) => { sent.push({ u, o }); return { status: reply }; });
  const ph = await phone();
  const code = await p.makeCode();
  const r = await p.subscribe({ code, sub: { endpoint: 'https://web.push.apple.com/QWxs', keys: ph.keys }, name: 'iPhone' });
  assert.equal(r.status, 200);
  return { storage, p, ph, sent, token: r.token, setReply: (n) => { reply = n; } };
}

test('encryption round trip and the VAPID signature', async () => {
  const ph = await phone();
  const body = await encryptFor(ph.keys, JSON.stringify({ hi: 1 }));
  assert.deepEqual(await ph.decrypt(body), { hi: 1 });
  const storage = memStorage();
  const v = await vapidKeys(storage);
  assert.equal(Buffer.from(v.pub, 'base64url').length, 65);
  assert.deepEqual(await vapidKeys(storage), v, 'made once, kept');
  assert.ok(pushEndpointOk('https://fcm.googleapis.com/fcm/send/x'));
  assert.ok(!pushEndpointOk('https://evil.example.com/x'));
});

test('business hours and roundup times are Brisbane weekdays', () => {
  assert.equal(inHours(BNE('2026-11-30T07:00:00')), true, 'Mon 7am');
  assert.equal(inHours(BNE('2026-11-30T17:29:00')), true);
  assert.equal(inHours(BNE('2026-11-30T17:30:00')), false, 'Mon 5:30pm');
  assert.equal(inHours(BNE('2026-11-30T06:59:00')), false);
  assert.equal(inHours(BNE('2026-11-28T10:00:00')), false, 'Saturday');
  assert.equal(nextSlot(BNE('2026-11-30T06:10:00')), BNE('2026-11-30T07:00:00'));
  assert.equal(nextSlot(BNE('2026-11-30T07:00:00')), BNE('2026-11-30T12:00:00'));
  assert.equal(nextSlot(BNE('2026-11-30T13:05:00')), BNE('2026-11-30T16:00:00'));
  assert.equal(nextSlot(BNE('2026-11-30T16:30:00')), BNE('2026-12-01T07:00:00'));
  assert.equal(nextSlot(BNE('2026-11-27T18:00:00')), BNE('2026-11-30T07:00:00'), 'Friday night waits for Monday 7am');
});

test('a major alert in hours pushes straight away with the badge count; a minor one waits for the roundup', async (t) => {
  const { p, ph, sent, storage } = await setup();
  t.mock.timers.enable({ apis: ['Date'], now: BNE('2026-11-30T10:00:00') });
  const r1 = await p.addAlerts([{ id: 'n-9527-1', level: 'major', title: '9527 Pearce', body: 'Asbestos in the board, could not fit the RCD', jn: '9527', staff: 'Pearce', url: 'https://office.aroflo.com/ims/Site/Service/workrequest/index.cfm?wrCoded=X' }], 'hub', 'https://r.example');
  assert.deepEqual(r1.pushed, ['n-9527-1']);
  assert.equal(sent.length, 1);
  const msg = await ph.decrypt(sent[0].o.body);
  assert.equal(msg.title, '9527 Pearce');
  assert.equal(msg.badge, 1);
  const r2 = await p.addAlerts([{ id: 'n-9541-1', level: 'minor', body: 'Fan left in the van' }], 'hub', 'https://r.example');
  assert.deepEqual(r2.pushed, []);
  assert.equal(r2.held, 1);
  assert.equal(sent.length, 1, 'minor not pushed');
  assert.equal(storage.alarm, BNE('2026-11-30T12:00:00'), 'roundup armed for 12pm');
  const d = await p.digest('https://r.example');
  assert.equal(d.sent, 1);
  const rd = await ph.decrypt(sent[1].o.body);
  assert.equal(rd.title, '1 thing to check');
  assert.match(rd.body, /Fan left in the van/);
  assert.equal((await p.digest('https://r.example')).sent, 0, 'nothing held twice');
  t.mock.timers.reset();
});

test('out of hours a major waits for the 7am summary', async (t) => {
  const { p, sent, storage } = await setup();
  t.mock.timers.enable({ apis: ['Date'], now: BNE('2026-11-30T21:00:00') });
  await p.addAlerts([{ id: 'n-1-a', level: 'major', body: 'Burnt neutral at the meter' }, { id: 'n-1-b', level: 'minor', body: 'Rail seized, listed for reconnect' }], 'hub', 'o');
  assert.equal(sent.length, 0);
  assert.equal(storage.alarm, BNE('2026-12-01T07:00:00'));
  await p.tick('n-1-b', true);
  t.mock.timers.setTime(BNE('2026-12-01T07:00:00'));
  const d = await p.digest('o');
  assert.equal(d.sent, 1);
  assert.equal(sent.length, 1, 'one summary, and the ticked one left out');
  t.mock.timers.reset();
});

test('edits, ticks, photos and what the lists hold', async () => {
  const { p } = await setup();
  await p.addAlerts([{ id: 'n-2-a', level: 'minor', body: 'first', url: 'https://evil.example.com/x', jn: '95x27' }], 'hub', 'o');
  let x = (await p.alerts())[0];
  assert.equal(x.url, undefined, 'only AroFlo links ride along');
  assert.equal(x.jn, '9527');
  await p.tick('n-2-a', true);
  await p.addAlerts([{ id: 'n-2-a', level: 'minor', body: 'first' }], 'hub', 'o');
  assert.equal((await p.alerts())[0].done, true, 'the same text again stays ticked');
  await p.addAlerts([{ id: 'n-2-a', level: 'major', body: 'now says burnt', renew: true }], 'hub', 'o');
  x = (await p.alerts())[0];
  assert.equal(x.done, false, 'an edit with something new comes back');
  assert.equal(x.level, 'major');
  // photos: JPEG only, 400 KB tops, gone when ticked
  assert.match(await p.putPhoto('n-2-a', Buffer.from('not a jpeg at all'.repeat(20)).toString('base64')), /only JPEG/);
  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(1000, 7)]);
  assert.equal(await p.putPhoto('n-2-a', jpeg.toString('base64')), null);
  assert.ok(await p.getPhoto('n-2-a'));
  assert.match(await p.putPhoto('n-2-a', Buffer.concat([jpeg, Buffer.alloc(500 * 1024)]).toString('base64')), /under 400 KB/);
  await p.tick('n-2-a', true);
  assert.equal(await p.getPhoto('n-2-a'), null, 'photo gone once ticked');
  assert.equal(await p.putPhoto('nope', jpeg.toString('base64')), 'no such alert');
  // bad ids and empty bodies are dropped
  const r = await p.addAlerts([{ id: 'x', body: 'short id' }, { id: 'good-id', body: '   ' }, { id: '../../etc', body: 'bad' }], 'hub', 'o');
  assert.equal(r.added, 0);
});

test('relay routes: the Hub adds and reads alerts with its key, the phone ticks with its own, phone_notify lands in the list', async () => {
  const storage = memStorage();
  const h = new Hub({ storage }, {});
  const sent = [];
  h.push.fetch = async (u, o) => { sent.push(u); return { status: 201 }; };
  const O = 'https://relay.example.workers.dev';
  const go = (p, init) => h.fetch(new Request(O + p, init));
  const hubKey = 'h'.repeat(64);
  await storage.put('hubkey', Buffer.from(await subtle.digest('SHA-256', new TextEncoder().encode(hubKey))).toString('hex'));
  const H = { authorization: 'Bearer ' + hubKey, 'content-type': 'application/json' };
  assert.equal((await go('/hub/alerts')).status, 401, 'no key, no list');
  assert.equal((await go('/hub/alerts', { headers: { authorization: 'Bearer ' + 'x'.repeat(64) } })).status, 401);
  // a phone
  const t = await h.rpc('tools/call', { name: 'phone_pair', arguments: {} }, O);
  const code = /code ([0-9A-F]{8})/.exec(t.content[0].text)[1];
  const ph = await phone();
  let r = await go('/push/subscribe', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ code, sub: { endpoint: 'https://web.push.apple.com/QWxs', keys: ph.keys } }) });
  const tok = (await r.json()).token;
  const P = { authorization: 'Bearer ' + tok, 'content-type': 'application/json' };
  // the Hub adds one
  r = await go('/hub/alerts', { method: 'POST', headers: H, body: JSON.stringify({ items: [{ id: 'n-9527-9', level: 'minor', title: '9527 Braydon', body: 'REC panels, cost check' }] }) });
  assert.equal((await r.json()).added, 1);
  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(800, 3)]).toString('base64');
  r = await go('/hub/alerts/photo', { method: 'POST', headers: H, body: JSON.stringify({ id: 'n-9527-9', image: jpeg }) });
  assert.equal(r.status, 200);
  // the phone sees it, its photo, and ticks it
  r = await go('/push/alerts', { headers: P });
  const items = (await r.json()).items;
  assert.equal(items[0].id, 'n-9527-9');
  assert.equal(items[0].photo, true);
  assert.equal((await go('/push/photo?id=n-9527-9')).status, 401, 'photos need the phone key');
  r = await go('/push/photo?id=n-9527-9', { headers: P });
  assert.equal(r.headers.get('content-type'), 'image/jpeg');
  r = await go('/push/tick', { method: 'POST', headers: P, body: JSON.stringify({ id: 'n-9527-9', done: true }) });
  assert.equal((await r.json()).ok, true);
  // the Hub reads the tick back
  r = await go('/hub/alerts', { headers: H });
  assert.equal((await r.json()).items[0].done, true);
  // Claude's note goes in the list and straight to the phone
  const n = await h.rpc('tools/call', { name: 'phone_notify', arguments: { title: 'Close-outs ready', body: '3 jobs ready' } }, O);
  assert.equal(n.isError, false);
  r = await go('/push/alerts', { headers: P });
  assert.equal((await r.json()).items.filter((x) => x.src === 'claude').length, 1);
  // a phone can't use the Hub's routes, and the Hub's key can't read the phone side
  assert.equal((await go('/hub/alerts', { headers: P })).status, 401);
  // the roundup alarm runs without throwing
  await h.alarm();
  // the page and its code
  assert.match(await (await go('/push')).text(), /Hub Link/);
  const { APP, SW } = await import('../src/push.js');
  new Function(APP);
  new Function(SW);
});
