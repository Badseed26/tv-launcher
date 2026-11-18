package com.ethan.tvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * Helper whose only job is to press Home after the launcher updates, so you land
 * back on the home screen instead of the last app. It doesn't read the screen.
 */
public class HomeButtonService extends AccessibilityService {

    static volatile HomeButtonService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (Updater.consumeGoHome(this)) goHomeSoon();
        // Starts listening to the TV remote task in Todoist (runs whenever the TV's on).
        Remote.start(this);
        ListenService.start(this);
    }

    /** Press Home a moment after the update settles, and once more to be sure. */
    void goHomeSoon() {
        Runnable home = new Runnable() {
            @Override
            public void run() {
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        };
        handler.postDelayed(home, 1500);
        handler.postDelayed(home, 4000);
    }

    /** After an auto run that started with the TV off: go home, then put the TV back to sleep. */
    static void sleepTvSoon(final android.content.Context c) {
        final HomeButtonService h = instance;
        if (h == null) {
            TvLog.line(c, "Auto run: couldn't put the TV back to sleep (helper not running)");
            return;
        }
        h.handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                h.performGlobalAction(GLOBAL_ACTION_HOME);
            }
        }, 800);
        h.handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean ok = android.os.Build.VERSION.SDK_INT >= 28 && h.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                TvLog.line(c, ok ? "Auto run: TV back to sleep" : "Auto run: couldn't put the TV back to sleep");
            }
        }, 3000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
