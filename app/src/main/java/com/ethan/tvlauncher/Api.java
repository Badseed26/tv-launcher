package com.ethan.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * The TV as Claude's computer (21/11). A request comes in on the Todoist remote as
 *   api {"id":"a1","do":"aroflo.task","args":{"jn":"9530"},"key":"...","branch":"main","dry":false}
 * and the answer goes back as
 *   TV: api a1 ok {...}      or      TV: api a1 err {...}
 * with anything big (and every failed job's log) attached as a file.
 *
 * - Jobs queue here (kept through a restart) and run one at a time in the Runner. They wait while the TV's on another
 *   app or HDMI, and go once it's off or on the home screen.
 * - The job code lives in jobs/ on GitHub and loads fresh every run (branch picks a test copy), so a fix is live on the
 *   next request with no new build.
 * - "key" makes a write safe to send twice: a key that's already done gets the same answer back, nothing runs again.
 * - tv.* requests are answered straight away without the Runner.
 * - Every job keeps a step log and screenshots on the TV (the last 50 jobs).
 */
final class Api {

    private static final String PREFS = "api";
    private static final int KEEP = 50;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Api() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- coming in

    /** Main thread. A request off the remote. Returns the first reply (or null when the answer comes later). */
    static String request(Context c, String body) {
        JSONObject r;
        try {
            r = new JSONObject(body.trim());
        } catch (Exception e) {
            return "api: that's not JSON (" + e.getMessage() + ")";
        }
        String id = r.optString("id"), what = r.optString("do");
        if (id.isEmpty() || what.isEmpty()) return "api: needs an id and a do";
        if (!id.matches("[A-Za-z0-9_.-]{1,40}")) return "api: id can only be letters, numbers, dot, dash or underscore";

        if (what.startsWith("tv.")) return answer(id, tv(c, what, r.optJSONObject("args")));

        String key = r.optString("key");
        if (!key.isEmpty()) {
            JSONObject done = keys(c).optJSONObject(key);
            if (done != null) {
                try {
                    done.put("again", true);
                } catch (Exception ignored) {
                }
                return answer(id, done);
            }
        }
        if (Crash.safeMode(c)) return answer(id, err("the TV's in safe mode after crashing (send api tv.safe with args {\"off\":true} once it's fixed)"));

        synchronized (Api.class) {
            JSONArray q = queue(c);
            for (int i = 0; i < q.length(); i++)
                if (id.equals(q.optJSONObject(i).optString("id"))) return "api " + id + " is already queued";
            try {
                r.put("in", System.currentTimeMillis());
            } catch (Exception ignored) {
            }
            q.put(r);
            p(c).edit().putString("queue", q.toString()).apply();
            int ahead = q.length() - 1 + (running != null ? 1 : 0);
            kick(c);
            String wait = busyReason(c);
            return "api " + id + " queued" + (ahead > 0 ? ", " + ahead + " ahead" : "")
                    + (wait != null && running == null ? ". Waiting: " + wait : "");
        }
    }

    // ---------------------------------------------------------------- the queue

    static volatile JSONObject running;
    private static long lastWaitNote;

    static JSONArray queue(Context c) {
        try {
            return new JSONArray(p(c).getString("queue", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Null when the TV's free for a job (off, or on one of our screens). */
    static String busyReason(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean on = pm != null && pm.isInteractive();
        if (!on) return null;
        if (RunnerActivity.instance != null && RunnerActivity.busy()) return "the Runner's on another job";
        if (RunnerActivity.instance != null || FilerActivity.instance != null || Updater.homeVisible) return null;
        if (Screen.top != null) return null;
        return "the TV's on " + Screen.name(c) + ", the job goes once it's off or on the home screen";
    }

    /** Main thread. Starts the next job if there is one and the TV's free. Called on a new job, every beat, and after each job. */
    static void kick(final Context c) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (running != null) return;
                JSONObject next;
                synchronized (Api.class) {
                    JSONArray q = queue(c);
                    if (q.length() == 0) return;
                    next = q.optJSONObject(0);
                }
                String busy = busyReason(c);
                if (busy != null) {
                    if (System.currentTimeMillis() - lastWaitNote > 30 * 60 * 1000) {
                        lastWaitNote = System.currentTimeMillis();
                        TvLog.line(c, "api: waiting, " + busy);
                    }
                    return;
                }
                RunnerActivity.Job j = new RunnerActivity.Job();
                j.kind = "api";
                j.api = next;
                j.from = "api";
                String r = RunnerActivity.start(c, j);
                if (r == null) {
                    running = next;
                    take(c, next.optString("id"));
                }
            }
        });
    }

    private static void take(Context c, String id) {
        synchronized (Api.class) {
            JSONArray q = queue(c), out = new JSONArray();
            for (int i = 0; i < q.length(); i++) if (!id.equals(q.optJSONObject(i).optString("id"))) out.put(q.opt(i));
            p(c).edit().putString("queue", out.toString()).apply();
        }
    }

    /** Next job waiting? The Runner stays open for it instead of closing. */
    static boolean hasNext(Context c) {
        return queue(c).length() > 0;
    }

    // ---------------------------------------------------------------- going out

    /** The Runner's done with a job: keep the log, remember the key, answer on Todoist, then the next job. */
    static void finished(final Context c, JSONObject req, JSONObject result, String log) {
        running = null;
        final String id = req.optString("id");
        boolean ok = result.optBoolean("ok") && !result.has("err");
        try {
            result.put("ok", ok);
            result.put("ms", System.currentTimeMillis() - req.optLong("started", System.currentTimeMillis()));
        } catch (Exception ignored) {
        }
        saveLog(c, id, req, result, log);
        String key = req.optString("key");
        if (ok && !key.isEmpty() && !req.optBoolean("dry")) {
            synchronized (Api.class) {
                JSONObject k = keys(c);
                try {
                    k.put(key, result);
                    // keep the newest 300 keys
                    JSONArray names = k.names();
                    if (names != null && names.length() > 300) k.remove(names.optString(0));
                } catch (Exception ignored) {
                }
                p(c).edit().putString("keys", k.toString()).apply();
            }
        }
        final String text = answer(id, result);
        String pretty;
        try {
            pretty = result.toString(1);
        } catch (Exception e) {
            pretty = result.toString();
        }
        final String file = !ok ? log : result.toString().length() > 3500 ? pretty : null;
        final String name = file == null ? null : "api-" + id + (ok ? ".json" : "-log.txt");
        post(c, file == null ? text : "api " + id + (ok ? " ok, result attached" : " err " + result.optString("err") + " (log attached)"), file, name);
        kick(c);
    }

    private static String answer(String id, JSONObject o) {
        boolean ok = o.optBoolean("ok") && !o.has("err");
        String s = o.toString();
        return "api " + id + (ok ? " ok " : " err ") + (s.length() > 3500 ? s.substring(0, 3500) + "...(cut, ask tv.log)" : s);
    }

    static JSONObject err(String why) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("err", why);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject keys(Context c) {
        try {
            return new JSONObject(p(c).getString("keys", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Posts on the TV remote task, with a file if there is one. */
    static void post(final Context c, final String line, final String file, final String name) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String token = Secrets.get(c, Secrets.TODOIST_TOKEN);
                if (token == null) return;
                try {
                    JSONObject att = file == null ? null : upload(token, file.getBytes(StandardCharsets.UTF_8), name, "text/plain");
                    JSONObject b = new JSONObject();
                    b.put("task_id", Remote.TASK);
                    b.put("content", "TV: " + line + (file != null && att == null ? "\n" + file.substring(0, Math.min(file.length(), 12000)) : ""));
                    if (att != null) b.put("attachment", att);
                    Http.todoist(token, "POST", "comments", b);
                } catch (Exception e) {
                    TvLog.line(c, "api: couldn't post to Todoist: " + e.getMessage());
                }
            }
        }, "api-post").start();
    }

    /** Todoist upload (raw body). Null if it didn't work. */
    static JSONObject upload(String token, byte[] data, String name, String type) {
        try {
            JSONObject r = new JSONObject();
            r.put("method", "POST");
            r.put("url", "https://api.todoist.com/api/v1/uploads");
            JSONObject h = new JSONObject();
            h.put("Authorization", "Bearer " + token);
            h.put("Content-Type", type);
            h.put("X-File-Name", name);
            h.put("Accept", "application/json");
            r.put("headers", h);
            r.put("b64", Base64.encodeToString(data, Base64.NO_WRAP));
            r.put("timeout", 60000);
            JSONObject res = new JSONObject(Http.run(r.toString()));
            if (res.optBoolean("error") || res.optInt("status") / 100 != 2) return null;
            JSONObject a = new JSONObject(new String(Base64.decode(res.optString("b64", ""), Base64.DEFAULT), StandardCharsets.UTF_8));
            return a.has("file_url") ? a : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- logs and pictures

    static File dir(Context c, String id) {
        File d = new File(new File(c.getFilesDir(), "api"), id);
        d.mkdirs();
        return d;
    }

    private static void saveLog(Context c, String id, JSONObject req, JSONObject result, String log) {
        try {
            File d = dir(c, id);
            String head = "api " + id + "  " + new SimpleDateFormat("EEE d/M h:mm:ss a", Locale.US).format(new Date())
                    + "\nrequest: " + req.toString() + "\nresult: " + result.toString() + "\n\n";
            FileOutputStream o = new FileOutputStream(new File(d, "log.txt"));
            o.write((head + (log == null ? "" : log)).getBytes(StandardCharsets.UTF_8));
            o.close();
            // keep the newest 50 jobs
            File[] all = new File(c.getFilesDir(), "api").listFiles();
            if (all != null && all.length > KEEP) {
                Arrays.sort(all, OLDEST);
                for (int i = 0; i < all.length - KEEP; i++) wipe(all[i]);
            }
        } catch (Exception ignored) {
        }
    }

    private static final java.util.Comparator<File> OLDEST = new java.util.Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return Long.compare(a.lastModified(), b.lastModified());
        }
    };

    static byte[] bytes(File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static void wipe(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File x : in) wipe(x);
        f.delete();
    }

    static String readLog(Context c, String id) {
        try {
            File f = new File(dir(c, id), "log.txt");
            return f.exists() ? new String(bytes(f), StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- tv.* (answered straight away)

    private static JSONObject tv(final Context c, String what, JSONObject a) {
        if (a == null) a = new JSONObject();
        JSONObject o = new JSONObject();
        try {
            switch (what) {
                case "tv.ping": {
                    PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                    o.put("ok", true);
                    o.put("build", Updater.currentVersion(c));
                    o.put("screen", pm != null && pm.isInteractive() ? "on" : "off");
                    o.put("front", Screen.name(c));
                    o.put("filer", FilerActivity.instance != null);
                    o.put("runner", RunnerActivity.instance != null ? RunnerActivity.state : "closed");
                    o.put("running", running != null ? running.optString("id") + " " + running.optString("do") : "");
                    o.put("queued", queue(c).length());
                    o.put("busy", busyReason(c) == null ? "" : busyReason(c));
                    o.put("safe", Crash.safeMode(c));
                    o.put("crash", Crash.last(c));
                    android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    o.put("freeMB", mi.availMem / 1048576);
                    return o;
                }
                case "tv.queue": {
                    o.put("ok", true);
                    o.put("running", running == null ? JSONObject.NULL : running);
                    o.put("queue", queue(c));
                    return o;
                }
                case "tv.cancel": {
                    String id = a.optString("id");
                    synchronized (Api.class) {
                        JSONArray q = queue(c), out = new JSONArray();
                        int n = 0;
                        for (int i = 0; i < q.length(); i++) {
                            if (id.isEmpty() || "all".equals(id) || id.equals(q.optJSONObject(i).optString("id"))) n++;
                            else out.put(q.opt(i));
                        }
                        p(c).edit().putString("queue", out.toString()).apply();
                        o.put("ok", true);
                        o.put("cancelled", n);
                    }
                    if (running != null && (id.equals(running.optString("id")) || "all".equals(id))) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                RunnerActivity r = RunnerActivity.instance;
                                if (r != null) r.cancelApi();
                            }
                        });
                        o.put("stopping", running.optString("id"));
                    }
                    return o;
                }
                case "tv.log": {
                    // one job's full log as a file, or the list of recent jobs
                    final String id = a.optString("id");
                    if (!id.isEmpty()) {
                        String log = readLog(c, id);
                        if (log == null) return err("no log for " + id);
                        post(c, "api log " + id + " attached", log, "api-" + id + "-log.txt");
                        o.put("ok", true);
                        o.put("sent", "log attached in the next comment");
                        return o;
                    }
                    File[] all = new File(c.getFilesDir(), "api").listFiles();
                    JSONArray list = new JSONArray();
                    if (all != null) {
                        Arrays.sort(all, java.util.Collections.reverseOrder(OLDEST));
                        for (int i = 0; i < all.length && i < a.optInt("n", 15); i++) {
                            String log = readLog(c, all[i].getName());
                            String line = all[i].getName();
                            if (log != null) {
                                int r = log.indexOf("\nresult: ");
                                if (r > 0) line += " " + log.substring(r + 9, Math.min(log.length(), log.indexOf('\n', r + 9) < 0 ? log.length() : Math.min(log.indexOf('\n', r + 9), r + 9 + 160)));
                            }
                            list.put(line);
                        }
                    }
                    o.put("ok", true);
                    o.put("jobs", list);
                    return o;
                }
                case "tv.shots": {
                    // a job's screenshots, one comment each
                    String id = a.optString("id");
                    File[] shots = dir(c, id).listFiles(new java.io.FilenameFilter() {
                        @Override
                        public boolean accept(File d, String n) {
                            return n.endsWith(".jpg");
                        }
                    });
                    if (shots == null || shots.length == 0) return err("no pictures for " + id);
                    Arrays.sort(shots);
                    final File[] send = shots.length > 12 ? Arrays.copyOfRange(shots, shots.length - 12, shots.length) : shots;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            String token = Secrets.get(c, Secrets.TODOIST_TOKEN);
                            if (token == null) return;
                            for (File f : send) {
                                try {
                                    byte[] b = bytes(f);
                                    JSONObject att = upload(token, b, f.getName(), "image/jpeg");
                                    JSONObject m = new JSONObject();
                                    m.put("task_id", Remote.TASK);
                                    m.put("content", "TV: api shot " + f.getName());
                                    if (att != null) m.put("attachment", att);
                                    Http.todoist(token, "POST", "comments", m);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }, "api-shots").start();
                    o.put("ok", true);
                    o.put("sending", send.length);
                    return o;
                }
                case "tv.safe": {
                    if (a.optBoolean("off")) Crash.clear(c);
                    o.put("ok", true);
                    o.put("safe", Crash.safeMode(c));
                    return o;
                }
                case "tv.wake": {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            ListenService.wakeScreen(c);
                            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                            if (pm != null && !pm.isInteractive()) WakeActivity.start(c, false);
                        }
                    });
                    o.put("ok", true);
                    return o;
                }
                default:
                    return err("unknown tv request " + what + " (tv.ping, tv.queue, tv.cancel, tv.log, tv.shots, tv.safe, tv.wake)");
            }
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }
}
