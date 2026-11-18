package com.ethan.tvlauncher;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.WindowManager;

/** Blank screen that exists only to switch the TV back on, then goes Home or opens the Filer. */
public class WakeActivity extends Activity {

    static void start(Context c, boolean openFiler) {
        start(c, openFiler, false, false);
    }

    static void start(Context c, boolean openFiler, boolean auto, boolean wasOff) {
        Context from = HomeButtonService.instance != null ? HomeButtonService.instance : c;
        Intent i = new Intent(from, WakeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("filer", openFiler);
        i.putExtra("auto", auto);
        i.putExtra("wasOff", wasOff);
        try {
            from.startActivity(i);
        } catch (Exception e) {
            TvLog.line(c, "Wake: couldn't start the wake screen: " + e.getMessage());
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null && Build.VERSION.SDK_INT >= 26) km.requestDismissKeyguard(this, null);
        } catch (Exception ignored) {
        }
        final boolean filer = getIntent().getBooleanExtra("filer", false);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                boolean on = pm != null && pm.isInteractive();
                TvLog.line(WakeActivity.this, "Wake screen: TV " + (on ? "is on" : "still off"));
                Intent next = new Intent(WakeActivity.this, filer ? FilerActivity.class : MainActivity.class);
                next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                next.putExtra("auto", getIntent().getBooleanExtra("auto", false));
                next.putExtra("wasOff", getIntent().getBooleanExtra("wasOff", false));
                startActivity(next);
                finish();
            }
        }, 1500);
    }
}
