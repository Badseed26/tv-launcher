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

  async recent() {
    const now = Date.now();
    return ((await this.storage.get('notices')) || []).filter((n) => now - Date.parse(n.at) < KEEP_MS);
  }

  async limited() {
    const now = Date.now();
    const sent = ((await this.storage.get('sentTimes')) || []).filter((t) => now - t < 3600000);
    if (sent.length >= PER_HOUR) return true;
    sent.push(now);
    await this.storage.put('sentTimes', sent);
    return false;
  }

  // Sends a notice to every phone (or one). Gone phones (404/410 from their push service) are dropped.
  async send({ title, body, tag }, origin, onlyId) {
    const notice = { title: clip(title, 60) || 'Hub', body: clip(body, 240), tag: clip(tag, 40) || undefined, at: new Date().toISOString() };
    if (!notice.body) throw new Error('Nothing to send');
    let list = await this.phones();
    const targets = list.filter((x) => !onlyId || x.id === onlyId);
    if (!targets.length) return { sent: 0, phones: 0, note: 'No phone is set up yet: make a code with phone_pair and open ' + origin + '/push on the phone' };
    if (await this.limited()) throw new Error('Too many notifications this hour (' + PER_HOUR + ' tops), not sent');
    if (!onlyId) {
      const l = [notice, ...(await this.recent())].slice(0, KEEP);
      await this.storage.put('notices', l);
    }
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

export const PAGE = '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">' +
  '<meta name="apple-mobile-web-app-capable" content="yes"><meta name="apple-mobile-web-app-title" content="Hub alerts"><meta name="theme-color" content="#0b0f17">' +
  '<link rel="manifest" href="/push/manifest.json"><link rel="icon" href="/push/icon.svg"><link rel="apple-touch-icon" href="/push/icon.svg">' +
  '<title>Hub alerts</title><style>' + CSS + '</style></head><body><main>' +
  '<h1>Hub alerts</h1><p id="lead">Notifications from Claude and your Hub, straight to this phone.</p>' +
  '<div class="box" id="setup" hidden><p id="ios" hidden>On an iPhone: tap Share, then <b>Add to Home Screen</b>, and open Hub alerts from there first. Notifications only work from the home screen.</p>' +
  '<p>Ask Claude for a phone code, put it in and tap Turn on.</p><input id="code" autocomplete="one-time-code" maxlength="8" placeholder="CODE">' +
  '<button id="on">Turn on notifications</button><p id="msg"></p></div>' +
  '<div class="box" id="live" hidden><p class="ok" id="state">Notifications are on for this phone.</p><button class="ghost" id="test">Send a test</button><p id="tmsg"></p></div>' +
  '<div class="box" id="recent" hidden><p>Latest</p><ul id="list"></ul></div>' +
  '<p style="text-align:center;font-size:11px;color:#4b5363;margin-top:18px">page v3</p></main><script src="/push/app.js?v=3"></script></body></html>';

export const APP = `(function(){
'use strict';
var $=function(i){return document.getElementById(i);};
var tok=null;try{tok=localStorage.getItem('hubPushToken');}catch(e){}
var ios=/iphone|ipad|ipod/i.test(navigator.userAgent),standalone=window.navigator.standalone||matchMedia('(display-mode: standalone)').matches;
function say(el,t,bad){el.textContent=t;el.className=bad?'err':'';}
function u8(s){var t=s.replace(/-/g,'+').replace(/_/g,'/');var b=atob(t+'==='.slice((t.length+3)%4));var o=new Uint8Array(b.length);for(var i=0;i<b.length;i++)o[i]=b.charCodeAt(i);return o;}
function when(s){var d=new Date(s);return d.toLocaleString('en-AU',{weekday:'short',hour:'numeric',minute:'2-digit',day:'numeric',month:'numeric'});}
function show(){
  var has=!!tok&&('Notification' in window)&&Notification.permission==='granted';
  $('setup').hidden=has;$('live').hidden=!has;$('ios').hidden=!(ios&&!standalone);
  if(!has)return;
  fetch('/push/recent',{headers:{authorization:'Bearer '+tok},cache:'no-store'}).then(function(r){
    if(r.status===401){tok=null;try{localStorage.removeItem('hubPushToken');}catch(e){}show();return null;}
    return r.json();
  }).then(function(j){
    if(!j)return;var l=$('list');l.textContent='';
    (j.notices||[]).forEach(function(n){var li=document.createElement('li'),b=document.createElement('b'),s=document.createElement('span'),m=document.createElement('small');
      b.textContent=n.title;s.textContent=n.body;m.textContent=when(n.at);li.appendChild(b);li.appendChild(s);li.appendChild(m);l.appendChild(li);});
    $('recent').hidden=!(j.notices||[]).length;
  }).catch(function(){});
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
    if(p!=='granted')throw new Error('Notifications are '+p+'. Turn them on for Hub alerts in Settings, Notifications, then try again.');
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
  fetch('/push/test',{method:'POST',headers:{authorization:'Bearer '+tok}}).then(function(r){return r.json().then(function(j){if(!r.ok)throw new Error(j.error||r.status);return j;});})
  .then(function(j){say(m,j.sent?'Sent. It should pop up in a few seconds.':'Not sent: '+JSON.stringify(j.failed||j.note||''),!j.sent);})
  .catch(function(e){say(m,String(e&&e.message||e),true);});
});
document.addEventListener('visibilitychange',function(){if(document.visibilityState==='visible')show();});
show();
})();`;

export const SW = `self.addEventListener('install',function(e){self.skipWaiting();});
self.addEventListener('activate',function(e){e.waitUntil(self.clients.claim());});
self.addEventListener('push',function(e){
  var n={title:'Hub',body:'Something needs you'};
  try{n=e.data?e.data.json():n;}catch(x){try{n.body=e.data.text();}catch(y){}}
  e.waitUntil(self.registration.showNotification(String(n.title||'Hub').slice(0,60),{body:String(n.body||'').slice(0,240),tag:n.tag||undefined,renotify:!!n.tag,icon:'/push/icon.svg',badge:'/push/icon.svg',data:{url:'/push'}}));
});
self.addEventListener('notificationclick',function(e){
  e.notification.close();
  e.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(function(l){
    for(var i=0;i<l.length;i++){if(l[i].url.indexOf('/push')>=0&&'focus' in l[i])return l[i].focus();}
    return self.clients.openWindow('/push');
  }));
});`;

export const MANIFEST = JSON.stringify({ name: 'Hub alerts', short_name: 'Hub alerts', start_url: '/push', scope: '/push', display: 'standalone',
  background_color: '#0b0f17', theme_color: '#0b0f17', icons: [{ src: '/push/icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' }] });

export const ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#6f8cff"/><stop offset="1" stop-color="#8a5bff"/></linearGradient></defs>' +
  '<rect width="96" height="96" rx="22" fill="#0b0f17"/><path d="M48 20c-11 0-19 8-19 19v13l-6 9h50l-6-9V39c0-11-8-19-19-19zm-7 45a7 7 0 0 0 14 0z" fill="url(#g)"/></svg>';
