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
