package com.ethan.tvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Runner (Ethan, 20/11): runs his other scripts on the TV, one job at a time, then closes.
 *  - Inbox pull: the EC Inbox Pull script reads a day's Inbox and Sent Items in Outlook (read only, unread put back),
 *    then the next 5 weeks of the AroFlo calendar get added, and the lot goes on the TV remote task as a file for the
 *    inbox check. Same days as the dashboard: today and the 6 days before.
 *  - Lookup: AroFlo task searches and a task's notes, for the inbox check when the PC's off.
 * Same hidden PC-size page as the Filer, one page at a time (low RAM). The Filer closes while it runs and reopens after.
 */
public class RunnerActivity extends Activity {

    private static final String PULL_URL =
            "https://raw.githubusercontent.com/Badseed26/ec-userscripts/main/src/ec-inbox-pull.js";
    // 20/11: the AroFlo side (calendar and lookups) loads from GitHub like the Inbox Pull, so a fix to it is live on the
    // next run without a new build. The copy built into the app is the fallback.
    private static final String AF_URL =
            "https://raw.githubusercontent.com/Badseed26/tv-launcher/main/app/src/main/assets/runner-af.js";
    private static final String OUTLOOK = "https://outlook.cloud.microsoft/mail/";
    private static final String CAL_URL = "https://office.aroflo.com/ims/Site/Calendar/index.cfm?viewfullcalendar=1&tid=IMS.CAL";
    private static final String AF_HOME = "https://office.aroflo.com/ims/Site/Home/index.cfm";
    private static final String[] OUTLOOK_ORIGINS = {
            "https://outlook.cloud.microsoft", "https://outlook.office.com", "https://outlook.office365.com"};
    private static final long PULL_MAX_MS = 10 * 60000;
    private static final long AF_MAX_MS = 3 * 60000;
    private static final int DIM = 0xFF8C92A8;
    private static final int GOOD = 0xFF4ADE80;
    private static final int WARN = 0xFFFBBF24;

    static volatile RunnerActivity instance;
    /** One line on what it's up to, for status and the phone page. */
    static volatile String state = "";

    /** What to run. kind: pull (day = today or YYYY-MM-DD) or lookup (terms). */
    static final class Job {
        String kind, day, from;
        List<String> terms;
        /** kind api: the request off the remote (see Api). */
        JSONObject api;
        Remote.Say say;
        boolean wasOff, filerWasOpen;
    }

    private static Job pending;

    /** True while a job's running. */
    static boolean busy() {
        RunnerActivity r = instance;
        return r != null && r.job != null;
    }

    // ---------------------------------------------------------------- starting

