package com.ethan.tvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * What's on the TV, for the phone remote (Ethan, 20/11). Our own screens (home, all apps, Filer, Runner and anything
 * we build later) get a picture. Any other app or input only gets its name, never a picture.
 * Every activity of ours calls on() in onResume and off() in onPause.
 */
final class Screen {
    private Screen() {
    }

    interface Shot {
        void done(byte[] jpg);
    }

    /** Our activity that's on screen right now, null when it's another app or the TV's off. */
    static volatile Activity top;
    /** The last other app the helper saw come to the front (package name only). */
    static volatile String otherPkg;

    static void on(Activity a) {
        top = a;
    }

    static void off(Activity a) {
        if (top == a) top = null;
    }

    /** From the accessibility helper: a window came to the front. Only the package name is kept. */
    static void saw(Context c, CharSequence pkg) {
        if (pkg == null) return;
        String p = pkg.toString();
        // overlays and keyboards don't count as the app on the TV
        if (p.equals(c.getPackageName()) || p.equals("android") || p.equals("com.android.systemui")
                || p.contains("inputmethod") || p.contains("packageinstaller")) return;
        otherPkg = p;
    }

    /** Plain name of what's up, for the phone: "Home screen", "Filer", "Runner", an app's name, or "TV off". */
    static String name(Context c) {
        android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return "TV off";
        Activity a = top;
        if (a != null) return ours(a);
        String p = otherPkg;
        if (p == null) return "Another app";
        return appName(c, p);
    }

    private static String ours(Activity a) {
        if (a instanceof MainActivity) return "Home screen";
        if (a instanceof AllAppsActivity) return "All apps";
        if (a instanceof FilerActivity) return "Filer";
        if (a instanceof RunnerActivity) return "Runner";
        CharSequence t = a.getTitle();
        return t != null && t.length() > 0 ? t.toString() : a.getClass().getSimpleName().replace("Activity", "");
    }

    static String appName(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0 && !l.toString().equals(pkg)) return l.toString();
        } catch (Exception ignored) {
        }
        String p = pkg.toLowerCase(java.util.Locale.US);
        if (p.contains("tvcenter") || p.contains("tvinput") || p.contains("livetv") || p.contains("hdmi") || p.contains("tvplayer"))
            return "HDMI or TV input";
        if (p.contains("settings")) return "Settings";
        String last = pkg.substring(pkg.lastIndexOf('.') + 1);
        return last.isEmpty() ? pkg : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    /** Main thread. A real tap at fx, fy (0 to 1 across the screen) on our own screen (home, all apps, Runner...). */
    static boolean tap(Activity a, float fx, float fy) {
        View root = a.getWindow().getDecorView();
        if (root.getWidth() == 0) return false;
        float x = fx * root.getWidth(), y = fy * root.getHeight();
        long down = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent d = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        d.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        a.dispatchTouchEvent(d);
        d.recycle();
        android.view.MotionEvent u = android.view.MotionEvent.obtain(down, down + 60, android.view.MotionEvent.ACTION_UP, x, y, 0);
        u.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        a.dispatchTouchEvent(u);
        u.recycle();
        return true;
    }

    /** Main thread. Back, Enter and up/down on our own screen, like the TV remote's buttons. */
    static boolean key(Activity a, String k) {
        if ("back".equals(k)) {
            a.onBackPressed();
            return true;
        }
        int code = "enter".equals(k) ? android.view.KeyEvent.KEYCODE_DPAD_CENTER
                : "up".equals(k) ? android.view.KeyEvent.KEYCODE_DPAD_UP
                : "down".equals(k) ? android.view.KeyEvent.KEYCODE_DPAD_DOWN : -1;
        if (code < 0) return false;
        a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
        a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
        return true;
    }

    /** Main thread. A JPEG about 1000 wide of our own activity's window. */
    static void shot(final Activity a, final Shot cb) {
        final View root = a.getWindow().getDecorView();
        final int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) {
            cb.done(null);
            return;
        }
        if (Build.VERSION.SDK_INT < 26) {
            cb.done(draw(root));
            return;
        }
        final Bitmap full;
        try {
            full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            cb.done(null);
            return;
        }
        try {
            android.view.PixelCopy.request(a.getWindow(), full, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    if (result == android.view.PixelCopy.SUCCESS) cb.done(jpeg(full, w));
                    else {
                        full.recycle();
                        cb.done(draw(root));
                    }
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable e) {
            full.recycle();
            cb.done(draw(root));
        }
    }

    private static byte[] draw(View v) {
        try {
            int vw = v.getWidth(), vh = v.getHeight();
            float k = Math.min(1f, 1000f / vw);
            Bitmap b = Bitmap.createBitmap(Math.round(vw * k), Math.round(vh * k), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            cv.scale(k, k);
            v.draw(cv);
            return jpeg(b, b.getWidth());
        } catch (Throwable e) {
            return null;
        }
    }

    static byte[] jpeg(Bitmap full, int w) {
        Bitmap small = full;
        if (w > 1000) {
            int th = Math.round(full.getHeight() * (1000f / w));
            small = Bitmap.createScaledBitmap(full, 1000, th, true);
            full.recycle();
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 70, out);
        small.recycle();
        return out.toByteArray();
    }
}
