package com.ethan.tvlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the TV remote listening, including while the TV looks switched off. Holds a "stay awake" lock so Android
 * doesn't put the TV into deep sleep, and notes when it did sleep anyway (so we know if the TV's hardware forces it).
 */
public class ListenService extends Service {

    private static final String CH = "listen";
    private PowerManager.WakeLock cpu;
    private Handler bg;
    private long lastBeat;
    private boolean screenWasOn = true;

    static void start(Context c) {
        Intent i = new Intent(c, ListenService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            TvLog.line(c, "Couldn't start the remote listener: " + e.getMessage());
        }
    }

    static final long PEEK_MS = 5 * 60000;

    /** The screen-off mail check, every 5 min while the TV's off or on the home screen. */
    static boolean peekOn(Context c) {
        return prefs(c).getBoolean("peek", true);
    }

    static void setPeekOn(Context c, boolean on) {
        prefs(c).edit().putBoolean("peek", on).apply();
    }

    /** Whether a hit wakes the TV and runs the Filer. Off while we're still testing the check. */
    static boolean peekOpens(Context c) {
        return prefs(c).getBoolean("peekOpens", false);
    }

    static void setPeekOpens(Context c, boolean on) {
        prefs(c).edit().putBoolean("peekOpens", on).apply();
    }

    static boolean stayAwake(Context c) {
        return prefs(c).getBoolean("stayAwake", true);
    }

