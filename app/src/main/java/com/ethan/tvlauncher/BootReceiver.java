package com.ethan.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the remote listener when the TV boots (e.g. after a power cut). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        TvLog.line(c, "TV booted");
        ListenService.start(c);
    }
}
