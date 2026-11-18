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
    static volatile boolean homeVisible;
    private static volatile boolean busy;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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

    /** Shows the install confirmation if one is waiting and the home screen is up. */
    static void showPendingConfirm(Context c) {
        Intent confirm = pendingConfirm;
        if (confirm == null || !homeVisible) return;
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
