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
            bg.postDelayed(this, 60000);
        }
    };

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
