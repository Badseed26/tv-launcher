package com.ethan.tvlauncher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filer screen (test build). While this screen is open, Outlook and AroFlo run hidden
 * behind it and the screen stays on. Close it and both sessions shut down straight away.
 * Only the status, free RAM and a short log are ever shown.
 */
public class FilerActivity extends Activity {

    private static final String OUTLOOK_URL = "https://outlook.office.com/mail/";
    private static final String AROFLO_URL = "https://office.aroflo.com/";
    private static final long TICK_MS = 8000;
    private static final long FILL_WINDOW_MS = 30L * 60 * 1000;
    private static final int MAX_PASSWORD_FILLS = 2;

    private static final int GREEN = 0xFF4ADE80;
    private static final int AMBER = 0xFFFBBF24;
    private static final int RED = 0xFFF87171;
    private static final int DIM = 0xFF8C92A8;

    private static String probeJs;

    /** The open Filer screen, so a code pushed from AirHQ can reach it. */
    static volatile FilerActivity instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private FrameLayout webHolder;
    private FrameLayout cover;
    private LinearLayout panel;
    private LinearLayout logBox;
    private TextView ramText;
    private TextView signInNote;
    private boolean running;
    private Session showing;
    private long lastRamLog;

    private Session outlook;
    private Session aroflo;

    /** One hidden browser session (Outlook or AroFlo). */
    private final class Session {
        final String name;
        final String url;
        WebView view;
        String state = "Starting";
        long lastAlive;
        int noResponse;
        boolean gaveUp;
        final List<Long> passwordFills = new ArrayList<>();
        TextView dot;
        TextView status;

