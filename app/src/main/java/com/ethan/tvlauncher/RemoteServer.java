package com.ethan.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The phone remote page (20/11). A tiny web server on port 8765 that only answers Tailscale addresses (your phone over
 * the private network) and only after the PIN. Live picture of the Filer's Outlook or AroFlo page, taps, typing, and the
 * Filer buttons, straight to the TV with no Todoist in between. Filer only, nothing else on the TV.
 */
final class RemoteServer {
    static final int PORT = 8765;
    private static RemoteServer running;

    private final Context c;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom rnd = new SecureRandom();
    private long failStart;
    private int fails;
    private volatile String note = "";

    private RemoteServer(Context c) {
        this.c = c.getApplicationContext();
    }

    static synchronized void start(Context c) {
        if (running != null) return;
        running = new RemoteServer(c);
        running.listen();
    }

    private SharedPreferences prefs() {
        return c.getSharedPreferences("webremote", Context.MODE_PRIVATE);
    }

    /** The PIN, made the first time it's asked for. */
    static String pin(Context c) {
        SharedPreferences p = c.getSharedPreferences("webremote", Context.MODE_PRIVATE);
        String pin = p.getString("pin", null);
        if (pin == null) {
            pin = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
            p.edit().putString("pin", pin).apply();
        }
        return pin;
    }

    /** New PIN, and every phone has to log in again. */
    static String newPin(Context c) {
        c.getSharedPreferences("webremote", Context.MODE_PRIVATE).edit().remove("pin").remove("tokens").apply();
        return pin(c);
    }

