package com.ethan.tvlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Movie finder (22/11, Ethan's ask). Claude asks from a chat (tv_job or the Todoist remote):
 *   movie.find {q, year, type}         where it's streaming in Australia (JustWatch), which of those are on the TV and logged in
 *   movie.open {q, year, type, app, profile}  the same, then opens the best app on the movie's page, ready for Ethan to press Play
 *   movie.apps {set:[...], rent:[...], profile} the streaming apps Ethan's logged into (kept on the TV), the ones he rents
 *                                  from, and his profile name ("Ethan", or per app {"Netflix":"Ethan","Stan":"E"})
 *   movie.look, press, key, type     drive the streaming app's menus (Stream.java), never while a movie's playing
 * Order for open: a subscription or free app he's logged into, then the cheapest rent on an app he's logged into.
 * Nothing on a subscription he hasn't got gets opened, the answer just says where it is.
 * The TV opens the app on the movie's page and picks Ethan's profile if the app asks. It never presses Play, Rent or
 * Buy, never pictures the other app, and only reads streaming apps' menus (never while a movie's playing).
 * Answered straight away, not through the Runner queue, so it works while Ethan's on another app.
 */
final class Movies {

    private Movies() {}

    private static final String JW = "https://apis.justwatch.com/graphql";
    private static final Handler main = new Handler(Looper.getMainLooper());

