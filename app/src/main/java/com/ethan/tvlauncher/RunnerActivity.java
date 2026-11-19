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
        Remote.Say say;
        boolean wasOff, filerWasOpen;
    }

    private static Job pending;

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
    private FrameLayout webHolder;
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

        FrameLayout cover = new FrameLayout(this);
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
    }

    // ---------------------------------------------------------------- jobs

    private void begin(Job j) {
        job = j;
        started = System.currentTimeMillis();
        bundle = null;
        pullLine = null;
        dayRow.setVisibility(View.GONE);
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
        JSONObject j = new JSONObject();
        try {
            if ("lookup".equals(job.kind)) {
                j.put("kind", "lookup");
                j.put("terms", new JSONArray(job.terms));
                j.put("id", token + started);
            } else {
                j.put("kind", "cal");
                j.put("weeks", 5);
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
        stopPage();
        state = "idle (last: " + (now != null ? now.getText() : "") + ")";
        setNow("Done.");
        dayRow.setVisibility(View.VISIBLE);
        if ("tv".equals(j.from) && !j.wasOff) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
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
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                // a notes lookup moves on to each task page: run the script again on each one
                if (view == web && job != null && "lookup".equals(job.kind)) pageRan = false;
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                if (view != web || job == null || pageWant == null || bundle == null && !"lookup".equals(job.kind)) return;
                if (u != null && u.contains("office.aroflo.com") && !pageRan) {
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
        // PC size behind the cover. 20/11: the TV doubles everything (pixel ratio 2), so 1600 x 1000 pixels was only
        // 800 x 500 to Outlook: folder list folded away and a missed day couldn't scroll back (Wed 18/11 came back empty).
        // Sized by the screen density now, so the page really is 1600 x 1000.
        float dens = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(1600 * dens), Math.round(1000 * dens));
        webHolder.addView(w, 0, lp);
        w.loadUrl(url);
    }

    private void stopPage() {
        WebView w = web;
        web = null;
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

    // ---------------------------------------------------------------- bits

    /** Newest Inbox Pull from GitHub (kept on the TV), or the last good copy. */
    private String loadScript() {
        File cache = new File(getFilesDir(), "ec-inbox-pull.js");
        try {
            String src = Http.get(PULL_URL + "?t=" + System.currentTimeMillis(), 20000);
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

    /** Newest AroFlo side from GitHub (kept on the TV), else the last good copy, else the one built in. */
    private void loadAf() {
        File cache = new File(getFilesDir(), "runner-af.js");
        try {
            String src = Http.get(AF_URL + "?t=" + System.currentTimeMillis(), 15000);
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
