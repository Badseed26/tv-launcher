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
        HomeButtonService h = HomeButtonService.instance;
        if (h == null) return Api.err("the accessibility helper isn't running on the TV");
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return Api.err("the TV's off");
        AccessibilityNodeInfo root = h.getRootInActiveWindow();
        String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!streaming(c, pkg)) return Api.err("there's no streaming app on the TV (it's on " + Screen.name(c) + "), open a movie first");
        if (playing(c, root)) return Api.err("a movie's playing on " + Screen.appName(c, pkg) + ", so hands off (Ethan's rule)");

        JSONObject o = new JSONObject();
        o.put("ok", true);
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
                    if (hits.size() != 1) return look(c, root, o.put("ok", false).put("err", hits.isEmpty() ? "nothing says " + a.optString("text") : hits.size() + " things say " + a.optString("text") + ", use a ref"));
                    n = hits.get(0);
                }
                String why = blocked(deep(n));
                if (why != null) return look(c, root, o.put("ok", false).put("err", "won't press " + why + " (starts the movie or costs money, that's Ethan's)"));
                o.put("pressed", deep(n));
                if (!click(n)) o.put("note", "the app didn't take the press, try focusing it with the arrows then enter");
                return after(c, h, o);
            }
            case "movie.key": {
                String k = a.optString("key").toLowerCase(Locale.US);
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
                        String why = blocked(deep(f));
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
                if (box == null) return look(c, root, o.put("ok", false).put("err", "no text box on screen (open the app's search first)"));
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.optString("text"));
                box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) o.put("note", "the box didn't take the text");
                return after(c, h, o.put("typed", a.optString("text")));
            }
            default:
                return Api.err("movie.look, movie.press, movie.key, movie.type");
        }
        return look(c, root, o);
    }

    /** After a press or key: give the app a moment, back out if a movie started, then a fresh look. */
    private static JSONObject after(Context c, HomeButtonService h, JSONObject o) throws Exception {
        Thread.sleep(2500);
        AccessibilityNodeInfo root = h.getRootInActiveWindow();
        if (root != null && playing(c, root)) {
            h.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            o.put("backedOut", "that started the movie playing, so it pressed Back (playing is Ethan's)");
            Thread.sleep(2000);
            root = h.getRootInActiveWindow();
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
                String flags = (n.isFocused() || n.isAccessibilityFocused() ? " (focused)" : "") + (n.isSelected() ? " (selected)" : "")
                        + (n.isEditable() ? " (text box)" : "") + (blocked(t) != null ? " (Ethan's)" : "");
                lines.put(l.size() + ": " + (t.isEmpty() ? "[no label]" : t) + flags);
            }
        }
        refs = l;
        o.put("screen", lines);
        if (PROFILE_SCREEN.matcher(norm(all.toString())).find()) o.put("profilePicker", true);
        return o;
    }

    // ---------------------------------------------------------------- the profile picker after movie.open

    /** Up to 25 s after the app opens: on a profile picker, pick Ethan's profile and send the movie's link again. */
    static void afterOpen(Context c, String pkg, String appName, String asked, Runnable again, JSONObject o) {
        HomeButtonService h = HomeButtonService.instance;
        if (h == null) return;
        long until = System.currentTimeMillis() + 25000;
        boolean picked = false;
        int settled = 0;
        try {
            while (System.currentTimeMillis() < until) {
                Thread.sleep(1000);
                AccessibilityNodeInfo root = h.getRootInActiveWindow();
                if (root == null || root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) continue;
                List<AccessibilityNodeInfo> all = nodes(root);
                StringBuilder text = new StringBuilder();
                for (AccessibilityNodeInfo n : all) text.append(deep(n).toLowerCase(Locale.US)).append('\n');
                if (!PROFILE_SCREEN.matcher(norm(text.toString())).find()) {
                    if (picked) return; // past it
                    // a few seconds of a real menu and no picker: it went straight in
                    if (all.size() > 5 && ++settled >= 4) return;
                    continue;
                }
                if (picked) continue; // still loading after the pick
                JSONArray names = new JSONArray();
                for (AccessibilityNodeInfo n : all) {
                    String t = deep(n);
                    if (!t.isEmpty() && t.length() < 40 && !PROFILE_SCREEN.matcher(norm(t.toLowerCase(Locale.US))).find()) names.put(t);
                }
                // who Ethan said to watch as this time, else his saved profile (Ethan, 22/11)
                String want = asked != null && !asked.trim().isEmpty() ? asked.trim() : profileFor(c, appName);
                if (want.isEmpty()) {
                    o.put("profilePicker", "it's on the profile picker and Ethan's profile isn't set (movie.apps {profile}). On screen: " + names);
                    return;
                }
                AccessibilityNodeInfo hit = null;
                for (AccessibilityNodeInfo n : all) {
                    String t = norm(deep(n).toLowerCase(Locale.US));
                    String w = norm(want.toLowerCase(Locale.US));
                    if (t.equals(w) || t.startsWith(w + ",") || t.startsWith(w + " profile") || t.startsWith(w + " ")) {
                        hit = n;
                        break;
                    }
                }
                if (hit == null) {
                    o.put("profilePicker", "it's on the profile picker and there's no " + want + " on it, so it stopped: ask Ethan who to watch as"
                            + " (then movie.open again with profile, or tv_stream press). On screen: " + names);
                    return;
                }
                if (!click(hit)) {
                    o.put("profilePicker", "found " + want + " on the profile picker but the app didn't take the press");
                    return;
                }
                picked = true;
                o.put("profile", "picked " + want);
                TvLog.line(c, "Movies: picked profile " + want + " on " + appName);
                Thread.sleep(3500);
                again.run(); // the movie's link again, apps drop it once you pick a profile
                until = System.currentTimeMillis() + 8000;
            }
        } catch (Exception ignored) {
        }
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

    // ---------------------------------------------------------------- bits

    static boolean streaming(Context c, String pkg) {
        return pkg != null && !pkg.isEmpty() && Movies.isStreaming(c, pkg);
    }

    /** A movie's actually playing: a seek bar on screen, or sound going with next to nothing on screen. */
    static boolean playing(Context c, AccessibilityNodeInfo root) {
        if (root == null) return false;
        int texts = 0;
        for (AccessibilityNodeInfo n : nodes(root)) {
            CharSequence cls = n.getClassName();
            if (cls != null && cls.toString().contains("SeekBar")) return true;
            if (!label(n).isEmpty()) texts++;
        }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        return am != null && am.isMusicActive() && texts < 4;
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
