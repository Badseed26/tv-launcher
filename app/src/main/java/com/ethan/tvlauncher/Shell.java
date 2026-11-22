package com.ethan.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;

/**
 * The app's end of the Shell helper (22/11): real remote keys, taps and the live picture for streaming apps.
 * Starts ShellMain through the TV's own debugging service (Adb.java, 127.0.0.1) and talks to it over a local socket.
 * The first time, the TV shows "Allow USB debugging?" and Ethan ticks Always allow with the TV remote: that's his OK.
 * Only fixed things go through here (keys off a short list, a tap, typed text, the picture). Nothing from a job, the
 * relay or the phone page can run a command of its own.
 * If the debugging's off or not allowed, everything falls back to the accessibility helper like before.
 */
final class Shell {

    private Shell() {}

    static volatile String state = "not started";
    private static volatile Socket link;
    private static OutputStream out;
    private static boolean trying;
    private static long lastTry, upSince;
    private static final ArrayDeque<String> log = new ArrayDeque<>();

    private static final Object frameLock = new Object();
    private static byte[] frame;
    private static long seq, lastVideo;
    // the helper's read of the screen: percent moving, percent black, a full screen video or not, ms the mirror's been on
    private static int statMoving, statBlack, statAge;
    private static boolean statVideo;
    private static long statAt;
    private static volatile long used;
    private static volatile boolean videoOn;

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("shell", Context.MODE_PRIVATE);
    }

    static boolean up() {
        Socket s = link;
        return s != null && !s.isClosed();
    }

    /** Starts the helper if it isn't running. Comes straight back; one go a minute at most. */
    static void ensure(final Context c) {
        synchronized (Shell.class) {
            if (up() || trying || System.currentTimeMillis() - lastTry < 60000 || p(c).getBoolean("off", false)) return;
            trying = true;
            lastTry = System.currentTimeMillis();
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    session(c.getApplicationContext());
                } finally {
                    synchronized (Shell.class) {
                        trying = false;
                    }
                }
            }
        }, "shell");
        t.setDaemon(true);
        t.start();
    }

    private static void session(Context c) {
        ServerSocket ss = null;
        Adb adb = null;
        Socket s = null;
        try {
            state = "connecting to the TV's debugging";
            KeyPair key = key(c);
            ss = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            ss.setSoTimeout(25000);
            byte[] r = new byte[16];
            new SecureRandom().nextBytes(r);
            StringBuilder token = new StringBuilder();
            for (byte b : r) token.append(String.format("%02x", b));
            int port = p(c).getInt("port", 5555);
            try {
                adb = Adb.connect("127.0.0.1", port, key, "ec-tv-app", 90000, new Adb.Note() {
                    @Override
                    public void waiting() {
                        state = "waiting on the TV: tick Always allow on its Allow USB debugging box, then OK";
                    }
                });
            } catch (java.net.ConnectException e) {
                throw new Exception("the TV's network debugging is off (nothing on port " + port + ")");
            }
            note("debugging link up");
            state = "starting the helper";
            final Adb.Stream st = adb.open("shell:CLASSPATH=" + c.getApplicationInfo().sourceDir + " exec app_process /system/bin "
                    + ShellMain.class.getName() + " " + ss.getLocalPort() + " " + token);
            // anything the helper prints (a crash) goes in the log
            Thread lt = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] d;
                        while ((d = st.read(0)) != null) {
                            String t = new String(d, StandardCharsets.UTF_8).trim();
                            if (!t.isEmpty()) note("helper said: " + (t.length() > 600 ? t.substring(0, 600) : t));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "shell-out");
            lt.setDaemon(true);
            lt.start();
            s = ss.accept();
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
            s.setSoTimeout(10000);
            byte[] first = msg(in, 'T');
            if (first == null || !token.toString().equals(new String(first, StandardCharsets.UTF_8))) throw new Exception("something else answered on the helper's socket");
            s.setSoTimeout(0);
            synchronized (Shell.class) {
                out = s.getOutputStream();
                link = s;
                upSince = System.currentTimeMillis();
            }
            state = "up";
            used = System.currentTimeMillis();
            p(c).edit().putBoolean("allowed", true).apply();
            TvLog.line(c, "Shell helper: up (real keys and the live picture)");
            while (true) {
                int type = in.readUnsignedByte();
                int n = in.readInt();
                if (n < 0 || n > 8 << 20) throw new Exception("bad message from the helper");
                byte[] b = new byte[n];
                in.readFully(b);
                if (type == 'F') {
                    synchronized (frameLock) {
                        // only kept while a phone's watching a streaming app's menus (video goes off otherwise)
                        if (videoOn) {
                            frame = b;
                            seq++;
                            frameLock.notifyAll();
                        }
                    }
                } else if (type == 'S') {
                    String[] v = new String(b, StandardCharsets.UTF_8).split(" ");
                    synchronized (frameLock) {
                        statMoving = Integer.parseInt(v[0]);
                        statBlack = Integer.parseInt(v[1]);
                        statVideo = "1".equals(v[2]);
                        statAge = (int) Math.min(Integer.MAX_VALUE, Long.parseLong(v[3]));
                        statAt = System.currentTimeMillis();
                    }
                } else if (type == 'L') note(new String(b, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            boolean was = link != null;
            String m = e instanceof java.io.EOFException || e instanceof java.net.SocketException || e.getMessage() == null ? "the helper stopped" : e.getMessage();
            if (!(was && state.startsWith("resting"))) state = (was ? "stopped: " : "not running: ") + m;
            note(state);
            if (was) TvLog.line(c, "Shell helper: " + state);
        } finally {
            synchronized (Shell.class) {
                // it had been going a while: try again soon, not in a minute
                if (link != null && System.currentTimeMillis() - upSince > 30000) lastTry = System.currentTimeMillis() - 50000;
                link = null;
                out = null;
            }
            synchronized (frameLock) {
                frame = null;
                videoOn = false;
                frameLock.notifyAll();
            }
            try {
                if (s != null) s.close();
            } catch (Exception ignored) {
            }
            try {
                if (ss != null) ss.close();
            } catch (Exception ignored) {
            }
            if (adb != null) adb.close();
        }
    }

    private static byte[] msg(DataInputStream in, char want) throws Exception {
        int type = in.readUnsignedByte();
        int n = in.readInt();
        if (type != want || n < 0 || n > 4096) return null;
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    /** Our debugging key, made once and kept in the app's own storage. */
    private static KeyPair key(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "shell.key");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (f.exists()) {
            try {
                String[] parts = new String(Api.bytes(f), StandardCharsets.UTF_8).trim().split("\n");
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP))));
            } catch (Exception ignored) {
                // unreadable: make a new one (the TV asks Ethan again)
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        FileOutputStream o = new FileOutputStream(f);
        o.write((android.util.Base64.encodeToString(kp.getPrivate().getEncoded(), android.util.Base64.NO_WRAP) + "\n"
                + android.util.Base64.encodeToString(kp.getPublic().getEncoded(), android.util.Base64.NO_WRAP) + "\n").getBytes(StandardCharsets.UTF_8));
        o.close();
        return kp;
    }

    // ---------------------------------------------------------------- what the app can ask for

    /** Starts it if need be and waits a few seconds for it, but only once Ethan's allowed it (so nothing waits on his tick). */
    static boolean ready(Context c, int waitMs) {
        if (up()) return true;
        ensure(c);
        if (!p(c).getBoolean("allowed", false)) return false;
        long until = System.currentTimeMillis() + waitMs;
        while (!up() && System.currentTimeMillis() < until) {
            synchronized (Shell.class) {
                if (!trying) break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        return up();
    }

    /** Every minute from the listener: the helper's a whole extra process on a low RAM TV, so it goes after 10 min unused. */
    static void idle() {
        Socket s = link;
        if (s == null || System.currentTimeMillis() - used < 10 * 60000) return;
        state = "resting (starts again when it's needed)";
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    private static boolean send(String line) {
        synchronized (Shell.class) {
            if (out == null) return false;
            used = System.currentTimeMillis();
            try {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (Exception e) {
                try {
                    link.close();
                } catch (Exception ignored) {
                }
                return false;
            }
        }
    }

    /** A real remote key. Only these. */
    static boolean key(String k) {
        int code;
        switch (k) {
            case "up": code = 19; break;
            case "down": code = 20; break;
            case "left": code = 21; break;
            case "right": code = 22; break;
            case "enter": case "ok": code = 23; break;
            case "back": code = 4; break;
            case "home": code = 3; break;
            case "delete": code = 67; break;
            case "playpause": code = 85; break;
            default: return false;
        }
        return send("key " + code);
    }

    /** A real finger tap, fx and fy 0 to 1 across the screen. */
    static boolean tap(float fx, float fy) {
        if (!(fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1)) return false;
        return send("tap " + fx + " " + fy);
    }

    /** Typed as real key presses (letters, digits, the usual marks). */
    static boolean text(String t) {
        if (t == null || t.isEmpty() || t.length() > 200) return false;
        return send("text " + android.util.Base64.encodeToString(t.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
    }

    static final class Frame {
        byte[] jpg;
        long seq;
    }

    /**
     * The live picture: the newest frame after `after` (0 for whatever's newest), waiting up to waitMs for one.
     * Null when nothing new came (the screen didn't change). Only RemoteServer asks, and only for a streaming app's menus.
     */
    static Frame frame(long after, int waitMs) {
        if (!up()) return null;
        long now = System.currentTimeMillis();
        synchronized (frameLock) {
            videoOn = true;
            mirror();
            if (after > seq) after = 0; // the phone's count is from before the app restarted
            long until = now + waitMs;
            while ((frame == null || seq <= after) && up()) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    frameLock.wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (frame == null || seq <= after) return null;
            Frame f = new Frame();
            f.jpg = frame;
            f.seq = seq;
            return f;
        }
    }

    /** Call with frameLock held. Keeps the helper mirroring (it stops 5 s after the last ask). */
    private static void mirror() {
        long now = System.currentTimeMillis();
        used = now;
        if (now - lastVideo > 3000) {
            // nobody's asked for a bit, the helper may have stopped mirroring: start fresh so what comes back is new
            frame = null;
            statAt = 0;
            send("video 0");
        }
        if (now - lastVideo > 1000) {
            lastVideo = now;
            send("video 960 8 55");
        }
    }

    /**
     * Is the screen a full screen video right now? 1 yes (nearly all of it's moving, or nearly all of it's black, which
     * is what a protected movie looks like to the mirror), 0 no (a menu, even with an ad or preview playing in part of
     * it), -1 can't tell. Only worth asking when sound's playing. Takes a second or so the first time (the mirror
     * has to watch for a moment).
     */
    static int video() {
        long until = System.currentTimeMillis() + 3500;
        while (up() && System.currentTimeMillis() < until) {
            synchronized (frameLock) {
                mirror();
                if (System.currentTimeMillis() - statAt < 1500 && (statVideo || statAge >= 1200)) return statVideo || statBlack >= 85 ? 1 : 0;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        return -1;
    }

    /** A movie's playing: the last menu picture's no good any more. The mirror stays on to see when it stops. */
    static void dropFrame() {
        synchronized (frameLock) {
            frame = null;
        }
    }

    /** Picture off (a movie's playing, or it's not a streaming app any more). The last frame's thrown away. */
    static void videoOff() {
        synchronized (frameLock) {
            if (!videoOn) return;
            videoOn = false;
            frame = null;
            lastVideo = 0;
        }
        send("video 0");
    }

    private static void note(String s) {
        synchronized (log) {
            log.add(new java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.US).format(new java.util.Date()) + " " + s);
            while (log.size() > 40) log.poll();
        }
    }

    /** For tv.shell: how it's going, and switching it off or on. */
    static JSONObject status(Context c, JSONObject a) throws Exception {
        if (a.has("port")) p(c).edit().putInt("port", a.optInt("port", 5555)).apply();
        if (a.has("off")) {
            p(c).edit().putBoolean("off", a.optBoolean("off")).apply();
            if (a.optBoolean("off")) {
                Socket s = link;
                if (s != null) try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (a.optBoolean("start")) {
            synchronized (Shell.class) {
                lastTry = 0;
            }
            ensure(c);
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("up", up());
        o.put("state", p(c).getBoolean("off", false) ? "switched off (tv.shell {off:false})" : state);
        o.put("port", p(c).getInt("port", 5555));
        synchronized (frameLock) {
            o.put("video", videoOn);
            o.put("frames", seq);
            if (System.currentTimeMillis() - statAt < 3000)
                o.put("screen", "moving " + statMoving + "%, black " + statBlack + "%" + (statVideo ? ", full screen video" : ""));
        }
        JSONArray l = new JSONArray();
        synchronized (log) {
            for (String s : log) l.put(s);
        }
        o.put("log", l);
        return o;
    }
}
