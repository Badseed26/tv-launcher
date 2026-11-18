// Stand-in for Tampermonkey, so the filer script runs on the TV unchanged. The app fills in __TOKEN__, __VER__ and __SCRIPT__.
// Only this injected code knows the token, so nothing else on the page can use the app's bridge.
(function () {
  if (window.top !== window.self || window.__ecShim) return;
  var B = window.ecGmBridge;
  if (!B) return;
  window.__ecShim = true;
  window.__ecDevice = 'TV';
  var TOKEN = '__TOKEN__';
  var cbs = {}, seq = 0;
  window.__ecGmDone = function (id, res) { var cb = cbs[id]; delete cbs[id]; if (cb) cb(res); };

  function GM_getValue(k, d) { var v = B.get(TOKEN, String(k)); if (v === null || v === undefined) return d; try { return JSON.parse(v); } catch (e) { return d; } }
  function GM_setValue(k, v) { B.set(TOKEN, String(k), JSON.stringify(v === undefined ? null : v)); }
  function GM_deleteValue(k) { B.del(TOKEN, String(k)); }
  function GM_listValues() { try { return JSON.parse(B.list(TOKEN)); } catch (e) { return []; } }
  function GM_registerMenuCommand() {}
  function GM_openInTab(url) { B.openTab(TOKEN, String(url)); return { close: function () {} }; }
  var GM_info = { script: { name: 'EC Inbox Filer', version: '__VER__' } };
  var unsafeWindow = window;

  function toB64(u8) { var s = ''; for (var i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); }
  function fromB64(b) { var bin = atob(b || ''); var u = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u; }
  function bodyOf(data) {
    if (data === null || data === undefined) return Promise.resolve({});
    if (typeof data === 'string') return Promise.resolve({ text: data });
    if (data instanceof FormData || data instanceof Blob || data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
      var r = new Response(data), ct = r.headers.get('content-type');
      return r.arrayBuffer().then(function (buf) { return { b64: toB64(new Uint8Array(buf)), ct: ct }; });
    }
    return Promise.resolve({ text: String(data) });
  }
  function GM_xmlhttpRequest(o) {
    var id = 'x' + (++seq);
    bodyOf(o.data).then(function (bd) {
      var headers = {}, k;
      for (k in (o.headers || {})) headers[k] = o.headers[k];
      if (bd.ct && !Object.keys(headers).some(function (h) { return h.toLowerCase() === 'content-type'; })) headers['Content-Type'] = bd.ct;
      cbs[id] = function (res) {
        if (res.error) { if (res.timeout) { if (o.ontimeout) o.ontimeout(res); } else if (o.onerror) o.onerror(res); return; }
        var u = fromB64(res.b64), r = { status: res.status, statusText: res.statusText || '', responseHeaders: res.headers || '', finalUrl: res.url || o.url, readyState: 4 };
        if (o.responseType === 'arraybuffer') { r.response = u.buffer; r.responseText = ''; }
        else if (o.responseType === 'blob') { r.response = new Blob([u]); r.responseText = ''; }
        else { var txt = new TextDecoder('utf-8').decode(u); r.responseText = txt; r.response = txt; if (o.responseType === 'json') { try { r.response = JSON.parse(txt); } catch (e) { r.response = null; } } }
        if (o.onload) o.onload(r);
      };
      B.xhr(TOKEN, id, JSON.stringify({ method: o.method || 'GET', url: o.url, headers: headers, body: bd.text === undefined ? null : bd.text, b64: bd.b64 || null, timeout: o.timeout || 60000 }));
    }).catch(function (e) { if (o.onerror) o.onerror({ error: true, message: String(e) }); });
    return { abort: function () {} };
  }

  // ---------------- the filer script, straight from GitHub ----------------
__SCRIPT__
})();