    static void setStayAwake(Context c, boolean on) {
        prefs(c).edit().putBoolean("stayAwake", on).apply();
        start(c);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("listen", Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        RemoteServer.start(this); // the phone remote page, over Tailscale
        HandlerThread t = new HandlerThread("listen-beat");
        t.start();
        bg = new Handler(t.getLooper());
        lastBeat = System.currentTimeMillis();
        bg.postDelayed(beat, 60000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "Sync", NotificationManager.IMPORTANCE_MIN));
            }
        }
        Notification n = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this))
                .setContentTitle("Sync service")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
        startForeground(1, n);
        applyLock();
        Remote.start(this);
        return START_STICKY;
    }

    private void applyLock() {
        boolean want = stayAwake(this);
        if (want && cpu == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvlauncher:listen");
            cpu.setReferenceCounted(false);
            cpu.acquire();
            TvLog.line(this, "Remote listener on, staying awake in standby");
        } else if (!want && cpu != null) {
            cpu.release();
            cpu = null;
            TvLog.line(this, "Remote listener on, TV allowed to sleep in standby");
        }
    }

    /** Every minute: notes screen on/off, and any time the TV slept so deeply the clock jumped. */
    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long gap = now - lastBeat;
            if (gap > 3 * 60000) {
                SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.getDefault());
                TvLog.line(ListenService.this, "TV was in deep sleep from " + f.format(new Date(lastBeat)) + " to "
                        + f.format(new Date(now)) + " (" + (gap / 60000) + " min), remote couldn't hear");
            }
            lastBeat = now;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean on = pm != null && pm.isInteractive();
            if (on != screenWasOn) {
                TvLog.line(ListenService.this, on ? "TV switched on" : "TV switched off (standby), still listening");
                screenWasOn = on;
            }
            peekIfDue(on);
            queueIfDue(on);
            if (now - Status.lastWrite > 5 * 60000) Status.write(ListenService.this, null);
            bg.postDelayed(this, 60000);
        }
    };

    private long lastPeek;

    private void peekIfDue(final boolean screenOn) {
        final Context c = this;
        if (!peekOn(c) || System.currentTimeMillis() - lastPeek < PEEK_MS) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (FilerActivity.instance != null || RunnerActivity.instance != null || Peek.busy) return; // Filer's open: it's watching mail itself
                if (screenOn && !Updater.homeVisible) return; // something's being watched, wait
                lastPeek = System.currentTimeMillis();
                peekNow(c, null);
            }
        });
    }

    private long lastQueue;

    /**
     * 20/11: notes and site info lines queued for AroFlo (filer-note, filer-site, from the dashboard or the inbox check)
     * only go on while the Filer's open. Every 5 min while the TV's off or on the home screen, open it for them like a
     * builder email does. Same switch as the mail check opening the Filer.
     */
    private void queueIfDue(final boolean screenOn) {
        final Context c = this;
        if (!peekOn(c) || !peekOpens(c) || System.currentTimeMillis() - lastQueue < PEEK_MS) return;
        if (FilerActivity.instance != null || RunnerActivity.instance != null) return;
        lastQueue = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = queued(c);
                if (n <= 0) return;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (FilerActivity.instance != null || RunnerActivity.instance != null || Peek.busy) return;
                        // one go per half hour, so an item the filer can't do doesn't keep waking the TV
                        if (System.currentTimeMillis() - prefs(c).getLong("queueOpenAt", 0) < 30 * 60000) return;
                        TvLog.line(c, "Note queue: " + n + " waiting for AroFlo");
                        openIfFree(c, null, false);
                    }
                });
            }
        }, "queue-check").start();
    }

    static final String FU_PROJECT = "6hfHcM9Gf74fgcJw";

    /** How many follow ups are queued for the filer to put on AroFlo. -1 if Todoist can't be reached. Not the main thread. */
    static int queued(Context c) {
        try {
            String token = Secrets.get(c, Secrets.TODOIST_TOKEN);
            if (token == null) return -1;
            String q = java.net.URLEncoder.encode("@filer-note | @filer-site", "UTF-8");
            org.json.JSONArray a = new org.json.JSONObject(Http.todoist(token, "GET", "tasks/filter?query=" + q + "&limit=50", null))
                    .optJSONArray("results");
            int n = 0;
            if (a != null) for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject t = a.getJSONObject(i);
                if (FU_PROJECT.equals(t.optString("project_id")) && !t.optBoolean("checked")) n++;
            }
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Main thread. Runs the check, logs it, and opens the Filer on a hit if that's switched on. */
    static void peekNow(final Context c, final Peek.Done after) {
        peekNow(c, after, false);
    }

    /**
     * test: treat the newest builder email in the list as new (even though the filer's had it) and run the whole thing,
     * wake, Filer, close, sleep. The filer still goes by its own seen list, so nothing gets filed twice.
     */
    static void peekNow(final Context c, final Peek.Done after, final boolean test) {
        Peek.run(c, new Peek.Done() {
            @Override
            public void done(Peek.Result r) {
                String extra = "";
                // a signed out Outlook means no builder email wakes the TV: say so once, tick it off once it's back
                if (r.ok) Alerts.clear(c, "outlook-out");
                else if (r.error.contains("signed out")) Alerts.raise(c, "outlook-out", "TV: Outlook is signed out on the TV",
                        "The TV's mail check found Outlook signed out, so no builder email will wake it. Open the Filer on the "
                                + "TV (or send open filer) so it signs back in.");
                Status.write(c, r);
                if (test && r.ok) {
                    Peek.Result t = new Peek.Result();
                    Peek.analyse(c, r.items, t, true);
                    if (t.relevant == 0) extra = " TEST: no builder email in the list to test with.";
                    else {
                        extra = " TEST: treating " + t.firstNew + " as new.";
                        openIfFree(c, t, true);
                    }
                } else if (r.relevant > 0) {
                    if (peekOpens(c)) openIfFree(c, r, false);
                    else extra = " (log only: not opening the Filer)";
                }
                TvLog.line(c, r.summary() + extra);
                if (after != null) after.done(r);
            }
        });
    }

    /** Checks the PC hasn't got the filer (Todoist device lock), then opens it here. Any thread. */
    private static void openIfFree(final Context c, final Peek.Result r, final boolean test) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String busyOn = null;
                try {
                    String token = Secrets.get(c, Secrets.TODOIST_TOKEN);
                    if (token != null) {
                        String d = new org.json.JSONObject(Http.todoist(token, "GET", "tasks/" + FilerActivity.DEV_TASK, null)).optString("description");
                        java.util.regex.Matcher id = java.util.regex.Pattern.compile("(?m)^id: (\\S+)$").matcher(d);
                        java.util.regex.Matcher last = java.util.regex.Pattern.compile("(?m)^last: (\\d+)$").matcher(d);
                        java.util.regex.Matcher dev = java.util.regex.Pattern.compile("(?m)^device: (.+)$").matcher(d);
                        String mine = GmStore.get(c).getJson("ecDeviceId");
                        if (mine != null) mine = mine.replace("\"", "");
                        if (id.find() && last.find() && !id.group(1).equals(mine)
                                && System.currentTimeMillis() - Long.parseLong(last.group(1)) < 180000)
                            busyOn = dev.find() ? dev.group(1) : "another device";
                    }
                } catch (Exception ignored) {
                    // can't reach Todoist: go ahead, the filer checks the lock itself and stands by if it has to
                }
                final String other = busyOn;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        String what = r == null ? "Note queue" : "Mail check";
                        TvLog.line(c, other != null ? what + ": not opening the Filer, it's running on the " + other
                                : what + ":" + (r == null ? openForQueue(c) : openForMail(c, r, test)));
                    }
                });
            }
        }, "open-check").start();
    }

    /** Notes are waiting: wake the TV if it's off and run the Filer, which closes itself once they're on. */
    private static String openForQueue(final Context c) {
        if (FilerActivity.instance != null) return " Filer's already open.";
        if (RunnerActivity.instance != null) return " The Runner's going, leaving it.";
        android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean on = pm != null && pm.isInteractive();
        if (on && !Updater.homeVisible) return " Not opening the Filer, something's on the TV.";
        prefs(c).edit().putLong("queueOpenAt", System.currentTimeMillis()).apply();
        if (!on) wakeScreen(c);
        WakeActivity.start(c, true, true, !on);
        return " Opening the Filer to put them on" + (on ? "." : " (waking the TV).");
    }

    /** A builder email's in: wake the TV if it's off and run the Filer, which closes itself once it's filed it. */
    private static String openForMail(final Context c, Peek.Result r, boolean test) {
        if (FilerActivity.instance != null) return " Filer's already open.";
        if (RunnerActivity.instance != null) return " The Runner's going, leaving it.";
        android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean on = pm != null && pm.isInteractive();
        if (on && !Updater.homeVisible) return " Not opening the Filer, something's on the TV.";
        // the same email doesn't open it more than once an hour (say the filer skipped it, or the PC's got it)
        long was = prefs(c).getLong("openedFor", 0), at = prefs(c).getLong("openedAt", 0);
        if (!test && was == r.newestRelevant && System.currentTimeMillis() - at < 60 * 60000) return " Already opened the Filer for this one.";
        prefs(c).edit().putLong("openedFor", r.newestRelevant).putLong("openedAt", System.currentTimeMillis()).apply();
        if (!on) wakeScreen(c);
        WakeActivity.start(c, true, true, !on);
        return " Opening the Filer" + (on ? "." : " (waking the TV).");
    }

    /** Turns the TV's screen back on (only works if the TV's awake enough to hear the remote). */
    @SuppressWarnings("deprecation")
    static void wakeScreen(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(POWER_SERVICE);
        if (pm == null) return;
        // Try 1: wake lock that switches the screen on (some TVs ignore this one)
        try {
            PowerManager.WakeLock w = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "tvlauncher:wake");
            w.acquire(10000);
        } catch (Exception e) {
            TvLog.line(c, "Wake: wake lock refused: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (cpu != null) cpu.release();
        super.onDestroy();
    }
}
