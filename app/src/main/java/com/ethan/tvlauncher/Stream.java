package com.ethan.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Driving a streaming app's menus (22/11, Ethan's ask: "it'll always end up on who's watching").
 * Through the accessibility helper, only on the streaming apps (Movies.KNOWN or his logged in list), only while one is
 * on the TV, and never while a movie's actually playing (hands off then, and anything that starts playback gets backed
 * out of). Text only, no pictures of other apps. Never pressed: play, resume, watch, rent, buy, subscribe, sign out and
 * friends (BLOCK).
 *   movie.look                     the app's screen as text, each thing with a ref number
 *   movie.press {ref | text}       presses one thing
 *   movie.key {key}                back, home, enter, up, down, left, right
 *   movie.type {text}              types into the search box
 * The phone page's streaming panel (/api/stream, Ethan's own phone only, 22/11) uses the same code as owner: no block
 * list, works while playing, so Ethan can key a PIN or press Play from his phone.
 * After movie.open the profile picker gets Ethan's profile picked by itself (movie.apps {profile}), then the movie's
 * link goes again (apps drop the link once you pick a profile).
 */
final class Stream {

    private Stream() {}

    // whole words, on the thing pressed and the text inside it
    private static final Pattern BLOCK = Pattern.compile("\\b(play|playing|resume|watch|watching|start|restart|rent|buy|purchase|pay|"
            + "subscribe|subscription|sign up|join|free trial|upgrade|checkout|order|sign out|log out|logout|delete|remove|"
            + "cancel membership|parental|pin)\\b");
    // things that open a title page or a profile, so "watch" in them is fine
    private static final Pattern PROFILE_SCREEN = Pattern.compile("who.?s watching|who is watching|choose (a |your )?profile|select (a |your )?profile|switch profile");

