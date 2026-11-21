package com.ethan.tvlauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The code the TV asks for before the Filer or Runner opens from the TV itself (Ethan, 21/11).
 * Opens from the phone page (its taps and keys too), the Todoist remote and auto runs never ask.
 * The code lives in the app's own storage, never in this repo. Until one's set with "tv code <digits>" on the
 * Todoist remote, it's the phone page's PIN.
 */
final class Lock {

    private static final String PREFS = "tvlock";
    /** uptimeMillis of the last tap or key the phone page sent to one of our screens. */
    static volatile long remoteAt;
    /** Once the code's gone in, the TV doesn't ask again for a couple of minutes. */
    private static long okUntil;
    private static int wrong;

    private Lock() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String code(Context c) {
        String s = p(c).getString("code", null);
        return s != null ? s : RemoteServer.pin(c);
    }

    static boolean ownCode(Context c) {
        return p(c).getString("code", null) != null;
    }

    static void setCode(Context c, String code) {
        p(c).edit().putString("code", code).apply();
        okUntil = 0;
    }

    /** True when the tap or key that got us here came from the phone page. */
    static boolean fromRemote() {
        return SystemClock.uptimeMillis() - remoteAt < 4000;
    }

    /** Runs go straight away from the phone page or just after the code went in, otherwise asks for the code first. */
    static void ask(final Activity a, final String what, final Runnable go) {
        if (fromRemote() || SystemClock.uptimeMillis() < okUntil) {
            go.run();
            return;
        }
        final Context c = a;
        final long lockedTill = p(c).getLong("lockedTill", 0);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(c, 28);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(c, 24));
        bg.setColor(Ui.CARD);
        bg.setStroke(Ui.dp(c, 1), 0x22FFFFFF);
        box.setBackground(bg);

        box.addView(Ui.text(c, "Code for the " + what, 22, Ui.TEXT_PRIMARY, Ui.MEDIUM));
        final TextView dots = Ui.text(c, " ", 30, Ui.TEXT_PRIMARY, Ui.MEDIUM);
        dots.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 6));
        box.addView(dots);
        final TextView msg = Ui.text(c, "Use the number keys or the pad", 14, Ui.TEXT_SECONDARY, Ui.REGULAR);
        msg.setPadding(0, 0, 0, Ui.dp(c, 16));
        box.addView(msg);

        final StringBuilder typed = new StringBuilder();
        final Runnable show = new Runnable() {
            @Override
            public void run() {
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < typed.length(); i++) s.append(i == 0 ? "●" : " ●");
                dots.setText(s.length() == 0 ? " " : s.toString());
            }
        };
        final Runnable check = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                if (now < p(c).getLong("lockedTill", 0)) {
                    msg.setText("Too many wrong tries, try again in a few minutes");
                    typed.setLength(0);
                    show.run();
                    return;
                }
                if (typed.toString().equals(code(c))) {
                    wrong = 0;
                    okUntil = SystemClock.uptimeMillis() + 2 * 60 * 1000;
                    d.dismiss();
                    go.run();
                    return;
                }
                wrong++;
                typed.setLength(0);
                show.run();
                if (wrong >= 5) {
                    wrong = 0;
                    p(c).edit().putLong("lockedTill", now + 10 * 60 * 1000).apply();
                    TvLog.line(c, "TV code: 5 wrong tries, locked 10 min");
                    msg.setText("Too many wrong tries, locked for 10 minutes");
                } else {
                    msg.setText("Wrong code");
                }
                dots.animate().translationX(Ui.dp(c, 10)).setDuration(60).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        dots.animate().translationX(0).setDuration(60).start();
                    }
                }).start();
            }
        };

        // Number pad for remotes with no number keys: 1 to 9, then Clear 0 OK
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "OK"};
        LinearLayout row = null;
        TextView first = null;
        for (int i = 0; i < keys.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                box.addView(row);
            }
            final String k = keys[i];
            TextView b = Ui.pill(c, k, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("Clear".equals(k)) {
                        typed.setLength(0);
                        msg.setText("Use the number keys or the pad");
                    } else if ("OK".equals(k)) {
                        check.run();
                        return;
                    } else if (typed.length() < 8) {
                        typed.append(k);
                    }
                    show.run();
                }
            });
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(Ui.dp(c, 96));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
            row.addView(b, lp);
            if (first == null) first = b;
        }

        d.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface di, int keyCode, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    if (typed.length() < 8) typed.append((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
                    show.run();
                    if (typed.length() == code(c).length()) check.run();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    if (typed.length() > 0) typed.setLength(typed.length() - 1);
                    show.run();
                    return true;
                }
                return false;
            }
        });

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (System.currentTimeMillis() < lockedTill) msg.setText("Too many wrong tries, try again in a few minutes");
        d.show();
        if (first != null) first.requestFocus();
    }
}
