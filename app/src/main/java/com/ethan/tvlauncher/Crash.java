package com.ethan.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Crash catcher and safe mode (21/11). Any crash gets its stack trace kept and posted on the Todoist remote the next
 * time the app's up, so it can be fixed from anywhere. Two crashes inside 10 minutes puts the TV in safe mode: api jobs
 * stop (tv.* requests, the remote, updates and the heartbeat keep going), so a bad change can always be undone with a
 * new build. tv.safe with {"off":true} clears it, and so does a new build.
 */
final class Crash {

    private static final String PREFS = "crash";
    private static boolean installed;

    private Crash() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void install(final Context ctx) {
        if (installed) return;
        installed = true;
        final Context c = ctx.getApplicationContext();
        // a new build clears safe mode (that's how a fix gets in)
        int build = Updater.currentVersion(c);
        if (p(c).getInt("build", -1) != build) p(c).edit().putInt("build", build).remove("count").remove("first").remove("safe").apply();
        final Thread.UncaughtExceptionHandler was = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String trace = sw.toString();
                    long now = System.currentTimeMillis();
                    SharedPreferences s = p(c);
                    long first = s.getLong("first", 0);
                    int n = now - first < 10 * 60 * 1000 ? s.getInt("count", 0) + 1 : 1;
                    s.edit().putString("trace", "build " + Updater.currentVersion(c) + ", thread " + t.getName() + "\n"
                                    + (trace.length() > 6000 ? trace.substring(0, 6000) : trace))
                            .putLong("at", now).putBoolean("posted", false)
                            .putLong("first", n == 1 ? now : first).putInt("count", n)
                            .putBoolean("safe", n >= 2).commit();
                } catch (Throwable ignored) {
                }
                if (was != null) was.uncaughtException(t, e);
            }
        });
    }

    static boolean safeMode(Context c) {
        return p(c).getBoolean("safe", false);
    }

    static void clear(Context c) {
        p(c).edit().remove("safe").remove("count").remove("first").apply();
    }

    /** Short line on the last crash for tv.ping, empty if none. */
    static String last(Context c) {
        SharedPreferences s = p(c);
        String t = s.getString("trace", "");
        if (t.isEmpty()) return "";
        String first = t.split("\n").length > 2 ? t.split("\n")[1] : t;
        return new java.text.SimpleDateFormat("d/M h:mm a", java.util.Locale.US).format(new java.util.Date(s.getLong("at", 0))) + " " + first;
    }

    /** Called once the remote's running: posts a crash that hasn't been reported yet. */
    static void report(Context c) {
        SharedPreferences s = p(c);
        if (s.getBoolean("posted", true) || s.getString("trace", "").isEmpty()) return;
        s.edit().putBoolean("posted", true).apply();
        Api.post(c, "The app crashed at " + new java.text.SimpleDateFormat("d/M h:mm:ss a", java.util.Locale.US)
                        .format(new java.util.Date(s.getLong("at", 0))) + (safeMode(c) ? ". Two crashes in 10 min, so it's in safe mode (api jobs paused)." : ".")
                        + " Trace attached.", s.getString("trace", ""), "crash.txt");
    }
}