    private static volatile List<AccessibilityNodeInfo> refs = new ArrayList<>();

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("movies", Context.MODE_PRIVATE);
    }

    static JSONObject run(Context c, String what, JSONObject a) throws Exception {
        return run(c, what, a, false);
    }

    /** owner: Ethan on his phone page, not Claude. Nothing blocked and it works while playing. */
    static JSONObject run(Context c, String what, JSONObject a, boolean owner) throws Exception {
        HomeButtonService h = HomeButtonService.instance;
        if (h == null) return Api.err("the accessibility helper isn't running on the TV");
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return Api.err("the TV's off");
        AccessibilityNodeInfo root = root(h);
        String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        // our own screen's up (home, Filer...): whatever the helper last saw of another app is old news (22/11)
        if (Screen.top != null) pkg = "";
        if (!streaming(c, pkg)) return Api.err("there's no streaming app on the TV (it's on " + Screen.name(c) + "), open a movie first");
        boolean playingNow = playing(c, root);
        if (playingNow && !owner) return Api.err("a movie's playing on " + Screen.appName(c, pkg) + ", so hands off (Ethan's rule)");

        JSONObject o = new JSONObject();
        o.put("ok", true);
        if (owner) o.put("playing", playingNow);
        o.put("owner", owner);
        o.put("app", Screen.appName(c, pkg));
        switch (what) {
            case "movie.look":
                break;
            case "movie.press": {
                AccessibilityNodeInfo n = null;
                if (a.has("ref")) {
                    int r = a.optInt("ref");
                    List<AccessibilityNodeInfo> l = refs;
                    if (r >= 1 && r <= l.size() && l.get(r - 1).refresh()) n = l.get(r - 1);
                    if (n == null) return Api.err("ref " + r + " is gone, look again");
                } else {
                    String t = a.optString("text").trim().toLowerCase(Locale.US);
                    if (t.isEmpty()) return Api.err("press needs ref or text");
                    List<AccessibilityNodeInfo> hits = new ArrayList<>();
                    for (AccessibilityNodeInfo x : nodes(root)) if (deep(x).toLowerCase(Locale.US).equals(t)) hits.add(x);
                    if (hits.isEmpty())
                        for (AccessibilityNodeInfo x : nodes(root)) if (deep(x).toLowerCase(Locale.US).contains(t)) hits.add(x);
                    // the same label twice is usually a tile and its inside (Disney+ doubles every profile, 22/11)
                    java.util.Set<String> labels = new java.util.HashSet<>();
                    for (AccessibilityNodeInfo x : hits) labels.add(deep(x));
                    if (labels.size() == 1 && hits.size() > 1) hits = hits.subList(0, 1);
                    if (hits.size() != 1) return look(c, root, o.put("ok", false).put("err", hits.isEmpty() ? "nothing says " + a.optString("text") : hits.size() + " things say " + a.optString("text") + ", use a ref"));
                    n = hits.get(0);
                }
                String why = owner ? null : blocked(deep(n));
                if (why != null) return look(c, root, o.put("ok", false).put("err", "won't press " + why + " (starts the movie or costs money, that's Ethan's)"));
                // nothing readable on it (YouTube, Netflix): pressing it does nothing or something unknown
                if (!owner && deep(n).isEmpty()) return look(c, root, o.put("ok", false).put("err", "there's nothing readable on that one, use tv_screen and the arrow keys"));
                o.put("pressed", deep(n));
                if (!pressNode(c, n)) o.put("note", "the app didn't take the press, move to it with the arrows then enter");
                return after(c, h, o);
            }
            case "movie.key": {
                String k = a.optString("key").toLowerCase(Locale.US);
                if (k.equals("ok")) k = "enter";
                // real remote keys through the Shell helper when it's up (22/11): YouTube and Netflix ignore the
                // accessibility presses, and Disney+ only opened its lower rows for a real Down
                if (k.matches("back|home|enter|up|down|left|right") && Shell.ready(c, 8000)) {
                    if (k.equals("enter") && !owner) {
                        AccessibilityNodeInfo f = focused(root);
                        String label = f == null ? "" : deep(f);
                        String why = blocked(label);
                        if (why != null) return look(c, root, o.put("ok", false).put("err", "won't press " + why + " (starts the movie or costs money, that's Ethan's)"));
                        if (label.isEmpty()) o.put("blind", "this app's screen can't be read, so the TV couldn't check what OK pressed: look at tv_screen");
                        else o.put("pressed", label);
                    }
                    if (Shell.key(k)) {
                        o.put("key", k);
                        o.put("how", "remote key");
                        if (k.equals("home")) return o.put("front", "home screen");
                        return after(c, h, o);
                    }
                }
                switch (k) {
                    case "back":
                        h.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                        return after(c, h, o.put("key", k));
                    case "home":
                        h.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                        o.put("key", k);
                        o.put("front", "home screen");
                        return o;
                    case "enter":
                    case "ok": {
                        AccessibilityNodeInfo f = focused(root);
                        if (f == null) return look(c, root, o.put("ok", false).put("err", "nothing has focus, press by ref"));
                        String why = owner ? null : blocked(deep(f));
                        if (why != null) return look(c, root, o.put("ok", false).put("err", "won't press " + why + " (starts the movie or costs money, that's Ethan's)"));
                        o.put("pressed", deep(f));
                        click(f);
                        return after(c, h, o);
                    }
                    case "up":
                    case "down":
                    case "left":
                    case "right": {
                        AccessibilityNodeInfo f = focused(root);
                        if (f == null) return look(c, root, o.put("ok", false).put("err", "nothing has focus, press by ref"));
                        int dir = k.equals("up") ? View.FOCUS_UP : k.equals("down") ? View.FOCUS_DOWN : k.equals("left") ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
                        AccessibilityNodeInfo nx = f.focusSearch(dir);
                        if (nx == null || !nx.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                            o.put("note", "the app didn't move focus that way, press by ref instead");
                        return after(c, h, o.put("key", k));
                    }
                    default:
                        return Api.err("keys: back, home, enter, up, down, left, right");
                }
            }
            case "movie.type": {
                AccessibilityNodeInfo box = null;
                AccessibilityNodeInfo f = focused(root);
                if (f != null && f.isEditable()) box = f;
                if (box == null) for (AccessibilityNodeInfo x : nodes(root)) if (x.isEditable()) { box = x; break; }
                if (box == null) {
                    // no box: a number pad on screen (PIN screens), press each digit's button (Ethan, 22/11)
                    String t = a.optString("text");
                    boolean pad = t.matches("\\d{1,8}");
                    if (pad) for (char ch : t.toCharArray()) {
                        boolean has = false;
                        for (AccessibilityNodeInfo x : nodes(root)) if (deep(x).equals(String.valueOf(ch))) { has = true; break; }
                        if (!has) pad = false;
                    }
                    if (!pad) {
                        // no box and no number pad the helper can read: type it as real key presses
                        if (Shell.ready(c, 4000) && Shell.text(t)) return after(c, h, o.put("typed", owner && t.matches("\\d+") ? t.length() + " digits" : t).put("how", "remote keys"));
                        return look(c, root, o.put("ok", false).put("err", "no text box on screen (open the app's search first)"));
                    }
                    for (char ch : t.toCharArray()) {
                        AccessibilityNodeInfo key = null;
                        for (AccessibilityNodeInfo x : nodes(root(h)))
                            if (deep(x).equals(String.valueOf(ch))) { key = x; break; }
                        if (key == null) return after(c, h, o.put("ok", false).put("err", "no box and no " + ch + " button on screen"));
                        click(key);
                        Thread.sleep(350);
                    }
                    // a PIN never goes in the answer
                    return after(c, h, o.put("typed", owner ? t.length() + " digits" : t));
                }
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.optString("text"));
                box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) && !(Shell.ready(c, 4000) && Shell.text(a.optString("text"))))
                    o.put("note", "the box didn't take the text");
                return after(c, h, o.put("typed", a.optString("text")));
            }
            default:
                return Api.err("movie.look, movie.press, movie.key, movie.type");
        }
        return look(c, root, o);
    }

    /** After a press or key: give the app a moment, back out if a movie started, then a fresh look. */
    private static JSONObject after(Context c, HomeButtonService h, JSONObject o) throws Exception {
        // Ethan's own press off the phone page: he's watching the live picture, no need to wait or read the screen back
        if (o.optBoolean("owner") && Shell.up()) return o;
        // a blind OK (a screen that can't be read) gets longer: a video it started takes a few seconds to get going
        Thread.sleep(o.optBoolean("owner") ? 1200 : o.has("blind") ? 6000 : 2500);
        AccessibilityNodeInfo root = root(h);
        if (!o.optBoolean("owner") && root != null && playing(c, root)) {
            h.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            o.put("backedOut", "that started the movie playing, so it pressed Back (playing is Ethan's)");
            Thread.sleep(2000);
            root = root(h);
        }
        String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (root == null || !streaming(c, pkg)) {
            o.put("front", Screen.name(c));
            return o;
        }
        return look(c, root, o);
    }

    /** The screen as text: one line per thing, ref first. Refs stay good until the next look. */
    private static JSONObject look(Context c, AccessibilityNodeInfo root, JSONObject o) throws Exception {
        List<AccessibilityNodeInfo> l = new ArrayList<>();
        JSONArray lines = new JSONArray();
        StringBuilder all = new StringBuilder();
        for (AccessibilityNodeInfo n : nodes(root)) {
            String t = deep(n);
            if (t.isEmpty() && !n.isFocusable()) continue;
            l.add(n);
            all.append(t.toLowerCase(Locale.US)).append('\n');
            if (lines.length() < 90) {
                if (o.optBoolean("owner")) {
                    // the phone page draws its own list
                    JSONObject it = new JSONObject().put("ref", l.size()).put("t", t.isEmpty() ? "[no label]" : t);
                    if (n.isFocused() || n.isAccessibilityFocused()) it.put("focus", true);
                    if (n.isSelected()) it.put("sel", true);
                    if (n.isEditable()) it.put("box", true);
                    lines.put(it);
                    continue;
                }
                String flags = (n.isFocused() || n.isAccessibilityFocused() ? " (focused)" : "") + (n.isSelected() ? " (selected)" : "")
                        + (n.isEditable() ? " (text box)" : "") + (blocked(t) != null ? " (won't press)" : "");
                lines.put(l.size() + ": " + (t.isEmpty() ? "[no label]" : t) + flags);
            }
        }
        refs = l;
        o.put("screen", lines);
        // an app that draws its own screen (YouTube, Netflix): nothing to read or press by name
        if (all.toString().trim().isEmpty()) o.put("blind", "this app's screen can't be read: look with tv_screen and move with the arrow keys and enter (real remote keys), don't press by ref or text");
        if (PROFILE_SCREEN.matcher(norm(all.toString())).find()) o.put("profilePicker", true);
        return o;
    }

    // ---------------------------------------------------------------- the profile picker after movie.open

    /**
     * Up to 25 s after the app opens: on a profile picker, pick Ethan's profile and send the movie's link again.
     * 23/11 (Ethan: "what's taking so long"): looks every half second instead of every second, sends the link again as
     * soon as the picker's gone instead of a flat 3.5 s, and if the app ignores the pick (iview took the press but
     * stayed on "Who's Watching?" for 8 s) has one more go with a real OK or tap.
     */
    static void afterOpen(Context c, String pkg, String appName, String asked, Runnable again, JSONObject o) {
        HomeButtonService h = HomeButtonService.instance;
        if (h == null) return;
        long until = System.currentTimeMillis() + 25000, pickedAt = 0;
        boolean picked = false, retried = false;
        int settled = 0;
        // who Ethan said to watch as this time, else his saved profile (Ethan, 22/11)
        String want = asked != null && !asked.trim().isEmpty() ? asked.trim() : profileFor(c, appName);
        try {
            while (System.currentTimeMillis() < until) {
                Thread.sleep(500);
                AccessibilityNodeInfo root = root(h);
                if (root == null || root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) continue;
                List<AccessibilityNodeInfo> all = nodes(root);
                StringBuilder text = new StringBuilder();
                for (AccessibilityNodeInfo n : all) text.append(deep(n).toLowerCase(Locale.US)).append('\n');
                if (!PROFILE_SCREEN.matcher(norm(text.toString())).find()) {
                    if (picked) {
                        // past it: the link again, apps drop it once you pick a profile
                        Thread.sleep(1000);
                        again.run();
                        return;
                    }
                    // about 3 s of a real menu and no picker: it went straight in
                    if (all.size() > 5 && ++settled >= 6) return;
                    continue;
                }
                if (picked) {
                    if (!retried && System.currentTimeMillis() - pickedAt > 2500) {
                        retried = true;
                        AccessibilityNodeInfo hit = profileNode(all, want);
                        if (hit != null && pressReal(c, hit)) {
                            pickedAt = System.currentTimeMillis();
                            TvLog.line(c, "Movies: " + appName + " ignored the profile pick, pressed it again");
                        }
                    }
                    // still reads as a picker 3 s after the second go: could be a "Switch profile" button on the app's
                    // own menu, so send the link anyway (the old way did after 3.5 s)
                    if (retried && System.currentTimeMillis() - pickedAt > 3000) {
                        again.run();
                        return;
                    }
                    continue; // still loading after the pick
                }
                JSONArray names = new JSONArray();
                for (AccessibilityNodeInfo n : all) {
                    String t = deep(n);
                    if (!t.isEmpty() && t.length() < 40 && !PROFILE_SCREEN.matcher(norm(t.toLowerCase(Locale.US))).find()) names.put(t);
                }
                if (want.isEmpty()) {
                    o.put("profilePicker", "it's on the profile picker and Ethan's profile isn't set (movie.apps {profile}). On screen: " + names);
                    return;
                }
                AccessibilityNodeInfo hit = profileNode(all, want);
                if (hit == null && pinned(all, want)) {
                    o.put("profilePicker", want + "'s profile has a PIN, so it stopped on the picker: that one's Ethan's to open");
                    return;
                }
                if (hit == null) {
                    o.put("profilePicker", "it's on the profile picker and there's no " + want + " on it, so it stopped: ask Ethan who to watch as"
                            + " (then movie.open again with profile, or tv_stream press). On screen: " + names);
                    return;
                }
                if (!pressNode(c, hit)) {
                    o.put("profilePicker", "found " + want + " on the profile picker but the app didn't take the press");
                    return;
                }
                picked = true;
                pickedAt = System.currentTimeMillis();
                o.put("profile", "picked " + want);
                TvLog.line(c, "Movies: picked profile " + want + " on " + appName);
                until = System.currentTimeMillis() + 10000;
            }
            // timed up still on the picker after picking: say so rather than "picked"
            if (picked) o.put("profilePicker", "pressed " + want + " but the app stayed on the picker: press it with tv_stream, then open again");
        } catch (Exception ignored) {
        }
    }

    /** The profile tile for want, skipping PIN locked ones (Ethan's to open). */
    private static AccessibilityNodeInfo profileNode(List<AccessibilityNodeInfo> all, String want) {
        for (AccessibilityNodeInfo n : all) {
            String t = norm(deep(n).toLowerCase(Locale.US));
            if (isProfile(t, want) && !t.contains("pin")) return n;
        }
        return null;
    }

    private static boolean pinned(List<AccessibilityNodeInfo> all, String want) {
        for (AccessibilityNodeInfo n : all) {
            String t = norm(deep(n).toLowerCase(Locale.US));
            if (isProfile(t, want) && t.contains("pin")) return true;
        }
        return false;
    }

    /** A real OK on the thing (focused first), or a real tap in its middle: for apps that take the accessibility press and do nothing. */
    private static boolean pressReal(Context c, AccessibilityNodeInfo n) {
        if (!Shell.ready(c, 8000)) return false;
        try {
            AccessibilityNodeInfo f = n;
            for (int i = 0; i < 6 && f != null && !f.isFocusable(); i++) f = f.getParent();
            if (f != null) {
                if (!f.isFocused()) {
                    f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Thread.sleep(350);
                    f.refresh();
                }
                if (f.isFocused()) return Shell.key("enter");
            }
        } catch (Throwable ignored) {
        }
        return tapNode(c, n);
    }

    /** "Ethan", "Ethan, profile 1", "Access Ethan's profile" (Disney+, 22/11), "Ethan's profile". */
    private static boolean isProfile(String t, String want) {
        String w = norm(want.toLowerCase(Locale.US)).trim();
        if (w.isEmpty()) return false;
        if (t.equals(w) || t.startsWith(w + ",") || t.startsWith(w + " profile")) return true;
        return Pattern.compile("(^|\\W)" + Pattern.quote(w) + "'s (pin protected )?profile\\b").matcher(t).find();
    }

    private static String profileFor(Context c, String appName) {
        String s = p(c).getString("profile", "");
        if (!s.trim().startsWith("{")) return s.trim();
        try {
            JSONObject m = new JSONObject(s);
            JSONArray k = m.names();
            for (int i = 0; k != null && i < k.length(); i++)
                if (Movies.key(k.optString(i)).equals(Movies.key(appName)) || Movies.key(appName).startsWith(Movies.key(k.optString(i))))
                    return m.optString(k.optString(i));
            return m.optString("all", m.optString("*", ""));
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- the phone page's picture

    /** The streaming app on screen right now (package), or null. */
    static String front(Context c) {
        // asked several times a second by the live picture: half a second's memory
        long now = android.os.SystemClock.uptimeMillis();
        if (now - frontAt < 500) return frontPkg;
        frontPkg = front0(c);
        frontAt = android.os.SystemClock.uptimeMillis();
        return frontPkg;
    }

    private static volatile long frontAt, playAt;
    private static volatile String frontPkg;
    private static volatile boolean playWas;

    private static String front0(Context c) {
        HomeButtonService h = HomeButtonService.instance;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (h == null || Screen.top != null || (pm != null && !pm.isInteractive())) return null;
        AccessibilityNodeInfo root = root(h);
        String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        return streaming(c, pkg) ? pkg : null;
    }

    static boolean playingNow(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - playAt < 700) return playWas;
        HomeButtonService h = HomeButtonService.instance;
        playWas = h != null && playing(c, root(h));
        playAt = android.os.SystemClock.uptimeMillis();
        return playWas;
    }

    /** The app's window that has the remote's focus. The "active window" can lag an app change (22/11: it said Disney+ on Stan). */
    static AccessibilityNodeInfo root(HomeButtonService h) {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : h.getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || !w.isFocused()) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) return r;
            }
        } catch (Throwable ignored) {
        }
        return h.getRootInActiveWindow();
    }

    /**
     * Presses a thing: the accessibility press, and if the app won't take it (Stan's profile tiles, 22/11), focus on it
     * and a real OK, and failing that a real tap in its middle.
     */
    private static boolean pressNode(Context c, AccessibilityNodeInfo n) {
        if (click(n)) return true;
        if (!Shell.ready(c, 8000)) return false;
        try {
            AccessibilityNodeInfo f = n;
            for (int i = 0; i < 6 && f != null && !f.isFocusable(); i++) f = f.getParent();
            if (f != null) {
                if (!f.isFocused()) {
                    f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Thread.sleep(350);
                    f.refresh();
                }
                if (f.isFocused()) return Shell.key("enter");
            }
        } catch (Throwable ignored) {
        }
        return tapNode(c, n);
    }

    /** A real finger tap in the middle of a thing (when the app won't take the accessibility press). */
    private static boolean tapNode(Context c, AccessibilityNodeInfo n) {
        if (!Shell.up()) return false;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        ((android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty() || dm.widthPixels <= 0) return false;
        return Shell.tap(r.exactCenterX() / dm.widthPixels, r.exactCenterY() / dm.heightPixels);
    }

    /** Ethan's tap on the phone page picture: presses the smallest thing under his finger (fx, fy 0 to 1 across the screen). */
    static boolean tapAt(Context c, float fx, float fy) {
        HomeButtonService h = HomeButtonService.instance;
        if (h == null) return false;
        AccessibilityNodeInfo root = root(h);
        if (root == null) return false;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        ((android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int x = Math.round(fx * dm.widthPixels), y = Math.round(fy * dm.heightPixels);
        android.graphics.Rect r = new android.graphics.Rect();
        AccessibilityNodeInfo best = null;
        long area = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes(root)) {
            if (!n.isClickable() && !n.isFocusable()) continue;
            n.getBoundsInScreen(r);
            if (!r.contains(x, y)) continue;
            long a = (long) r.width() * r.height();
            if (a < area) {
                area = a;
                best = n;
            }
        }
        if (best != null && click(best)) return true;
        // nothing readable under it (YouTube and Netflix draw their own screens): a real tap on that spot
        return Shell.ready(c, 3000) && Shell.tap(fx, fy);
    }

    // ---------------------------------------------------------------- bits

    static boolean streaming(Context c, String pkg) {
        return pkg != null && !pkg.isEmpty() && Movies.isStreaming(c, pkg);
    }

    /**
     * A movie's actually playing. A seek bar on screen says so. A screen with real menus on it (4 or more labels) isn't.
     * Sound with next to nothing readable used to count as playing, but YouTube's home screen plays an ad with sound
     * and its screen can't be read, which locked everything on a menu (22/11). So then the Shell helper's mirror
     * decides: a full screen of moving video, or of black (a protected movie), is playing. A menu with an ad in part of
     * it isn't. Without the helper it's the old guess.
     */
    static boolean playing(Context c, AccessibilityNodeInfo root) {
        if (root == null) return false;
        int texts = 0;
        for (AccessibilityNodeInfo n : nodes(root)) {
            CharSequence cls = n.getClassName();
            if (cls != null && cls.toString().contains("SeekBar")) return true;
            if (!label(n).isEmpty()) texts++;
        }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (texts >= 4 || am == null || !am.isMusicActive()) return false;
        // the helper takes about 5 s to start from cold (23/11: a 4 s wait gave up just before it came up, and the
        // old guess then locked Claude out of YouTube's search results over an ad's preview)
        if (Shell.ready(c, 12000)) {
            int v = Shell.video();
            if (v >= 0) return v == 1;
        }
        return true;
    }

    private static String blocked(String label) {
        java.util.regex.Matcher m = BLOCK.matcher(norm(label.toLowerCase(Locale.US)));
        return m.find() ? "\"" + label + "\"" : null;
    }

    private static boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo b = n;
        for (int i = 0; i < 6 && b != null && !b.isClickable(); i++) b = b.getParent();
        if (b == null) b = n;
        b.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return b.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static AccessibilityNodeInfo focused(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return f != null ? f : root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
    }

    /** Visible things with a label, or that can be pressed or focused, in screen order. */
    private static List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        walk(root, out, 0);
        return out;
    }

    private static void walk(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40 || out.size() > 200) return;
        if (n.isVisibleToUser() && (!label(n).isEmpty() || n.isClickable() || n.isFocusable() || n.isEditable())) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1);
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.toString().trim().isEmpty()) t = n.getContentDescription();
        if ((t == null || t.toString().trim().isEmpty()) && android.os.Build.VERSION.SDK_INT >= 26) t = n.getHintText();
        String s = t == null ? "" : t.toString().replaceAll("\\s+", " ").trim();
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }

    /** Its own label, or the text inside it (a tile with a name under it). */
    private static String deep(AccessibilityNodeInfo n) {
        String s = label(n);
        if (!s.isEmpty()) return s;
        StringBuilder b = new StringBuilder();
        inner(n, b, 0);
        String r = b.toString().trim();
        return r.length() > 100 ? r.substring(0, 100) + "..." : r;
    }

    private static void inner(AccessibilityNodeInfo n, StringBuilder b, int depth) {
        if (n == null || depth > 4 || b.length() > 100) return;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch == null) continue;
            String l = label(ch);
            if (!l.isEmpty()) b.append(b.length() > 0 ? " " : "").append(l);
            else inner(ch, b, depth + 1);
        }
    }

    private static String norm(String s) {
        return s.replace('’', '\'').replace('‘', '\'');
    }
}
