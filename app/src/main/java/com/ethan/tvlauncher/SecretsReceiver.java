package com.ethan.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes logins and keys pushed from ADB (AirHQ). Locked in the manifest so only the
 * ADB shell can send to it, not other apps. Replies with what was saved, never the values.
 */
public class SecretsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent.getBooleanExtra("clear", false)) {
            Secrets.clear(c);
            setResultCode(1);
            setResultData("Cleared all saved logins and keys");
            return;
        }

        List<String> saved = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String name : Secrets.ALL) {
            String value = intent.getStringExtra(name);
            if (value == null || value.isEmpty()) continue;
            try {
                Secrets.put(c, name, value);
                saved.add(name);
            } catch (Exception e) {
                failed.add(name);
            }
        }

        List<String> missing = new ArrayList<>();
        for (String name : Secrets.ALL) {
            if (!Secrets.has(c, name)) missing.add(name);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Saved: ").append(saved.isEmpty() ? "nothing" : join(saved));
        if (!failed.isEmpty()) sb.append(". FAILED: ").append(join(failed));
        sb.append(". Still missing: ").append(missing.isEmpty() ? "none" : join(missing));
        setResultCode(1);
        setResultData(sb.toString());
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }
}
