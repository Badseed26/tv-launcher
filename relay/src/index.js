// EC TV relay (22/11). One Durable Object ("hub") holds everything:
// - OAuth for Claude's custom connector: dynamic client registration, a sign in page with Ethan's own password
//   (OWNER_PASSWORD secret), PKCE codes, access and refresh tokens (stored hashed).
// - The MCP endpoint (/mcp, JSON over HTTP): tools that see and drive the TV and run its api jobs.
// - The TV's side: it pairs once with a code Claude makes (tv_pair), then keeps a long poll open (/tv/poll) and posts
//   answers back (/tv/result). Nothing on the home network is opened up; the TV only ever dials out.
// Every safety block lives on the TV (job code and the phone page actions), so this relay can't get round them.

export default {
  async fetch(req, env) {
    return env.HUB.get(env.HUB.idFromName('hub')).fetch(req);
  }
};

const enc = new TextEncoder();
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
const sha = async (s) => hex(await crypto.subtle.digest('SHA-256', enc.encode(s)));
const rand = (n) => hex(crypto.getRandomValues(new Uint8Array(n)));
const b64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const json = (o, status = 200, extra = {}) => new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...extra } });
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
// Only Claude's own callback (and localhost, for testing) can get a sign in code
const REDIRECT_OK = /^(https:\/\/(claude\.ai|claude\.com|[a-z0-9-]+\.claude\.ai|[a-z0-9-]+\.claude\.com)\/|http:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/)/;
const ACCESS_TTL = 24 * 3600, REFRESH_TTL = 60 * 24 * 3600;

