package com.ethan.tvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every launchable app on the TV, A to Z. */
public class AllAppsActivity extends Activity {

    private static final int COLUMNS = 6;
    private LinearLayout grid;

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
        content.setPadding(pad, Ui.dp(this, 44), pad, 0);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = Ui.text(this, "All apps", 36, Ui.TEXT_PRIMARY, Ui.LIGHT);
        content.addView(title);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Ui.dp(this, 12);
        content.addView(scroll, slp);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 48));
        scroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Screen.on(this);
        build();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Screen.off(this);
    }

    private void build() {
        grid.removeAllViews();
        final PackageManager pm = getPackageManager();

        Map<String, String> apps = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, apps);
        collect(pm, Intent.CATEGORY_LAUNCHER, apps);
        apps.remove(getPackageName());

        List<Map.Entry<String, String>> sorted = new ArrayList<>(apps.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                return a.getValue().compareToIgnoreCase(b.getValue());
            }
        });

        int pad = Ui.dp(this, 56);
        int gap = Ui.dp(this, 24);
        int tileW = (Ui.screenWidth(this) - pad * 2 - gap * (COLUMNS - 1)) / COLUMNS;
        int tileH = tileW * 9 / 16;

        LinearLayout row = null;
        int count = 0;
        View first = null;
        for (Map.Entry<String, String> app : sorted) {
            View t = Ui.appTile(this, app.getKey(), app.getValue(), tileW, tileH, count, null);
            if (t == null) continue;
            if (count % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (count > 0) rlp.topMargin = Ui.dp(this, 30);
                grid.addView(row, rlp);
            }
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (count % COLUMNS != 0) tlp.leftMargin = gap;
            row.addView(t, tlp);
            if (first == null) first = (View) t.getTag();
            count++;
        }

        if (first != null) {
            final View f = first;
            f.post(new Runnable() {
                @Override
                public void run() {
                    f.requestFocus();
                }
            });
        }
    }

    private static void collect(PackageManager pm, String category, Map<String, String> out) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(category);
        List<ResolveInfo> list;
        try {
            list = pm.queryIntentActivities(i, 0);
        } catch (Exception e) {
            return;
        }
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (out.containsKey(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            out.put(pkg, label != null ? label.toString() : pkg);
        }
    }
}
