package com.ethan.tvlauncher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Screen-off mail check. Asks Outlook for the newest emails without opening the Filer and says whether any of them are
 * ones the filer would take. First choice is straight from code with the sign-in Outlook last used (no page at all).
 * When that sign-in's run out, an invisible Outlook page loads just long enough to catch the mail list, then goes.
 * Nothing in the mailbox gets changed: the page's writes (read status included) are blocked.
 */
final class Peek {

    interface Done {
        void done(Result r);
    }

    static final class Result {
        boolean ok;
        String how = "";
        String error = "";
        int count;
        int relevant;
        String newest = "";
        String firstNew = "";
        long newestRelevant;
        String actions = "";
        String blocked = "";
        String diag = "";
        long ms;

        String summary() {
            String head = "Mail check (" + how + ", " + Math.round(ms / 100.0) / 10.0 + " s): ";
            if (!ok) return head + error + (blocked.isEmpty() ? "" : " Blocked: " + blocked + ".")
                    + (diag.isEmpty() ? "" : " [read: " + diag + "]") + (actions.isEmpty() ? "" : " [seen: " + actions + "]");
            return head + count + " emails, newest " + newest + ". "
                    + (relevant > 0 ? relevant + " new for the filer: " + firstNew : "Nothing new for the filer.")
                    + (blocked.isEmpty() ? "" : " Blocked: " + blocked);
        }
    }

    private static final String URL = "https://outlook.cloud.microsoft/mail/";
    private static final String[] ORIGINS = {
            "https://outlook.cloud.microsoft", "https://outlook.office.com", "https://outlook.office365.com"};
    private static final long PAGE_TIMEOUT_MS = 90000;
    private static final Pattern SKIP_SUBJ = Pattern.compile(
            "Acceptance Required - Purchase Order|Safety Advice Request Form - Submission|Remittance Advice|Tax Invoice|RCTI",
            Pattern.CASE_INSENSITIVE);

    static volatile boolean busy;
    static volatile String last = "";
    // The request Outlook used for its mail list, sign-in included. Memory only, never saved to storage.
    private static JSONObject replay;
    private static long replayAt;
    private static String js;
    private static Peek current;

    private final Context c;
    private final Done cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long t0 = System.currentTimeMillis();
    private final String token = UUID.randomUUID().toString();
    private final List<String> actions = new ArrayList<>();
    private final List<String> blocked = new ArrayList<>();
    private final List<String> diags = new ArrayList<>();
    private WebView view;
    private WindowManager wm;
    private boolean done;
    private String replayNote = "";

    private Peek(Context c, Done cb) {
        this.c = c.getApplicationContext();
        this.cb = cb;
    }

    /** Main thread. */
    static void run(Context c, Done cb) {
        if (busy) {
            Result r = new Result();
            r.error = "already checking";
            cb.done(r);
            return;
        }
        busy = true;
        current = new Peek(c, cb);
        current.start();
    }

    /** The Filer's opening: drop any check that's still going. */
    static void cancel() {
        Peek p = current;
        if (p != null) p.finish(null, "", "stopped, the Filer opened");
    }