        Session(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (probeJs == null) probeJs = readAsset("probe.js");

        outlook = new Session("Outlook", OUTLOOK_URL);
        aroflo = new Session("AroFlo", AROFLO_URL);

        buildUi();
        running = true;
        instance = this;
        log("Filer started (test build)");
        if (!Secrets.has(this, Secrets.MS_PASS) || !Secrets.has(this, Secrets.AROFLO_PASS)) {
            log("No logins saved yet, sign in with Show Outlook / Show AroFlo");
        }

        startSession(outlook);
        startSession(aroflo);
        handler.postDelayed(tick, 3000);
        handler.postDelayed(drift, 30000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Closed, Home pressed, another app opened or the TV switched off: shut it all down.
        shutdown();
        finish();
    }

    @Override
    public void onBackPressed() {
        if (showing != null) {
            hideSession();
        } else {
            finish();
        }
    }

    private void shutdown() {
        if (!running) return;
        running = false;
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        stopSession(outlook);
        stopSession(aroflo);
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
        writeLogLine(stamp() + "  Filer closed, both sessions shut down");
    }

    // ---------------------------------------------------------------- sessions

    private void startSession(Session s) {
        WebView w = new WebView(this);
        WebSettings st = w.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        st.setSupportMultipleWindows(false);
        st.setJavaScriptCanOpenWindowsAutomatically(true);
        st.setMediaPlaybackRequiresUserGesture(true);
        st.setUserAgentString(desktopUserAgent());

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);

        if (Build.VERSION.SDK_INT >= 26) {
            // Keep the page's renderer important even though it's never on screen.
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        w.setWebChromeClient(new WebChromeClient());
        w.setWebViewClient(new Client(s));
        w.setFocusable(true);

        s.view = w;
        s.noResponse = 0;
        // Sits behind the dark cover: laid out full size so the pages render properly.
        webHolder.addView(w, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        w.loadUrl(s.url);
        setState(s, "Loading");
    }

    private void stopSession(Session s) {
        WebView w = s.view;
        s.view = null;
        if (w == null) return;
        try {
            webHolder.removeView(w);
            w.stopLoading();
            w.destroy();
        } catch (Exception ignored) {
        }
    }

    private void restartSession(Session s, String why) {
        log(s.name + ": " + why + ", restarting it");
        resetTries(s);
        if (showing == s) hideSession();
        stopSession(s);
        startSession(s);
    }

    private final class Client extends WebViewClient {
        private final Session s;

        Client(Session s) {
            this.s = s;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (running && view == s.view) probe(s);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view == s.view && running) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (running) restartSession(s, "page crashed or was closed for memory");
                    }
                });
            } else {
                try {
                    view.destroy();
                } catch (Exception ignored) {
                }
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- checking

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            probe(outlook);
            probe(aroflo);
            updateRam();
            refreshAges();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void probe(final Session s) {
        final WebView w = s.view;
        if (w == null) return;
        String creds = credsFor(s, w.getUrl());
        w.evaluateJavascript(probeJs.replace("__CREDS__", creds), new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (running && w == s.view) handleProbe(s, value);
            }
        });
    }

    /** Only hands the login to the page when it's that site's own sign-in page. */
    private String credsFor(Session s, String url) {
        if (url == null || s.gaveUp) return "null";
        String host = Uri.parse(url).getHost();
        if (host == null) return "null";
        host = host.toLowerCase(Locale.ROOT);
        try {
            if (s == outlook && (host.equals("login.microsoftonline.com") || host.equals("login.live.com"))) {
                return creds("ms", Secrets.MS_USER, Secrets.MS_PASS);
            }
            if (s == aroflo && (host.equals("aroflo.com") || host.endsWith(".aroflo.com"))) {
                return creds("af", Secrets.AROFLO_USER, Secrets.AROFLO_PASS);
            }
        } catch (Exception ignored) {
        }
        return "null";
    }

    private String creds(String kind, String userKey, String passKey) throws Exception {
        String user = Secrets.get(this, userKey);
        String pass = Secrets.get(this, passKey);
        if (user == null && pass == null) return "null";
        JSONObject j = new JSONObject();
        j.put("kind", kind);
        if (user != null) j.put("user", user);
        if (pass != null && passwordFillAllowed(kind.equals("ms") ? outlook : aroflo)) j.put("pass", pass);
        return j.toString();
    }

    private boolean passwordFillAllowed(Session s) {
        long now = System.currentTimeMillis();
        Iterator<Long> it = s.passwordFills.iterator();
        while (it.hasNext()) {
            if (now - it.next() > FILL_WINDOW_MS) it.remove();
        }
        return s.passwordFills.size() < MAX_PASSWORD_FILLS;
    }

    private void handleProbe(Session s, String raw) {
        JSONObject j;
        try {
            Object v = new JSONTokener(raw).nextValue();
            j = new JSONObject((String) v);
        } catch (Exception e) {
            s.noResponse++;
            if (s.noResponse >= 4) {
                restartSession(s, "stopped responding");
            } else {
                setState(s, "No response");
            }
            return;
        }
        s.noResponse = 0;

        String host = j.optString("host", "").toLowerCase(Locale.ROOT);
        String path = j.optString("path", "");
        boolean pwd = j.optBoolean("pwd");
        boolean code = j.optBoolean("code");
        boolean limit = j.optBoolean("limit");
        String number = j.optString("number", "");
        String act = j.optString("act", "");
        boolean ready = "complete".equals(j.optString("ready"));

        if (!act.isEmpty()) {
            log(s.name + ": " + act);
            if (act.contains("password")) {
                s.passwordFills.add(System.currentTimeMillis());
                if (!passwordFillAllowed(s)) {
                    s.gaveUp = true;
                    log(s.name + ": tried the saved login twice, stopping so the account doesn't lock");
                }
            }
        }

        boolean hasLogin = s == outlook ? Secrets.has(this, Secrets.MS_PASS) : Secrets.has(this, Secrets.AROFLO_PASS);
        String signingIn = s.gaveUp ? "Sign-in failed, check login"
                : hasLogin ? "Signing in" : "Needs sign-in (no login saved)";
        String state;

        if (s == outlook) {
            if (host.startsWith("login.")) {
                state = number.isEmpty() ? signingIn : "Approve sign-in: " + number;
            } else if (host.startsWith("outlook.") && path.contains("/mail") && !pwd) {
                state = "Alive";
            } else {
                state = "Loading";
            }
        } else {
            if (limit) {
                state = "Session limit";
            } else if (code) {
                state = "Needs code";
            } else if (pwd) {
                state = signingIn;
            } else if (host.contains("aroflo") && ready) {
                state = "Alive";
            } else {
                state = "Loading";
            }
        }

        if ("Alive".equals(state)) {
            s.lastAlive = System.currentTimeMillis();
            resetTries(s);
        } else if ("Session limit".equals(state)) {
            // Not a wrong password: after Continue AroFlo wants the login again.
            resetTries(s);
        }
        setState(s, state);
        updateSignInNote();
    }

    /** Lets the saved login be tried again (after the session limit or a command from AirHQ). */
    private void resetTries(Session s) {
        s.passwordFills.clear();
        s.gaveUp = false;
    }

    private void setState(Session s, String state) {
        if (!state.equals(s.state)) {
            s.state = state;
            log(s.name + ": " + state);
        }
        if (s.status == null) return;
        int colour = state.equals("Alive") ? GREEN
                : (state.startsWith("Needs") || state.startsWith("Sign-in failed") || state.startsWith("Approve")
                        || state.startsWith("Session limit")) ? RED
                : AMBER;
        s.dot.setTextColor(colour);
        s.status.setText(statusText(s));
    }

    private String statusText(Session s) {
        if (!"Alive".equals(s.state) || s.lastAlive == 0) return s.state;
        long secs = (System.currentTimeMillis() - s.lastAlive) / 1000;
        return "Alive, checked " + secs + "s ago";
    }

    private void refreshAges() {
        if (outlook.status != null) outlook.status.setText(statusText(outlook));
        if (aroflo.status != null) aroflo.status.setText(statusText(aroflo));
    }

    private void updateSignInNote() {
        String note = "";
        if (outlook.state.startsWith("Approve sign-in: ")) {
            note = "Microsoft sign-in: enter " + outlook.state.substring(17) + " on your phone";
        } else if ("Session limit".equals(aroflo.state)) {
            note = "AroFlo session limit: continuing signs out one of your other devices. Confirm from AirHQ";
        } else if ("Needs code".equals(aroflo.state)) {
            note = "AroFlo wants a code: send it from AirHQ";
        }
        signInNote.setText(note);
        signInNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateRam() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long free = mi.availMem / (1024 * 1024);
        long total = mi.totalMem / (1024 * 1024);
        String txt = "Free RAM: " + free + " MB of " + total + " MB" + (mi.lowMemory ? "  (LOW)" : "");
        ramText.setText(txt);
        ramText.setTextColor(mi.lowMemory ? RED : free < 300 ? AMBER : DIM);
        long now = System.currentTimeMillis();
        if (now - lastRamLog > 5 * 60 * 1000) {
            lastRamLog = now;
            log(txt);
        }
    }

    /** Types a sign-in code (sent from AirHQ) into AroFlo's code screen. */
    boolean enterAroFloCode(String code) {
        log("AroFlo: code received from phone");
        return sendToAroFlo("code", "code", code);
    }

    /** Presses a button on AroFlo's screen by its text (sent from AirHQ). */
    boolean pressOnAroFlo(String label) {
        log("AroFlo: asked to press " + label);
        return sendToAroFlo("press", "label", label);
    }

    private boolean sendToAroFlo(String kind, String key, String value) {
        final Session s = aroflo;
        final WebView w = s.view;
        if (!running || w == null) return false;
        resetTries(s);
        try {
            JSONObject j = new JSONObject();
            j.put("kind", kind);
            j.put(key, value);
            w.evaluateJavascript(probeJs.replace("__CREDS__", j.toString()), new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (running && w == s.view) handleProbe(s, value);
                }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- show / hide (test build)

    private void showSession(Session s) {
        if (s.view == null) return;
        showing = s;
        webHolder.bringChildToFront(s.view);
        cover.setVisibility(View.GONE);
        s.view.requestFocus();
        log(s.name + " shown on screen (Back to hide)");
    }

    private void hideSession() {
        showing = null;
        cover.setVisibility(View.VISIBLE);
        cover.requestFocus();
    }

    // ---------------------------------------------------------------- screen

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        webHolder = new FrameLayout(this);
        root.addView(webHolder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cover = new FrameLayout(this);
        cover.setBackgroundColor(0xFF05060B);
        cover.setClickable(true);
        cover.setClipChildren(false);
        root.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setAlpha(0.85f);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        int pad = Ui.dp(this, 64);
        panel.setPadding(pad, Ui.dp(this, 48), pad, Ui.dp(this, 32));
        cover.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = Ui.text(this, "Filer", 26, Ui.TEXT_PRIMARY, Ui.LIGHT);
        panel.addView(title);
        TextView sub = Ui.text(this, "Test build. Close this screen and everything shuts down.", 13, DIM, Ui.REGULAR);
        LinearLayout.LayoutParams sublp = wrap();
        sublp.topMargin = Ui.dp(this, 6);
        panel.addView(sub, sublp);

        LinearLayout.LayoutParams rowlp = wrap();
        rowlp.topMargin = Ui.dp(this, 24);
        panel.addView(statusRow(outlook), rowlp);
        LinearLayout.LayoutParams row2 = wrap();
        row2.topMargin = Ui.dp(this, 10);
        panel.addView(statusRow(aroflo), row2);

        ramText = Ui.text(this, "Free RAM: ...", 14, DIM, Ui.REGULAR);
        LinearLayout.LayoutParams ramlp = wrap();
        ramlp.topMargin = Ui.dp(this, 14);
        panel.addView(ramText, ramlp);

        signInNote = Ui.text(this, "", 20, AMBER, Ui.MEDIUM);
        signInNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams notelp = wrap();
        notelp.topMargin = Ui.dp(this, 14);
        panel.addView(signInNote, notelp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        buttons.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10));
        View first = addPill(buttons, "Show Outlook", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSession(outlook);
            }
        }, false);
        addPill(buttons, "Show AroFlo", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSession(aroflo);
            }
        }, true);
        addPill(buttons, "Reload both", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartSession(outlook, "reload pressed");
                restartSession(aroflo, "reload pressed");
            }
        }, true);
        addPill(buttons, "Close", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }, true);
        LinearLayout.LayoutParams blp = wrap();
        blp.topMargin = Ui.dp(this, 18);
        blp.leftMargin = -Ui.dp(this, 6);
        panel.addView(buttons, blp);

        TextView logTitle = Ui.text(this, "LOG", 12, DIM, Ui.MEDIUM);
        logTitle.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams ltlp = wrap();
        ltlp.topMargin = Ui.dp(this, 18);
        panel.addView(logTitle, ltlp);

        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lblp = wrap();
        lblp.topMargin = Ui.dp(this, 8);
        panel.addView(logBox, lblp);

        setContentView(root);
        first.requestFocus();
    }

    private View statusRow(Session s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        s.dot = Ui.text(this, "●", 18, AMBER, Ui.REGULAR);
        TextView name = Ui.text(this, s.name, 18, Ui.TEXT_PRIMARY, Ui.MEDIUM);
        s.status = Ui.text(this, s.state, 18, DIM, Ui.REGULAR);
        row.addView(s.dot);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.leftMargin = Ui.dp(this, 10);
        nlp.width = Ui.dp(this, 90);
        row.addView(name, nlp);
        row.addView(s.status, wrap());
        return row;
    }

    private View addPill(LinearLayout parent, String label, View.OnClickListener l, boolean gap) {
        TextView p = Ui.pill(this, label, l);
        p.setTextSize(14);
        LinearLayout.LayoutParams lp = wrap();
        if (gap) lp.leftMargin = Ui.dp(this, 12);
        parent.addView(p, lp);
        return p;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Slowly drifts the whole panel around so nothing sits in one spot for hours. */
    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            float dx = (random.nextFloat() * 2 - 1) * Ui.dp(FilerActivity.this, 36);
            float dy = (random.nextFloat() * 2 - 1) * Ui.dp(FilerActivity.this, 24);
            panel.animate().translationX(dx).translationY(dy).setDuration(20000).start();
            handler.postDelayed(this, 45000);
        }
    };

    // ---------------------------------------------------------------- log

    private void log(String msg) {
        String line = stamp() + "  " + msg;
        lines.addFirst(line);
        while (lines.size() > 200) lines.removeLast();
        writeLogLine(line);
        if (logBox == null) return;
        logBox.removeAllViews();
        int n = 0;
        for (String l : lines) {
            TextView t = Ui.text(this, l, 13, n == 0 ? 0xFFC9CDDA : DIM, Ui.REGULAR);
            LinearLayout.LayoutParams lp = wrap();
            lp.topMargin = Ui.dp(this, 4);
            logBox.addView(t, lp);
            if (++n >= 12) break;
        }
    }

    private String stamp() {
        return clock.format(new Date());
    }

    /** Keeps a small log file on the TV (job numbers and actions only, never logins). */
    private void writeLogLine(String line) {
        try {
            File f = new File(getFilesDir(), "filer.log");
            if (f.length() > 256 * 1024) {
                File old = new File(getFilesDir(), "filer.old.log");
                old.delete();
                f.renameTo(old);
            }
            FileWriter w = new FileWriter(f, true);
            w.write(line + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- helpers

    private String desktopUserAgent() {
        String def = WebSettings.getDefaultUserAgent(this);
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(def);
        String ver = m.find() ? m.group(1) : "120.0.0.0";
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + ver + " Safari/537.36";
    }

    private String readAsset(String name) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(name), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "JSON.stringify({})";
        }
    }
}
