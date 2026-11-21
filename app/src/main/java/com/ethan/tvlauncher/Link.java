package com.ethan.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The relay link (22/11): Claude's MCP connector talks to a small Cloudflare relay, and the TV keeps a long poll open to
 * it, so Claude's tool calls reach the TV in about a second without opening anything on the home network.
 * Commands: status, screen, tap, type, key, scroll, view, cmd, api, wait, shots. They run the phone page's own actions
 * (RemoteServer.local) and the api jobs, so every block those have still applies.
 * Pairing: Claude makes a one-time code through the connector and sends "link <relay url> <code>" on the Todoist remote.
 * The TV swaps it for its own key (kept in the Keystore). The relay url is kept in app storage, never in this public repo.
 */
final class Link {
    private Link() {}

    private static final String KEY = "link_key";
    private static volatile Thread loop;
    private static volatile String state = "off";
    private static volatile long lastOk;
    private static final ExecutorService work = Executors.newFixedThreadPool(3);

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("link", Context.MODE_PRIVATE);
    }

    static String status(Context c) {
        String url = p(c).getString("url", "");
        if (url.isEmpty() || !Secrets.has(c, KEY)) return "Relay link: not paired.";
        return "Relay link: " + state + (lastOk > 0 ? ", last heard " + ((System.currentTimeMillis() - lastOk) / 1000) + " s ago" : "") + ".";
    }

    /** "link <url> <code>" on the Todoist remote. */
    static String pair(final Context c, String url, String code) {
        url = url.replaceAll("/+$", "");
        if (!url.matches("https://[a-z0-9.-]+\\.workers\\.dev")) return "Link: the relay address should be https://<name>.workers.dev";
        try {
            JSONObject r = new JSONObject();
            r.put("method", "POST");
            r.put("url", url + "/tv/pair");
            JSONObject h = new JSONObject();
            h.put("Content-Type", "application/json");
            r.put("headers", h);
            r.put("b64", Base64.encodeToString(new JSONObject().put("code", code).toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            r.put("timeout", 20000);
            JSONObject res = new JSONObject(Http.run(r.toString()));
            String body = new String(Base64.decode(res.optString("b64", ""), Base64.DEFAULT), StandardCharsets.UTF_8);
            if (res.optInt("status") != 200) return "Link: the relay said no (" + res.optInt("status") + " " + body.replaceAll("\\s+", " ").trim() + ")";
            String key = new JSONObject(body).optString("key");
            if (key.length() < 32) return "Link: the relay didn't send a key back";
            Secrets.put(c, KEY, key);
            p(c).edit().putString("url", url).apply();
            TvLog.line(c, "Link: paired with the relay");
            start(c);
            return "Link: paired. Claude's connector can reach the TV now.";
        } catch (Exception e) {
            return "Link: couldn't pair (" + e.getMessage() + ")";
        }
    }

    static String off(Context c) {
        Secrets.remove(c, KEY);
        p(c).edit().remove("url").apply();
        state = "off";
        return "Link: unpaired, the TV won't talk to the relay until it's paired again.";
    }

    /** Started by ListenService. One loop for the life of the app. */
    static synchronized void start(final Context c) {
        if (loop != null && loop.isAlive()) return;
        final Context app = c.getApplicationContext();
        loop = new Thread(new Runnable() {
            @Override
            public void run() {
                long wait = 0;
                while (true) {
                    try {
                        if (wait > 0) Thread.sleep(wait);
                        String url = p(app).getString("url", ""), key = Secrets.get(app, KEY);
                        if (url.isEmpty() || key == null) {
                            state = "off";
                            wait = 60000;
                            continue;
                        }
                        JSONObject r = new JSONObject();
                        r.put("method", "GET");
                        r.put("url", url + "/tv/poll?b=" + Updater.currentVersion(app));
                        JSONObject h = new JSONObject();
                        h.put("Authorization", "Bearer " + key);
                        r.put("headers", h);
                        r.put("timeout", 45000);
                        JSONObject res = new JSONObject(Http.run(r.toString()));
                        int st = res.optInt("status");
                        if (st == 200) {
                            lastOk = System.currentTimeMillis();
                            state = "connected";
                            wait = 0;
                            final JSONObject cmd = new JSONObject(new String(Base64.decode(res.optString("b64", ""), Base64.DEFAULT), StandardCharsets.UTF_8));
                            final String u = url, k = key;
                            work.execute(new Runnable() {
                                @Override
                                public void run() {
                                    answer(app, u, k, cmd);
                                }
                            });
                        } else if (st == 204) {
                            lastOk = System.currentTimeMillis();
                            state = "connected";
                            wait = 0;
                        } else if (st == 401 || st == 403) {
                            state = "the relay refused the TV's key (pair again)";
                            TvLog.line(app, "Link: " + state);
                            wait = 5 * 60000;
                        } else {
                            state = "can't reach the relay (" + (res.optBoolean("error") ? res.optString("message") : "status " + st) + ")";
                            wait = Math.min(60000, Math.max(5000, wait * 2));
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        state = "error: " + e.getMessage();
                        wait = Math.min(60000, Math.max(5000, wait * 2));
                    }
                }
            }
        }, "relay-link");
        loop.setDaemon(true);
        loop.start();
    }

    private static void answer(Context c, String url, String key, JSONObject cmd) {
        JSONObject out;
        try {
            out = run(c, cmd.optString("cmd"), cmd.optJSONObject("args") != null ? cmd.optJSONObject("args") : new JSONObject());
        } catch (Exception e) {
            out = new JSONObject();
            try {
                out.put("ok", false);
                out.put("err", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        try {
            JSONObject body = new JSONObject();
            body.put("id", cmd.optString("id"));
            body.put("result", out);
            JSONObject r = new JSONObject();
            r.put("method", "POST");
            r.put("url", url + "/tv/result");
            JSONObject h = new JSONObject();
            h.put("Authorization", "Bearer " + key);
            h.put("Content-Type", "application/json");
            r.put("headers", h);
            r.put("b64", Base64.encodeToString(body.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            r.put("timeout", 60000);
            Http.run(r.toString());
        } catch (Exception e) {
            TvLog.line(c, "Link: couldn't send an answer back: " + e.getMessage());
        }
    }

    private static JSONObject local(String method, String path, Map<String, String> a) throws Exception {
        Object[] r = RemoteServer.local(method, path, a);
        int code = (Integer) r[0];
        String type = (String) r[1];
        byte[] body = (byte[]) r[2];
        JSONObject o = new JSONObject();
        if (type.startsWith("image/")) {
            o.put("ok", code == 200);
            o.put("images", new JSONArray().put(Base64.encodeToString(body, Base64.NO_WRAP)));
            return o;
        }
        String t = new String(body, StandardCharsets.UTF_8);
        if (type.contains("json")) {
            try {
                JSONObject j = new JSONObject(t);
                if (!j.has("ok")) j.put("ok", code == 200);
                return j;
            } catch (Exception ignored) {
            }
        }
        o.put("ok", code == 200);
        if (code != 200) o.put("err", t);
        else o.put("text", t);
        return o;
    }

    private static Map<String, String> m(String... kv) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) a.put(kv[i], kv[i + 1]);
        return a;
    }

    private static JSONObject run(Context c, String cmd, JSONObject a) throws Exception {
        switch (cmd) {
            case "ping": {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("build", Updater.currentVersion(c));
                return o;
            }
            case "status":
                return local("GET", "/api/status", m());
            case "screen":
                return local("GET", "/api/shot", m());
            case "tap":
                return local("POST", "/api/tap", m("x", a.optString("x"), "y", a.optString("y")));
            case "type":
                return local("POST", "/api/type", m("text", a.optString("text")));
            case "key":
                return local("POST", "/api/key", m("k", a.optString("key")));
            case "scroll":
                return local("POST", "/api/scroll", m("dy", a.optString("dy", "500")));
            case "view":
                return local("POST", "/api/view", m("s", a.optString("s")));
            case "cmd":
                return local("POST", "/api/cmd", m("c", a.optString("c")));
            case "api":
                return api(c, a.optJSONObject("req"), a.optInt("wait", 170));
            case "wait":
                return waitFor(c, a.optString("id"), a.optInt("wait", 170), false);
            case "shots":
                return withShots(c, new JSONObject().put("ok", true), a.optString("id"), Math.max(1, Math.min(6, a.optInt("n", 3))));
            default:
                throw new Exception("unknown command " + cmd);
        }
    }

    /** An api job (same as the Todoist remote), waiting for its answer. */
    private static JSONObject api(Context c, JSONObject req, int wait) throws Exception {
        if (req == null || req.optString("id").isEmpty()) throw new Exception("api needs req with an id and a do");
        final String id = req.optString("id");
        final AtomicReference<JSONObject> got = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Api.onDone(id, new Api.Waiter() {
            @Override
            public void done(JSONObject result) {
                got.set(result);
                done.countDown();
            }
        });
        String reply = Api.request(c, req.toString());
        // tv.* and repeated keys answer straight away: "api <id> ok {...}"
        if (reply != null && reply.startsWith("api " + id + " ") && reply.contains("{")) {
            Api.onDone(id, null);
            JSONObject j = new JSONObject(reply.substring(reply.indexOf('{')));
            return j;
        }
        if (reply != null && !reply.contains("queued")) {
            Api.onDone(id, null);
            return new JSONObject().put("ok", false).put("err", reply);
        }
        return waitFor(c, id, wait, true, done, got, reply);
    }

    private static JSONObject waitFor(Context c, String id, int wait, boolean registered) throws Exception {
        if (id.isEmpty()) throw new Exception("wait needs the job id");
        JSONObject fin = finishedResult(c, id);
        if (fin != null) return withShots(c, fin, id, fin.optInt("pictures", 0));
        final AtomicReference<JSONObject> got = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Api.onDone(id, new Api.Waiter() {
            @Override
            public void done(JSONObject result) {
                got.set(result);
                done.countDown();
            }
        });
        return waitFor(c, id, wait, true, done, got, null);
    }

    private static JSONObject waitFor(Context c, String id, int wait, boolean reg, CountDownLatch done, AtomicReference<JSONObject> got, String note)
            throws Exception {
        boolean in = done.await(Math.max(5, Math.min(280, wait)), TimeUnit.SECONDS);
        if (!in) {
            // the job keeps going on the TV: ask again with wait {id}
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("still", "running");
            o.put("id", id);
            if (note != null) o.put("note", note);
            return o;
        }
        JSONObject r = got.get();
        return withShots(c, r, id, r.optInt("pictures", 0));
    }

    /** A finished job's answer off its saved log, or null. */
    private static JSONObject finishedResult(Context c, String id) {
        File f = new File(Api.dir(c, id), "log.txt");
        if (!f.exists()) return null;
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("result: ")) {
                    br.close();
                    return new JSONObject(line.substring(8));
                }
            }
            br.close();
        } catch (Exception ignored) {
        }
        return null;
    }

    /** A job's newest pictures added to its answer (web.shot and friends). */
    private static JSONObject withShots(Context c, JSONObject r, String id, int n) {
        if (n <= 0) return r;
        try {
            File[] shots = Api.dir(c, id).listFiles(new java.io.FilenameFilter() {
                @Override
                public boolean accept(File d, String name) {
                    return name.endsWith(".jpg");
                }
            });
            if (shots == null || shots.length == 0) return r;
            Arrays.sort(shots);
            JSONArray imgs = new JSONArray();
            for (int i = Math.max(0, shots.length - n); i < shots.length; i++) {
                java.io.FileInputStream in = new java.io.FileInputStream(shots[i]);
                byte[] b = new byte[(int) shots[i].length()];
                int got = 0;
                while (got < b.length) {
                    int k = in.read(b, got, b.length - got);
                    if (k < 0) break;
                    got += k;
                }
                in.close();
                imgs.put(Base64.encodeToString(b, Base64.NO_WRAP));
            }
            r.put("images", imgs);
        } catch (Exception ignored) {
        }
        return r;
    }
}
