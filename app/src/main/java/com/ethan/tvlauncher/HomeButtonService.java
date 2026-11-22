package com.ethan.tvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * Helper that presses Home after the launcher updates, Install during a remote update, and (22/11, movie finder) reads
 * and presses streaming apps' menus when Claude asks (Stream.java, never while a movie's playing). Other apps: their
 * package name only.
 */
public class HomeButtonService extends AccessibilityService {

    static volatile HomeButtonService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        // movie finder: see the streaming apps' whole menus (tiles often aren't marked important)
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                        // and which window has the remote's focus, so the app in front is never a stale guess
                        | android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
        } catch (Exception ignored) {
        }
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

    /**
     * A picture of the whole screen (Android 11 on), for the phone page when a streaming app's up (Ethan, 22/11: like
     * the app's own screens, just never video playback). Protected video comes out black anyway, and the phone page
     * doesn't ask while a movie's playing. Null when it can't (too soon after the last one, under a second).
     */
    void shot(final Screen.Shot cb) {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            cb.done(null);
            return;
        }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    byte[] jpg = null;
                    try {
                        android.hardware.HardwareBuffer hb = r.getHardwareBuffer();
                        android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw != null) {
                            android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            if (sw != null) jpg = Screen.jpeg(sw, sw.getWidth());
                        }
                        hb.close();
                    } catch (Throwable ignored) {
                    }
                    cb.done(jpg);
                }

                @Override
                public void onFailure(int code) {
                    cb.done(null);
                }
            });
        } catch (Throwable e) {
            cb.done(null);
        }
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
        // Phone remote (20/11): note which app came to the front, its package name only. Nothing from inside it.
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) Screen.saw(this, event.getPackageName());
        // Only while a remote update is going: press Install on the system prompt for our own app, nothing else.
        if (System.currentTimeMillis() > Updater.autoConfirmUntil || event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !pkg.toString().contains("packageinstaller")) return;
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String me = String.valueOf(getApplicationInfo().loadLabel(getPackageManager()));
        if (root.findAccessibilityNodeInfosByText(me).isEmpty()) return; // not our app's prompt
        for (String label : new String[]{"Install", "Update", "INSTALL", "UPDATE"}) {
            for (android.view.accessibility.AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
                CharSequence t = n.getText();
                if (t == null || !t.toString().trim().equalsIgnoreCase(label)) continue;
                android.view.accessibility.AccessibilityNodeInfo b = n;
                while (b != null && !b.isClickable()) b = b.getParent();
                if (b != null && b.isEnabled() && b.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                    Updater.autoConfirmUntil = System.currentTimeMillis() + 60000; // keep watching in case there's a second screen
                    TvLog.line(this, "Update: pressed " + t + " on the install prompt");
                    return;
                }
            }
        }
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
