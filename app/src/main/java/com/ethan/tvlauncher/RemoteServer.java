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

    private void route(OutputStream out, String method, String path, Map<String, String> a, String cookie) throws Exception {
        boolean authed = authed(cookie);
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
                json(out, ok(onMain(new Job() {
                    @Override
                    public Object run(FilerActivity f) {
                        return f.webTap(num(a.get("x")), num(a.get("y")));
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
                json(out, ok(onMain(new Job() {
                    @Override
                    public Object run(FilerActivity f) {
                        return f.webKey(String.valueOf(a.get("k")));
                    }
                })));
                return;
            case "/api/scroll":
                json(out, ok(onMain(new Job() {
                    @Override
                    public Object run(FilerActivity f) {
                        return f.webScroll((int) num(a.get("dy")));
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
            default:
                send(out, 404, "text/plain", "Not here".getBytes(StandardCharsets.UTF_8), null);
        }
    }

    // ---------------------------------------------------------------- what the page asks for

    private JSONObject status() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("build", Updater.currentVersion(c));
        o.put("note", note);
        o.put("check", Peek.last);
        o.put("runner", RunnerActivity.instance != null ? RunnerActivity.state : "");
        JSONArray days = new JSONArray();
        for (String d : RunnerActivity.days()) days.put(new JSONArray().put(d).put(RunnerActivity.nice(d)));
        o.put("days", days);
        android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        o.put("screen", pm != null && pm.isInteractive());
        onMain(new Job() {
            @Override
            public Object run(FilerActivity f) {
                try {
                    o.put("filer", true);
                    o.put("which", f.webWhich());
                    o.put("outlook", f.webState("outlook"));
                    o.put("aroflo", f.webState("aroflo"));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        if (!o.has("filer")) o.put("filer", false);
        return o;
    }

    /** A picture of whatever's on the TV's Filer right now (the page the phone picked, or the Filer screen). */
    private void shot(OutputStream out) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<byte[]> img = new AtomicReference<>();
        main.post(new Runnable() {
            @Override
            public void run() {
                final FilerActivity f = FilerActivity.instance;
                if (f == null) {
                    done.countDown();
                    return;
                }
                f.webKeepAlive();
                // a moment for the page to draw at TV size if it's only just been put on screen
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        f.webShot(new FilerActivity.Shot() {
                            @Override
                            public void done(byte[] jpg) {
                                img.set(jpg);
                                done.countDown();
                            }
                        });
                    }
                }, 250);
            }
        });
        boolean inTime = done.await(8, TimeUnit.SECONDS);
        byte[] b = img.get();
        if (b == null) send(out, 409, "text/plain", (FilerActivity.instance == null ? "The Filer's closed"
                : inTime ? "The TV couldn't take a picture of the page" : "The TV took too long taking the picture")
                .getBytes(StandardCharsets.UTF_8), null);
        else send(out, 200, "image/jpeg", b, null, "Cache-Control: no-store");
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
