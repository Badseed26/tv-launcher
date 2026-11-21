package com.ethan.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * Live weather for the home screen (Ethan, 21/11), from Open-Meteo (free, no key). The place is set with
 * "weather <town>" on the Todoist remote and kept in the app's own storage, so where the TV lives never goes in
 * this public repo. Nothing shows until a place is set. Refreshes every 15 min while the home screen's up.
 */
final class Weather {

    private static final String PREFS = "weather";
    private static final long EVERY = 15 * 60 * 1000;
    private static volatile boolean busy;

    private Weather() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasPlace(Context c) {
        return p(c).contains("lat");
    }

    static String place(Context c) {
        return p(c).getString("place", "");
    }

    /** Big line, small line, e.g. "24°" and "Partly cloudy  |  High 29°  Low 18°  |  Rain 40%". Null when there's nothing yet. */
    static String[] lines(Context c) {
        SharedPreferences s = p(c);
        if (!s.contains("temp")) return null;
        return new String[] {s.getString("temp", ""), s.getString("more", "")};
    }

    /** Background thread. Finds a town in Australia and keeps it. Returns what to tell Ethan. */
    static String setPlace(Context c, String town) {
        try {
            String j = Http.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&countryCode=AU&name="
                    + URLEncoder.encode(town, "UTF-8"), 15000);
            JSONArray r = new JSONObject(j).optJSONArray("results");
            if (r == null || r.length() == 0) return "Couldn't find " + town + " in Australia, nothing changed. Try a bigger town nearby.";
            JSONObject o = r.getJSONObject(0);
            String name = o.optString("name") + (o.has("admin1") ? ", " + o.optString("admin1") : "");
            p(c).edit().putFloat("lat", (float) o.getDouble("latitude")).putFloat("lon", (float) o.getDouble("longitude"))
                    .putString("place", name).remove("at").apply();
            refresh(c, true);
            String[] l = lines(c);
            return "Weather set to " + name + "." + (l != null ? " Now: " + l[0] + " " + l[1] : "");
        } catch (Exception e) {
            return "Couldn't look up " + town + ": " + e.getMessage();
        }
    }

    static void clear(Context c) {
        p(c).edit().clear().apply();
    }

    /** True when it's due (15 min) and a place is set. */
    static boolean due(Context c) {
        return hasPlace(c) && !busy && System.currentTimeMillis() - p(c).getLong("at", 0) > EVERY;
    }

    /** Background thread. Fetches the weather now if it's due (or forced). Returns true when it got new numbers. */
    static boolean refresh(Context c, boolean force) {
        if (!hasPlace(c) || busy || (!force && !due(c))) return false;
        busy = true;
        try {
            SharedPreferences s = p(c);
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                            + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                            + "&timezone=auto&forecast_days=1",
                    s.getFloat("lat", 0), s.getFloat("lon", 0));
            JSONObject o = new JSONObject(Http.get(url, 15000));
            JSONObject cur = o.getJSONObject("current");
            JSONObject day = o.getJSONObject("daily");
            long t = Math.round(cur.getDouble("temperature_2m"));
            long feels = Math.round(cur.optDouble("apparent_temperature", t));
            String what = describe(cur.optInt("weather_code", -1), cur.optInt("is_day", 1) == 1);
            long hi = Math.round(day.getJSONArray("temperature_2m_max").getDouble(0));
            long lo = Math.round(day.getJSONArray("temperature_2m_min").getDouble(0));
            JSONArray rain = day.optJSONArray("precipitation_probability_max");
            int r = rain != null && !rain.isNull(0) ? rain.getInt(0) : -1;
            StringBuilder more = new StringBuilder(what);
            if (Math.abs(feels - t) >= 2) more.append(", feels like ").append(feels).append("°");
            more.append("   High ").append(hi).append("°  Low ").append(lo).append("°");
            if (r >= 0) more.append("   Rain ").append(r).append("%");
            s.edit().putString("temp", t + "°").putString("more", more.toString())
                    .putLong("at", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            // Try again in 5 min, not straight away
            p(c).edit().putLong("at", System.currentTimeMillis() - EVERY + 5 * 60 * 1000).apply();
            return false;
        } finally {
            busy = false;
        }
    }

    /** Open-Meteo's WMO weather codes in plain words. */
    static String describe(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly sunny" : "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Foggy";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: return "Light showers";
            case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Storms";
            case 96: case 99: return "Storms with hail";
            default: return "";
        }
    }
}
