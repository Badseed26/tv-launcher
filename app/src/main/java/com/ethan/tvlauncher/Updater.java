package com.ethan.tvlauncher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks the GitHub release for a newer build and installs it. */
final class Updater {

    static final String VERSION_URL =
            "https://github.com/Badseed26/tv-launcher/releases/download/latest/version.json";
    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000;

    /** Install confirmation waiting to be shown once the home screen is visible. */
    static volatile Intent pendingConfirm;
    /** Set by the remote Update command: until then, the helper presses Install on our own update's prompt. */
    static volatile long autoConfirmUntil;
    static volatile boolean homeVisible;
    private static volatile boolean busy;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long GO_HOME_WINDOW_MS = 3L * 60 * 1000;

    private Updater() {}

    static int currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Quiet check, at most every few hours. Only speaks up if there's an update. */
    static void maybeAutoCheck(Context c) {
        SharedPreferences p = c.getSharedPreferences("updater", Context.MODE_PRIVATE);
        long last = p.getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return;
        check(c, false);
    }

    /** Check now. manual = true shows every result, false stays quiet unless updating. */
    /** From the dashboard's Update TV button. Replies with what it's doing. */
    static void remoteUpdate(final Context ctx, final Remote.Say say) {
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject j = new JSONObject(getString(VERSION_URL + "?t=" + System.currentTimeMillis()));
                    int latest = j.getInt("versionCode"), mine = currentVersion(c);
                    if (latest <= mine) {
                        say.say("Already up to date (build " + mine + ").");
                        return;
                    }
                    if (HomeButtonService.instance == null) {
                        say.say("Build " + latest + " is out but the Home helper isn't running, so it can't press Install. Update it at the TV.");
                        return;
                    }
                    boolean filer = FilerActivity.instance != null;
                    // 21/11: the install box can't be pressed in standby, so wake the TV for it and put it back to sleep after
                    android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
                    final boolean off = pm != null && !pm.isInteractive();
                    c.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
                            .putBoolean("reopenFiler", filer).putBoolean("sleepAfter", off).putLong("remoteAt", System.currentTimeMillis()).commit();
                    if (off) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                ListenService.wakeScreen(c);
                                WakeActivity.start(c, false);
                            }
                        });
                        Thread.sleep(6000);
                    }
                    autoConfirmUntil = System.currentTimeMillis() + 3 * 60000;
                    TvLog.line(c, "Update: remote update to build " + latest + " (from " + mine + ")");
                    say.say("Updating build " + mine + " to " + latest + ". The TV restarts the app"
                            + (filer ? " and opens the Filer again" : "") + ". Send status in a couple of minutes.");
                    check(c, false);
                } catch (Exception e) {
                    say.say("Update check failed: " + e.getMessage());
                }
            }
        }, "remote-update").start();
    }

    static void check(Context ctx, final boolean manual) {
        final Context c = ctx.getApplicationContext();
        if (busy) {
            if (manual) toast(c, "Already checking for updates");
            return;
        }
        busy = true;
        c.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
                .putLong("lastCheck", System.currentTimeMillis()).apply();
        if (manual) toast(c, "Checking for updates...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject j = new JSONObject(getString(VERSION_URL + "?t=" + System.currentTimeMillis()));
                    int latest = j.getInt("versionCode");
                    int mine = currentVersion(c);
                    if (latest <= mine) {
                        if (manual) toast(c, "You're up to date (build " + mine + ")");
                        return;
                    }
                    toast(c, "Downloading launcher update (build " + latest + ")...");
                    File apk = new File(c.getCacheDir(), "update.apk");
                    download(j.getString("apk"), apk);
                    install(c, apk);
                } catch (Exception e) {
                    if (manual) toast(c, "Update check failed: " + e.getMessage());
                } finally {
                    busy = false;
                }
            }
        }, "launcher-updater").start();
    }

    /** Marks that the home screen should come back up after this update. */
    static void setGoHome(Context c) {
        c.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
                .putLong("goHomeAt", System.currentTimeMillis()).commit();
    }

    /** True (once) if an update just happened and Home should be pressed. */
    static boolean consumeGoHome(Context c) {
        SharedPreferences p = c.getSharedPreferences("updater", Context.MODE_PRIVATE);
        long at = p.getLong("goHomeAt", 0);
        if (at == 0) return false;
        p.edit().remove("goHomeAt").commit();
        return System.currentTimeMillis() - at < GO_HOME_WINDOW_MS;
    }

    /** Shows the install confirmation if one is waiting and the home screen is up. */
    static void showPendingConfirm(Context c) {
        Intent confirm = pendingConfirm;
        boolean remote = System.currentTimeMillis() < autoConfirmUntil;
        if (confirm == null || (!homeVisible && !remote)) return;
        if (remote) {
            // remote update: wake the TV if it's off, open the prompt from the helper (allowed from the background)
            pendingConfirm = null;
            ListenService.wakeScreen(c);
            Context from = HomeButtonService.instance != null ? HomeButtonService.instance : c.getApplicationContext();
            try {
                from.startActivity(confirm);
                TvLog.line(c, "Update: install prompt opened, the helper presses Install");
            } catch (Exception e) {
                TvLog.line(c, "Update: couldn't open the install prompt: " + e.getMessage());
            }
            return;
        }
        pendingConfirm = null;
        try {
            c.getApplicationContext().startActivity(confirm);
        } catch (Exception e) {
            toast(c, "Couldn't open the update prompt");
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "tv-launcher");
        con.setUseCaches(false);
        int code = con.getResponseCode();
        if (code != 200) {
            con.disconnect();
            throw new IOException("server said " + code);
        }
        return con;
    }

    private static String getString(String url) throws IOException {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        } finally {
            con.disconnect();
        }
    }

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        setGoHome(c);
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                copy(in, out);
                session.fsync(out);
            }
            Intent result = new Intent(c, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(c, id, result, flags);
            session.commit(pending.getIntentSender());
        } finally {
            session.close();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    static void toast(final Context c, final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
