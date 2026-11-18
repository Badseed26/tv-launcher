package com.ethan.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Takes an AroFlo sign-in code pushed from AirHQ and hands it to the open Filer screen.
 * Locked to the ADB shell in the manifest, same as the logins.
 */
public class CodeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String press = intent.getStringExtra("press");
        if (press != null && !press.trim().isEmpty()) {
            FilerActivity f = FilerActivity.instance;
            boolean sent = f != null && f.pressOnAroFlo(press.trim());
            setResultCode(sent ? 1 : 0);
            setResultData(f == null ? "Filer isn't open on the TV"
                    : sent ? "Asked AroFlo to press " + press.trim() + ", check the Filer log" : "AroFlo session isn't running");
            return;
        }

        String code = intent.getStringExtra("code");
        if (code == null || code.trim().isEmpty()) {
            setResultCode(0);
            setResultData("No code given");
            return;
        }
        code = code.replaceAll("\\s", "");
        FilerActivity f = FilerActivity.instance;
        if (f == null) {
            setResultCode(0);
            setResultData("Filer isn't open on the TV");
            return;
        }
        boolean sent = f.enterAroFloCode(code);
        setResultCode(sent ? 1 : 0);
        setResultData(sent ? "Code sent to AroFlo, check the Filer log" : "AroFlo session isn't running");
    }
}
