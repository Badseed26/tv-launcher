package com.ethan.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs right after the launcher has been updated and brings the home screen back up. */
public class UpdatedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;

        // The Home helper presses Home once it reconnects after the update.
        Updater.setGoHome(c);
        HomeButtonService helper = HomeButtonService.instance;
        if (helper != null && Updater.consumeGoHome(c)) helper.goHomeSoon();

        // Also try opening the home screen directly (works where Android allows it).
        Intent home = new Intent(c, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            c.startActivity(home);
        } catch (Exception ignored) {
        }
        Updater.toast(c, "Launcher updated to build " + Updater.currentVersion(c));
        TvLog.line(c, "Update: now on build " + Updater.currentVersion(c));
        // a remote update with the Filer open: open it again once the helper's back
        final android.content.SharedPreferences p = c.getSharedPreferences("updater", Context.MODE_PRIVATE);
        if (p.getBoolean("reopenFiler", false) && System.currentTimeMillis() - p.getLong("remoteAt", 0) < 10 * 60000) {
            p.edit().putBoolean("reopenFiler", false).apply();
            final Context app = c.getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    TvLog.line(app, "Update: opening the Filer again");
                    WakeActivity.start(app, true);
                }
            }, 12000);
        }
    }
}
