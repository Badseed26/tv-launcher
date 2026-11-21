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
import android.graphics.Bitmap;
import android.os.Message;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
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
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filer screen (test build). While this screen is open, Outlook and AroFlo run hidden
 * behind it and the screen stays on. Close it and both sessions shut down straight away.
 * Only the status, free RAM and a short log are ever shown.
 */
public class FilerActivity extends Activity {

    // Opened with #ec-filer like the PC's filer tabs, so the filer script knows these are its working pages.
    private static final String OUTLOOK_URL = "https://outlook.cloud.microsoft/mail/#ec-filer";
    private static final String AROFLO_URL = "https://office.aroflo.com/ims/Site/Home/index.cfm#ec-filer";
    private static final String SCRIPT_URL =
            "https://raw.githubusercontent.com/Badseed26/ec-userscripts/main/src/ec-inbox-filer.js";
    static final String DEV_TASK = "6j3H69rH7Cj9858X";
    private static final String[] FILER_ORIGINS = {
            "https://outlook.cloud.microsoft", "https://outlook.office.com",
            "https://outlook.office365.com", "https://office.aroflo.com"};
    private static final long TICK_MS = 8000;
    private static final long FILL_WINDOW_MS = 30L * 60 * 1000;
    private static final int MAX_PASSWORD_FILLS = 2;

    private static final int GREEN = 0xFF4ADE80;
    private static final int AMBER = 0xFFFBBF24;
    private static final int RED = 0xFFF87171;
    private static final int DIM = 0xFF8C92A8;

    private static String probeJs;
    private static String shimJs;

    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private GmStore store;
    private String injection;   // shim + filer script, or null if it couldn't be loaded
    private String filerVersion = "";
    private TextView filerTitle;

    /** The open Filer screen, so a code pushed from AirHQ can reach it. */
    static volatile FilerActivity instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private FrameLayout webHolder;
    private View firstButton;
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

    // Auto run (opened by the mail check): closes itself once the filer's done, and puts the TV back to sleep
    // if it was off. A Filer opened by hand never closes itself.
    private boolean auto;
    private boolean autoWasOff;
    private long openedAt;
    private int idleTicks;

    /** One hidden browser session (Outlook or AroFlo). */
    private final class Session {
        final String name;
        final String url;
        WebView view;
        String state = "Starting";
        long lastAlive;
        int noResponse;
        boolean injectLate;
        boolean gaveUp;
        long holdFillUntil;
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
        // Opened remotely while the TV was off: switch the screen on with it.
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        }
        if (probeJs == null) probeJs = readAsset("probe.js");
        if (shimJs == null) shimJs = readAsset("gm-shim.js");

        Peek.cancel();
        auto = getIntent().getBooleanExtra("auto", false);
        autoWasOff = getIntent().getBooleanExtra("wasOff", false);
        openedAt = System.currentTimeMillis();
        outlook = new Session("Outlook", OUTLOOK_URL);
        aroflo = new Session("AroFlo", AROFLO_URL);

        buildUi();
        running = true;
        instance = this;
        log(auto ? "Filer opened by the mail check" + (autoWasOff ? " (TV was off)" : " (TV was on the home screen)")
                : "Filer started");
        if (!Secrets.has(this, Secrets.MS_PASS) || !Secrets.has(this, Secrets.AROFLO_PASS)) {
            log("No logins saved yet, sign in with Show Outlook / Show AroFlo");
        }