    private void start() {
        final JSONObject rq = replay;
        if (rq == null) {
            startPage();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONArray items = replayNow(rq);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (done) return;
                        if (items != null) finish(items, "code only, sign-in from " + time(replayAt), null);
                        else startPage();
                    }
                });
            }
        }, "peek").start();
    }

    /** Asks Outlook for the list again with the same request it used. Null if the sign-in's no good any more. */
    private JSONArray replayNow(JSONObject rq) {
        try {
            JSONObject r = new JSONObject();
            String url = rq.getString("url");
            r.put("url", url);
            r.put("method", rq.optString("method", "GET"));
            JSONObject h = new JSONObject();
            JSONObject from = rq.optJSONObject("headers");
            if (from != null) {
                Iterator<String> it = from.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String lk = k.toLowerCase(Locale.ROOT);
                    if (lk.equals("content-length") || lk.equals("host") || lk.equals("accept-encoding") || lk.equals("cookie")) continue;
                    h.put(k, from.optString(k));
                }
            }
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) h.put("Cookie", ck);
            if (!h.has("user-agent") && !h.has("User-Agent")) h.put("User-Agent", userAgent(c));
            r.put("headers", h);
            if (!rq.isNull("body") && rq.has("body")) r.put("body", rq.getString("body"));
            r.put("timeout", 30000);
            JSONObject res = new JSONObject(Http.run(r.toString()));
            if (res.optBoolean("error")) {
                replayNote = "code only failed (" + res.optString("message", "no connection") + ")";
                return null;
            }
            int status = res.optInt("status");
            if (status != 200) {
                replayNote = "code only failed (Outlook said " + status + ")";
                replay = null;
                return null;
            }
            String text = new String(Base64.decode(res.optString("b64", ""), Base64.DEFAULT), StandardCharsets.UTF_8);
            JSONArray items = extract(text);
            if (items.length() == 0) {
                replayNote = "code only came back with no emails";
                replay = null;
                return null;
            }
            return items;
        } catch (Exception e) {
            replayNote = "code only failed (" + e.getMessage() + ")";
            return null;
        }
    }

    private void startPage() {
        try {
            if (js == null) js = readAsset(c, "peek.js");
            HomeButtonService h = HomeButtonService.instance;
            WebView w = new WebView(h != null ? h : c);
            WebSettings st = w.getSettings();
            st.setJavaScriptEnabled(true);
            st.setDomStorageEnabled(true);
            st.setDatabaseEnabled(true);
            st.setUseWideViewPort(true);
            st.setLoadWithOverviewMode(true);
            st.setMediaPlaybackRequiresUserGesture(true);
            st.setUserAgentString(userAgent(c));
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(w, true);
            if (Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            w.addJavascriptInterface(new Bridge(), "ecPeekBridge");
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                finish(null, "invisible page", "this TV's WebView can't run the check");
                w.destroy();
                return;
            }
            HashSet<String> o = new HashSet<>();
            for (String s : ORIGINS) o.add(s);
            WebViewCompat.addDocumentStartJavaScript(w, js.replace("@@PEEKTOKEN@@", token), o);
            w.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                    if (url != null && url.contains("login.")) finish(null, "invisible page", "Outlook's signed out, open the Filer to sign it back in");
                }

                @Override
                public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                    view = null;
                    finish(null, "invisible page", "the page crashed (low on memory?)");
                    return true;
                }
            });
            w.setFocusable(false);
            view = w;
            // Not drawn anywhere you can see: a see-through, untouchable window from the helper service, sized like a PC.
            if (h != null) {
                wm = (WindowManager) h.getSystemService(Context.WINDOW_SERVICE);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1600, 1000,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.alpha = 0f;
                lp.gravity = Gravity.TOP | Gravity.START;
                wm.addView(w, lp);
            } else {
                w.layout(0, 0, 1600, 1000);
            }
            w.loadUrl(URL);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish(null, "invisible page", "no mail list after " + PAGE_TIMEOUT_MS / 1000 + " s");
                }
            }, PAGE_TIMEOUT_MS);
        } catch (Exception e) {
            finish(null, "invisible page", "couldn't start the page: " + e.getMessage());
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void items(String t, final String json) {
            if (!token.equals(t)) return;
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject o = new JSONObject(json);
                        JSONObject rq = o.optJSONObject("req");
                        if (rq != null && rq.has("url")) {
                            replay = rq;
                            replayAt = System.currentTimeMillis();
                        }
                        finish(o.optJSONArray("items"), "invisible page" + (o.optString("action").isEmpty() ? "" : ", " + o.optString("action"))
                                + (rq != null && hasAuth(rq) ? ", sign-in kept" : ", no sign-in to keep"), null);
                    } catch (Exception e) {
                        finish(null, "invisible page", "couldn't read the list: " + e.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void seen(String t, final String a) {
            if (!token.equals(t)) return;
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (actions.size() < 25) actions.add(a);
                }
            });
        }

        @JavascriptInterface
        public void diag(String t, final String a) {
            if (!token.equals(t)) return;
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (diags.size() < 20) diags.add(a);
                }
            });
        }

        @JavascriptInterface
        public void blocked(String t, final String a) {
            if (!token.equals(t)) return;
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!blocked.contains(a)) blocked.add(a);
                }
            });
        }
    }

    private static boolean hasAuth(JSONObject rq) {
        JSONObject h = rq.optJSONObject("headers");
        if (h == null) return false;
        Iterator<String> it = h.keys();
        while (it.hasNext()) if (it.next().equalsIgnoreCase("authorization")) return true;
        return false;
    }

    private void finish(JSONArray items, String how, String error) {
        if (done) return;
        done = true;
        main.removeCallbacksAndMessages(null);
        WebView w = view;
        view = null;
        if (w != null) {
            try {
                if (wm != null) wm.removeViewImmediate(w);
            } catch (Exception ignored) {
            }
            try {
                w.stopLoading();
                w.destroy();
            } catch (Exception ignored) {
            }
        }
        busy = false;
        if (current == this) current = null;
        Result r = new Result();
        r.ms = System.currentTimeMillis() - t0;
        r.how = (replayNote.isEmpty() ? "" : replayNote + ", then ") + how;
        r.actions = join(actions);
        r.blocked = join(blocked);
        r.diag = join(diags);
        if (items == null) {
            r.error = error == null ? "nothing" : error;
        } else {
            r.ok = true;
            analyse(c, items, r);
        }
        last = time(System.currentTimeMillis()) + " " + r.summary();
        cb.done(r);
    }

    /** Which of these the filer would take: newer than the newest it's seen, from a sender on its list, not a skip. */
    static void analyse(Context c, JSONArray items, Result r) {
        r.count = items.length();
        GmStore st = GmStore.get(c);
        long newest = num(st.getJson("wfNewest")), startAt = num(st.getJson("wfStartAt"));
        JSONObject R = null;
        try {
            String rs = st.getJson("wfRules");
            if (rs != null) R = new JSONObject(rs);
        } catch (Exception ignored) {
        }
        JSONArray senders = R == null ? null : R.optJSONArray("senders");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            long t = it.optLong("t");
            if (i == 0) r.newest = time(t) + " from " + cut(it.optString("from"), 30) + ": " + cut(it.optString("subj"), 50);
            if (newest > 0 && t <= newest) continue;
            if (startAt > 0 && t < startAt - 60000) continue;
            String subj = it.optString("subj");
            if (SKIP_SUBJ.matcher(subj).find()) continue;
            String head = (it.optString("from") + " " + subj + " " + it.optString("prev")).toLowerCase(Locale.ROOT);
            if (senders == null || !matches(senders, head)) continue;
            r.relevant++;
            if (t > r.newestRelevant) r.newestRelevant = t;
            if (r.firstNew.isEmpty()) r.firstNew = cut(subj, 60) + " (" + time(t) + ")";
        }
        if (senders == null) r.firstNew = "";
        if (senders == null && r.relevant == 0) r.newest += " (no filer rules on the TV yet)";
    }

    private static boolean matches(JSONArray senders, String head) {
        for (int i = 0; i < senders.length(); i++) {
            JSONObject s = senders.optJSONObject(i);
            if (s == null) continue;
            String k = s.optString("k");
            if (!k.isEmpty() && head.contains(k)) return true;
            String b = s.optString("builder").toLowerCase(Locale.ROOT).trim();
            if (!b.isEmpty() && head.contains(b.split(" ")[0])) return true;
            JSONArray w = s.optJSONArray("words");
            if (w != null) for (int j = 0; j < w.length(); j++) {
                String x = w.optString(j);
                if (x.length() > 2 && head.contains(x)) return true;
            }
        }
        return false;
    }

    // ---- the same email-finding as peek.js, for the code-only replies ----

    static JSONArray extract(String text) {
        List<JSONObject> out = new ArrayList<>();
        HashSet<String> keys = new HashSet<>();
        try {
            Object j = new org.json.JSONTokener(text).nextValue();
            walk(j, 0, out, keys, new int[]{0});
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(out, new java.util.Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("t"), a.optLong("t"));
            }
        });
        JSONArray a = new JSONArray();
        for (int i = 0; i < out.size() && i < 40; i++) a.put(out.get(i));
        return a;
    }

    private static void walk(Object o, int d, List<JSONObject> out, HashSet<String> keys, int[] n) throws Exception {
        if (o == null || d > 30 || n[0] > 400000) return;
        n[0]++;
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), d + 1, out, keys, n);
            return;
        }
        if (!(o instanceof JSONObject)) return;
        JSONObject x = (JSONObject) o;
        String subj = x.optString("ConversationTopic", x.optString("Subject", ""));
        String when = first(x, "LastDeliveryTime", "DateTimeReceived", "ReceivedOrRenewTime", "LastDeliveryOrRenewTime");
        long t = when.isEmpty() ? -1 : parseTime(when);
        if (!subj.isEmpty() && t > 0) {
            String k = subj + "|" + t;
            if (keys.add(k)) {
                JSONObject it = new JSONObject();
                it.put("t", t);
                it.put("from", sender(x));
                it.put("subj", cut(subj, 200));
                it.put("prev", cut(x.optString("Preview", ""), 200));
                it.put("unread", x.optInt("UnreadCount", 0) > 0 || (x.has("IsRead") && !x.optBoolean("IsRead", true)));
                out.add(it);
            }
            return;
        }
        Iterator<String> it = x.keys();
        while (it.hasNext()) walk(x.opt(it.next()), d + 1, out, keys, n);
    }

    private static String sender(JSONObject o) {
        for (String f : new String[]{"From", "Sender", "LastSender"}) {
            JSONObject m = o.optJSONObject(f);
            if (m == null) continue;
            JSONObject mb = m.optJSONObject("Mailbox");
            if (mb == null) mb = m;
            String s = (mb.optString("Name", "") + " " + mb.optString("EmailAddress", "")).trim();
            if (!s.isEmpty()) return s;
        }
        JSONArray u = o.optJSONArray("UniqueSenders");
        if (u != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < u.length(); i++) sb.append(i > 0 ? ", " : "").append(u.optString(i));
            return sb.toString();
        }
        return "";
    }

    private static String first(JSONObject o, String... ks) {
        for (String k : ks) {
            Object v = o.opt(k);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        }
        return "";
    }

    private static long parseTime(String s) {
        String[] fmts = {"yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"};
        for (String f : fmts) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(f, Locale.US);
                if (f.endsWith("'Z'")) df.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                Date d = df.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        // Trim fractions longer than milliseconds (Outlook sends 7 digits sometimes) and try again.
        Matcher m = Pattern.compile("^(.*T\\d\\d:\\d\\d:\\d\\d)\\.\\d+(.*)$").matcher(s);
        if (m.find()) return parseTime(m.group(1) + m.group(2));
        return -1;
    }

    // ---- bits ----

    private static long num(String json) {
        try {
            return json == null ? 0 : (long) Double.parseDouble(json.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static String time(long t) {
        return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(t)).toLowerCase(Locale.ROOT);
    }

    private static String cut(String s, int n) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? ", " : "").append(s);
        return sb.toString();
    }

    static String userAgent(Context c) {
        String def = WebSettings.getDefaultUserAgent(c);
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(def);
        String ver = m.find() ? m.group(1) : "120.0.0.0";
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + ver + " Safari/537.36";
    }

    private static String readAsset(Context c, String name) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(name), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }
}
