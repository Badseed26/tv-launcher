package com.ethan.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Follow ups the TV raises on its own when something needs Ethan (signed out, AroFlo work waiting), and ticks off again
 * once it's sorted. One per problem: raising it again while it's open does nothing. They go in WRE follow ups >
 * Errors / to check, where the filer's own problems go, so they show on the dashboard.
 */
final class Alerts {
    private static final String PROJECT = "6hfHcM9Gf74fgcJw";
    private static final String SECTION = "6hmpxJ488W9GCXpP";

    private Alerts() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("alerts", Context.MODE_PRIVATE);
    }

    static boolean isUp(Context c, String key) {
        return prefs(c).contains(key);
    }

    /** Any thread. */
    static void raise(final Context ctx, final String key, final String title, final String desc) {
        final Context c = ctx.getApplicationContext();
        if (isUp(c, key)) return;
        prefs(c).edit().putString(key, "pending").apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = Secrets.get(c, Secrets.TODOIST_TOKEN);
                    if (token == null) throw new Exception("no Todoist token");
                    JSONObject b = new JSONObject();
                    b.put("content", title);
                    b.put("description", desc + "\nThe TV ticks this off itself once it's sorted.\ntv: " + key);
                    b.put("project_id", PROJECT);
                    b.put("section_id", SECTION);
                    b.put("priority", 4); // p1
                    b.put("due_string", "today");
                    JSONObject t = new JSONObject(Http.todoist(token, "POST", "tasks", b));
                    prefs(c).edit().putString(key, t.optString("id")).apply();
                    TvLog.line(c, "Follow up added: " + title);
                } catch (Exception e) {
                    prefs(c).edit().remove(key).apply(); // try again next time
                    TvLog.line(c, "Couldn't add the follow up (" + title + "): " + e.getMessage());
                }
            }
        }, "alert").start();
    }

    /** Any thread. Ticks the follow up off if this TV raised one. */
    static void clear(final Context ctx, final String key) {
        final Context c = ctx.getApplicationContext();
        final String id = prefs(c).getString(key, null);
        if (id == null || id.equals("pending")) return;
        prefs(c).edit().remove(key).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = Secrets.get(c, Secrets.TODOIST_TOKEN);
                    if (token != null) Http.todoist(token, "POST", "tasks/" + id + "/close", null);
                    TvLog.line(c, "Follow up ticked off: " + key);
                } catch (Exception e) {
                    TvLog.line(c, "Couldn't tick off the follow up " + key + ": " + e.getMessage());
                }
            }
        }, "alert").start();
    }
}
