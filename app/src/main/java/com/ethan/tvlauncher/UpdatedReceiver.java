package com.ethan.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs right after the launcher has been updated and brings the home screen back up. */
public class UpdatedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Intent home = new Intent(c, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            c.startActivity(home);
        } catch (Exception ignored) {
        }
        Updater.toast(c, "Launcher updated to build " + Updater.currentVersion(c));
    }
}
