package com.ethan.tvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Ethan work apps (21/11): its own menu screen behind the TV code, with Filer and Runner as tiles. Closing one comes
 * back here, Back goes home. Folds itself away after 5 min with nobody touching it, so it isn't left open on the TV.
 * New work apps go in here as more tiles.
 */
public class WorkAppsActivity extends Activity {

    private static final long IDLE = 5 * 60 * 1000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idle = new Runnable() {
        @Override
        public void run() {
            finish();
        }
    };
    private View first;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.addView(new Ui.BackgroundView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int pad = Ui.dp(this, 56);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, Ui.dp(this, 44), pad, Ui.dp(this, 24));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(Ui.text(this, "Ethan work apps", 36, Ui.TEXT_PRIMARY, Ui.LIGHT));
        TextView hint = Ui.text(this, "Back to go home", 15, Ui.TEXT_SECONDARY, Ui.REGULAR);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(this, 8);
        content.addView(hint, hlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 36);
        content.addView(row, rlp);

        first = tile(row, "Filer", "Inbox filer, Outlook and AroFlo", FilerActivity.class);
        tile(row, "Runner", "Inbox pull and lookups", RunnerActivity.class);

        setContentView(root);
    }

    /** One big focusable card that opens an app. */
    private View tile(LinearLayout row, String name, String sub, final Class<?> cls) {
        final LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.BOTTOM);
        int p = Ui.dp(this, 26);
        t.setPadding(p, p, p, p);
        int r = Ui.dp(this, 22);
        GradientDrawable idleBg = new GradientDrawable();
        idleBg.setCornerRadius(r);
        idleBg.setColor(0x1FFFFFFF);
        idleBg.setStroke(Ui.dp(this, 1), 0x22FFFFFF);
        GradientDrawable on = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF3A5BFF, 0xFF9B5CFF});
        on.setCornerRadius(r);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, idleBg);
        t.setBackground(bg);
        t.addView(Ui.text(this, name, 30, Ui.TEXT_PRIMARY, Ui.MEDIUM));
        TextView s = Ui.text(this, sub, 15, 0xCCF2F4FA, Ui.REGULAR);
        s.setPadding(0, Ui.dp(this, 8), 0, 0);
        t.addView(s);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(WorkAppsActivity.this, cls));
            }
        });
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float sc = hasFocus ? 1.06f : 1f;
                v.animate().scaleX(sc).scaleY(sc).setDuration(140).start();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 320), Ui.dp(this, 180));
        lp.rightMargin = Ui.dp(this, 28);
        row.addView(t, lp);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Screen.on(this);
        poke();
        if (first != null && getCurrentFocus() == null) first.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Screen.off(this);
        handler.removeCallbacks(idle);
    }

    private void poke() {
        handler.removeCallbacks(idle);
        handler.postDelayed(idle, IDLE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        poke();
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        poke();
        return super.dispatchTouchEvent(e);
    }
}
