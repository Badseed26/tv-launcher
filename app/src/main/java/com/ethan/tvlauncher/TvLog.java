package com.ethan.tvlauncher;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes a line to the TV log (the one AirHQ's log command and the remote's status read). */
final class TvLog {

    private TvLog() {}

    static synchronized void line(Context c, String msg) {
        try {
            File f = new File(c.getApplicationContext().getFilesDir(), "filer.log");
            if (f.length() > 256 * 1024) {
                File old = new File(c.getApplicationContext().getFilesDir(), "filer.old.log");
                old.delete();
                f.renameTo(old);
            }
            FileWriter w = new FileWriter(f, true);
            w.write(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()) + "  " + msg + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }
}
