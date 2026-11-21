package com.ethan.tvlauncher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * The sites Claude can drive on the TV with web.* jobs (21/11), and their saved logins.
 * Logins go in from the phone page (Saved logins) into the Keystore (Secrets) and only the TV app ever reads them back:
 * it fills a sign in page itself, the job code never sees them.
 */
final class Sites {
    private Sites() {}

    /** name, label, host (regex), sign in page */
    static final String[][] LIST = {
            {"cnw", "CNW", "^(shop\\.|www\\.)?cnw\\.com\\.au$", "https://shop.cnw.com.au/login"},
            {"tradezone", "Tradezone", "^(www\\.)?tradezone\\.com\\.au$", "https://www.tradezone.com.au/customer/account/login/"},
            {"ambrose", "Ambrose Tradies Web", "^tradies\\.ambrosebuilding\\.com\\.au$", "https://tradies.ambrosebuilding.com.au/"},
    };

    /** Never opened on the TV (Ethan, 21/11: BridgeSelect's off until he's shown Claude how it's done). */
    static final Pattern BLOCKED = Pattern.compile("(^|\\.)(bridgeselect\\.com\\.au|rectraders\\.com\\.au|bridgeselect\\.com)$|bridgeselect|rectraders",
            Pattern.CASE_INSENSITIVE);

    static String[] byName(String name) {
        for (String[] s : LIST) if (s[0].equals(name)) return s;
        return null;
    }

    static String[] byHost(String host) {
        if (host == null) return null;
        for (String[] s : LIST) if (Pattern.compile(s[2], Pattern.CASE_INSENSITIVE).matcher(host).find()) return s;
        return null;
    }

    /** Sites a web.* job may start on: these plus AroFlo. Outlook has its own read only jobs. */
    static boolean startOk(String host) {
        if (host == null || BLOCKED.matcher(host).find()) return false;
        return host.equals("office.aroflo.com") || byHost(host) != null;
    }

    static String userKey(String name) {
        return "site_" + name + "_user";
    }

    static String passKey(String name) {
        return "site_" + name + "_pass";
    }

    static boolean saved(Context c, String name) {
        return Secrets.has(c, passKey(name));
    }

    static void save(Context c, String name, String user, String pass) throws Exception {
        Secrets.put(c, userKey(name), user == null ? "" : user);
        Secrets.put(c, passKey(name), pass);
    }

    static void clear(Context c, String name) {
        Secrets.remove(c, userKey(name));
        Secrets.remove(c, passKey(name));
    }

    /** For the phone page: which sites have a login saved (never the login itself). */
    static JSONArray list(Context c) {
        JSONArray a = new JSONArray();
        for (String[] s : LIST) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", s[0]);
                o.put("label", s[1]);
                o.put("saved", saved(c, s[0]));
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        return a;
    }
}