    /** The TV's Tailscale address, if Tailscale's on. */
    static String tailscaleIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && tailscale(a)) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 100.64.0.0/10 and fd7a:115c:a1e0::/48, the addresses Tailscale hands out. */
    private static boolean tailscale(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        if (a instanceof Inet6Address) return (b[0] & 0xff) == 0xfd && (b[1] & 0xff) == 0x7a && (b[2] & 0xff) == 0x11
                && (b[3] & 0xff) == 0x5c && (b[4] & 0xff) == 0xa1 && (b[5] & 0xff) == 0xe0;
        return false;
    }

    private void listen() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try (ServerSocket ss = new ServerSocket(PORT)) {
                        TvLog.line(c, "Phone remote: listening on port " + PORT);
                        while (true) {
                            final Socket s = ss.accept();
                            if (!tailscale(s.getInetAddress()) && !s.getInetAddress().isLoopbackAddress()) {
                                s.close(); // home Wi-Fi or anything else: not for us
                                continue;
                            }
                            pool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    serve(s);
                                }
                            });
                        }
                    } catch (Exception e) {
                        TvLog.line(c, "Phone remote: server stopped (" + e.getMessage() + "), restarting in 30 s");
                        try {
                            Thread.sleep(30000);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }, "phone-remote");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- requests

    private void serve(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];
            Map<String, String> h = new HashMap<>();
            String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) {
                int i = l.indexOf(':');
                if (i > 0) h.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
            }
            int len = 0;
            try {
                len = Integer.parseInt(h.containsKey("content-length") ? h.get("content-length") : "0");
            } catch (Exception ignored) {
            }
            byte[] body = new byte[Math.min(len, 65536)];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            Map<String, String> args = form(query);
            args.putAll(form(new String(body, 0, got, StandardCharsets.UTF_8)));
            route(s.getOutputStream(), method, path, args, h.get("cookie"));
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final ThreadLocal<Boolean> trusted = new ThreadLocal<>();

    /** Paths the relay link (Link.java) may use. Never logins, PIN or the login page. */
    private static final java.util.Set<String> LINK_OK = new java.util.HashSet<>(java.util.Arrays.asList(
            "/api/status", "/api/shot", "/api/view", "/api/tap", "/api/type", "/api/key", "/api/scroll", "/api/cmd"));

    /**
     * The relay link (22/11) runs the phone page's own actions here, already trusted (it signed in to the relay with
     * the TV's pairing key). Gives back {status, type, body bytes}.
     */
    static Object[] local(String method, String path, Map<String, String> a) throws Exception {
        RemoteServer r = running;
        if (r == null) throw new Exception("the phone page server isn't running");
        if (!LINK_OK.contains(path)) throw new Exception("not allowed: " + path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        trusted.set(true);
        try {
            r.route(out, method, path, a, null);
        } finally {
            trusted.set(false);
        }
        byte[] all = out.toByteArray();
        int i = 0;
        while (i + 3 < all.length && !(all[i] == 13 && all[i + 1] == 10 && all[i + 2] == 13 && all[i + 3] == 10)) i++;
        String head = new String(all, 0, Math.min(i, all.length), StandardCharsets.UTF_8);
        int code = Integer.parseInt(head.split(" ")[1]);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)Content-Type: ([^\\r\\n;]+)").matcher(head);
        byte[] body = i + 4 <= all.length ? java.util.Arrays.copyOfRange(all, i + 4, all.length) : new byte[0];
        return new Object[] {code, m.find() ? m.group(1) : "", body};
    }

    private void route(OutputStream out, String method, String path, Map<String, String> a, String cookie) throws Exception {
        boolean authed = Boolean.TRUE.equals(trusted.get()) || authed(cookie);
        if (path.equals("/login") && method.equals("POST")) {
            long now = System.currentTimeMillis();
            if (now - failStart > 10 * 60000) {
                failStart = now;
                fails = 0;
            }
            if (fails >= 5) {
                send(out, 429, "text/plain", "Too many tries, wait 10 min".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            if (pin(c).equals(a.get("pin"))) {
                String tok = newToken();
                TvLog.line(c, "Phone remote: a phone logged in");
                send(out, 303, "text/plain", new byte[0], "ectv=" + tok + "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Strict", "Location: /");
            } else {
                fails++;
                TvLog.line(c, "Phone remote: wrong PIN");
                send(out, 303, "text/plain", new byte[0], null, "Location: /?bad=1");
            }
            return;
        }
        if (!authed) {
            if (path.startsWith("/api/")) send(out, 401, "application/json", "{\"login\":true}".getBytes(StandardCharsets.UTF_8), null);
            else send(out, 200, "text/html; charset=utf-8", asset("remote-login.html"), null);
            return;
        }
        // Claude through the relay never taps, types or presses keys on Tradies Web: the site sign in there is Ethan's (22/11)
        final boolean viaLink = Boolean.TRUE.equals(trusted.get());
        if (viaLink && (path.equals("/api/tap") || path.equals("/api/type") || path.equals("/api/key")) && runnerOnTradies()) {
            json(out, ok(false));
            return;
        }
        switch (path) {
            case "/":
                send(out, 200, "text/html; charset=utf-8", asset("remote.html"), null);
                return;
            case "/api/status":
                json(out, status());
                return;
            case "/api/shot":
                shot(out);
                return;
            case "/api/view":
                json(out, ok(onMain(new Job() {
                    @Override
                    public Object run(FilerActivity f) {
                        String v = String.valueOf(a.get("s"));
                        if (v.equals("outlook") || v.equals("aroflo")) return f.webShow(v);
                        f.webHide();
                        return true;
                    }
                })));
                return;
            case "/api/tap":
                // 20/11: a Filer page gets the Filer's own tap, any other screen of ours (home, all apps, Runner,
                // the Filer screen) a plain tap. Another app gets nothing.
                json(out, ok(onTop(new TopJob() {
                    @Override
                    public Object run(android.app.Activity t) {
                        float x = num(a.get("x")), y = num(a.get("y"));
                        if (t instanceof FilerActivity && ((FilerActivity) t).webTap(x, y)) return true;
                        return Screen.tap(t, x, y);
                    }
                })));
                return;
            case "/api/type":
                json(out, ok(onMain(new Job() {
                    @Override
                    public Object run(FilerActivity f) {
                        return f.webType(a.get("text"));
                    }
                })));
                return;
            case "/api/key":
                json(out, ok(onTop(new TopJob() {
                    @Override
                    public Object run(android.app.Activity t) {
                        String k = String.valueOf(a.get("k"));
                        if (t instanceof FilerActivity && !((FilerActivity) t).webWhich().isEmpty()) return ((FilerActivity) t).webKey(k);
                        // Back on the Filer screen would close the Filer: that's what Close Filer (with its confirm) is for
                        if (t instanceof FilerActivity && "back".equals(k)) return false;
                        return Screen.key(t, k);
                    }
                })));
                return;
            case "/api/scroll":
                json(out, ok(onTop(new TopJob() {
                    @Override
                    public Object run(android.app.Activity t) {
                        int dy = (int) num(a.get("dy"));
                        if (t instanceof FilerActivity && !((FilerActivity) t).webWhich().isEmpty()) return ((FilerActivity) t).webScroll(dy);
                        for (int i = 0; i < 3; i++) Screen.key(t, dy < 0 ? "up" : "down");
                        return true;
                    }
                })));
                return;
            case "/api/code":
                json(out, ok(onMain(new Job() {
                    @Override
                    public Object run(FilerActivity f) {
                        return f.enterAroFloCode(String.valueOf(a.get("code")).replaceAll("\\D", ""));
                    }
                })));
                return;
            case "/api/cmd":
                json(out, cmd(String.valueOf(a.get("c"))));
                return;
            case "/api/signin":
                // Ethan's Sign in to site for the Tradies Web booking open on the TV (22/11). POST starts it, GET says how it went.
                // Not on the relay's list (LINK_OK), so only Ethan's phone can start it.
                if (viaLink) {
                    send(out, 403, "text/plain", "Not from the relay".getBytes(StandardCharsets.UTF_8), null);
                    return;
                }
                if (method.equals("POST")) TvLog.line(c, "Phone remote: Ethan pressed Sign in to site");
                send(out, 200, "application/json", RunnerActivity.tradiesSignIn(method.equals("POST")).getBytes(StandardCharsets.UTF_8), null);
                return;
            case "/api/signature": {
                // Ethan's signature for the site sign in, drawn once on the phone page and kept where the Tradies quick fill
                // looks for it (GM value ecSignature: strokes of [x, y] points from 0 to 1). Never sent anywhere else.
                if (viaLink) {
                    send(out, 403, "text/plain", "Not from the relay".getBytes(StandardCharsets.UTF_8), null);
                    return;
                }
                GmStore gs = GmStore.get(c);
                JSONObject o = new JSONObject();
                if (method.equals("POST")) {
                    if ("clear".equals(a.get("do"))) {
                        gs.delete("ecSignature");
                        TvLog.line(c, "Phone remote: cleared the saved signature");
                        o.put("ok", true);
                    } else {
                        String strokes = cleanStrokes(a.get("strokes"));
                        if (strokes == null) {
                            o.put("ok", false);
                            o.put("msg", "That signature didn't come through right, try again");
                        } else {
                            gs.setJson("ecSignature", JSONObject.quote(strokes));
                            TvLog.line(c, "Phone remote: saved a signature for the site sign in");
                            o.put("ok", true);
                        }
                    }
                }
                o.put("saved", gs.getJson("ecSignature") != null);
                json(out, o);
                return;
            }
            case "/api/logins": {
                // Saved logins for Claude's jobs on the TV (21/11). Only ever says which are saved, never the login.
                JSONObject o = new JSONObject();
                if (method.equals("POST")) {
                    String site = String.valueOf(a.get("site")), act = String.valueOf(a.get("do"));
                    if (Sites.byName(site) == null) {
                        o.put("ok", false);
                        o.put("msg", "unknown site");
                    } else if (act.equals("clear")) {
                        Sites.clear(c, site);
                        TvLog.line(c, "Phone remote: cleared the saved " + site + " login");
                        o.put("ok", true);
                    } else {
                        String user = a.get("user"), pass = a.get("pass");
                        if (pass == null || pass.isEmpty()) {
                            o.put("ok", false);
                            o.put("msg", "needs the password");
                        } else {
                            Sites.save(c, site, user == null ? "" : user.trim(), pass);
                            TvLog.line(c, "Phone remote: saved a " + site + " login");
                            o.put("ok", true);
                        }
                    }
                }
                o.put("sites", Sites.list(c));
                json(out, o);
                return;
            }
            default:
                send(out, 404, "text/plain", "Not here".getBytes(StandardCharsets.UTF_8), null);
        }
    }

    /** Strokes from the phone's signature pad, checked and rounded: 1 to 30 strokes of 2 to 400 points from 0 to 1. */
    private static String cleanStrokes(String raw) {
        try {
            org.json.JSONArray in = new org.json.JSONArray(raw), outA = new org.json.JSONArray();
            if (in.length() < 1 || in.length() > 30) return null;
            for (int i = 0; i < in.length(); i++) {
                org.json.JSONArray st = in.getJSONArray(i), so = new org.json.JSONArray();
                if (st.length() < 2 || st.length() > 400) continue;
                for (int k = 0; k < st.length(); k++) {
                    org.json.JSONArray pt = st.getJSONArray(k);
                    double x = pt.getDouble(0), y = pt.getDouble(1);
                    if (x < 0 || x > 1 || y < 0 || y > 1) return null;
                    so.put(new org.json.JSONArray().put(Math.round(x * 10000) / 10000.0).put(Math.round(y * 10000) / 10000.0));
                }
                outA.put(so);
            }
            return outA.length() > 0 ? outA.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Is the Runner up front on a Tradies Web page? Asks on the main thread, 3 s tops. */
    private static boolean runnerOnTradies() {
        final RunnerActivity r = RunnerActivity.instance;
        if (r == null) return false;
        final java.util.concurrent.CountDownLatch d = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean(true);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    on.set(r.onTradies());
                } finally {
                    d.countDown();
                }
            }
        });
        try {
            // can't tell in time: treat it as on Tradies, so a tap never slips through
            if (!d.await(3, java.util.concurrent.TimeUnit.SECONDS)) return true;
        } catch (InterruptedException e) {
            return true;
        }
        return on.get();
    }

    // ---------------------------------------------------------------- what the page asks for

    private JSONObject status() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("build", Updater.currentVersion(c));
        o.put("note", note);
        o.put("check", Peek.last);
        o.put("runner", RunnerActivity.instance != null ? RunnerActivity.state : "");
        final AtomicReference<String> on = new AtomicReference<>("");
        onMainAny(new Runnable() {
            @Override
            public void run() {
                on.set(Screen.name(c));
            }
        });
        o.put("on", on.get());
        JSONArray days = new JSONArray();
        for (String d : RunnerActivity.days()) days.put(new JSONArray().put(d).put(RunnerActivity.nice(d)));
        o.put("days", days);
        android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        o.put("screen", pm != null && pm.isInteractive());
        onMain(new Job() {
            @Override
            public Object run(FilerActivity f) {
                try {
                    o.put("which", f.webWhich());
                    o.put("outlook", f.webState("outlook"));
                    o.put("aroflo", f.webState("aroflo"));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        // 20/11: open or closed comes straight from the Filer, so a busy moment on the TV can't make it flicker "closed"
        o.put("filer", FilerActivity.instance != null);
        return o;
    }

    /**
     * A picture of what's on the TV (20/11): any of our own screens (home, Filer, Runner, all apps). Another app or the
     * TV being off gets a 409 with a line saying so, never a picture.
     */
    private void shot(OutputStream out) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<byte[]> img = new AtomicReference<>();
        final AtomicReference<String> why = new AtomicReference<>();
        main.post(new Runnable() {
            @Override
            public void run() {
                final android.app.Activity a = Screen.top;
                if (a == null) {
                    String n = Screen.name(c);
                    why.set(n.equals("TV off") ? "The TV's off" : "On the TV: " + n);
                    done.countDown();
                    return;
                }
                final Screen.Shot cb = new Screen.Shot() {
                    @Override
                    public void done(byte[] jpg) {
                        img.set(jpg);
                        done.countDown();
                    }
                };
                if (a instanceof FilerActivity) {
                    final FilerActivity f = (FilerActivity) a;
                    f.webKeepAlive();
                    // a moment for the page to draw at TV size if it's only just been put on screen
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            f.webShot(new FilerActivity.Shot() {
                                @Override
                                public void done(byte[] jpg) {
                                    cb.done(jpg);
                                }
                            });
                        }
                    }, 250);
                } else Screen.shot(a, cb);
            }
        });
        boolean inTime = done.await(8, TimeUnit.SECONDS);
        byte[] b = img.get();
        if (b != null) send(out, 200, "image/jpeg", b, null, "Cache-Control: no-store");
        else send(out, 409, "text/plain", (why.get() != null ? why.get()
                : inTime ? "The TV couldn't take a picture" : "The TV took too long taking the picture")
                .getBytes(StandardCharsets.UTF_8), null);
    }

    private JSONObject cmd(String cmd) throws Exception {
        JSONObject o = new JSONObject();
        if (cmd.startsWith("pull:")) {
            final String day = RunnerActivity.parseDay(cmd.substring(5));
            if (day == null) {
                note = "Can only pull today or the 6 days before.";
                o.put("ok", false);
                return o;
            }
            note = "Starting the inbox pull for " + RunnerActivity.nice(day) + "...";
            main.post(new Runnable() {
                @Override
                public void run() {
                    RunnerActivity.Job j = new RunnerActivity.Job();
                    j.kind = "pull";
                    j.day = day;
                    j.from = "phone";
                    j.say = new Remote.Say() {
                        @Override
                        public void say(String text) {
                            note = text;
                        }
                    };
                    String r = RunnerActivity.start(c, j);
                    note = r != null ? r : "Pulling the inbox for " + RunnerActivity.nice(day)
                            + ". Takes a couple of minutes, the bundle goes on the TV remote task in Todoist.";
                }
            });
            o.put("ok", true);
            return o;
        }
        switch (cmd) {
            case "open":
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (FilerActivity.instance == null) {
                            ListenService.wakeScreen(c);
                            WakeActivity.start(c, true);
                        }
                    }
                });
                note = "Opening the Filer, give it a minute to sign in.";
                break;
            case "close":
                onMain(new Job() {
                    @Override
                    public Object run(FilerActivity f) {
                        f.finish();
                        return true;
                    }
                });
                note = "Filer closed.";
                break;
            case "hide":
                onMain(new Job() {
                    @Override
                    public Object run(FilerActivity f) {
                        f.webHide();
                        return true;
                    }
                });
                note = "";
                break;
            case "reload":
                onMain(new Job() {
                    @Override
                    public Object run(FilerActivity f) {
                        f.remoteReload();
                        return true;
                    }
                });
                note = "Reloading Outlook and AroFlo.";
                break;
            case "check":
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (FilerActivity.instance != null) {
                            note = "The Filer's open, it's watching the mail itself.";
                            return;
                        }
                        note = "Checking the mail...";
                        ListenService.peekNow(c, new Peek.Done() {
                            @Override
                            public void done(Peek.Result r) {
                                note = r.summary();
                            }
                        });
                    }
                });
                break;
            case "wake": // the Wake TV button (Ethan, 21/11), same as wake on the Todoist remote
                android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
                final boolean on = pm != null && pm.isInteractive();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        ListenService.wakeScreen(c);
                        if (!on) WakeActivity.start(c, false);
                    }
                });
                note = on ? "TV's already on." : "Turning the TV on, give it a few seconds.";
                break;
            case "update":
                note = "Checking for an update...";
                Updater.remoteUpdate(c, new Remote.Say() {
                    @Override
                    public void say(String text) {
                        note = text;
                    }
                });
                break;
            default:
                o.put("ok", false);
                return o;
        }
        o.put("ok", true);
        return o;
    }

    // ---------------------------------------------------------------- bits

    private interface Job {
        Object run(FilerActivity f);
    }

    private interface TopJob {
        Object run(android.app.Activity top);
    }

    /** Runs on the main thread with our screen that's up (null when it's another app or the TV's off). Waits up to 5 s. */
    private Object onTop(final TopJob j) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Object> res = new AtomicReference<>();
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.app.Activity t = Screen.top;
                    if (t != null) res.set(j.run(t));
                } catch (Exception ignored) {
                }
                done.countDown();
            }
        });
        try {
            done.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return res.get();
    }

    /** Runs r on the main thread and waits up to 2 s. */
    private void onMainAny(final Runnable r) {
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Exception ignored) {
                }
                done.countDown();
            }
        });
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /** Runs on the main thread with the open Filer, null if it's closed. Waits up to 5 s. */
    private Object onMain(final Job j) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Object> res = new AtomicReference<>();
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    FilerActivity f = FilerActivity.instance;
                    if (f != null) res.set(j.run(f));
                } catch (Exception ignored) {
                }
                done.countDown();
            }
        });
        try {
            done.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return res.get();
    }

    private static JSONObject ok(Object r) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", Boolean.TRUE.equals(r));
        } catch (Exception ignored) {
        }
        return o;
    }

    private boolean authed(String cookie) {
        if (cookie == null) return false;
        String tokens = prefs().getString("tokens", "");
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("ectv=")) {
                String t = p.substring(5);
                if (t.length() >= 32 && ("," + tokens + ",").contains("," + t + ",")) return true;
            }
        }
        return false;
    }

    private String newToken() {
        byte[] b = new byte[24];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        String t = sb.toString();
        String tokens = prefs().getString("tokens", "");
        String[] list = tokens.isEmpty() ? new String[0] : tokens.split(",");
        StringBuilder keep = new StringBuilder(t);
        for (int i = 0; i < list.length && i < 4; i++) keep.append(',').append(list[i]); // last 5 phones
        prefs().edit().putString("tokens", keep.toString()).apply();
        return t;
    }

    private byte[] asset(String name) throws Exception {
        InputStream in = c.getAssets().open(name);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static float num(String s) {
        try {
            return Float.parseFloat(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static Map<String, String> form(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            try {
                if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) break;
        }
        if (ch < 0 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void json(OutputStream out, JSONObject o) throws Exception {
        send(out, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8), null, "Cache-Control: no-store");
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String cookie, String... extra) throws Exception {
        String reason = code == 200 ? "OK" : code == 303 ? "See Other" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found"
                : code == 409 ? "Conflict" : code == 429 ? "Too Many Requests" : "Error";
        StringBuilder h = new StringBuilder("HTTP/1.1 " + code + " " + reason + "\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Connection: close\r\n");
        h.append("X-Frame-Options: DENY\r\n");
        if (cookie != null) h.append("Set-Cookie: ").append(cookie).append("\r\n");
        for (String e : extra) h.append(e).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