    /** Today and the 6 days before, as YYYY-MM-DD, newest first (same as the dashboard's Pull). */
    static List<String> days() {
        List<String> out = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (int i = 0; i <= 6; i++) {
            out.add(f.format(c.getTime()));
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        return out;
    }

    static String nice(String ymd) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(ymd);
            String s = new SimpleDateFormat("EEE d/M", Locale.US).format(d);
            return ymd.equals(days().get(0)) ? "Today, " + s : s;
        } catch (Exception e) {
            return ymd;
        }
    }

    /** today, yesterday, a weekday name, YYYY-MM-DD or d/m. Null if it's not one of the last 7 days. */
    static String parseDay(String s) {
        List<String> ds = days();
        s = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("today")) return ds.get(0);
        if (s.equals("yesterday")) return ds.get(1);
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2})/(\\d{1,2})(?:/\\d{2,4})?$").matcher(s);
            for (String d : ds) {
                if (d.equals(s)) return d;
                Date dt = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d);
                String name = new SimpleDateFormat("EEE", Locale.US).format(dt).toLowerCase(Locale.ROOT);
                String full = new SimpleDateFormat("EEEE", Locale.US).format(dt).toLowerCase(Locale.ROOT);
                if (s.equals(name) || s.equals(full)) return d;
                if (m.matches() && Integer.parseInt(m.group(1)) == Integer.parseInt(d.substring(8))
                        && Integer.parseInt(m.group(2)) == Integer.parseInt(d.substring(5, 7))) return d;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Main thread. Starts a job, waking the TV if it's off. Returns the first reply, or null when the Runner will answer
     * itself. Never takes the TV off an HDMI input or another app.
     */
    static String start(Context c, Job j) {
        RunnerActivity r = instance;
        if (r != null && r.job != null) return "Runner's busy: " + state;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean on = pm != null && pm.isInteractive();
        if (on && r == null && FilerActivity.instance == null && !Updater.homeVisible)
            return "Something's on the TV (HDMI or an app), so I'm not taking it over. Try again when it's off or on the home screen.";
        if (Peek.busy) Peek.cancel();
        j.wasOff = !on;
        j.filerWasOpen = FilerActivity.instance != null;
        if (r != null) {
            r.begin(j);
            return null;
        }
        pending = j;
        if (!on) {
            ListenService.wakeScreen(c);
            WakeActivity.startRunner(c);
        } else {
            Intent i = new Intent(c, RunnerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        }
        return null;
    }

    // ---------------------------------------------------------------- the screen

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(2);
    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final SimpleDateFormat clock = new SimpleDateFormat("h:mm:ss a", Locale.US);
    private String shimJs, afJs;
    private FrameLayout webHolder, cover;
    private TextView strip;
    private WebView web;
    private LinearLayout logBox, dayRow;
    private TextView title, now;
    private GmStore store;
    private boolean alive;

    private Job job;
    private long started, pageAt;
    private String pageWant;
    private boolean pageRan;
    private String bundle, pullLine, pickedDay;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crash.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        }
        shimJs = asset("gm-shim.js");
        afJs = asset("runner-af.js");
        store = GmStore.get(this);
        instance = this;
        alive = true;
        buildUi();
        Job j = pending;
        pending = null;
        if (j != null) begin(j);
        else {
            state = "waiting for a day to be picked";
            say("Pick a day and press Pull inbox. Same as the dashboard: today or the 6 days before.", DIM);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Screen.on(this); // phone remote picture (20/11)
    }

    @Override
    protected void onPause() {
        super.onPause();
        Screen.off(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Mid job: keep going in the background (stopping half way could leave Lisa's emails read), close when done.
        if (job == null) finish();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        if (instance == this) instance = null;
        if (job != null) state = "stopped part way (the Runner was closed)";
        handler.removeCallbacksAndMessages(null);
        stopPage();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (job != null) {
            say("Still running, it'll close itself when it's done.", WARN);
            return;
        }
        super.onBackPressed();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        webHolder = new FrameLayout(this);
        webHolder.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.addView(webHolder, new FrameLayout.LayoutParams(-1, -1));

        cover = new FrameLayout(this);
        cover.addView(new Ui.BackgroundView(this), new FrameLayout.LayoutParams(-1, -1));
        cover.setClickable(true);
        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        int pad = Ui.dp(this, 64);
        panel.setPadding(pad, Ui.dp(this, 48), pad, Ui.dp(this, 32));
        cover.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        title = Ui.text(this, "Runner", 30, Ui.TEXT_PRIMARY, Ui.LIGHT);
        panel.addView(title);
        TextView sub = Ui.text(this, "Inbox pull for the inbox check. Read only, unread emails go back to unread.", 14, DIM, Ui.REGULAR);
        LinearLayout.LayoutParams slp = wrap();
        slp.topMargin = Ui.dp(this, 6);
        panel.addView(sub, slp);

        dayRow = new LinearLayout(this);
        dayRow.setOrientation(LinearLayout.HORIZONTAL);
        dayRow.setClipChildren(false);
        dayRow.setClipToPadding(false);
        dayRow.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10));
        View first = null;
        for (final String d : days()) {
            TextView p = Ui.pill(this, nice(d), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickedDay = d;
                    markPicked();
                }
            });
            p.setTag(d);
            LinearLayout.LayoutParams lp = wrap();
            if (first != null) lp.leftMargin = Ui.dp(this, 10);
            dayRow.addView(p, lp);
            if (first == null) first = p;
        }
        LinearLayout.LayoutParams dlp = wrap();
        dlp.topMargin = Ui.dp(this, 26);
        panel.addView(dayRow, dlp);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setClipChildren(false);
        acts.setClipToPadding(false);
        acts.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10));
        TextView go = Ui.pill(this, "Pull inbox", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Job j = new Job();
                j.kind = "pull";
                j.day = pickedDay != null ? pickedDay : days().get(0);
                j.from = "tv";
                String r = start(RunnerActivity.this, j);
                if (r != null) say(r, WARN);
            }
        });
        TextView close = Ui.pill(this, "Close", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        acts.addView(go);
        LinearLayout.LayoutParams clp = wrap();
        clp.leftMargin = Ui.dp(this, 14);
        acts.addView(close, clp);
        LinearLayout.LayoutParams alp = wrap();
        alp.topMargin = Ui.dp(this, 4);
        panel.addView(acts, alp);

        now = Ui.text(this, "", 20, Ui.TEXT_PRIMARY, Ui.MEDIUM);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = Ui.dp(this, 18);
        panel.addView(now, nlp);

        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = wrap();
        llp.topMargin = Ui.dp(this, 14);
        panel.addView(logBox, llp);

        // 20/11 (Ethan): the page runs full screen while a job's going, with this strip on top, then the TV goes back to
        // how it was. Behind the Runner screen Outlook's mail list wouldn't scroll back past the newest 24 emails.
        strip = Ui.text(this, "", 15, Ui.TEXT_PRIMARY, Ui.MEDIUM);
        strip.setBackgroundColor(0xE60B0F17);
        strip.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        strip.setVisibility(View.GONE);
        root.addView(strip, new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        pickedDay = days().get(0);
        markPicked();
        if (first != null) first.requestFocus();
    }

    private void markPicked() {
        for (int i = 0; i < dayRow.getChildCount(); i++) {
            TextView p = (TextView) dayRow.getChildAt(i);
            String d = (String) p.getTag();
            p.setText((d.equals(pickedDay) ? "✓ " : "") + nice(d));
        }
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** A line on the screen and in the TV's log. */
    private void say(String msg, int colour) {
        TvLog.line(this, "Runner: " + msg);
        if (logBox == null) return;
        TextView t = Ui.text(this, clock.format(new Date()) + "   " + msg, 15, colour, Ui.REGULAR);
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = Ui.dp(this, 6);
        logBox.addView(t, 0, lp);
        while (logBox.getChildCount() > 12) logBox.removeViewAt(logBox.getChildCount() - 1);
    }

    private void setNow(String s) {
        state = s;
        if (now != null) now.setText(s);
        if (strip != null) strip.setText("Runner  \u00B7  " + s + "   (the TV goes back to how it was when it's done)");
    }

    // ---------------------------------------------------------------- jobs

    private void begin(Job j) {
        job = j;
        started = System.currentTimeMillis();
        bundle = null;
        pullLine = null;
        dayRow.setVisibility(View.GONE);
        if ("api".equals(j.kind)) {
            beginApi(j);
            return;
        }
        if ("lookup".equals(j.kind)) {
            title.setText("Runner  ·  AroFlo lookup");
            setNow("Looking up " + j.terms.size() + " in AroFlo...");
            say("Lookup: " + android.text.TextUtils.join("; ", j.terms), DIM);
            net.execute(new Runnable() {
                @Override
                public void run() {
                    loadAf();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (alive && job != null) openAroFlo(AF_HOME);
                        }
                    });
                }
            });
            return;
        }
        title.setText("Runner  ·  inbox pull, " + nice(j.day));
        setNow("Getting the Inbox Pull script...");
        say("Inbox pull for " + nice(j.day) + (j.wasOff ? " (TV was off)" : "") + (j.filerWasOpen ? ", Filer closed for now" : ""), DIM);
        net.execute(new Runnable() {
            @Override
            public void run() {
                loadAf();
                final String src = loadScript();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive) return;
                        if (src == null) {
                            fail("couldn't get the Inbox Pull script from GitHub");
                            return;
                        }
                        startPull(src);
                    }
                });
            }
        });
    }

    private void startPull(String src) {
        String inj = shimJs.replace("__TOKEN__", token).replace("__VER__", versionOf(src)).replace("__SCRIPT__", src);
        boolean today = job.day.equals(days().get(0));
        String url = OUTLOOK + "#ec-inbox-run" + (today ? "" : "=" + job.day);
        startPage(url, inj);
        setNow("Opening Outlook...");
        handler.postDelayed(watchPull, 3000);
    }

    private final Runnable watchPull = new Runnable() {
        @Override
        public void run() {
            if (!alive || job == null) return;
            String url = web != null ? web.getUrl() : "";
            if (url != null && url.contains("login.") && System.currentTimeMillis() - started > 30000) {
                fail("Outlook's signed out on the TV. Open the Filer so it signs back in, then try again.");
                return;
            }
            try {
                String st = store.getJson("ecStatus");
                if (st != null) {
                    JSONObject o = new JSONObject(st);
                    if (o.optLong("at") >= started) setNow(o.optString("t"));
                    if (o.optLong("at") >= started) pullLine = o.optString("t");
                }
                String bj = store.getJson("ecBundle");
                if (bj != null) {
                    JSONObject o = new JSONObject(bj);
                    if (o.optLong("at") >= started - 5000 && !o.optString("text").isEmpty()) {
                        bundle = o.optString("text");
                        // the pull writes its last status just after the bundle: give it a moment, then go by the bundle
                        handler.postDelayed(afterPull, 1500);
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
            if (System.currentTimeMillis() - started > PULL_MAX_MS) {
                fail("the pull didn't finish in 10 min (last: " + pullLine + "). Some unread emails may now show as read: "
                        + "check Outlook, or hit Fix unread in the Inbox Pull on the PC.");
                return;
            }
            handler.postDelayed(this, 2000);
        }
    };

    private static final String OUTLOOK_DIAG = "(function(){try{return JSON.stringify({w:innerWidth,h:innerHeight,dpr:devicePixelRatio,"
            + "path:location.pathname,rows:document.querySelectorAll('div[role=option][data-convid]').length,"
            + "tree:[].slice.call(document.querySelectorAll('[role=treeitem]')).slice(0,6).map(function(t){return (t.getAttribute('title')||t.textContent||'').slice(0,30);}),"
            + "btns:[].slice.call(document.querySelectorAll('button[aria-label]')).map(function(b){return b.getAttribute('aria-label');})"
            + ".filter(function(l){return /pane|navig|folder|menu|expand|collapse/i.test(l);}).slice(0,10)});}catch(e){return 'diag failed '+e;}})()";

    /** Bundle's in. If the pull hit a problem, note what Outlook looked like on the TV, then on to the calendar. */
    private final Runnable afterPull = new Runnable() {
        @Override
        public void run() {
            if (!alive || job == null) return;
            try {
                String st = store.getJson("ecStatus");
                if (st != null) {
                    JSONObject o = new JSONObject(st);
                    if (o.optLong("at") >= started) pullLine = o.optString("t");
                }
            } catch (Exception ignored) {
            }
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("(?m)^!! PROBLEM: (.*)$").matcher(bundle);
            final boolean problem = pm.find();
            if (problem && (pullLine == null || !pullLine.contains("Problem"))) pullLine = "\u26A0 Problem: " + pm.group(1);
            say("Pull done: " + (pullLine == null ? "" : pullLine), problem ? WARN : GOOD);
            if (problem && web != null) {
                web.evaluateJavascript(OUTLOOK_DIAG, new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        bundle = bundle + "\n\nOutlook on the TV when it stopped: " + v + "\n";
                        TvLog.line(RunnerActivity.this, "Runner: Outlook diag " + v);
                        pullLine = pullLine + " (Outlook diag in the bundle)";
                        toCalendar();
                    }
                });
            } else {
                toCalendar();
            }
        }
    };

    private void toCalendar() {
        stopPage();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (alive && job != null) openAroFlo(CAL_URL);
            }
        }, 1500);
    }

    private void openAroFlo(String url) {
        setNow(url.equals(CAL_URL) ? "Reading the AroFlo calendar..." : "Opening AroFlo...");
        pageWant = url.equals(CAL_URL) ? "Calendar" : "/ims/";
        pageRan = false;
        pageAt = System.currentTimeMillis();
        startPage(url, null);
        handler.postDelayed(afTimeout, AF_MAX_MS);
    }

    private final Runnable afTimeout = new Runnable() {
        @Override
        public void run() {
            if (!alive || job == null) return;
            final String stuck = "{\"err\":\"AroFlo didn't answer in 3 min (" + (web == null ? "no page" : String.valueOf(web.getUrl()).replaceAll("[?#].*$", "")) + ")\"}";
            if (web == null) {
                afDone(stuck);
                return;
            }
            // keep whatever weeks of the calendar it got
            web.evaluateJavascript("JSON.stringify(window.__runPartial||null)", new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    if (!alive || job == null) return;
                    try {
                        String inner = new JSONArray("[" + v + "]").optString(0, "null");
                        if (inner != null && inner.startsWith("{")) {
                            JSONObject o = new JSONObject(inner);
                            o.put("weeks", o.optInt("weeks") + " (stopped early, AroFlo was slow)");
                            afDone(o.toString());
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                    afDone(stuck);
                }
            });
        }
    };

    /** Runs the AroFlo part once the page we want has loaded. */
    private void runAf() {
        if (pageRan || web == null) return;
        pageRan = true;
        if ("api".equals(job.kind)) {
            runApi();
            return;
        }
        JSONObject j = new JSONObject();
        try {
            if ("lookup".equals(job.kind)) {
                j.put("kind", "lookup");
                j.put("terms", new JSONArray(job.terms));
                j.put("id", token + started);
            } else {
                j.put("kind", "cal");
                j.put("weeks", 5);
                if (job.day != null) j.put("day", job.day); // calendar starts on the pull's day (20/11)
            }
        } catch (Exception ignored) {
        }
        web.evaluateJavascript(afJs.replace("__TOKEN__", token).replace("__JOB__", j.toString()), null);
    }

    private void afDone(String json) {
        handler.removeCallbacks(afTimeout);
        stopPage();
        JSONObject r;
        try {
            r = new JSONObject(json);
        } catch (Exception e) {
            r = new JSONObject();
        }
        final String err = r.optString("err", "");
        if ("lookup".equals(job.kind)) {
            if (!err.isEmpty()) {
                fail("lookup didn't work: " + err);
                return;
            }
            String text = r.optString("text");
            say("Lookup done", GOOD);
            post("Lookup:\n" + text, null, null);
            finishJob();
            return;
        }
        // pull: add the calendar to the bundle and hand it over
        StringBuilder sb = new StringBuilder(bundle);
        String calLine;
        sb.append("\n\n=== AROFLO CALENDAR, read by the TV ").append(new SimpleDateFormat("d/M h:mm a", Locale.US).format(new Date())).append(" ===\n");
        if (!err.isEmpty()) {
            calLine = "AroFlo calendar not read: " + err;
            sb.append(calLine).append('\n');
            say(calLine, WARN);
        } else {
            JSONArray lines = r.optJSONArray("lines");
            int n = lines == null ? 0 : lines.length();
            calLine = "Calendar: " + n + " visits over " + r.optString("weeks") + " weeks from " + r.optString("from");
            sb.append(calLine).append(". One line a visit: day, time, staff | JN / status / substatus / note | client, PO, address\n");
            for (int i = 0; i < n; i++) sb.append(lines.optString(i)).append('\n');
            say(calLine, GOOD);
        }
        final String text = sb.toString();
        setNow("Handing the bundle over...");
        post("Inbox pull " + nice(job.day) + " done. " + (pullLine == null ? "" : pullLine.replaceAll("\\s*Sent to the dashboard.*$", "")
                        .replaceFirst("^(\u2713|\u26A0) (Copied|Ready \\(copy failed, hit Copy\\)): ", "$1 ")) + " " + calLine,
                text, "ec-inbox-" + job.day + ".txt");
        finishJob();
    }

    private void fail(String why) {
        say("Failed: " + why, 0xFFF87171);
        post(("lookup".equals(job.kind) ? "Lookup" : "Inbox pull " + nice(job.day)) + " failed: " + why, null, null);
        finishJob();
    }

    /** Back to where the TV was: Filer reopened, asleep again, or home. Opened on the TV itself: stays on this screen. */
    private void finishJob() {
        final Job j = job;
        job = null;
        // web.* jobs keep their page open as a tab for the next request (21/11), like a Chrome tab
        String what = j != null && j.api != null ? j.api.optString("do") : "";
        if ("api".equals(j.kind) && what.startsWith("web.") && !what.equals("web.close") && web != null) {
            JSONObject a = j.api.optJSONObject("args");
            int mins = Math.max(1, Math.min(15, a != null ? a.optInt("keep", 5) : 5));
            tabOpen = true;
            carry = j;
            handler.removeCallbacks(tabClose);
            handler.postDelayed(tabClose, mins * 60000L);
            state = "tab open on " + host(web.getUrl()) + ", closes in " + mins + " min if nothing else comes";
            setNow(state);
            return;
        }
        tabOpen = false;
        handler.removeCallbacks(tabClose);
        stopPage();
        state = "idle (last: " + (now != null ? now.getText() : "") + ")";
        setNow("Done.");
        dayRow.setVisibility(View.VISIBLE);
        if ("tv".equals(j.from) && !j.wasOff) return;
        // more api jobs waiting: stay open for them, keeping how the TV was before the first one
        if ("api".equals(j.kind) && Api.hasNext(this)) {
            carry = j;
            return;
        }
        restore(j);
    }

    private boolean tabOpen;

    /** The tab's had nothing for a while: close it and put the TV back how it was. */
    private final Runnable tabClose = new Runnable() {
        @Override
        public void run() {
            if (job != null || !tabOpen) return;
            tabOpen = false;
            Job j = carry;
            carry = null;
            stopPage();
            state = "idle (tab closed)";
            setNow("Done.");
            dayRow.setVisibility(View.VISIBLE);
            if (j != null) restore(j);
            else finish();
        }
    };

    private static String host(String u) {
        try {
            return String.valueOf(android.net.Uri.parse(u).getHost());
        } catch (Exception e) {
            return "?";
        }
    }

    private void restore(final Job j) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (job != null) return; // another job started in the meantime
                if (j.filerWasOpen) {
                    Intent i = new Intent(RunnerActivity.this, FilerActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    finish();
                } else {
                    finish();
                    if (j.wasOff) HomeButtonService.sleepTvSoon(getApplicationContext());
                }
            }
        }, 4000);
    }

    /** Answers on the TV remote task, with the bundle as a file when there is one (split into comments if the upload fails). */
    private void post(final String line, final String file, final String name) {
        final Job j = job;
        if (j != null && j.say != null && file == null) {
            j.say.say(line);
            return;
        }
        net.execute(new Runnable() {
            @Override
            public void run() {
                String token = Secrets.get(RunnerActivity.this, Secrets.TODOIST_TOKEN);
                if (token == null) return;
                try {
                    JSONObject att = file == null ? null : upload(token, file, name);
                    JSONObject b = new JSONObject();
                    b.put("task_id", Remote.TASK);
                    b.put("content", "TV: " + line + (att != null ? " Bundle attached (" + name + ", "
                            + (file.length() / 1024) + " KB)." : ""));
                    if (att != null) b.put("attachment", att);
                    String made = Http.todoist(token, "POST", "comments", b);
                    // the heartbeat names the newest bundle: "<comment id> <day> <time>", the dashboard picks it up from there
                    if (att != null && name != null && name.startsWith("ec-inbox-")) {
                        String id = new JSONObject(made).optString("id");
                        String day = name.replace("ec-inbox-", "").replace(".txt", "");
                        if (!id.isEmpty()) {
                            getSharedPreferences("runner", MODE_PRIVATE).edit()
                                    .putString("bundle", id + " " + day + " " + System.currentTimeMillis()).apply();
                            Status.write(RunnerActivity.this, null);
                        }
                    }
                    if (file != null && att == null) {
                        int parts = (file.length() + 13499) / 13500;
                        for (int i = 0; i < parts; i++) {
                            JSONObject p = new JSONObject();
                            p.put("task_id", Remote.TASK);
                            p.put("content", "TV: " + name + " part " + (i + 1) + " of " + parts + "\n"
                                    + file.substring(i * 13500, Math.min(file.length(), (i + 1) * 13500)));
                            Http.todoist(token, "POST", "comments", p);
                        }
                    }
                } catch (Exception e) {
                    TvLog.line(RunnerActivity.this, "Runner: couldn't post to Todoist: " + e.getMessage());
                }
            }
        });
    }

    /** Todoist upload (raw body). Null if it didn't work. */
    private static JSONObject upload(String token, String text, String name) {
        try {
            JSONObject r = new JSONObject();
            r.put("method", "POST");
            r.put("url", "https://api.todoist.com/api/v1/uploads");
            JSONObject h = new JSONObject();
            h.put("Authorization", "Bearer " + token);
            h.put("Content-Type", "text/plain");
            h.put("X-File-Name", name);
            h.put("Accept", "application/json");
            r.put("headers", h);
            r.put("b64", Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            r.put("timeout", 60000);
            JSONObject res = new JSONObject(Http.run(r.toString()));
            if (res.optBoolean("error") || res.optInt("status") / 100 != 2) return null;
            JSONObject a = new JSONObject(new String(Base64.decode(res.optString("b64", ""), Base64.DEFAULT), StandardCharsets.UTF_8));
            return a.has("file_url") ? a : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the hidden page

    private void startPage(String url, String inject) {
        stopPage();
        WebView w = new WebView(this);
        WebSettings st = w.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        st.setMediaPlaybackRequiresUserGesture(true);
        String def = WebSettings.getDefaultUserAgent(this);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([\\d.]+)").matcher(def);
        st.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + (m.find() ? m.group(1) : "120.0.0.0") + " Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        if (Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        w.addJavascriptInterface(new Bridge(), "ecRun");
        w.addJavascriptInterface(new GmBridge(), "ecGmBridge");
        w.addJavascriptInterface(new ApiBridge(), "ecApi");
        if (inject != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            HashSet<String> origins = new HashSet<>();
            for (String o : OUTLOOK_ORIGINS) origins.add(o);
            WebViewCompat.addDocumentStartJavaScript(w, inject, origins);
        } else if (inject != null) {
            fail("this TV's WebView can't run scripts at page start");
            return;
        }
        w.setWebChromeClient(new WebChromeClient());
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                // api jobs never go near admin or settings areas (21/11)
                String u = String.valueOf(req.getUrl());
                if ((job != null && "api".equals(job.kind) || tabOpen) && (BLOCKED.matcher(u).find() || Sites.BLOCKED.matcher(String.valueOf(req.getUrl().getHost())).find())) {
                    apiLine("BLOCKED: tried to open " + u.replaceAll("[?#].*$", ""));
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                // a notes lookup moves on to each task page: run the script again on each one
                if (view == web && job != null && ("lookup".equals(job.kind) || "api".equals(job.kind))) pageRan = false;
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                if (view != web || job == null || pageWant == null || bundle == null && !"lookup".equals(job.kind) && !"api".equals(job.kind)) return;
                boolean apiPage = job != null && "api".equals(job.kind) && u != null && (u.contains("office.aroflo.com") || u.contains("outlook.") || u.contains("login.microsoftonline.com")
                        || "web".equals(pageWant) && u.startsWith("http"));
                if (u != null && (u.contains("office.aroflo.com") || apiPage) && !pageRan) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (web == view && job != null) runAf();
                        }
                    }, 2500);
                }
            }
        });
        w.setFocusable(false);
        web = w;
        // PC size: the TV doubles everything (pixel ratio 2), so it's sized by the density to really be 1600 x 1000 to
        // the page. Then shrunk to fit the screen and shown full screen while the job runs (20/11).
        float dens = getResources().getDisplayMetrics().density;
        int pw = Math.round(1600 * dens), ph = Math.round(1000 * dens);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pw, ph);
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        float k = Math.min(sw / (float) pw, sh / (float) ph);
        w.setPivotX(0);
        w.setPivotY(0);
        w.setScaleX(k);
        w.setScaleY(k);
        w.setTranslationX((sw - pw * k) / 2f);
        w.setTranslationY((sh - ph * k) / 2f);
        webHolder.addView(w, 0, lp);
        cover.setVisibility(View.GONE);
        strip.setVisibility(View.VISIBLE);
        w.loadUrl(url);
    }

    private void stopPage() {
        WebView w = web;
        web = null;
        if (cover != null) cover.setVisibility(View.VISIBLE);
        if (strip != null) strip.setVisibility(View.GONE);
        if (w == null) return;
        try {
            webHolder.removeView(w);
            w.stopLoading();
            w.destroy();
        } catch (Exception ignored) {
        }
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void done(String t, final String json) {
            if (!token.equals(t)) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (alive && job != null) afDone(json);
                }
            });
        }
    }

    /** Tampermonkey's storage and web requests for the Inbox Pull script (the same store as the Filer's). */
    private final class GmBridge {
        private boolean ok(String t) {
            return alive && token.equals(t);
        }

        @JavascriptInterface
        public String get(String t, String key) {
            return ok(t) ? store.getJson(key) : null;
        }

        @JavascriptInterface
        public void set(String t, String key, String json) {
            if (ok(t)) store.setJson(key, json);
        }

        @JavascriptInterface
        public void del(String t, String key) {
            if (ok(t)) store.delete(key);
        }

        @JavascriptInterface
        public String list(String t) {
            return ok(t) ? store.listJson() : "[]";
        }

        @JavascriptInterface
        public void xhr(String t, final String id, final String req) {
            if (!ok(t)) return;
            net.execute(new Runnable() {
                @Override
                public void run() {
                    final String res = Http.run(req);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (web != null) web.evaluateJavascript("window.__ecGmDone&&window.__ecGmDone("
                                    + JSONObject.quote(id) + "," + res + ")", null);
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public void openTab(String t, String url) {
        }
    }


    // ---------------------------------------------------------------- api jobs (21/11, see Api.java)

    private static final java.util.regex.Pattern BLOCKED = java.util.regex.Pattern.compile(
            "(?i)(siteadmin|site_admin|/admin/|/administration|usermanagement|/settings/)");
    private static final String JOBS_RAW = "https://raw.githubusercontent.com/Badseed26/tv-launcher/";
    private Job carry;
    private String apiJs, apiState, apiHook;
    private final java.util.List<String[]> apiFiles = new java.util.ArrayList<>();
    private final StringBuilder apiLog = new StringBuilder();
    private int apiShots, apiRuns;
    private boolean apiRetried;

    private void beginApi(final Job j) {
        if (carry != null) {
            j.wasOff = carry.wasOff;
            j.filerWasOpen = carry.filerWasOpen;
            carry = null;
        }
        final JSONObject req = j.api;
        try {
            req.put("started", started);
        } catch (Exception ignored) {
        }
        final String what = req.optString("do"), id = req.optString("id");
        final String area = what.contains(".") ? what.substring(0, what.indexOf('.')) : what;
        apiLog.setLength(0);
        apiFiles.clear();
        apiState = null;
        apiShots = 0;
        apiRuns = 0;
        apiRetried = false;
        title.setText("Runner  ·  api " + what);
        setNow("api " + id + ": getting the job code...");
        say("api " + id + ": " + what + (req.optBoolean("dry") ? " (dry run)" : ""), DIM);
        apiLine("job " + id + " " + what + " args " + req.optJSONObject("args") + (req.optBoolean("dry") ? " DRY RUN" : "")
                + " branch " + req.optString("branch", "main") + " build " + Updater.currentVersion(this));
        net.execute(new Runnable() {
            @Override
            public void run() {
                final String code = jobCode(area, req.optString("branch", "main"), false);
                // Outlook jobs: a hook that runs before Outlook's own code, catching its sign-in and blocking every mailbox write
                apiHook = null;
                if ("outlook".equals(area)) {
                    try {
                        apiHook = jobFile("jobs/outlook-hook.js", req.optString("branch", "main"));
                    } catch (Exception e) {
                        apiLine("couldn't get the Outlook hook: " + e.getMessage());
                    }
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive || job != j) return;
                        if (code == null) {
                            apiDone(Api.err("couldn't get the job code for " + area + " from GitHub").toString());
                            return;
                        }
                        if ("outlook".equals(area) && (apiHook == null || !apiHook.contains("ecApiHook"))) {
                            apiDone(Api.err("no Outlook hook, not opening Outlook without the write block").toString());
                            return;
                        }
                        apiJs = code;
                        JSONObject ja = req.optJSONObject("args");
                        boolean reuse = "web".equals(area) && tabOpen && web != null && (ja == null || ja.optString("url").isEmpty());
                        boolean wasTab = tabOpen;
                        tabOpen = false;
                        handler.removeCallbacks(tabClose);
                        if (reuse) {
                            apiLine("on the open tab: " + String.valueOf(web.getUrl()).replaceAll("[?#].*$", ""));
                            pageWant = area;
                            pageRan = true;
                            pageAt = System.currentTimeMillis();
                            runApi();
                            long max0 = Math.max(30, Math.min(900, ja != null ? ja.optInt("timeout", 120) : 120)) * 1000L;
                            handler.postDelayed(apiTimeout, max0);
                            return;
                        }
                        if ("web".equals(area) && (ja == null || ja.optString("url").isEmpty())) {
                            apiDone(Api.err(wasTab ? "web jobs need a url when the tab isn't a web tab" : "no tab open (it closes after a few minutes idle), send web.open with a url").toString());
                            return;
                        }
                        String url = startUrl(area, ja);
                        if (url == null) {
                            apiDone(Api.err("web".equals(area) ? "the TV only opens AroFlo, CNW, Tradezone and Ambrose pages (https), not " + (ja == null ? "" : ja.optString("url"))
                                    : "nothing on the TV knows how to do " + what).toString());
                            return;
                        }
                        apiLine("open " + url.replaceAll("[?#].*$", ""));
                        setNow("api " + id + ": opening " + area + "...");
                        pageWant = area;
                        pageRan = false;
                        pageAt = System.currentTimeMillis();
                        startPage(url, apiHook != null ? apiHook.replace("__TOKEN__", token) : null);
                        long max = Math.max(30, Math.min(900, req.optJSONObject("args") != null ? req.optJSONObject("args").optInt("timeout", 180) : 180)) * 1000L;
                        handler.postDelayed(apiTimeout, max);
                    }
                });
            }
        });
    }

    /** Where a job starts. AroFlo only for now; more areas get added here and as files in jobs/. */
    private static String startUrl(String area, JSONObject args) {
        String u = args == null ? "" : args.optString("url");
        if ("aroflo".equals(area)) return u.startsWith("https://office.aroflo.com/") ? u : AF_HOME;
        if ("outlook".equals(area)) return OUTLOOK;
        if ("web".equals(area)) {
            if (!u.startsWith("https://")) return null;
            return Sites.startOk(android.net.Uri.parse(u).getHost()) ? u : null;
        }
        return null;
    }

    private static String shaFor, sha;
    private static long shaAt;

    /**
     * A file from the repo at the newest commit on the branch: one GitHub API call for the commit (kept 15 s), then the
     * raw file at that exact commit, which isn't rate limited and is never a stale copy.
     */
    private static String jobFile(String path, String branch) throws Exception {
        if (sha == null || !branch.equals(shaFor) || System.currentTimeMillis() - shaAt > 15000) {
            JSONObject r = new JSONObject();
            r.put("method", "GET");
            r.put("url", "https://api.github.com/repos/Badseed26/tv-launcher/commits/" + java.net.URLEncoder.encode(branch, "UTF-8"));
            JSONObject h = new JSONObject();
            h.put("Accept", "application/vnd.github.sha");
            h.put("User-Agent", "ec-tv-runner");
            r.put("headers", h);
            r.put("timeout", 20000);
            JSONObject res = new JSONObject(Http.run(r.toString()));
            String got = res.optInt("status") == 200 ? new String(Base64.decode(res.optString("b64", ""), Base64.DEFAULT), StandardCharsets.UTF_8).trim() : "";
            if (got.matches("[0-9a-f]{40}")) {
                sha = got;
                shaFor = branch;
                shaAt = System.currentTimeMillis();
            } else {
                // rate limited or offline: the branch's raw file (can be up to 5 min old)
                return Http.get(JOBS_RAW + branch + "/" + path + "?t=" + System.currentTimeMillis(), 20000);
            }
        }
        return Http.get(JOBS_RAW + sha + "/" + path, 20000);
    }

    /** jobs/lib.js plus jobs/<area>.js off GitHub (the branch asked for), or the last copy that worked. */
    private String jobCode(String area, String branch, boolean lastGood) {
        File good = new File(getFilesDir(), "api-" + area + ".js");
        if (!lastGood) {
            try {
                String lib = jobFile("jobs/lib.js", branch);
                String a = jobFile("jobs/" + area + ".js", branch);
                if (lib.contains("window.ec") && a.contains("ec.run")) return lib + "\n" + a;
            } catch (Exception e) {
                apiLine("couldn't get the job code from GitHub (" + e.getMessage() + "), using the last copy that worked");
            }
        }
        try {
            if (good.exists()) return read(new java.io.FileInputStream(good));
        } catch (Exception ignored) {
        }
        return null;
    }

    private void keepGood(String area, String code) {
        try {
            FileWriter w = new FileWriter(new File(getFilesDir(), "api-" + area + ".js"), false);
            w.write(code);
            w.close();
        } catch (Exception ignored) {
        }
    }

    /** Runs the job code on each page it lands on, with the state it saved before the page changed. */
    private void runApi() {
        if (web == null || job == null) return;
        apiRuns++;
        if (apiRuns > 40) {
            apiDone(Api.err("went through 40 pages without finishing, stopped").toString());
            return;
        }
        JSONObject jj = new JSONObject();
        try {
            JSONObject req = job.api;
            jj.put("id", req.optString("id"));
            jj.put("do", req.optString("do"));
            jj.put("args", req.optJSONObject("args") != null ? req.optJSONObject("args") : new JSONObject());
            jj.put("dry", req.optBoolean("dry"));
            jj.put("n", apiRuns);
            jj.put("state", apiState != null ? new JSONObject(apiState) : new JSONObject());
        } catch (Exception ignored) {
        }
        String u = String.valueOf(web.getUrl()).replaceAll("[?#].*$", "");
        apiLine("page " + apiRuns + ": " + u);
        setNow("api " + job.api.optString("id") + ": working on " + u.replaceAll("^https://[^/]+", ""));
        // *.run jobs: the TV puts the code on the page itself (Outlook's page won't let a script turn text into code).
        // On its own, so a mistake in it can't stop the job code: the job then says the code didn't parse.
        JSONObject ra = job.api.optJSONObject("args");
        if (ra != null && ra.has("js") && job.api.optString("do").endsWith(".run"))
            web.evaluateJavascript("window.__ecRun = async function (ec, A, S, svc, search, getItem) {\n" + ra.optString("js") + "\n};", null);
        web.evaluateJavascript(apiJs.replace("__TOKEN__", token).replace("__JOB__", jj.toString()), null);
    }

    private final Runnable apiTimeout = new Runnable() {
        @Override
        public void run() {
            if (!alive || job == null || !"api".equals(job.kind)) return;
            apiShot("timed out");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (alive && job != null && "api".equals(job.kind))
                        apiDone(Api.err("timed out on " + (web == null ? "no page" : String.valueOf(web.getUrl()).replaceAll("[?#].*$", ""))
                                + (apiState != null ? ", got as far as " + apiState : "")).toString());
                }
            }, 1500);
        }
    };

    /** tv.cancel on the running job. */
    void cancelApi() {
        if (job != null && "api".equals(job.kind)) apiDone(Api.err("cancelled").toString());
    }

    private void apiDone(String json) {
        if (job == null || !"api".equals(job.kind)) return;
        handler.removeCallbacks(apiTimeout);
        JSONObject r;
        try {
            r = new JSONObject(json);
        } catch (Exception e) {
            r = Api.err("bad answer from the job code: " + json);
        }
        final Job j = job;
        final JSONObject req = j.api;
        final String what = req.optString("do"), area = what.contains(".") ? what.substring(0, what.indexOf('.')) : what;
        // the job code itself fell over: run it once more with the last copy that worked
        if (r.optBoolean("crash") && !apiRetried) {
            final String old = jobCode(area, "main", true);
            if (old != null && !old.equals(apiJs)) {
                apiRetried = true;
                apiLine("job code crashed (" + r.optString("err") + "), trying again with the last copy that worked");
                apiJs = old;
                apiState = null;
                apiRuns = 0;
                stopPage();
                pageWant = area;
                pageRan = false;
                startPage(startUrl(area, req.optJSONObject("args")), null);
                handler.postDelayed(apiTimeout, 180000);
                return;
            }
        }
        boolean ok = r.optBoolean("ok") && !r.has("err");
        if (ok && !apiRetried && apiJs != null) keepGood(area, apiJs);
        if (apiRetried) {
            try {
                r.put("note", "the newest job code crashed, this answer came from the last copy that worked");
            } catch (Exception ignored) {
            }
        }
        apiLine((ok ? "OK " : "FAILED ") + r.toString());
        say("api " + req.optString("id") + (ok ? " done" : " failed: " + r.optString("err")), ok ? GOOD : WARN);
        String log = apiLog.toString();
        final java.util.List<String[]> files = new java.util.ArrayList<>(apiFiles);
        apiFiles.clear();
        finishJob();
        Api.finished(getApplicationContext(), req, r, log);
        if (ok) for (String[] f : files) Api.postFile(getApplicationContext(), "api " + req.optString("id") + " file " + f[0], f[0], f[1], f[2]);
    }

    private void apiLine(String s) {
        apiLog.append(new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date())).append("  ").append(s).append('\n');
        if (apiLog.length() > 200000) apiLog.delete(0, 50000);
    }

    /** A small picture of the job's page, kept with the job (Api.dir). */
    private void apiShot(String label) {
        WebView w = web;
        if (w == null || job == null || job.api == null || w.getWidth() == 0) return;
        try {
            int ww = w.getWidth(), hh = w.getHeight();
            float k = Math.min(1f, 900f / ww);
            Bitmap b = Bitmap.createBitmap(Math.round(ww * k), Math.round(hh * k), Bitmap.Config.RGB_565);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            cv.scale(k, k);
            w.draw(cv);
            apiShots++;
            String name = String.format(Locale.US, "%02d-%s.jpg", apiShots, label.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", ""));
            if (name.length() > 60) name = name.substring(0, 56) + ".jpg";
            java.io.FileOutputStream o = new java.io.FileOutputStream(new File(Api.dir(this, job.api.optString("id")), name));
            b.compress(Bitmap.CompressFormat.JPEG, 65, o);
            o.close();
            b.recycle();
            apiLine("picture " + name);
        } catch (Throwable e) {
            apiLine("picture failed: " + e.getMessage());
        }
    }

    /** A real tap at a point on the page (page pixels), like a finger. AroFlo ignores some script clicks. */
    private void apiTap(float x, float y, float pageWidth) {
        WebView w = web;
        if (w == null || pageWidth <= 0) return;
        float k = w.getWidth() / pageWidth, px = x * k, py = y * k;
        long down = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent d = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, px, py, 0);
        d.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        w.dispatchTouchEvent(d);
        d.recycle();
        android.view.MotionEvent u = android.view.MotionEvent.obtain(down, down + 70, android.view.MotionEvent.ACTION_UP, px, py, 0);
        u.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        w.dispatchTouchEvent(u);
        u.recycle();
    }

    /** What the job code can ask of the TV. Every call carries the job's token, so only our page can use it. */
    private final class ApiBridge {
        private boolean ok(String t) {
            return alive && token.equals(t) && job != null && "api".equals(job.kind);
        }

        @JavascriptInterface
        public void log(String t, final String s) {
            if (!ok(t)) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    apiLine(s.length() > 2000 ? s.substring(0, 2000) + "..." : s);
                }
            });
        }

        @JavascriptInterface
        public void save(String t, String state) {
            if (ok(t)) apiState = state;
        }

        @JavascriptInterface
        public void shot(String t, final String label) {
            if (!ok(t)) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    apiShot(label);
                }
            });
        }

        @JavascriptInterface
        public void tap(String t, final float x, final float y, final float pageWidth) {
            if (!ok(t)) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    apiTap(x, y, pageWidth);
                }
            });
        }

        @JavascriptInterface
        public void done(String t, final String json) {
            if (!ok(t)) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (alive && job != null) apiDone(json);
                }
            });
        }

        /** A file for the answer (an email's attachment, say), base64. Goes on the remote task as its own comment. */
        @JavascriptInterface
        public void file(String t, final String name, final String b64, final String type) {
            if (!ok(t) || name == null || b64 == null) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (apiFiles.size() < 10) {
                        apiFiles.add(new String[] {name.replaceAll("[\\\\/:*?\"<>|]+", "_"), b64, type == null ? "application/octet-stream" : type});
                        apiLine("file " + name + " (" + (b64.length() * 3 / 4 / 1024) + " KB)");
                    }
                }
            });
        }

        /**
         * A photo or document off AroFlo, fetched by the app with the TV's AroFlo sign in (21/11), so a job on another site
         * (an Ambrose report) can upload it. AroFlo only. Answers through window.__ecGot(id, b64, type, err).
         */
        @JavascriptInterface
        public void grab(String t, final String id, final String path) {
            if (!ok(t) || path == null) return;
            final String url = path.startsWith("/") ? "https://office.aroflo.com" + path : path;
            net.execute(new Runnable() {
                @Override
                public void run() {
                    String b64 = null, type = "", err = null;
                    try {
                        if (!url.startsWith("https://office.aroflo.com/")) throw new Exception("only AroFlo files");
                        JSONObject r = new JSONObject();
                        r.put("method", "GET");
                        r.put("url", url);
                        JSONObject h = new JSONObject();
                        String ck = CookieManager.getInstance().getCookie("https://office.aroflo.com/");
                        if (ck != null) h.put("Cookie", ck);
                        h.put("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                        r.put("headers", h);
                        r.put("timeout", 60000);
                        JSONObject res = new JSONObject(Http.run(r.toString()));
                        if (res.optBoolean("error")) throw new Exception(res.optString("message"));
                        if (res.optInt("status") < 200 || res.optInt("status") >= 300) throw new Exception("AroFlo said " + res.optInt("status"));
                        b64 = res.optString("b64");
                        if (b64.length() > 22000000) throw new Exception("over 16 MB, too big");
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)content-type\\W+([a-z]+/[a-z0-9.+-]+)").matcher(res.optString("headers"));
                        if (m.find()) type = m.group(1);
                        if (type.startsWith("text/html")) throw new Exception("AroFlo sent a page, not the file (signed out?)");
                        apiLine("grabbed " + url.replaceAll("[?#].*$", "").replaceAll("^.*/", "") + " (" + (b64.length() * 3 / 4 / 1024) + " KB)");
                    } catch (Exception e) {
                        err = String.valueOf(e.getMessage());
                        b64 = null;
                    }
                    final String js = "window.__ecGot&&window.__ecGot(" + JSONObject.quote(id) + "," + (b64 == null ? "null" : "\"" + b64 + "\"")
                            + "," + JSONObject.quote(type) + "," + (err == null ? "null" : JSONObject.quote(err)) + ")";
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (web != null) web.evaluateJavascript(js, null);
                        }
                    });
                }
            });
        }

        /** A supplier or Ambrose sign in page: the TV fills the saved login itself, the job code never sees it. */
        @JavascriptInterface
        public String siteLogin(String t, final String name) {
            if (!ok(t)) return "no";
            final String[] site = Sites.byName(name);
            final String why = siteLoginAllowed(site);
            if (why != null) return why;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    doSiteLogin(site);
                }
            });
            return "ok";
        }

        /** AroFlo's sign-in page: the TV fills the saved login itself, the job code never sees it. */
        @JavascriptInterface
        public String login(String t) {
            if (!ok(t)) return "no";
            final String why = afLoginAllowed();
            if (why != null) return why;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    afLogin();
                }
            });
            return "ok";
        }
    }

    /**
     * CNW, Tradezone or Ambrose sign in page (21/11): the TV fills the login saved on the phone page itself. At most 3
     * goes per site in 30 min so the account never locks. Handles one page (email and password) or two (email, Next,
     * then password). Ticks "remember me" so it stays signed in longer.
     */
    private String siteLoginAllowed(String[] site) {
        if (site == null) return "this isn't a site the TV keeps a login for";
        if (!Sites.saved(this, site[0])) return "no " + site[1] + " login saved on the TV yet (Ethan: phone page, Saved logins)";
        android.content.SharedPreferences sp = getSharedPreferences("api", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        String k = "login_" + site[0];
        String[] t = sp.getString(k, "0,0,0").split(",");
        int recent = 0;
        for (String x : t) if (now - Long.parseLong(x) < 30 * 60000) recent++;
        if (recent >= 3) return "already tried the saved " + site[1] + " login 3 times in 30 min, stopping so it doesn't lock";
        sp.edit().putString(k, t[1] + "," + t[2] + "," + now).apply();
        return null;
    }

    private void doSiteLogin(String[] site) {
        WebView w = web;
        if (w == null || site == null) return;
        // the site itself, or its sign in host (Auth0 for Ambrose admin, 21/11, or a login or b2c host)
        String hh = host(w.getUrl());
        if (Sites.byHost(hh) == null && !hh.matches("(?i).*(login|b2c|auth0\\.com|\\.auth\\.).*")) return;
        try {
            JSONObject c = new JSONObject();
            c.put("user", Secrets.get(this, Sites.userKey(site[0])));
            c.put("pass", Secrets.get(this, Sites.passKey(site[0])));
            apiLine("signing in to " + site[1] + " with the saved login");
            w.evaluateJavascript("(function(c){var vis=function(e){var r=e.getBoundingClientRect(),st=getComputedStyle(e);return r.width>0&&r.height>0&&st.visibility!='hidden';};"
                    + "var setV=function(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;el.focus();d.call(el,v);"
                    + "['input','keyup','change'].forEach(function(t){el.dispatchEvent(new Event(t,{bubbles:true}));});el.blur();};"
                    + "var all=function(q,r){return [].slice.call((r||document).querySelectorAll(q)).filter(vis);};"
                    + "var pwd=all('input[type=password]')[0],f=(pwd&&pwd.form)||document;"
                    + "var uf=all('input[type=email],input[type=text],input:not([type])',f).filter(function(i){return /user|email|login|name|account|customer/i.test((i.name||'')+(i.id||'')+(i.autocomplete||'')+(i.placeholder||''))||i.type=='email';})[0]"
                    + "||all('input[type=email],input[type=text],input:not([type])',f)[0];"
                    + "if(uf&&c.user&&!uf.value)setV(uf,c.user);if(pwd)setV(pwd,c.pass);"
                    + "all('input[type=checkbox]',f).forEach(function(x){var l=(x.name||'')+(x.id||'')+((x.closest('label')||{}).textContent||'');if(/remember|keep|stay/i.test(l)&&!x.checked)x.click();});"
                    + "setTimeout(function(){var b=null,pats=pwd?[/^\\s*(log\\s?in|sign\\s?in|login)\\s*$/i,/(log|sign)\\s?in/i]:[/^\\s*(next|continue)\\s*$/i,/(log|sign)\\s?in/i];"
                    + "var cs=all('button,input[type=submit],a[role=button],[role=button]');for(var i=0;i<pats.length&&!b;i++)for(var j=0;j<cs.length;j++){"
                    + "var tt=(cs[j].textContent||cs[j].value||'').trim();if(pats[i].test(tt)){b=cs[j];break;}}"
                    + "if(!b&&f!==document)b=f.querySelector('button[type=submit],input[type=submit],button:not([type])');if(b)b.click();else if(pwd&&pwd.form)pwd.form.requestSubmit?pwd.form.requestSubmit():pwd.form.submit();},500);})(" + c + ")", null);
        } catch (Exception e) {
            apiLine("sign in failed: " + e.getMessage());
        }
    }

    /** Null when the TV may try the saved AroFlo login now: at most 2 goes in 30 min, so the account never locks. */
    private String afLoginAllowed() {
        if (!Secrets.has(this, Secrets.AROFLO_PASS)) return "no AroFlo login saved on the TV";
        android.content.SharedPreferences sp = getSharedPreferences("api", MODE_PRIVATE);
        long now = System.currentTimeMillis(), a = sp.getLong("afLogin1", 0), b = sp.getLong("afLogin2", 0);
        if (now - a < 30 * 60000 && now - b < 30 * 60000) return "already tried the saved AroFlo login twice in 30 min, stopping so it doesn't lock";
        sp.edit().putLong("afLogin1", b).putLong("afLogin2", now).apply();
        return null;
    }

    private void afLogin() {
        WebView w = web;
        if (w == null) return;
        String host = android.net.Uri.parse(String.valueOf(w.getUrl())).getHost();
        if (host == null || !(host.equals("aroflo.com") || host.endsWith(".aroflo.com"))) return;
        try {
            JSONObject c = new JSONObject();
            c.put("user", Secrets.get(this, Secrets.AROFLO_USER));
            c.put("pass", Secrets.get(this, Secrets.AROFLO_PASS));
            apiLine("signing in to AroFlo with the saved login");
            w.evaluateJavascript("(function(c){var vis=function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;};"
                    + "var setV=function(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;el.focus();d.call(el,v);"
                    + "['input','keyup','change'].forEach(function(t){el.dispatchEvent(new Event(t,{bubbles:true}));});};"
                    + "var pwd=[].slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];if(!pwd)return;var f=pwd.form||document;"
                    + "var uf=[].slice.call(f.querySelectorAll('input[type=text],input[type=email],input:not([type])')).filter(vis)[0];"
                    + "if(uf&&c.user)setV(uf,c.user);setV(pwd,c.pass);setTimeout(function(){var b=null,pats=[/office\\s*login/i,/^\\s*(log\\s?in|sign\\s?in)\\s*$/i];"
                    + "var cs=document.querySelectorAll('button,input[type=submit],a[role=button],[role=button]');for(var i=0;i<pats.length&&!b;i++)for(var j=0;j<cs.length;j++){"
                    + "var tt=(cs[j].textContent||cs[j].value||'').trim();if(vis(cs[j])&&pats[i].test(tt)){b=cs[j];break;}}"
                    + "if(!b)b=f.querySelector('button[type=submit],input[type=submit]');if(b)b.click();else if(pwd.form)pwd.form.submit();},600);})(" + c + ")", null);
        } catch (Exception e) {
            apiLine("sign in failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- bits

    /** Newest Inbox Pull from GitHub (kept on the TV), or the last good copy. */
    private String loadScript() {
        File cache = new File(getFilesDir(), "ec-inbox-pull.js");
        try {
            String src = fresh("ec-userscripts", "src/ec-inbox-pull.js", PULL_URL);
            if (src.contains("==UserScript==")) {
                FileWriter w = new FileWriter(cache, false);
                w.write(src);
                w.close();
                return src;
            }
        } catch (Exception ignored) {
        }
        try {
            if (cache.exists()) return read(new java.io.FileInputStream(cache));
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Newest copy of a script straight from GitHub's API (not cached, so a push is live on the next run; the raw file
     * server can hand out the old copy for 5 min). Falls back to the raw file.
     */
    private static String fresh(String repo, String path, String rawUrl) throws Exception {
        return fresh(repo, path, rawUrl, "main");
    }

    private static String fresh(String repo, String path, String rawUrl, String ref) throws Exception {
        try {
            JSONObject r = new JSONObject();
            r.put("method", "GET");
            r.put("url", "https://api.github.com/repos/Badseed26/" + repo + "/contents/" + path + "?ref=" + java.net.URLEncoder.encode(ref, "UTF-8"));
            JSONObject h = new JSONObject();
            h.put("Accept", "application/vnd.github.raw");
            h.put("User-Agent", "ec-tv-runner");
            r.put("headers", h);
            r.put("timeout", 20000);
            JSONObject res = new JSONObject(Http.run(r.toString()));
            if (!res.optBoolean("error") && res.optInt("status") == 200)
                return new String(Base64.decode(res.optString("b64", ""), Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        return Http.get(rawUrl + "?t=" + System.currentTimeMillis(), 20000);
    }

    /** Newest AroFlo side from GitHub (kept on the TV), else the last good copy, else the one built in. */
    private void loadAf() {
        File cache = new File(getFilesDir(), "runner-af.js");
        try {
            String src = fresh("tv-launcher", "app/src/main/assets/runner-af.js", AF_URL);
            if (src.contains("__JOB__") && src.contains("ecRun.done")) {
                FileWriter w = new FileWriter(cache, false);
                w.write(src);
                w.close();
                afJs = src;
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            if (cache.exists()) {
                String c = read(new java.io.FileInputStream(cache));
                if (c.contains("__JOB__")) afJs = c;
            }
        } catch (Exception ignored) {
        }
    }

    private static String versionOf(String src) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("@version\\s+(\\S+)").matcher(src);
        return m.find() ? m.group(1) : "?";
    }

    private String asset(String name) {
        try {
            return read(getAssets().open(name));
        } catch (Exception e) {
            return "";
        }
    }

    private static String read(java.io.InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }
}