    // The streaming apps on the TV (same as the home screen tiles). Anything else is matched by its name.
    private static final String[][] KNOWN = {
            {"netflix", "com.netflix.ninja"},
            {"youtube", "com.google.android.youtube.tv"},
            {"disney", "com.disney.disneyplus"},
            {"stan", "au.com.stan.and"},
            {"paramount", "com.cbs.ca"},
            {"abciview", "au.net.abc.iview"},
            {"appletv", "com.apple.atve.androidtv.appletv"},
            {"primevideo", "com.amazon.amazonvideo.livingroom"},
            {"googletv", "com.google.android.videos"},
    };

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("movies", Context.MODE_PRIVATE);
    }

    /** From Api, off the main thread. Answers through Api.side. */
    static void request(final Context c, final JSONObject req) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject a = req.optJSONObject("args");
                if (a == null) a = new JSONObject();
                JSONObject out;
                try {
                    switch (req.optString("do")) {
                        case "movie.apps":
                            out = apps(c, a);
                            break;
                        case "movie.find":
                            out = find(c, a, false);
                            break;
                        case "movie.open":
                            out = find(c, a, true);
                            break;
                        case "movie.look":
                        case "movie.press":
                        case "movie.key":
                        case "movie.type":
                            out = Stream.run(c, req.optString("do"), a);
                            break;
                        default:
                            out = Api.err("unknown movie request (movie.find, movie.open, movie.apps, movie.look, movie.press, movie.key, movie.type)");
                    }
                } catch (Exception e) {
                    out = Api.err(String.valueOf(e.getMessage()));
                }
                Api.side(c, req, out);
            }
        }, "movies").start();
    }

    // ---------------------------------------------------------------- Ethan's apps

    private static JSONObject apps(Context c, JSONObject a) throws Exception {
        if (a.has("set")) p(c).edit().putString("apps", list(a.opt("set")).toString()).apply();
        if (a.has("rent")) p(c).edit().putString("rent", list(a.opt("rent")).toString()).apply();
        if (a.has("profile")) p(c).edit().putString("profile", String.valueOf(a.opt("profile")).trim()).apply();
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("loggedIn", mine(c, "apps"));
        o.put("rentFrom", mine(c, "rent"));
        o.put("profile", p(c).getString("profile", ""));
        // what's on the TV that looks like a streaming app, so Claude can ask Ethan which he's logged into
        JSONArray on = new JSONArray();
        Map<String, String> all = installed(c);
        for (String[] k : KNOWN) if (all.containsKey(k[1])) on.put(all.get(k[1]));
        o.put("onTv", on);
        o.put("allApps", new JSONArray(all.values()));
        return o;
    }

    private static JSONArray list(Object v) {
        JSONArray out = new JSONArray();
        if (v instanceof JSONArray) {
            JSONArray j = (JSONArray) v;
            for (int i = 0; i < j.length(); i++) if (!j.optString(i).trim().isEmpty()) out.put(j.optString(i).trim());
        } else if (v != null) {
            for (String s : String.valueOf(v).split(",")) if (!s.trim().isEmpty()) out.put(s.trim());
        }
        return out;
    }

    private static JSONArray mine(Context c, String which) {
        try {
            return new JSONArray(p(c).getString(which, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static boolean inList(JSONArray l, String name) {
        String k = key(name);
        for (int i = 0; i < l.length(); i++) if (same(key(l.optString(i)), k)) return true;
        return false;
    }

    /** A streaming app Claude may drive the menus of: one of the TV's known ones, or one in his logged in list. */
    static boolean isStreaming(Context c, String pkg) {
        for (String[] k : KNOWN) if (k[1].equals(pkg)) return true;
        String name = Screen.appName(c, pkg);
        return !name.equals(pkg) && inList(mine(c, "apps"), name);
    }

    /** Plain key for an app or service name: "Disney+" and "Disney Plus" both come out "disney". */
    static String key(String name) {
        String s = (" " + String.valueOf(name).toLowerCase(Locale.US).replace("+", " plus ") + " ").replaceAll("[^a-z0-9]+", " ");
        s = s.replace(" plus ", " ").replace(" amazon ", " ").replace(" app ", " ").replace(" with ads ", " ").replaceAll("\\s+", "");
        if (s.equals("video") || s.equals("primevideostore")) return "primevideo";
        if (s.startsWith("googleplay")) return "googletv";
        return s;
    }

    private static boolean same(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || (a.startsWith(b) && b.length() >= 4) || (b.startsWith(a) && a.length() >= 4);
    }

    /** Every launchable app: package to name. */
    private static Map<String, String> installed(Context c) {
        Map<String, String> out = new LinkedHashMap<>();
        PackageManager pm = c.getPackageManager();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(cat);
                for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(c.getPackageName()) || out.containsKey(pkg)) continue;
                    CharSequence l = ri.loadLabel(pm);
                    out.put(pkg, l != null ? l.toString() : pkg);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** The TV app for a JustWatch service name, or null when it's not on the TV. */
    private static String appFor(Map<String, String> all, String service) {
        String k = key(service);
        for (String[] kn : KNOWN) if (same(kn[0], k) && all.containsKey(kn[1])) return kn[1];
        for (Map.Entry<String, String> e : all.entrySet()) if (same(key(e.getValue()), k)) return e.getKey();
        return null;
    }

    // ---------------------------------------------------------------- finding it

    private static JSONObject find(Context c, JSONObject a, boolean open) throws Exception {
        String q = a.optString("q").trim();
        int year = a.optInt("year", 0);
        Matcher ym = Pattern.compile("^(.*?)[\\s,]*\\(?((?:19|20)\\d\\d)\\)?$").matcher(q);
        if (year == 0 && ym.matches() && !ym.group(1).trim().isEmpty()) {
            q = ym.group(1).trim();
            year = Integer.parseInt(ym.group(2));
        }
        if (q.isEmpty()) return Api.err("needs q, the movie's name (year optional)");
        String type = a.optString("type").toUpperCase(Locale.US); // MOVIE or SHOW

        JSONArray nodes = search(q);
        if (nodes.length() == 0) {
            // not on JustWatch (Iron Lung on YouTube, 22/11): with an app named, open that app to find it there
            String app = a.optString("app");
            Map<String, String> all = installed(c);
            if (!open || app.isEmpty() || appFor(all, app) == null) return Api.err("JustWatch has nothing called " + q + " in Australia"
                    + (open && !app.isEmpty() ? ", and " + app + " isn't on the TV" : ". If you know which app has it (new releases to rent are often on YouTube), open with app set to it"));
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("title", q);
            return appOnly(c, a, q, type, all, o);
        }

        // best match: same title, then same year, then type, then JustWatch's own order
        int best = 0, bestScore = -1;
        String nq = q.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i), ct = n.optJSONObject("content");
            if (ct == null) continue;
            int s = 0;
            if (ct.optString("title").toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "").equals(nq)) s += 4;
            int y = ct.optInt("originalReleaseYear");
            if (year > 0 && y == year) s += 4;
            else if (year > 0 && Math.abs(y - year) == 1) s += 2;
            if (!type.isEmpty() && type.equals(n.optString("objectType"))) s += 2;
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        JSONObject t = nodes.optJSONObject(best), ct = t.optJSONObject("content");

        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("title", ct.optString("title"));
        o.put("year", ct.optInt("originalReleaseYear"));
        o.put("type", t.optString("objectType").toLowerCase(Locale.US));
        if (!ct.optString("fullPath").isEmpty()) o.put("justwatch", "https://www.justwatch.com" + ct.optString("fullPath"));
        JSONArray others = new JSONArray();
        for (int i = 0; i < nodes.length() && others.length() < 5; i++) {
            if (i == best) continue;
            JSONObject n = nodes.optJSONObject(i), nc = n.optJSONObject("content");
            if (nc != null) others.put(nc.optString("title") + " (" + nc.optInt("originalReleaseYear") + ", " + n.optString("objectType").toLowerCase(Locale.US) + ")");
        }
        if (others.length() > 0) o.put("otherMatches", others);

        // where it is, one line per app and way of watching
        Map<String, String> all = installed(c);
        JSONArray loggedIn = mine(c, "apps"), rentFrom = mine(c, "rent");
        boolean guessing = loggedIn.length() == 0;
        Map<String, JSONObject> where = new LinkedHashMap<>();
        JSONArray offers = t.optJSONArray("offers");
        for (int i = 0; offers != null && i < offers.length(); i++) {
            JSONObject f = offers.optJSONObject(i);
            JSONObject pk = f.optJSONObject("package");
            if (pk == null) continue;
            String svc = pk.optString("clearName");
            if (svc.toLowerCase(Locale.US).contains("channel")) continue; // add-on channels inside Prime or Apple, Ethan hasn't got any
            String kind = kind(f.optString("monetizationType"));
            if (kind == null) continue;
            String pkg0 = appFor(all, svc);
            // one line per TV app: "Netflix", "Netflix Kids" and "Netflix Standard with Ads" are all the Netflix app
            String id = (pkg0 != null ? pkg0 : key(svc)) + "|" + kind;
            JSONObject w = where.get(id);
            double price = f.optDouble("retailPriceValue", 0);
            if (w != null) {
                // keep the cheapest rent or buy, and the best picture otherwise
                if (price > 0 && w.optDouble("priceValue", 0) > 0 && price >= w.optDouble("priceValue")) continue;
                if (price == 0 && rank(f.optString("presentationType")) <= rank(w.optString("quality"))) continue;
            }
            String pkg = appFor(all, svc);
            w = new JSONObject();
            w.put("app", svc);
            w.put("kind", kind);
            if (!f.optString("retailPrice").isEmpty() && !"null".equals(f.optString("retailPrice"))) w.put("price", f.optString("retailPrice"));
            if (price > 0) w.put("priceValue", price);
            w.put("quality", f.optString("presentationType"));
            w.put("onTv", pkg != null);
            if (pkg != null) w.put("tvApp", all.get(pkg));
            w.put("loggedIn", pkg != null && (guessing || inList(loggedIn, svc) || inList(loggedIn, all.get(pkg))));
            w.put("url", page(f.optString("standardWebURL").replace("&amp;", "&")));
            if (!f.optString("tvLink").isEmpty() && !"null".equals(f.optString("tvLink"))) w.put("tvLink", page(f.optString("tvLink")));
            if (pkg != null) w.put("pkg", pkg);
            where.put(id, w);
        }
        JSONArray list = new JSONArray();
        for (JSONObject w : where.values()) list.put(w);
        o.put("where", list);
        if (guessing) o.put("note", "Ethan's logged in apps aren't set yet (movie.apps {set:[...]}), so every streaming app on the TV counts as logged in");

        // what open does: Ethan's app to watch on, else Ethan's app to rent on
        JSONObject pick = null;
        String why = null;
        String force = a.optString("app");
        if (!force.isEmpty()) {
            for (String k : new String[]{"subscription", "free", "free with ads", "rent", "buy"})
                for (JSONObject w : where.values())
                    if (pick == null && k.equals(w.optString("kind")) && (same(key(w.optString("app")), key(force)) || same(key(w.optString("tvApp")), key(force))))
                        pick = w;
            if (pick == null) {
                if (open && appFor(all, force) != null) return appOnly(c, a, q, type, all, o);
                why = "JustWatch doesn't list it on " + force;
            } else if (!pick.optBoolean("onTv")) {
                why = pick.optString("app") + " isn't on the TV";
                pick = null;
            }
        } else {
            for (String k : new String[]{"subscription", "free", "free with ads"})
                for (JSONObject w : where.values())
                    if (pick == null && k.equals(w.optString("kind")) && w.optBoolean("loggedIn")) pick = w;
            if (pick == null) {
                // cheapest rent on an app he's logged into (his rent list first, if he's set one)
                for (int pass = 0; pass < 2 && pick == null; pass++)
                    for (JSONObject w : where.values()) {
                        if (!"rent".equals(w.optString("kind")) || !w.optBoolean("loggedIn")) continue;
                        if (pass == 0 && rentFrom.length() > 0 && !inList(rentFrom, w.optString("app")) && !inList(rentFrom, w.optString("tvApp")))
                            continue;
                        if (pass == 0 && rentFrom.length() == 0) continue;
                        if (pick == null || w.optDouble("priceValue", 999) < pick.optDouble("priceValue", 999)) pick = w;
                    }
            }
        }

        // the subscriptions it's on that Ethan isn't logged into
        JSONArray missing = new JSONArray();
        for (JSONObject w : where.values())
            if (("subscription".equals(w.optString("kind")) || w.optString("kind").startsWith("free")) && !w.optBoolean("loggedIn"))
                missing.put(w.optString("app") + (w.optBoolean("onTv") ? " (on the TV, not logged in)" : " (not on the TV)"));
        if (missing.length() > 0) o.put("notLoggedIn", missing);

        if (pick == null) {
            o.put("plan", why != null ? why : list.length() == 0 ? "JustWatch has nowhere streaming or renting it in Australia right now"
                    : "nothing to open: it's not on a subscription or rent app Ethan's logged into");
            if (open) o.put("opened", false);
            return o;
        }
        o.put("plan", ("rent".equals(pick.optString("kind")) ? "open the rent page on " : "buy".equals(pick.optString("kind")) ? "open the buy page on " : "open it on ")
                + pick.optString("tvApp", pick.optString("app"))
                + (pick.has("price") ? " (" + pick.optString("price") + ")" : "")
                + ("rent".equals(pick.optString("kind")) && missing.length() > 0 ? ", it's not on any of Ethan's subscriptions" : ""));
        if (!open) return o;

        o.put("opened", launch(c, pick, a.optString("profile"), o));
        return o;
    }

    /**
     * JustWatch doesn't have the title on the app Claude named: open the app for Claude to find it there. YouTube gets
     * its search link for the title (untested whether the TV app follows it), and the way through its screens that
     * worked on 22/11, because nothing in YouTube can be read.
     */
    private static JSONObject appOnly(Context c, JSONObject a, String q, String type, Map<String, String> all, JSONObject o) throws Exception {
        String pkg = appFor(all, a.optString("app"));
        JSONObject w = new JSONObject().put("pkg", pkg).put("tvApp", all.get(pkg)).put("app", a.optString("app"));
        boolean yt = pkg.equals("com.google.android.youtube.tv");
        String find = q + ("SHOW".equals(type) ? "" : " movie");
        if (yt) w.put("url", "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(find, "UTF-8"));
        o.put("plan", "JustWatch doesn't list it on " + all.get(pkg) + ", so open " + all.get(pkg) + " to search for it there");
        o.put("opened", launch(c, w, a.optString("profile"), o));
        o.put("next", yt ? "Look at tv_screen. If it's not showing search results for the title: key up (top bar), right (search), enter, type \""
                + find + "\", then left and down to the matching suggestion and enter. Enter on the movie's card (it shows a price) opens its page. "
                + "Stop there with Buy or rent focused, never press it."
                : "Find it with tv_screen and tv_stream: the app's search, arrows, enter.");
        return o;
    }

    /** A show's own page, not one of its episodes: Paramount+ links go to an episode, which starts playing (22/11). */
    private static String page(String link) {
        return link.replaceAll("(paramountplus\\.com/shows/[^/#?]+)/video/[^#?]*", "$1/")
                .replaceAll("(paramountplus\\.com%2Fshows%2F[^%#;]+)%2Fvideo%2F[^;#]*", "$1%2F");
    }

    private static String kind(String m) {
        switch (m) {
            case "FLATRATE":
                return "subscription";
            case "FREE":
                return "free";
            case "ADS":
                return "free with ads";
            case "RENT":
                return "rent";
            case "BUY":
                return "buy";
            default:
                return null;
        }
    }

    private static int rank(String q) {
        return "_4K".equals(q) ? 3 : "HD".equals(q) ? 2 : "SD".equals(q) ? 1 : 0;
    }

    private static final String Q1 = "query($country:Country!,$language:Language!,$first:Int!,$f:TitleFilter!,$of:OfferFilter!){"
            + "popularTitles(country:$country,filter:$f,first:$first,sortBy:POPULAR,sortRandomSeed:0){edges{node{"
            + "id objectType content(country:$country,language:$language){title originalReleaseYear fullPath}"
            + "offers(country:$country,platform:WEB,filter:$of){monetizationType presentationType retailPrice(language:$language) retailPriceValue "
            + "package{clearName technicalName} standardWebURL TVLINK}}}}}";

    /** JustWatch search in Australia: the title nodes, best first. Asks for the Android TV deep link, without it if JustWatch says no. */
    private static JSONArray search(String q) throws Exception {
        JSONObject v = new JSONObject();
        v.put("country", "AU");
        v.put("language", "en");
        v.put("first", 6);
        v.put("f", new JSONObject().put("searchQuery", q).put("includeTitlesWithoutUrl", true));
        v.put("of", new JSONObject().put("bestOnly", false));
        String err = null;
        for (String link : new String[]{"tvLink:deeplinkURL(platform:ANDROID_TV)", ""}) {
            JSONObject body = new JSONObject().put("query", Q1.replace("TVLINK", link)).put("variables", v);
            JSONObject r = new JSONObject(post(JW, body.toString()));
            if (r.has("errors") && r.optJSONObject("data") == null) {
                err = r.optJSONArray("errors").optJSONObject(0).optString("message");
                continue;
            }
            JSONObject d = r.optJSONObject("data");
            JSONArray edges = d == null || d.optJSONObject("popularTitles") == null ? null : d.optJSONObject("popularTitles").optJSONArray("edges");
            JSONArray out = new JSONArray();
            for (int i = 0; edges != null && i < edges.length(); i++) out.put(edges.optJSONObject(i).optJSONObject("node"));
            return out;
        }
        throw new Exception("JustWatch said: " + err);
    }

    private static String post(String url, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
        con.setDoOutput(true);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        con.setFixedLengthStreamingMode(b.length);
        try {
            OutputStream os = con.getOutputStream();
            os.write(b);
            os.close();
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String text = bo.toString("UTF-8");
            if (code >= 400 && !text.trim().startsWith("{")) throw new Exception("JustWatch said " + code);
            return text;
        } finally {
            con.disconnect();
        }
    }

    // ---------------------------------------------------------------- opening it

    /** Wakes the TV if it's off, opens the app on the movie's page (deep link, then its web link, then just the app). */
    private static boolean launch(final Context c, JSONObject w, String profile, JSONObject o) throws Exception {
        final String pkg = w.optString("pkg");
        final List<Intent> tries = new ArrayList<>();
        final List<String> how = new ArrayList<>();
        String tv = w.optString("tvLink"), url = w.optString("url");
        // JustWatch's TV links for Disney+ and Prime start the movie playing (22/11): turn them into the title page,
        // and drop any other play link so the web link (a title page) goes instead
        tv = tv.replace("disneyplus.com/play/", "disneyplus.com/browse/entity-").replace("primevideo.com/watch?", "primevideo.com/detail?")
                .replace("netflix.com/watch/", "netflix.com/title/").replace("&time=0", "");
        if (Pattern.compile("/(play|watch)\\b").matcher(tv).find()) tv = "";
        if (!tv.isEmpty()) {
            try {
                Intent i = tv.startsWith("intent:") ? Intent.parseUri(tv, Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, Uri.parse(tv));
                i.setComponent(null);
                i.setSelector(null);
                tries.add(i);
                how.add("JustWatch's TV link");
            } catch (Exception ignored) {
            }
        }
        Matcher nf = Pattern.compile("netflix\\.com/(?:[a-z-]+/)?(?:title|watch)/(\\d+)").matcher(url);
        if (pkg.equals("com.netflix.ninja") && nf.find()) {
            tries.add(new Intent(Intent.ACTION_VIEW, Uri.parse("netflix://title/" + nf.group(1))));
            how.add("Netflix title link");
            tries.add(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/title/" + nf.group(1))));
            how.add("Netflix web link");
        }
        if (url.startsWith("http")) {
            tries.add(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            how.add("the movie's web link");
        }
        for (Intent i : tries) {
            i.setPackage(pkg);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }

        // wake first, the wake screen goes home after 1.5 s, so the app goes on top after that
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean wasOff = pm != null && !pm.isInteractive();
        if (wasOff) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    ListenService.wakeScreen(c);
                    WakeActivity.start(c, false);
                }
            });
            Thread.sleep(4000);
            o.put("woke", true);
        }

        final AtomicReference<String> used = new AtomicReference<>();
        final AtomicReference<Intent> sent = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context from = HomeButtonService.instance != null ? HomeButtonService.instance : c;
                    PackageManager p = c.getPackageManager();
                    for (int i = 0; i < tries.size(); i++) {
                        Intent in = tries.get(i);
                        try {
                            if (in.getData() != null && p.resolveActivity(in, 0) == null) {
                                // some apps don't say BROWSABLE, try without
                                in.removeCategory(Intent.CATEGORY_BROWSABLE);
                                if (p.resolveActivity(in, 0) == null) continue;
                            }
                            from.startActivity(in);
                            sent.set(in);
                            used.set(how.get(i));
                            return;
                        } catch (Exception ignored) {
                        }
                    }
                    // no link took: just open the app
                    Intent li = p.getLeanbackLaunchIntentForPackage(pkg);
                    if (li == null) li = p.getLaunchIntentForPackage(pkg);
                    if (li != null) {
                        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        from.startActivity(li);
                        used.set("app only");
                    }
                } catch (Exception e) {
                    TvLog.line(c, "Movies: couldn't open " + pkg + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            }
        });
        done.await(10, TimeUnit.SECONDS);
        String u = used.get();
        if (u == null) {
            o.put("how", "couldn't open " + w.optString("tvApp"));
            return false;
        }
        o.put("how", u.equals("app only") ? "opened " + w.optString("tvApp") + " but couldn't go straight to the movie, search for it there" : u);
        TvLog.line(c, "Movies: " + o.optString("title") + " on " + w.optString("tvApp") + " (" + u + ")");
        // a profile picker gets Ethan's profile, then the link goes again
        final Intent again = sent.get();
        Stream.afterOpen(c, pkg, w.optString("tvApp"), profile, new Runnable() {
            @Override
            public void run() {
                if (again == null) return;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Context from = HomeButtonService.instance != null ? HomeButtonService.instance : c;
                            from.startActivity(again);
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        }, o);
        Thread.sleep(1500);
        o.put("front", Screen.name(c));
        o.put("next", "movie.look shows the app's screen, to check it's on the movie's page");
        // YouTube's screen can't be read, so nothing here can pick or name the account (22/11)
        if (pkg.equals("com.google.android.youtube.tv")) o.put("account", "YouTube opens on whoever used it last and the TV can't read which. "
                + "Check the account on tv_screen and tell Ethan, a rental is charged to the account that's active. On its account picker, move with the arrows and enter.");
        return true;
    }
}
