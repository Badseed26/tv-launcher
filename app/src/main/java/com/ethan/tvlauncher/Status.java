package com.ethan.tvlauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

/**
 * The TV's heartbeat on the dashboard (19/11). After every mail check, and at least every 5 min, the TV writes where it's
 * at into the TV remote task's description. The dashboard shows how long ago that was, so a dead TV (power out, Wi-Fi
 * down, app killed) shows up instead of looking like a quiet inbox.
 */
final class Status {
    static volatile long lastWrite;

    private Status() {
    }

    /** Any thread. r is the mail check just done, or null. */
    static void write(final Context ctx, final Peek.Result r) {
        final Context c = ctx.getApplicationContext();
        lastWrite = System.currentTimeMillis();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                boolean on = pm != null && pm.isInteractive();
                FilerActivity f = FilerActivity.instance;
                long now = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder("TV status, the TV writes this every few minutes. Don't edit.\n\n");
                sb.append("last: ").append(now).append('\n');
                sb.append("at: ").append(Peek.time(now)).append('\n');
                sb.append("build: ").append(Updater.currentVersion(c)).append('\n');
                sb.append("screen: ").append(on ? (Updater.homeVisible ? "on, home screen" : "on") : "off (standby)").append('\n');
                sb.append("filer: ").append(f == null ? "closed" : f.remoteStatus().replace("\n", " | ")).append('\n');
                sb.append("mail check: ").append(ListenService.peekOn(c) ? (ListenService.peekOpens(c) ? "on" : "log only") : "off").append('\n');
                String last = r != null ? Peek.time(now) + " " + r.summary() : Peek.last;
                if (!last.isEmpty()) sb.append("last check: ").append(last.length() > 300 ? last.substring(0, 300) + "..." : last).append('\n');
                final String desc = sb.toString();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String token = Secrets.get(c, Secrets.TODOIST_TOKEN);
                            if (token == null) return;
                            JSONObject b = new JSONObject();
                            b.put("description", desc);
                            Http.todoist(token, "POST", "tasks/" + Remote.TASK, b);
                        } catch (Exception ignored) {
                            // next beat tries again
                        }
                    }
                }, "status").start();
            }
        });
    }
}