const TOOLS = [
  { name: 'tv_screen', description: 'A picture of what the TV app has on screen right now (home, the Filer, or the Runner page a job or web tab is on). Other apps and HDMI only give their name. Look after actions to check what happened.',
    inputSchema: { type: 'object', properties: {} } },
  { name: 'tv_tap', description: 'Tap the TV screen at a spot on the picture from tv_screen, as fractions of its width and height (0 to 1). Works on the TV app\'s own screens only.',
    inputSchema: { type: 'object', properties: { x: { type: 'number' }, y: { type: 'number' } }, required: ['x', 'y'] } },
  { name: 'tv_type', description: 'Type text into whatever box has focus on the TV app\'s screen (Filer pages).',
    inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } },
  { name: 'tv_key', description: 'Press a key on the TV app: back, enter, tab, backspace, escape, up, down, left, right, reload.',
    inputSchema: { type: 'object', properties: { key: { type: 'string' } }, required: ['key'] } },
  { name: 'tv_scroll', description: 'Scroll the TV app\'s page, dy in pixels (negative is up).',
    inputSchema: { type: 'object', properties: { dy: { type: 'number' } }, required: ['dy'] } },
  { name: 'tv_status', description: 'The TV\'s status: build, screen, Filer page states, last mail check, Runner state.',
    inputSchema: { type: 'object', properties: {} } },
  { name: 'tv_filer', description: 'Filer and TV buttons from the phone page: open, close, reload, check (mail check), wake, update (install the newest build), or pull:<YYYY-MM-DD> (inbox pull for that day).',
    inputSchema: { type: 'object', properties: { c: { type: 'string' } }, required: ['c'] } },
  { name: 'tv_view', description: 'Which Filer page shows on the TV: outlook, aroflo, or empty for the Filer screen.',
    inputSchema: { type: 'object', properties: { s: { type: 'string' } }, required: ['s'] } },
  { name: 'tv_job', description: 'Run one of the TV\'s api jobs (same as the Todoist remote, see the tv-launcher CLAUDE.md): aroflo.* (search, task, calendar, files, file, run, note.add, note.edit, siteinfo.add, schedule.add, schedule.move), outlook.* (read only: check, search, read, attachment, run), web.* tabs (open, look, text, press, fill, key, scroll, wait, shot, steps, upload, ambrose, run, close), movie.* (find, open, apps: use tv_movie) and tv.* (ping, queue, cancel, log, shots, safe, wake). Waits for the answer (up to wait seconds, 100 by default); if it\'s still going, call tv_wait with the id. Pictures from the job come back as images.',
    inputSchema: { type: 'object', properties: {
      do: { type: 'string' }, args: { type: 'object' }, dry: { type: 'boolean' }, key: { type: 'string', description: 'for writes: the same key twice only runs once' },
      branch: { type: 'string' }, id: { type: 'string' }, wait: { type: 'number' } }, required: ['do'] } },
  { name: 'tv_movie', description: 'Find a movie or show for Ethan to watch on the TV. Looks it up on JustWatch (what\'s streaming where in Australia), then opens it on a streaming app he\'s logged into, on the movie\'s page ready for him to press Play. If it\'s not on any of his subscriptions but can be rented on one of his apps, it opens the cheapest rent page there (he presses Rent himself, the TV never pays). The answer says where it is (where: app, kind, price, onTv, loggedIn), what it did (plan, opened, how) and which subscriptions it\'s on that he hasn\'t got (notLoggedIn): tell him that plainly. open:false only looks. app picks the app. If otherMatches shows it might be a different title, check with him. apps sets the streaming apps he\'s logged into (a list of names), rent the ones he rents from; apps:[] with nothing else just lists them and the apps on the TV. If the app asks who\'s watching it picks his profile (set with profile) and sends the movie again; profilePicker in the answer means it couldn\'t. Then use tv_stream to check it\'s on the movie\'s page.',
    inputSchema: { type: 'object', properties: {
      q: { type: 'string', description: 'the movie or show name, year optional' }, year: { type: 'number' }, type: { type: 'string', description: 'movie or show' },
      app: { type: 'string' }, open: { type: 'boolean', description: 'default true' },
      apps: { type: 'array', items: { type: 'string' } }, rent: { type: 'array', items: { type: 'string' } },
      profile: { description: 'his profile name in the streaming apps, a string for all of them or {"Netflix":"Ethan"} per app' } } } },
  { name: 'tv_stream', description: 'Drive the streaming app on the TV (Netflix, Stan, Disney+ and so on) like the remote, to get past profile pickers, check it\'s on the movie\'s page, or search in the app. look gives the screen as text with ref numbers (no pictures), press {ref or text}, key {back, home, enter, up, down, left, right}, type {text} into the search box. Every answer has a fresh look. It won\'t press Play, Resume, Watch, Rent, Buy, Subscribe or sign out (marked Ethan\'s in the look): leave the movie ready and tell him. While a movie\'s actually playing it refuses everything, and if a press starts playback it backs out.',
    inputSchema: { type: 'object', properties: { do: { type: 'string', enum: ['look', 'press', 'key', 'type'] },
      ref: { type: 'number' }, text: { type: 'string' }, key: { type: 'string' } }, required: ['do'] } },
  { name: 'tv_wait', description: 'Wait more for an api job that tv_job said is still running (by its id).',
    inputSchema: { type: 'object', properties: { id: { type: 'string' }, wait: { type: 'number' } }, required: ['id'] } },
  { name: 'tv_pictures', description: 'The newest pictures an api job took (n, 1 to 6), by its id.',
    inputSchema: { type: 'object', properties: { id: { type: 'string' }, n: { type: 'number' } }, required: ['id'] } },
  { name: 'tv_pair', description: 'Make a one-time code (15 min) for pairing the TV with this relay. Send the line it gives on the Todoist TV remote.',
    inputSchema: { type: 'object', properties: {} } }
];

