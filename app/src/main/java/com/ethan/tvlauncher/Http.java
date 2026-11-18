package com.ethan.tvlauncher;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Plain web requests for the filer script (its GM_xmlhttpRequest) and for Todoist. */
final class Http {

    private Http() {}

    /**
     * Runs a request described as JSON {method, url, headers, body | b64, timeout}.
     * Returns JSON {status, statusText, headers, url, b64} or {error, timeout, message}.
     */
    static String run(String reqJson) {
        JSONObject out = new JSONObject();
        HttpURLConnection con = null;
        try {
            JSONObject r = new JSONObject(reqJson);
            String method = r.optString("method", "GET").toUpperCase();
            con = (HttpURLConnection) new URL(r.getString("url")).openConnection();
            int timeout = r.optInt("timeout", 60000);
            con.setConnectTimeout(Math.min(timeout, 30000));
            con.setReadTimeout(timeout);
            con.setInstanceFollowRedirects(true);
            con.setRequestMethod(method);
            JSONObject h = r.optJSONObject("headers");
            if (h != null) {
                Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    con.setRequestProperty(k, h.optString(k));
                }
            }
            byte[] body = null;
            if (!r.isNull("b64") && r.has("b64")) body = Base64.decode(r.getString("b64"), Base64.DEFAULT);
            else if (!r.isNull("body") && r.has("body")) body = r.getString("body").getBytes(StandardCharsets.UTF_8);
            if (body != null && !method.equals("GET")) {
                con.setDoOutput(true);
                con.setFixedLengthStreamingMode(body.length);
                OutputStream os = con.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in);
            StringBuilder hs = new StringBuilder();
            for (Map.Entry<String, List<String>> e : con.getHeaderFields().entrySet()) {
                if (e.getKey() == null) continue;
                for (String v : e.getValue()) hs.append(e.getKey()).append(": ").append(v).append("\r\n");
            }
            out.put("status", code);
            out.put("statusText", con.getResponseMessage() == null ? "" : con.getResponseMessage());
            out.put("headers", hs.toString());
            out.put("url", con.getURL().toString());
            out.put("b64", Base64.encodeToString(data, Base64.NO_WRAP));
        } catch (SocketTimeoutException e) {
            try {
                out.put("error", true);
                out.put("timeout", true);
                out.put("message", "timed out");
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            try {
                out.put("error", true);
                out.put("message", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        } finally {
            if (con != null) con.disconnect();
        }
        return out.toString();
    }

    /** Simple Todoist call. Returns the reply body, or throws. */
    static String todoist(String token, String method, String path, JSONObject body) throws Exception {
        JSONObject r = new JSONObject();
        r.put("method", method);
        r.put("url", "https://api.todoist.com/api/v1/" + path);
        JSONObject h = new JSONObject();
        h.put("Authorization", "Bearer " + token);
        h.put("Content-Type", "application/json");
        r.put("headers", h);
        if (body != null) r.put("body", body.toString());
        r.put("timeout", 20000);
        JSONObject res = new JSONObject(run(r.toString()));
        if (res.optBoolean("error")) throw new Exception(res.optString("message", "no connection"));
        int status = res.optInt("status");
        String text = new String(Base64.decode(res.optString("b64", ""), Base64.DEFAULT), StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) throw new Exception("Todoist said " + status);
        return text;
    }

    static String get(String url, int timeoutMs) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(timeoutMs);
        con.setReadTimeout(timeoutMs);
        con.setUseCaches(false);
        try {
            if (con.getResponseCode() != 200) throw new Exception("server said " + con.getResponseCode());
            return new String(readAll(con.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }
}
