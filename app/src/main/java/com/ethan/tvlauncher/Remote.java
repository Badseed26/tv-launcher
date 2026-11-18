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
    private static final String HELP = "Commands: wake, open filer, close filer, status, log, reload, press <button text>, code <number>, stay awake on, stay awake off, check mail, test mail check, mail check on, mail check off, mail check opens on, mail check opens off";

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
        for (int page = 0; page < 5; page++) {
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
        for (JSONObject o : all) {
            String id = o.optString("id");
            if (seen.contains("," + id + ",")) continue;
            seen = seen + id + ",";
            if (seen.length() > 6000) seen = "," + seen.substring(seen.length() - 4000).replaceFirst("^[^,]*,", "");
            p.edit().putString("seen", seen).apply();
            String text = o.optString("content", "").trim();
            if (text.isEmpty() || text.startsWith("TV:")) continue;
            final String cmd = text.replaceAll("\\s+", " ");
            handle(token, cmd);
        }
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
                            + (f != null && f.isAuto() ? "\nThis Filer was opened by the mail check." : "");
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