export class Hub {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.cmds = [];      // commands waiting for the TV
    this.pollers = [];   // the TV's open polls
    this.waiting = new Map(); // command id -> {res, rej, t}
    this.lastPoll = 0;
  }

  async fetch(req) {
    const u = new URL(req.url), p = u.pathname, m = req.method, origin = u.origin;
    try {
      if (p === '/' && m === 'GET') return new Response('EC TV relay', { headers: { 'content-type': 'text/plain' } });
      if (p.startsWith('/.well-known/oauth-protected-resource')) return json({ resource: origin + '/mcp', authorization_servers: [origin], bearer_methods_supported: ['header'] });
      if (p.startsWith('/.well-known/oauth-authorization-server') || p.startsWith('/.well-known/openid-configuration')) return json({
        issuer: origin, authorization_endpoint: origin + '/authorize', token_endpoint: origin + '/token', registration_endpoint: origin + '/register',
        response_types_supported: ['code'], grant_types_supported: ['authorization_code', 'refresh_token'], code_challenge_methods_supported: ['S256'],
        token_endpoint_auth_methods_supported: ['none', 'client_secret_post', 'client_secret_basic'], scopes_supported: ['tv'] });
      if (p === '/register' && m === 'POST') return this.register(req);
      if (p === '/authorize') return m === 'POST' ? this.authorizePost(req, origin) : this.authorizeGet(u);
      if (p === '/token' && m === 'POST') return this.token(req);
      if (p === '/mcp') {
        if (m !== 'POST') return new Response(null, { status: 405, headers: { allow: 'POST' } });
        if (!(await this.bearer(req))) return json({ error: 'unauthorized' }, 401, { 'www-authenticate': 'Bearer resource_metadata="' + origin + '/.well-known/oauth-protected-resource"' });
        return this.mcp(req, origin);
      }
      if (p === '/tv/pair' && m === 'POST') return this.tvPair(req);
      if (p === '/tv/poll' && m === 'GET') return (await this.tvAuth(req)) ? this.tvPoll(req) : json({ error: 'bad key' }, 401);
      if (p === '/tv/result' && m === 'POST') return (await this.tvAuth(req)) ? this.tvResult(req) : json({ error: 'bad key' }, 401);
      return new Response('Not here', { status: 404 });
    } catch (e) {
      return json({ error: 'server_error', error_description: String(e && e.message || e) }, 500);
    }
  }

  // ------------------------------------------------------------------ OAuth
  async register(req) {
    const b = await req.json().catch(() => ({}));
    const uris = Array.isArray(b.redirect_uris) ? b.redirect_uris.filter((x) => typeof x === 'string') : [];
    if (!uris.length || !uris.every((x) => REDIRECT_OK.test(x))) return json({ error: 'invalid_redirect_uri', error_description: 'only Claude can connect to this relay' }, 400);
    const id = 'c_' + rand(12);
    const client = { client_id: id, client_name: String(b.client_name || 'Claude').slice(0, 80), redirect_uris: uris, token_endpoint_auth_method: 'none',
      grant_types: ['authorization_code', 'refresh_token'], response_types: ['code'], client_id_issued_at: Math.floor(Date.now() / 1000) };
    await this.ctx.storage.put('client:' + id, client);
    return json(client, 201);
  }

  async checkAuthorize(q) {
    const client = await this.ctx.storage.get('client:' + q.client_id);
    if (!client) return 'unknown client, remove the connector and add it again';
    if (!client.redirect_uris.includes(q.redirect_uri)) return 'that redirect address isn\'t registered';
    if (q.response_type !== 'code') return 'response_type must be code';
    if (!q.code_challenge || q.code_challenge_method !== 'S256') return 'PKCE (S256) is required';
    return null;
  }

  page(body, status = 200) {
    return new Response('<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
      '<title>EC TV relay</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(900px 500px at 10% -10%,#1d2b55,transparent 60%),radial-gradient(700px 400px at 110% 0,#3a1f5c,transparent 55%),#0b0f17;color:#e8ecf4;font:16px -apple-system,Segoe UI,sans-serif}' +
      'form,.box{width:min(380px,90vw);background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.1);border-radius:16px;padding:22px}h1{font-size:19px;margin:0 0 6px}p{color:#93a0b8;font-size:14px}' +
      'input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid rgba(255,255,255,.15);background:rgba(0,0,0,.3);color:#fff;font-size:16px;margin:8px 0 14px}' +
      'button{width:100%;padding:12px;border:0;border-radius:10px;background:linear-gradient(135deg,#6f8cff,#8a5bff);color:#fff;font-weight:600;font-size:16px}.err{color:#ff7a7a}</style></head><body>' +
      body + '</body></html>', { status, headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'x-frame-options': 'DENY' } });
  }

  async authorizeGet(u, err) {
    const q = Object.fromEntries(u.searchParams);
    const bad = await this.checkAuthorize(q);
    if (bad) return this.page('<div class="box"><h1>Can\'t connect</h1><p class="err">' + esc(bad) + '</p></div>', 400);
    if (!this.env.OWNER_PASSWORD) return this.page('<div class="box"><h1>Not set up yet</h1><p>The relay has no password yet.</p></div>', 503);
    const client = await this.ctx.storage.get('client:' + q.client_id);
    const hidden = ['client_id', 'redirect_uri', 'state', 'code_challenge', 'code_challenge_method', 'response_type', 'scope', 'resource']
      .map((k) => '<input type="hidden" name="' + k + '" value="' + esc(q[k] || '') + '">').join('');
    return this.page('<form method="post" action="/authorize"><h1>Let ' + esc(client.client_name) + ' use the TV</h1>' +
      '<p>Your EC TV relay password.</p>' + (err ? '<p class="err">' + esc(err) + '</p>' : '') +
      '<input type="password" name="password" autocomplete="current-password" autofocus>' + hidden + '<button>Allow</button></form>');
  }

  async authorizePost(req, origin) {
    const f = Object.fromEntries((await req.formData()).entries());
    const bad = await this.checkAuthorize(f);
    if (bad) return this.page('<div class="box"><h1>Can\'t connect</h1><p class="err">' + esc(bad) + '</p></div>', 400);
    const fails = (await this.ctx.storage.get('fails')) || { n: 0, since: 0 };
    if (Date.now() - fails.since > 15 * 60000) { fails.n = 0; fails.since = Date.now(); }
    const u = new URL(origin + '/authorize');
    for (const k of ['client_id', 'redirect_uri', 'state', 'code_challenge', 'code_challenge_method', 'response_type', 'scope', 'resource']) if (f[k]) u.searchParams.set(k, f[k]);
    if (fails.n >= 5) return this.authorizeGet(u, 'Too many wrong tries, wait 15 minutes.');
    const a = enc.encode(await sha(String(f.password || ''))), b = enc.encode(await sha(String(this.env.OWNER_PASSWORD || '')));
    const ok = this.env.OWNER_PASSWORD && crypto.subtle.timingSafeEqual(a, b);
    if (!ok) { fails.n++; await this.ctx.storage.put('fails', fails); return this.authorizeGet(u, 'Wrong password.'); }
    await this.ctx.storage.put('fails', { n: 0, since: Date.now() });
    const code = rand(24);
    await this.ctx.storage.put('code:' + code, { client_id: f.client_id, redirect_uri: f.redirect_uri, challenge: f.code_challenge, exp: Date.now() + 5 * 60000 });
    const to = new URL(f.redirect_uri);
    to.searchParams.set('code', code);
    if (f.state) to.searchParams.set('state', f.state);
    return Response.redirect(to.toString(), 302);
  }

  async issue(client_id) {
    const at = rand(32), rt = rand(32);
    await this.ctx.storage.put('at:' + await sha(at), { client_id, exp: Date.now() + ACCESS_TTL * 1000 });
    await this.ctx.storage.put('rt:' + await sha(rt), { client_id, exp: Date.now() + REFRESH_TTL * 1000 });
    return json({ access_token: at, token_type: 'Bearer', expires_in: ACCESS_TTL, refresh_token: rt, scope: 'tv' });
  }

  async token(req) {
    const ct = req.headers.get('content-type') || '';
    const f = ct.includes('json') ? await req.json() : Object.fromEntries((await req.formData()).entries());
    let client_id = f.client_id;
    const basic = req.headers.get('authorization');
    if (!client_id && basic && /^Basic /i.test(basic)) client_id = atob(basic.slice(6)).split(':')[0];
    if (f.grant_type === 'authorization_code') {
      const c = await this.ctx.storage.get('code:' + f.code);
      await this.ctx.storage.delete('code:' + f.code);
      if (!c || c.exp < Date.now()) return json({ error: 'invalid_grant' }, 400);
      if (c.client_id !== client_id || c.redirect_uri !== f.redirect_uri) return json({ error: 'invalid_grant' }, 400);
      const chal = b64url(await crypto.subtle.digest('SHA-256', enc.encode(String(f.code_verifier || ''))));
      if (chal !== c.challenge) return json({ error: 'invalid_grant', error_description: 'PKCE check failed' }, 400);
      return this.issue(client_id);
    }
    if (f.grant_type === 'refresh_token') {
      const k = 'rt:' + await sha(String(f.refresh_token || ''));
      const r = await this.ctx.storage.get(k);
      await this.ctx.storage.delete(k); // rotate
      if (!r || r.exp < Date.now() || (client_id && r.client_id !== client_id)) return json({ error: 'invalid_grant' }, 400);
      return this.issue(r.client_id);
    }
    return json({ error: 'unsupported_grant_type' }, 400);
  }

  async bearer(req) {
    const h = req.headers.get('authorization') || '';
    if (!/^Bearer /i.test(h)) return false;
    const t = await this.ctx.storage.get('at:' + await sha(h.slice(7).trim()));
    return !!(t && t.exp > Date.now());
  }

  // ------------------------------------------------------------------ the TV
  async tvAuth(req) {
    const h = req.headers.get('authorization') || '';
    const want = await this.ctx.storage.get('tvkey');
    return !!(want && /^Bearer /i.test(h) && (await sha(h.slice(7).trim())) === want);
  }

  async tvPair(req) {
    const b = await req.json().catch(() => ({}));
    const code = String(b.code || '').toUpperCase();
    const p = await this.ctx.storage.get('pair');
    if (!p || p.exp < Date.now() || p.tries >= 5) return json({ error: 'no pairing code waiting (make a new one with tv_pair)' }, 403);
    if (code !== p.code) { p.tries = (p.tries || 0) + 1; await this.ctx.storage.put('pair', p); return json({ error: 'wrong code' }, 403); }
    await this.ctx.storage.delete('pair');
    const key = rand(32);
    await this.ctx.storage.put('tvkey', await sha(key));
    return json({ key });
  }

  tvPoll(req) {
    this.lastPoll = Date.now();
    if (this.cmds.length) return json(this.cmds.shift());
    return new Promise((res) => {
      const me = { res, signal: req.signal };
      me.t = setTimeout(() => { this.pollers = this.pollers.filter((x) => x !== me); res(new Response(null, { status: 204 })); }, 25000);
      this.pollers.push(me);
    });
  }

  pump() {
    while (this.cmds.length && this.pollers.length) {
      const p = this.pollers.shift();
      if (p.signal && p.signal.aborted) { clearTimeout(p.t); continue; }
      clearTimeout(p.t);
      this.lastPoll = Date.now();
      p.res(json(this.cmds.shift()));
    }
  }

  async tvResult(req) {
    const b = await req.json();
    const w = this.waiting.get(b.id);
    if (w) { this.waiting.delete(b.id); clearTimeout(w.t); w.res(b.result || {}); }
    return json({ ok: true });
  }

  send(cmd, args, ms) {
    if (!this.pollers.length && Date.now() - this.lastPoll > 60000)
      return Promise.reject(new Error('The TV isn\'t connected to the relay right now (off at the wall, no internet, or not paired). Use the Todoist remote instead.'));
    const id = crypto.randomUUID();
    const p = new Promise((res, rej) => {
      const t = setTimeout(() => { this.waiting.delete(id); this.cmds = this.cmds.filter((c) => c.id !== id); rej(new Error('The TV didn\'t answer in ' + Math.round(ms / 1000) + ' s')); }, ms);
      this.waiting.set(id, { res, rej, t });
    });
    this.cmds.push({ id, cmd, args });
    this.pump();
    return p;
  }

  // ------------------------------------------------------------------ MCP
  async mcp(req, origin) {
    const body = await req.json().catch(() => null);
    if (!body) return json({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } }, 400);
    const one = async (msg) => {
      if (!msg || msg.id === undefined || msg.id === null) return null; // notifications
      try { return { jsonrpc: '2.0', id: msg.id, result: await this.rpc(msg.method, msg.params || {}, origin) }; }
      catch (e) { return { jsonrpc: '2.0', id: msg.id, error: { code: e.code || -32603, message: String(e.message || e) } }; }
    };
    if (Array.isArray(body)) {
      const out = (await Promise.all(body.map(one))).filter(Boolean);
      return out.length ? json(out) : new Response(null, { status: 202 });
    }
    const r = await one(body);
    return r ? json(r) : new Response(null, { status: 202 });
  }

  async rpc(method, params, origin) {
    if (method === 'initialize') return {
      protocolVersion: params.protocolVersion || '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'EC TV', version: '1.0' },
      instructions: 'Ethan\'s TV as Claude\'s computer (tv-launcher repo, CLAUDE.md has the whole api). Use tv_job for AroFlo, Outlook (read only) and web tabs; tv_screen to see the page; tv_movie to find a movie and open it on his streaming apps, tv_stream to drive that app\'s menus. Never send, submit, pay, delete, or touch site safety sign ins: the TV blocks those anyway.' };
    if (method === 'ping') return {};
    if (method === 'tools/list') return { tools: TOOLS };
    if (method !== 'tools/call') { const e = new Error('Method not found'); e.code = -32601; throw e; }
    const a = params.arguments || {}, name = params.name;
    try {
      return await this.tool(name, a, origin);
    } catch (e) {
      return { content: [{ type: 'text', text: String(e.message || e) }], isError: true };
    }
  }

  async tool(name, a, origin) {
    const out = (r) => {
      const imgs = Array.isArray(r.images) ? r.images : [];
      delete r.images;
      const content = [{ type: 'text', text: JSON.stringify(r) }];
      for (const b of imgs.slice(0, 6)) content.push({ type: 'image', data: b, mimeType: 'image/jpeg' });
      return { content, isError: r.ok === false };
    };
    switch (name) {
      case 'tv_pair': {
        const code = rand(4).toUpperCase();
        await this.ctx.storage.put('pair', { code, exp: Date.now() + 15 * 60000, tries: 0 });
        return { content: [{ type: 'text', text: 'Send this on the Todoist TV remote within 15 minutes: link ' + origin + ' ' + code }] };
      }
      case 'tv_screen': return out(await this.send('screen', {}, 20000));
      case 'tv_tap': return out(await this.send('tap', { x: a.x, y: a.y }, 15000));
      case 'tv_type': return out(await this.send('type', { text: a.text }, 15000));
      case 'tv_key': return out(await this.send('key', { key: a.key }, 15000));
      case 'tv_scroll': return out(await this.send('scroll', { dy: a.dy }, 15000));
      case 'tv_status': return out(await this.send('status', {}, 15000));
      case 'tv_filer': return out(await this.send('cmd', { c: a.c }, 20000));
      case 'tv_view': return out(await this.send('view', { s: a.s || '' }, 15000));
      case 'tv_job': {
        const wait = Math.max(5, Math.min(240, Number(a.wait) || 100));
        const req = { id: a.id || 'm' + Date.now().toString(36), do: a.do, args: a.args || {} };
        if (a.dry) req.dry = true;
        if (a.key) req.key = a.key;
        if (a.branch) req.branch = a.branch;
        const r = await this.send('api', { req, wait }, (wait + 25) * 1000);
        if (!r.id) r.id = req.id;
        return out(r);
      }
      case 'tv_movie': {
        const req = { id: 'mv' + Date.now().toString(36), args: {} };
        if (Array.isArray(a.apps) || Array.isArray(a.rent) || a.profile != null) {
          req.do = 'movie.apps';
          if (Array.isArray(a.apps) && a.apps.length) req.args.set = a.apps;
          if (Array.isArray(a.rent) && a.rent.length) req.args.rent = a.rent;
          if (a.profile != null) req.args.profile = a.profile;
        } else {
          if (!a.q) throw new Error('tv_movie needs q (the movie name), or apps to set his logged in apps');
          req.do = a.open === false ? 'movie.find' : 'movie.open';
          req.args = { q: a.q };
          if (a.year) req.args.year = a.year;
          if (a.type) req.args.type = a.type;
          if (a.app) req.args.app = a.app;
        }
        const r = await this.send('api', { req, wait: 60 }, 85000);
        if (!r.id) r.id = req.id;
        return out(r);
      }
      case 'tv_stream': {
        const req = { id: 'st' + Date.now().toString(36), do: 'movie.' + (a.do || 'look'), args: {} };
        for (const k of ['ref', 'text', 'key']) if (a[k] != null) req.args[k] = a[k];
        const r = await this.send('api', { req, wait: 30 }, 55000);
        if (!r.id) r.id = req.id;
        return out(r);
      }
      case 'tv_wait': {
        const wait = Math.max(5, Math.min(240, Number(a.wait) || 100));
        return out(await this.send('wait', { id: a.id, wait }, (wait + 25) * 1000));
      }
      case 'tv_pictures': return out(await this.send('shots', { id: a.id, n: a.n || 3 }, 30000));
      default: { const e = new Error('unknown tool ' + name); throw e; }
    }
  }
}
