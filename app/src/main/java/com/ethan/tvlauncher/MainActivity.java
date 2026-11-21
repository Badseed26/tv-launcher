package com.ethan.tvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements Ui.FocusListener {

    /** Home screen apps, in order. Package name, then the label shown under the tile. */
    private static final String[][] FAVOURITES = {
            {"com.mediatek.wwtv.tvcenter", "Live TV"},
            {"com.netflix.ninja", "Netflix"},
            {"com.google.android.youtube.tv", "YouTube"},
            {"com.disney.disneyplus", "Disney+"},
            {"au.com.stan.and", "Stan"},
            {"com.cbs.ca", "Paramount+"},
            {"au.net.abc.iview", "ABC iview"},
            {"com.apple.atve.androidtv.appletv", "Apple TV"},
            {"com.spotify.tv.android", "Spotify"},
    };

    private static final int COLUMNS = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView clock;
    private TextView date;
    private LinearLayout grid;
    private final List<View> cards = new ArrayList<>();
    private int lastFocused = 0;
    private LinearLayout weatherCol;
    private TextView weatherTemp, weatherMore, weatherPlace;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            showWeather();
            if (Weather.due(MainActivity.this)) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (Weather.refresh(getApplicationContext(), false)) handler.post(new Runnable() {
                            @Override
                            public void run() {
                                showWeather();
                            }
                        });
                    }
                }, "weather").start();
            }
            handler.postDelayed(this, 10_000);
        }
    };

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

        // Top bar: clock and date on the left, buttons on the right.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setClipChildren(false);

        LinearLayout timeCol = new LinearLayout(this);
        timeCol.setOrientation(LinearLayout.VERTICAL);
        timeCol.setClipChildren(false);
        clock = Ui.text(this, "", 64, Ui.TEXT_PRIMARY, Ui.LIGHT);
        date = Ui.text(this, "", 18, Ui.TEXT_SECONDARY, Ui.REGULAR);
        timeCol.addView(clock);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 10);
        dlp.leftMargin = Ui.dp(this, 3);
        timeCol.addView(date, dlp);
        top.addView(timeCol);

        // Live weather (21/11), under the buttons on the right so the top row never gets pushed wide. Hidden until a
        // place is set with "weather <town>" on the Todoist remote.
        weatherCol = new LinearLayout(this);
        weatherCol.setOrientation(LinearLayout.HORIZONTAL);
        weatherCol.setGravity(android.view.Gravity.CENTER_VERTICAL);
        weatherTemp = Ui.text(this, "", 38, Ui.TEXT_PRIMARY, Ui.LIGHT);
        weatherCol.addView(weatherTemp);
        LinearLayout wText = new LinearLayout(this);
        wText.setOrientation(LinearLayout.VERTICAL);
        weatherPlace = Ui.text(this, "", 15, Ui.TEXT_PRIMARY, Ui.MEDIUM);
        wText.addView(weatherPlace);
        weatherMore = Ui.text(this, "", 15, Ui.TEXT_SECONDARY, Ui.REGULAR);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 6);
        wText.addView(weatherMore, mlp);
        LinearLayout.LayoutParams wtlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wtlp.leftMargin = Ui.dp(this, 14);
        weatherCol.addView(wText, wtlp);
        weatherCol.setVisibility(View.GONE);

        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        // Breathing room so the selected button can grow without hitting anything.
        buttons.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 12));
        // One "Ethan work apps" button behind the TV code (Ethan, 21/11). Opens its own menu screen with Filer and
        // Runner on it. The phone page never gets asked for the code.
        final TextView work = Ui.pill(this, "Ethan work apps", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Lock.ask(MainActivity.this, "work apps", new Runnable() {
                    @Override
                    public void run() {
                        startActivity(new Intent(MainActivity.this, WorkAppsActivity.class));
                    }
                });
            }
        });
        TextView updates = Ui.pill(this, "Updates", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Updater.check(MainActivity.this, true);
            }
        });
        TextView apps = Ui.pill(this, "All apps", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AllAppsActivity.class));
            }
        });
        TextView settings = Ui.pill(this, "Settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.openSettings(MainActivity.this);
            }
        });
        buttons.addView(work);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.leftMargin = Ui.dp(this, 14);
        buttons.addView(updates, ulp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = Ui.dp(this, 14);
        buttons.addView(apps, alp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(this, 14);
        buttons.addView(settings, slp);
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(android.view.Gravity.END);
        rightCol.setClipChildren(false);
        rightCol.setClipToPadding(false);
        rightCol.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Ui.dp(this, 6);
        wlp.rightMargin = Ui.dp(this, 14);
        rightCol.addView(weatherCol, wlp);
        top.addView(rightCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // App grid, centred in the space below the top bar.
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER_VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void buildGrid() {
        grid.removeAllViews();
        cards.clear();

        int pad = Ui.dp(this, 56);
        int gap = Ui.dp(this, 28);
        int tileW = (Ui.screenWidth(this) - pad * 2 - gap * (COLUMNS - 1)) / COLUMNS;
        int tileH = tileW * 9 / 16;

        List<View> tiles = new ArrayList<>();
        for (String[] fav : FAVOURITES) {
            View t = Ui.appTile(this, fav[0], fav[1], tileW, tileH, tiles.size(), this);
            if (t != null) tiles.add(t);
        }

        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Ui.dp(this, 36);
                grid.addView(row, rlp);
            }
            View t = tiles.get(i);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLUMNS != 0) tlp.leftMargin = gap;
            row.addView(t, tlp);
            cards.add((View) t.getTag());
        }

        if (tiles.isEmpty()) {
            TextView empty = Ui.text(this, "No apps found. Open All apps to pick one.",
                    18, Ui.TEXT_SECONDARY, Ui.REGULAR);
            grid.addView(empty);
        }
    }

    private void focusTile(int index) {
        if (cards.isEmpty()) return;
        int i = Math.max(0, Math.min(index, cards.size() - 1));
        cards.get(i).requestFocus();
    }

    @Override
    public void onTileFocused(int index) {
        lastFocused = index;
    }

    private void updateClock() {
        Date now = new Date();
        String timePattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        clock.setText(new SimpleDateFormat(timePattern, Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
    }

    private void showWeather() {
        if (weatherCol == null) return;
        String[] l = Weather.hasPlace(this) ? Weather.lines(this) : null;
        if (l == null) {
            weatherCol.setVisibility(View.GONE);
            return;
        }
        weatherTemp.setText(l[0]);
        String pl = Weather.place(this);
        int comma = pl.indexOf(',');
        weatherPlace.setText(comma > 0 ? pl.substring(0, comma) : pl);
        weatherMore.setText(l[1]);
        weatherCol.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildGrid();
        handler.post(new Runnable() {
            @Override
            public void run() {
                focusTile(lastFocused);
            }
        });
        handler.removeCallbacks(tick);
        handler.post(tick);

        ListenService.start(this);
        Screen.on(this);
        Updater.homeVisible = true;
        Updater.showPendingConfirm(this);
        Updater.maybeAutoCheck(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Screen.off(this);
        Updater.homeVisible = false;
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while already home jumps back to the first tile.
        lastFocused = 0;
        focusTile(0);
    }

    @Override
    public void onBackPressed() {
        // Home screen: back does nothing.
    }
}