        freeUpRam();
        store = GmStore.get(this);
        seedStore();
        clearTabLocks();
        // Get the newest filer script, then open both pages with it.
        net.execute(new Runnable() {
            @Override
            public void run() {
                final String src = loadScript();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!running) return;
                        if (src != null) {
                            filerVersion = versionOf(src);
                            injection = shimJs.replace("__TOKEN__", token).replace("__VER__", filerVersion)
                                    .replace("__SCRIPT__", src);
                            log("Filer script " + filerVersion + " loaded");
                        } else {
                            log("Couldn't get the filer script, running sign-in only");
                        }
                        if (filerTitle != null) filerTitle.setText((auto ? "Filer (auto run)" : "Filer") + "  \u00B7  build " + Updater.currentVersion(FilerActivity.this)
                                + (filerVersion.isEmpty() ? "" : "  \u00B7  script " + filerVersion));
                        startSession(outlook);
                        startSession(aroflo);
                    }
                });
            }
        });
        handler.postDelayed(tick, 3000);
        handler.postDelayed(drift, 30000);
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
        clearTabLocks();
        releaseDevice(getApplicationContext());
        net.shutdown();
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
        // Pages that open a new window get it loaded in the same hidden view instead.
        st.setSupportMultipleWindows(true);
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
        w.setWebChromeClient(new Chrome(s));
        w.addJavascriptInterface(new GmBridge(s), "ecGmBridge");
        if (injection != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            HashSet<String> origins = new HashSet<>();
            for (String o : FILER_ORIGINS) origins.add(o);
            WebViewCompat.addDocumentStartJavaScript(w, injection, origins);
            s.injectLate = false;
        } else {
            s.injectLate = injection != null;
        }
        w.setWebViewClient(new Client(s));
        w.setFocusable(false);

        s.view = w;
        s.noResponse = 0;
        // Sits behind the dark cover, laid out like a PC window (1600 x 1000). At the TV's own width Outlook squashes the
        // reading pane and the filer can't see the attachments (18/11, Strafford Rd schedule PDF).
        webHolder.addView(w, 0, hiddenSize());
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
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (running && view == s.view) log(s.name + " > " + shortUrl(url));
            // Older WebView without document-start scripts: put the filer in as early as we can.
            if (s.injectLate && injection != null && url != null) {
                for (String o : FILER_ORIGINS) {
                    if (url.startsWith(o)) {
                        view.evaluateJavascript(injection, null);
                        break;
                    }
                }
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
            if (!running || view != s.view || resp == null) return;
            String path = req.getUrl().getPath();
            if (path != null && (path.contains("PeopleGraph") || path.contains("/people/"))) return;
            if (req.isForMainFrame() || resp.getStatusCode() >= 400 && isSite(req.getUrl().getHost())) {
                log(s.name + " http " + resp.getStatusCode() + " " + shortUrl(req.getUrl().toString()));
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (!running || view != s.view || err == null) return;
            if (req.isForMainFrame() || isSite(req.getUrl().getHost())) {
                log(s.name + " error " + err.getErrorCode() + " " + err.getDescription() + " " + shortUrl(req.getUrl().toString()));
            }
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

    /** Logs console errors and popups, and loads any new window in the same hidden view. */
    private final class Chrome extends WebChromeClient {
        private final Session s;

        Chrome(Session s) {
            this.s = s;
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            if (running && m != null && m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                String msg = m.message();
                if (msg != null && msg.length() > 140) msg = msg.substring(0, 140) + "...";
                log(s.name + " console: " + msg);
            }
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message resultMsg) {
            if (!running) return false;
            // Catch the new window's first address and load it in the main view instead.
            final WebView temp = new WebView(FilerActivity.this);
            temp.setWebViewClient(new WebViewClient() {
                private boolean done;

                private void take(String url) {
                    if (done || url == null || url.equals("about:blank")) return;
                    done = true;
                    log(s.name + " opened a new window > " + shortUrl(url) + " (loading it here)");
                    if (s.view != null) s.view.loadUrl(url);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                temp.destroy();
                            } catch (Exception ignored) {
                            }
                        }
                    });
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                    take(req.getUrl().toString());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String url, Bitmap favicon) {
                    take(url);
                }
            });
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(temp);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            log(s.name + " alert: " + clip(message));
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            // A hidden page can't show a yes/no box, so log it and say yes.
            log(s.name + " confirm box (said yes): " + clip(message));
            result.confirm();
            return true;
        }
    }

    private static String clip(String m) {
        if (m == null) return "";
        m = m.replace('\n', ' ');
        return m.length() > 140 ? m.substring(0, 140) + "..." : m;
    }

    /** Web address without the query part, so no tokens end up in the log. */
    private static String shortUrl(String url) {
        if (url == null) return "";
        try {
            Uri u = Uri.parse(url);
            String out = u.getHost() + u.getPath();
            return out.length() > 90 ? out.substring(0, 90) + "..." : out;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isSite(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.contains("aroflo") || host.contains("microsoft") || host.contains("office") || host.contains("live.com");
    }

    /** Last lines of the log file, for AirHQ. */
    static String tail(Context c, int n) {
        try {
            File f = new File(c.getFilesDir(), "filer.log");
            BufferedReader r = new BufferedReader(new java.io.FileReader(f));
            ArrayDeque<String> last = new ArrayDeque<>();
            String line;
            while ((line = r.readLine()) != null) {
                last.addLast(line);
                if (last.size() > n) last.removeFirst();
            }
            r.close();
            StringBuilder sb = new StringBuilder();
            for (String l : last) sb.append(l).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "No log yet";
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
            renderLog();
            if (auto) checkAutoDone();
            watchSignIns();
            if (webUntil > 0 && System.currentTimeMillis() > webUntil) {
                webUntil = 0;
                if (showing != null) hideSession(); // the phone stopped looking
            }
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
            if (s == aroflo && System.currentTimeMillis() < s.holdFillUntil) return "null";
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

        JSONObject tap = j.optJSONObject("tap");
        if (tap != null) realTap(s, tap);

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

    /** Sends a real touch tap to the hidden page at the spot the page reported (looks like a finger, not code). */
    private void realTap(final Session s, JSONObject t) {
        final WebView w = s.view;
        if (w == null || w.getWidth() == 0) {
            log(s.name + ": couldn't tap, page not laid out");
            return;
        }
        double vw = t.optDouble("vw", 0), vh = t.optDouble("vh", 0);
        if (vw <= 0 || vh <= 0) return;
        final float x = (float) (t.optDouble("x") * w.getWidth() / vw);
        final float y = (float) (t.optDouble("y") * w.getHeight() / vh);
        // Short pause so the page has scrolled the button into place.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running || w != s.view) return;
                long down = SystemClock.uptimeMillis();
                MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
                d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                w.dispatchTouchEvent(d);
                d.recycle();
                MotionEvent u = MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, x, y, 0);
                u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                w.dispatchTouchEvent(u);
                u.recycle();
                log(s.name + ": tapped it");
            }
        }, 400);
    }

    /** Apps that just sit in RAM after you've used them. Closed when the Filer opens; they reopen normally. */
    private static final String[] LEFTOVERS = {
            "com.spotify.tv.android", "com.google.android.youtube.tv", "com.netflix.ninja",
            "com.disney.disneyplus", "au.com.stan.and", "com.cbs.ca", "au.net.abc.iview",
            "com.apple.atve.androidtv.appletv", "com.android.vending",
            "com.google.android.packageinstaller",
    };

    private void freeUpRam() {
        final ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        final long before = availMb(am);
        for (String pkg : LEFTOVERS) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (Exception ignored) {
            }
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                long freed = availMb(am) - before;
                log("Closed leftover apps" + (freed > 0 ? ", freed about " + freed + " MB" : ""));
            }
        }, 1500);
    }

    private static long availMb(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    private long outlookOutSince, aroOutSince, lastAutoContinue;

    /**
     * 19/11. Outlook signed out for 10 min: a follow up, since nothing gets filed till it's back. AroFlo bumped by the
     * session limit: it presses CONTINUE itself any time (21/11, the TV taking the spot doesn't sign Ethan's PC or phone out).
     */
    private void watchSignIns() {
        long now = System.currentTimeMillis();
        if ("Alive".equals(outlook.state)) {
            outlookOutSince = 0;
            Alerts.clear(this, "outlook-out");
        } else if (outlook.state.startsWith("Sign") || outlook.state.startsWith("Approve") || outlook.state.startsWith("Needs")) {
            if (outlookOutSince == 0) outlookOutSince = now;
            else if (now - outlookOutSince > 10 * 60000)
                Alerts.raise(this, "outlook-out", "TV: Outlook is signed out on the TV",
                        "Outlook on the TV has said '" + outlook.state + "' for 10 min. No builder email gets filed till it's back. "
                                + "If it says Approve sign-in, approve the number in the Authenticator app.");
        }
        if ("Alive".equals(aroflo.state)) {
            aroOutSince = 0;
            Alerts.clear(this, "aroflo-limit");
            Alerts.clear(this, "aroflo-out");
            return;
        }
        if (aroOutSince == 0) aroOutSince = now;
        if ("Session limit".equals(aroflo.state)) {
            // 21/11: the TV taking the spot doesn't sign Ethan's PC or phone out of AroFlo, so it just presses
            // CONTINUE any time it's bumped (at most every 10 min), day or night.
            if (now - lastAutoContinue > 10 * 60000) {
                lastAutoContinue = now;
                log("AroFlo: User Session Limit page, pressing CONTINUE");
                pressOnAroFlo("CONTINUE");
            }
        } else if (aroflo.state.startsWith("Sign-in failed") && now - aroOutSince > 10 * 60000) {
            Alerts.raise(this, "aroflo-out", "TV: AroFlo sign-in failed on the TV",
                    "The TV tried the saved AroFlo login twice and stopped so the account doesn't lock. Check the password, "
                            + "or press Show on AroFlo on the TV to see what it says.");
        }
    }

    /** AroFlo jobs the filer has queued but not done. Reading mail doesn't need AroFlo. */
    private boolean aroWorkWaiting() {
        if (store == null) return false;
        try {
            String jobs = store.getJson("wfJobs");
            if (jobs == null) return false;
            JSONArray a = new JSONArray(jobs);
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j != null && "new".equals(j.optString("status"))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Weeknights 6pm to 6am and all weekend. */
    static boolean quietHours() {
        java.util.Calendar k = java.util.Calendar.getInstance();
        int d = k.get(java.util.Calendar.DAY_OF_WEEK), h = k.get(java.util.Calendar.HOUR_OF_DAY);
        return d == java.util.Calendar.SATURDAY || d == java.util.Calendar.SUNDAY || h < 6 || h >= 18;
    }

    /** Auto run: close once the filer's had time to look and has nothing going, or give up after 12 min. */
    /** Follow ups queued for AroFlo (notes, site info), checked once a minute on auto runs. Not idle while there are any. */
    private volatile int queuedWork;
    private long queueCheckedAt;

    private void checkAutoDone() {
        long ran = System.currentTimeMillis() - openedAt;
        if (System.currentTimeMillis() - queueCheckedAt > 60000) {
            queueCheckedAt = System.currentTimeMillis();
            try {
                net.execute(new Runnable() {
                    @Override
                    public void run() {
                        queuedWork = ListenService.queued(getApplicationContext());
                    }
                });
            } catch (Exception ignored) {
            }
        }
        try {
            String dev = store == null ? null : store.getJson("wfDev");
            if (dev != null && new JSONObject(dev).optBoolean("ok", true) == false && ran > 60000) {
                autoClose("the filer's running on another device");
                return;
            }
        } catch (Exception ignored) {
        }
        if (ran > 12 * 60000) {
            autoClose("ran 12 min, closing anyway");
            return;
        }
        if (ran > 5 * 60000 && !("Alive".equals(outlook.state) && "Alive".equals(aroflo.state))) {
            autoClose("couldn't get both signed in (Outlook: " + outlook.state + ", AroFlo: " + aroflo.state + ")");
            return;
        }
        if (ran < 120000) return;
        if (filerIdle()) {
            // about a minute of nothing going, so follow-on work (booking plans) lands before it shuts (19/11, Banyula Dr)
            if (++idleTicks >= 8) autoClose("done, nothing left to file");
        } else {
            idleTicks = 0;
        }
    }

    /** Both pages up, nothing queued or mid way, and both filer tabs saying they're idle. */
    private boolean filerIdle() {
        if (!"Alive".equals(outlook.state) || !"Alive".equals(aroflo.state) || store == null) return false;
        if (queuedWork > 0) return false;
        try {
            String p = store.getJson("wfPending");
            if (p != null && !p.equals("null")) return false;
            String rr = store.getJson("wfReread");
            if (rr != null && new JSONArray(rr).length() > 0) return false;
            String jobs = store.getJson("wfJobs");
            if (jobs != null) {
                JSONArray a = new JSONArray(jobs);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject j = a.optJSONObject(i);
                    if (j != null && "new".equals(j.optString("status"))) return false;
                }
            }
            String mail = filerChip("mail").toLowerCase(Locale.ROOT), af = filerChip("af").toLowerCase(Locale.ROOT);
            return mail.startsWith("watching") && af.startsWith("idle");
        } catch (Exception e) {
            return false;
        }
    }

    private void autoClose(String why) {
        if (!running) return;
        log("Auto run: " + why);
        final boolean sleep = autoWasOff;
        finish();
        if (sleep) HomeButtonService.sleepTvSoon(getApplicationContext());
    }

    boolean isAuto() {
        return auto;
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
        String base;
        if (!"Alive".equals(s.state) || s.lastAlive == 0) {
            base = s.state;
        } else {
            long secs = (System.currentTimeMillis() - s.lastAlive) / 1000;
            base = "Alive, checked " + secs + "s ago";
        }
        String chip = filerChip(s == outlook ? "mail" : "af");
        return chip.isEmpty() ? base : base + "   |   filer: " + chip;
    }

    /** What the filer script's own status chip says for that page. */
    private String filerChip(String k) {
        if (store == null) return "";
        try {
            String j = store.getJson("wfState_" + k);
            if (j == null) return "";
            JSONObject o = new JSONObject(j);
            return o.optString("msg", "");
        } catch (Exception e) {
            return "";
        }
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
            note = "AroFlo session limit: the TV presses Continue itself";
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
    boolean pressOnAroFlo(final String label) {
        log("AroFlo: asked to press " + label);
        if (!running || aroflo == null || aroflo.view == null) return false;
        if (showing == aroflo) return sendToAroFlo("press", "label", label);
        // 19/11: a tap on the hidden page never landed (nothing draws it, so the page can't tell where the tap is).
        // Bring AroFlo on screen, let it lay out at TV size, press, then put it away again.
        showSession(aroflo);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                sendToAroFlo("press", "label", label);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (running && showing == aroflo) hideSession();
                    }
                }, 6000);
            }
        }, 1500);
        return true;
    }

    private boolean sendToAroFlo(String kind, String key, String value) {
        final Session s = aroflo;
        final WebView w = s.view;
        if (!running || w == null) return false;
        resetTries(s);
        // Give AroFlo time to finish what was pressed before the login gets touched again.
        s.holdFillUntil = System.currentTimeMillis() + 25000;
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

    private FrameLayout.LayoutParams hiddenSize() {
        float d = getResources().getDisplayMetrics().density;
        return new FrameLayout.LayoutParams(Math.round(1600 * d), Math.round(1000 * d));
    }

    private void showSession(Session s) {
        if (s.view == null) return;
        showing = s;
        // Fit it to the TV while it's on screen; it goes back to PC size when hidden.
        s.view.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webHolder.bringChildToFront(s.view);
        webHolder.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        s.view.setFocusable(true);
        s.view.setFocusableInTouchMode(true);
        cover.setVisibility(View.GONE);
        s.view.requestFocus();
        log(s.name + " shown on screen (Back to hide)");
    }

    private void hideSession() {
        Session was = showing;
        showing = null;
        if (was != null && was.view != null) {
            was.view.setLayoutParams(hiddenSize());
            was.view.clearFocus();
            was.view.setFocusable(false);
            was.view.setFocusableInTouchMode(false);
        }
        webHolder.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        cover.setVisibility(View.VISIBLE);
        // Put the remote back on the Filer's buttons (again a moment later, after the page lets go).
        refocusButtons();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (showing == null) refocusButtons();
            }
        }, 150);
    }

    private void refocusButtons() {
        if (firstButton == null) return;
        if (!firstButton.requestFocus()) firstButton.requestFocusFromTouch();
    }

    /** While no page is showing, the remote can only ever drive the Filer's own buttons. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (showing == null && e.getKeyCode() != KeyEvent.KEYCODE_BACK
                && e.getKeyCode() != KeyEvent.KEYCODE_HOME) {
            View f = getCurrentFocus();
            if (f == null || f instanceof WebView || !isInside(f, cover)) {
                refocusButtons();
                if (e.getAction() == KeyEvent.ACTION_DOWN
                        && (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isInside(View v, View parent) {
        android.view.ViewParent p = v.getParent();
        while (p != null) {
            if (p == parent) return true;
            p = p.getParent();
        }
        return v == parent;
    }

    // ---------------------------------------------------------------- screen

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        webHolder = new FrameLayout(this);
        // Hidden pages can't take the remote's focus unless one is being shown.
        webHolder.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
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

        TextView title = Ui.text(this, (getIntent().getBooleanExtra("auto", false) ? "Filer (auto run)" : "Filer")
                + "  \u00B7  build " + Updater.currentVersion(this), 26, Ui.TEXT_PRIMARY, Ui.LIGHT);
        filerTitle = title;
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
        logBox.setMinimumWidth(Ui.dp(this, 700));
        logBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lblp.topMargin = Ui.dp(this, 8);
        panel.addView(logBox, lblp);

        setContentView(root);
        firstButton = first;
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
        p.setFocusableInTouchMode(true);
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
        renderLog();
    }

    /** The filer's own log (same lines the dashboard shows), then the TV's last few lines. */
    private void renderLog() {
        if (logBox == null) return;
        logBox.removeAllViews();
        List<String> filer = filerLog(10);
        int n = 0;
        for (String l : filer) {
            addLogLine(l, n++ == 0 ? 0xFFC9CDDA : DIM);
        }
        if (!filer.isEmpty()) {
            TextView tvHead = Ui.text(this, "TV", 12, DIM, Ui.MEDIUM);
            tvHead.setLetterSpacing(0.15f);
            LinearLayout.LayoutParams hl = wrap();
            hl.topMargin = Ui.dp(this, 12);
            logBox.addView(tvHead, hl);
        }
        int m = 0;
        for (String l : lines) {
            addLogLine(l, filer.isEmpty() && m == 0 ? 0xFFC9CDDA : DIM);
            if (++m >= (filer.isEmpty() ? 12 : 4)) break;
        }
    }

    private void addLogLine(String text, int colour) {
        TextView t = Ui.text(this, text, 13, colour, Ui.REGULAR);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 4);
        logBox.addView(t, lp);
    }

    /** Newest first, date dropped (the filer writes "18/11/2026 8:08:36 pm [mail] ..."). */
    List<String> filerLog(int max) {
        List<String> out = new ArrayList<>();
        if (store == null) return out;
        try {
            String j = store.getJson("wfLog");
            if (j == null) return out;
            JSONArray a = new JSONArray(j);
            for (int i = a.length() - 1; i >= 0 && out.size() < max; i--) {
                out.add(a.optString(i).replaceFirst("^\\d{1,2}/\\d{1,2}/\\d{4}\\s+", ""));
            }
        } catch (Exception ignored) {
        }
        return out;
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

    // ---------------------------------------------------------------- filer script

    /** Tampermonkey's GM_* calls, for one page. Every call needs the token only the injected script knows. */
    private final class GmBridge {
        private final Session s;

        GmBridge(Session s) {
            this.s = s;
        }

        private boolean ok(String t) {
            return running && token.equals(t);
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
            try {
                net.execute(new Runnable() {
                    @Override
                    public void run() {
                        final String res = Http.run(req);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                WebView w = s.view;
                                if (running && w != null) {
                                    w.evaluateJavascript("window.__ecGmDone&&window.__ecGmDone("
                                            + JSONObject.quote(id) + "," + res + ")", null);
                                }
                            }
                        });
                    }
                });
            } catch (Exception ignored) {
            }
        }

        /** The filer reopens a quiet tab with GM_openInTab: here that restarts the matching page. */
        @JavascriptInterface
        public void openTab(String t, final String url) {
            if (!ok(t)) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!running) return;
                    restartSession(url.contains("aroflo") ? aroflo : outlook, "filer asked to reopen it");
                }
            });
        }
    }

    /**
     * The filer script's "this tab is working" locks last 3 minutes, so after the Filer's closed and reopened the new pages
     * would sit waiting on the old ones. The TV only ever runs one Filer, so anything left over is stale: clear it.
     */
    private void clearTabLocks() {
        if (store == null) return;
        store.delete("wfAfLock");
        store.delete("wfMailLock");
        try {
            String j = store.getJson("wfJobs");
            if (j != null) {
                JSONArray a = new JSONArray(j);
                boolean changed = false;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && o.has("run")) {
                        o.remove("run");
                        changed = true;
                    }
                }
                if (changed) store.setJson("wfJobs", a.toString());
            }
        } catch (Exception ignored) {
        }
    }

    /** Puts the Claude key and Todoist token where the filer script looks for them, and marks this device as the TV. */
    private void seedStore() {
        String key = Secrets.get(this, Secrets.CLAUDE_KEY);
        String td = Secrets.get(this, Secrets.TODOIST_TOKEN);
        if (key != null) store.setJson("ecAnthropicKey", JSONObject.quote(key));
        if (td != null) store.setJson("todoistToken", JSONObject.quote(td));
        store.setJson("ecDeviceName", JSONObject.quote("TV"));
    }

    /** Newest script from GitHub (kept on the TV), or the last good copy if GitHub can't be reached. */
    private String loadScript() {
        File cache = new File(getFilesDir(), "ec-inbox-filer.js");
        try {
            String src = Http.get(SCRIPT_URL + "?t=" + System.currentTimeMillis(), 20000);
            if (src.contains("==UserScript==")) {
                FileWriter w = new FileWriter(cache, false);
                w.write(src);
                w.close();
                return src;
            }
        } catch (Exception ignored) {
        }
        try {
            if (cache.exists()) {
                BufferedReader r = new BufferedReader(new java.io.FileReader(cache));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                r.close();
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String versionOf(String src) {
        Matcher m = Pattern.compile("@version\\s+(\\S+)").matcher(src);
        return m.find() ? m.group(1) : "?";
    }

    /** Frees the "* Filer device" task straight away if the TV had it, so the PC can take over without waiting. */
    static void releaseDevice(final Context c) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String td = Secrets.get(c, Secrets.TODOIST_TOKEN);
                    String mine = GmStore.get(c).getJson("ecDeviceId");
                    if (td == null || mine == null) return;
                    mine = new JSONTokener(mine).nextValue().toString();
                    JSONObject t = new JSONObject(Http.todoist(td, "GET", "tasks/" + DEV_TASK, null));
                    String d = t.optString("description", "");
                    if (!d.contains("id: " + mine + "\n")) return;
                    JSONObject b = new JSONObject();
                    b.put("content", "* Filer device");
                    b.put("description", "Free. The TV closed its Filer at " + new SimpleDateFormat("h:mm a", Locale.getDefault())
                            .format(new Date()) + ". Don't edit.");
                    Http.todoist(td, "POST", "tasks/" + DEV_TASK, b);
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- remote (TV remote task)

    String remoteStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Filer open, build ").append(Updater.currentVersion(this));
        if (!filerVersion.isEmpty()) sb.append(", script ").append(filerVersion);
        sb.append("\nOutlook: ").append(statusText(outlook));
        sb.append("\nAroFlo: ").append(statusText(aroflo));
        sb.append("\n").append(ramText.getText());
        return sb.toString();
    }

    void remoteReload() {
        clearTabLocks(); // the old pages' "working" locks would hold the new ones up for a minute or two
        restartSession(outlook, "reload from the TV remote task");
        restartSession(aroflo, "reload from the TV remote task");
    }

    // ---------------------------------------------------------------- the phone remote page (RemoteServer), main thread

    interface Shot {
        void done(byte[] jpg);
    }

    private long webUntil;

    private Session webSession(String which) {
        return "aroflo".equals(which) ? aroflo : outlook;
    }

    /** Puts that page on the TV for the phone. It goes back behind the Filer screen 2 min after the phone stops looking. */
    boolean webShow(String which) {
        Session s = webSession(which);
        if (!running || s == null || s.view == null) return false;
        webUntil = System.currentTimeMillis() + 120000;
        if (showing != s) {
            showSession(s);
            log(s.name + ": opened from the phone remote");
        }
        return true;
    }

    /** The phone's still looking: keep its page up another 2 min. */
    void webKeepAlive() {
        if (showing != null) webUntil = System.currentTimeMillis() + 120000;
    }

    String webWhich() {
        return showing == aroflo ? "aroflo" : showing == outlook ? "outlook" : "";
    }

    String webState(String which) {
        Session s = webSession(which);
        return s == null ? "" : s.state;
    }

    void webHide() {
        webUntil = 0;
        if (showing != null) hideSession();
    }

    /** What's on the TV right now (the shown page), as a JPEG about 1000 wide. */
    void webShot(final Shot cb) {
        final View root = getWindow().getDecorView();
        final int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) {
            cb.done(null);
            return;
        }
        if (Build.VERSION.SDK_INT < 26) {
            cb.done(drawShot());
            return;
        }
        final Bitmap full;
        try {
            full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            cb.done(null);
            return;
        }
        try {
            android.view.PixelCopy.request(getWindow(), full, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    if (result == android.view.PixelCopy.SUCCESS) {
                        cb.done(jpeg(full, w));
                    } else {
                        full.recycle();
                        if (!shotFailLogged) {
                            shotFailLogged = true;
                            log("Phone remote: screen copy said " + result + ", drawing the page instead");
                        }
                        cb.done(drawShot());
                    }
                }
            }, handler);
        } catch (Throwable e) {
            full.recycle();
            log("Phone remote: screen copy failed (" + e.getMessage() + "), drawing the page instead");
            cb.done(drawShot());
        }
    }

    private boolean shotFailLogged;

    /** Second way: draw the showing page itself into a picture. */
    private byte[] drawShot() {
        Session s = showing;
        if (s == null || s.view == null || s.view.getWidth() == 0) return null;
        try {
            int vw = s.view.getWidth(), vh = s.view.getHeight();
            float k = Math.min(1f, 1000f / vw);
            Bitmap b = Bitmap.createBitmap(Math.round(vw * k), Math.round(vh * k), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            cv.scale(k, k);
            cv.translate(-s.view.getScrollX(), -s.view.getScrollY());
            s.view.draw(cv);
            return jpeg(b, b.getWidth());
        } catch (Throwable e) {
            log("Phone remote: couldn't draw the page (" + e.getMessage() + ")");
            return null;
        }
    }

    private static byte[] jpeg(Bitmap full, int w) {
        Bitmap small = full;
        if (w > 1000) {
            int th = Math.round(full.getHeight() * (1000f / w));
            small = Bitmap.createScaledBitmap(full, 1000, th, true);
            full.recycle();
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 70, out);
        small.recycle();
        return out.toByteArray();
    }

    /** A real tap at fx, fy (0 to 1 across the screen) on the page that's showing. */
    boolean webTap(float fx, float fy) {
        final Session s = showing;
        if (s == null || s.view == null || s.view.getWidth() == 0) return false;
        webUntil = System.currentTimeMillis() + 120000;
        int[] at = new int[2];
        s.view.getLocationInWindow(at);
        View root = getWindow().getDecorView();
        float x = fx * root.getWidth() - at[0], y = fy * root.getHeight() - at[1];
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        s.view.dispatchTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0);
        u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        s.view.dispatchTouchEvent(u);
        u.recycle();
        return true;
    }

    /** Types into whatever box has focus on the showing page, as key presses. */
    boolean webType(String text) {
        Session s = showing;
        if (s == null || s.view == null || text == null) return false;
        webUntil = System.currentTimeMillis() + 120000;
        KeyEvent[] evs = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (evs == null) {
            // something the key map can't do (emoji and the like): put it in with the page's own insert
            s.view.evaluateJavascript("document.execCommand('insertText',false," + JSONObject.quote(text) + ")", null);
            return true;
        }
        for (KeyEvent e : evs) s.view.dispatchKeyEvent(e);
        return true;
    }

    boolean webKey(String k) {
        Session s = showing;
        if (s == null || s.view == null) return false;
        webUntil = System.currentTimeMillis() + 120000;
        if ("back".equals(k)) {
            if (s.view.canGoBack()) s.view.goBack();
            return true;
        }
        if ("reload".equals(k)) {
            s.view.reload();
            return true;
        }
        int code;
        switch (k) {
            case "enter": code = KeyEvent.KEYCODE_ENTER; break;
            case "tab": code = KeyEvent.KEYCODE_TAB; break;
            case "backspace": code = KeyEvent.KEYCODE_DEL; break;
            case "escape": code = KeyEvent.KEYCODE_ESCAPE; break;
            case "up": code = KeyEvent.KEYCODE_DPAD_UP; break;
            case "down": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "left": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "right": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            default: return false;
        }
        s.view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        s.view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        return true;
    }

    /** Scrolls whatever's under the middle of the page (Outlook's lists scroll inside boxes, not the page). */
    boolean webScroll(int dy) {
        Session s = showing;
        if (s == null || s.view == null) return false;
        webUntil = System.currentTimeMillis() + 120000;
        s.view.evaluateJavascript("(function(){var e=document.elementFromPoint(innerWidth/2,innerHeight/2);"
                + "while(e&&!(e.scrollHeight>e.clientHeight+5&&/auto|scroll/.test(getComputedStyle(e).overflowY)))e=e.parentElement;"
                + "(e||document.scrollingElement).scrollBy(0," + dy + ");})()", null);
        return true;
    }

    void remoteLog(String msg) {
        log(msg);
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
