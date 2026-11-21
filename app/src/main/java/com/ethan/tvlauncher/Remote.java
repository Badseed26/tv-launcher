package com.ethan.tvlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "* TV remote" task in Todoist. Every 20 seconds while the TV's on, new comments on it are read as
 * Filer commands and answered with a comment starting "TV:". Filer commands only, nothing else on the TV.
 */
final class Remote {

    static final String TASK = "6j3H69pvQ9hQ86pX";
    private static final long EVERY_MS = 20000;
    private static final String HELP = "Commands: wake, open filer, close filer, status, log, reload, press <button text>, code <number>, stay awake on, stay awake off, update, web remote, web remote new pin, check mail, test mail check, mail check on, mail check off, mail check opens on, mail check opens off, inbox pull <day>, lookup <terms; ...>";

    private static Remote inst;

    static synchronized void start(Context c) {
        if (inst == null) inst = new Remote(c.getApplicationContext());
        inst.go();
    }

    private final Context c;
    private final Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean going;

    private Remote(Context c) {
        this.c = c;
        HandlerThread t = new HandlerThread("tv-remote");
        t.start();
        bg = new Handler(t.getLooper());
    }

    private void go() {
        if (going) return;
        going = true;
        bg.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                check();
            } catch (Exception ignored) {
            }
            bg.postDelayed(this, EVERY_MS);
        }
    };

    private SharedPreferences prefs() {
        return c.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    private void check() throws Exception {
        String token = Secrets.get(c, Secrets.TODOIST_TOKEN);
        if (token == null) return;
        List<JSONObject> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            String path = "comments?task_id=" + TASK + "&limit=100" + (cursor != null ? "&cursor=" + URLEncoder.encode(cursor, "UTF-8") : "");
            JSONObject res = new JSONObject(Http.todoist(token, "GET", path, null));
            JSONArray a = res.optJSONArray("results");
            if (a != null) for (int i = 0; i < a.length(); i++) all.add(a.getJSONObject(i));
            cursor = res.isNull("next_cursor") ? null : res.optString("next_cursor", null);
            if (cursor == null || cursor.isEmpty()) break;
        }

        SharedPreferences p = prefs();
        String seen = p.getString("seen", null);
        if (seen == null) {
            // First run: don't act on anything already there.
            StringBuilder sb = new StringBuilder(",");
            for (JSONObject o : all) sb.append(o.optString("id")).append(',');
            p.edit().putString("seen", sb.toString()).apply();
            return;
        }
        long now = System.currentTimeMillis();
        for (JSONObject o : all) {
            String id = o.optString("id");
            if (seen.contains("," + id + ",")) continue;
            // 19/11: the seen list only holds the last couple of hundred ids, so a long-lived task would start re-running
            // old commands. Anything over 30 min old is never a fresh command.
            long at = postedAt(o);
            if (at > 0 && now - at > 30 * 60000) continue;
            seen = seen + id + ",";
            if (seen.length() > 6000) seen = "," + seen.substring(seen.length() - 4000).replaceFirst("^[^,]*,", "");
            p.edit().putString("seen", seen).apply();
            String text = o.optString("content", "").trim();
            if (text.isEmpty() || text.startsWith("TV:")) continue;
            // lookups can come one a line: keep them apart
            final String cmd = text.toLowerCase(Locale.ROOT).startsWith("lookup")
                    ? text.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "; ") : text.replaceAll("\\s+", " ");
            handle(token, cmd);
        }
        prune(token, all);
    }

    /** Comments older than 2 days get deleted, an hour apart at most, so the task (and every poll) stays small. */
    private void prune(String token, List<JSONObject> all) {
        SharedPreferences p = prefs();
        long now = System.currentTimeMillis();
        if (now - p.getLong("pruned", 0) < 60 * 60000) return;
        p.edit().putLong("pruned", now).apply();
        int n = 0;
        for (JSONObject o : all) {
            long at = postedAt(o);
            if (at <= 0 || now - at < 2 * 24 * 3600000L) continue;
            try {
                Http.todoist(token, "DELETE", "comments/" + o.optString("id"), null);
                n++;
            } catch (Exception e) {
                break;
            }
            if (n >= 40) break;
        }
        if (n > 0) TvLog.line(c, "Remote: cleared " + n + " old comments off the TV remote task");
    }

    private static long postedAt(JSONObject o) {
        String s = o.optString("posted_at", "");
        if (s.isEmpty()) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = f.parse(s.length() >= 19 ? s.substring(0, 19) : s);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    interface Say {
        void say(String text);
    }

    private void say(final String token, String text) {
        final String out = "TV: " + text;
        bg.post(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("task_id", TASK);
                    b.put("content", out.length() > 14000 ? out.substring(0, 14000) : out);
                    Http.todoist(token, "POST", "comments", b);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void handle(final String token, final String cmd) {
        final String lc = cmd.toLowerCase(Locale.ROOT);
        main.post(new Runnable() {
            @Override
            public void run() {
                FilerActivity f = FilerActivity.instance;
                if (f != null) f.remoteLog("Remote: " + cmd);
                else TvLog.line(c, "Remote: " + cmd);
                android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
                boolean screenOn = pm != null && pm.isInteractive();
                String reply;
                if (lc.equals("wake") || lc.equals("turn on")) {
                    ListenService.wakeScreen(c);
                    // Try 2: a screen that asks Android to switch the TV on as it opens
                    if (!screenOn) WakeActivity.start(c, false);
                    reply = screenOn ? "TV was already on." : "Turning the TV on. Send status in a few seconds to check.";
                } else if (lc.equals("weather") || lc.startsWith("weather ")) {
                    // Home screen weather (21/11): "weather <town>" sets the place, "weather off" hides it
                    final String town = cmd.trim().substring(7).trim();
                    if (town.isEmpty()) {
                        String[] l = Weather.lines(c);
                        reply = !Weather.hasPlace(c) ? "No weather place set. Send weather <town> to set one."
                                : "Weather for " + Weather.place(c) + (l != null ? ": " + l[0] + " " + l[1] : ", nothing fetched yet.");
                    } else if (town.equalsIgnoreCase("off")) {
                        Weather.clear(c);
                        reply = "Weather's off the home screen.";
                    } else {
                        reply = "Looking up " + town + "...";
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                say(token, Weather.setPlace(c, town));
                            }
                        }, "weather-place").start();
                    }
                } else if (lc.equals("tv code") || lc.startsWith("tv code ")) {
                    // The code the TV asks for before the Filer or Runner opens from the TV itself (21/11)
                    String n = lc.substring(7).trim();
                    if (n.isEmpty()) {
                        reply = Lock.ownCode(c) ? "The TV code is " + Lock.code(c) + "." : "No TV code set, the TV uses the phone page PIN (" + RemoteServer.pin(c) + "). Send tv code <4 to 8 digits> to set one.";
                    } else if (!n.matches("\\d{4,8}")) {
                        reply = "The TV code has to be 4 to 8 digits, nothing changed.";
                    } else {
                        Lock.setCode(c, n);
                        reply = "TV code set. The TV asks for it before the Filer or Runner opens from the TV, the phone page doesn't.";
                    }
                } else if (lc.equals("web remote") || lc.equals("web remote new pin")) {
                    String pin = lc.endsWith("new pin") ? RemoteServer.newPin(c) : RemoteServer.pin(c);
                    String ip = RemoteServer.tailscaleIp();
                    reply = ip == null
                            ? "Tailscale isn't connected on the TV, so the phone page can't be reached. Open Tailscale on the TV and check it's on."
                            : "Phone remote: open http://" + ip + ":" + RemoteServer.PORT + " on your phone (Tailscale on), PIN " + pin
                            + (lc.endsWith("new pin") ? ". New PIN, any phone that was logged in has to log in again." : ".")
                            + " In Safari, Share > Add to Home Screen to make it an app.";
                } else if (lc.equals("update") || lc.equals("update tv")) {
                    Updater.remoteUpdate(c, new Say() {
                        @Override
                        public void say(String text) {
                            Remote.this.say(token, text);
                        }
                    });
                    reply = null;
                } else if (lc.equals("inbox pull") || lc.startsWith("inbox pull ") || lc.equals("pull") || lc.startsWith("pull ")) {
                    String arg = cmd.replaceFirst("(?i)^(inbox )?pull\\s*", "").trim();
                    String day = RunnerActivity.parseDay(arg);
                    if (day == null) {
                        reply = "Can only pull today or the 6 days before (like the dashboard). Try inbox pull today, yesterday, thu or 19/11.";
                    } else {
                        RunnerActivity.Job j = new RunnerActivity.Job();
                        j.kind = "pull";
                        j.day = day;
                        j.from = "remote";
                        j.say = new Say() {
                            @Override
                            public void say(String text) {
                                Remote.this.say(token, text);
                            }
                        };
                        String r = RunnerActivity.start(c, j);
                        reply = r != null ? r : "Pulling the inbox for " + RunnerActivity.nice(day)
                                + (f != null ? " (Filer closed for now, it reopens after)" : "")
                                + ". Takes a couple of minutes, the bundle comes back here as a file.";
                    }
                } else if (lc.startsWith("lookup")) {
                    List<String> terms = new ArrayList<>();
                    for (String t : cmd.substring(6).split(";")) if (!t.replaceFirst("^[\\s:,]+", "").trim().isEmpty()) terms.add(t.replaceFirst("^[\\s:,]+", "").trim());
                    if (terms.isEmpty()) {
                        reply = "lookup what? e.g. lookup 9364; Norman Dr; po 20251598; notes 9364";
                    } else {
                        RunnerActivity.Job j = new RunnerActivity.Job();
                        j.kind = "lookup";
                        j.terms = terms;
                        j.from = "remote";
                        j.say = new Say() {
                            @Override
                            public void say(String text) {
                                Remote.this.say(token, text);
                            }
                        };
                        reply = RunnerActivity.start(c, j);
                    }
                } else if (lc.equals("test mail check")) {
                    if (f != null) {
                        reply = "Close the Filer first, the test opens it itself.";
                    } else {
                        ListenService.peekNow(c, new Peek.Done() {
                            @Override
                            public void done(Peek.Result r) {
                                say(token, r.summary() + " (test: see the log for what happens next)");
                            }
                        }, true);
                        reply = "Test mail check: checking the mail, then waking the TV and running the Filer on the newest builder email.";
                    }
                } else if (lc.equals("check mail") || lc.equals("peek")) {
                    if (f != null) {
                        reply = "Filer's open, it's watching the mail itself.";
                    } else {
                        ListenService.peekNow(c, new Peek.Done() {
                            @Override
                            public void done(Peek.Result r) {
                                say(token, r.summary());
                            }
                        });
                        reply = "Checking the mail, back in a minute.";
                    }
                } else if (lc.equals("mail check on") || lc.equals("mail check off")) {
                    boolean on = lc.endsWith("on");
                    ListenService.setPeekOn(c, on);
                    reply = on ? "Mail check on: every 5 min while the TV's off or on the home screen."
                            : "Mail check off.";
                } else if (lc.equals("mail check opens on") || lc.equals("mail check opens off")) {
                    boolean on = lc.endsWith("on");
                    ListenService.setPeekOpens(c, on);
                    reply = on ? "A builder email will now wake the TV and run the Filer, then put it back to sleep."
                            : "Mail check will only log what it finds, it won't open the Filer.";
                } else if (lc.equals("stay awake on") || lc.equals("stay awake off")) {
                    boolean on = lc.endsWith("on");
                    ListenService.setStayAwake(c, on);
                    reply = on ? "Stay awake on: the TV keeps listening while it looks off (about 15 W)."
                            : "Stay awake off: the TV sleeps properly in standby and can't hear the remote till it's switched on.";
                } else if (lc.equals("open filer") || lc.equals("open")) {
                    if (!screenOn) ListenService.wakeScreen(c);
                    if (f != null) {
                        reply = "Filer's already open.";
                    } else if (!screenOn) {
                        WakeActivity.start(c, true);
                        reply = "Turning the TV on and opening the Filer. Send status in a minute to check.";
                    } else {
                        Intent i = new Intent(c, FilerActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            c.startActivity(i);
                            reply = "Opening the Filer. Send status in a minute to check both are up.";
                        } catch (Exception e) {
                            reply = "Couldn't open the Filer: " + e.getMessage();
                        }
                    }
                } else if (lc.equals("close filer") || lc.equals("close")) {
                    if (f == null) {
                        reply = "Filer's already closed.";
                    } else {
                        f.finish();
                        reply = "Filer closed, both sessions shut down.";
                    }
                } else if (lc.equals("status")) {
                    String tv = (screenOn ? "TV is on" : "TV looks off (standby) but it's listening")
                            + (ListenService.stayAwake(c) ? ", stay awake on" : ", stay awake off")
                            + (ListenService.peekOn(c) ? ", mail check on" + (ListenService.peekOpens(c) ? " (opens the Filer)" : " (log only)") : ", mail check off") + "."
                            + (Peek.last.isEmpty() ? "" : "\nLast " + Peek.last)
                            + (f != null && f.isAuto() ? "\nThis Filer was opened by the mail check." : "")
                            + (RunnerActivity.instance != null ? "\nRunner: " + RunnerActivity.state : "");
                    reply = f == null ? "Filer's closed. " + tv : f.remoteStatus() + "\n" + tv;
                } else if (lc.equals("log")) {
                    List<String> l = f != null ? f.filerLog(20) : null;
                    if (l == null || l.isEmpty()) {
                        reply = "TV log:\n" + FilerActivity.tail(c, 20);
                    } else {
                        StringBuilder sb = new StringBuilder("Filer log, newest first:");
                        for (String s : l) sb.append('\n').append(s);
                        reply = sb.toString();
                    }
                } else if (lc.equals("reload")) {
                    if (f == null) {
                        reply = "Filer's closed, send open filer.";
                    } else {
                        f.remoteReload();
                        reply = "Reloading Outlook and AroFlo.";
                    }
                } else if (lc.startsWith("press ")) {
                    String label = cmd.substring(6).trim();
                    reply = f == null ? "Filer's closed." : f.pressOnAroFlo(label)
                            ? "Asked AroFlo to press " + label + ". Send status to check." : "AroFlo isn't running.";
                } else if (lc.startsWith("code ")) {
                    String code = cmd.substring(5).replaceAll("\\s", "");
                    reply = f == null ? "Filer's closed." : f.enterAroFloCode(code)
                            ? "Code sent to AroFlo. Send status to check." : "AroFlo isn't running.";
                } else {
                    reply = "Didn't get that. " + HELP;
                }
                if (reply == null) return; // answered later (update)
                final String out = "TV: " + reply;
                bg.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject b = new JSONObject();
                            b.put("task_id", TASK);
                            b.put("content", out.length() > 14000 ? out.substring(0, 14000) : out);
                            Http.todoist(token, "POST", "comments", b);
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        });
    }
}
