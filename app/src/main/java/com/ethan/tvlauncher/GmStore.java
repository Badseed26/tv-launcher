package com.ethan.tvlauncher;

import android.content.Context;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * The filer script's saved values (what Tampermonkey's GM_getValue / GM_setValue keep), shared by the
 * Outlook and AroFlo pages like two Chrome tabs share them. One small file per key, held in memory too.
 */
final class GmStore {

    private static GmStore inst;

    static synchronized GmStore get(Context c) {
        if (inst == null) inst = new GmStore(new File(c.getApplicationContext().getFilesDir(), "gm"));
        return inst;
    }

    private final File dir;
    private final Map<String, String> cache = new HashMap<>();
    private boolean loaded;

    private GmStore(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try {
                String all = read(f);
                int nl = all.indexOf('\n');
                if (nl > 0) cache.put(all.substring(0, nl), all.substring(nl + 1));
            } catch (Exception ignored) {
            }
        }
    }

    /** Value as JSON text, or null. */
    synchronized String getJson(String key) {
        load();
        return cache.get(key);
    }

    synchronized void setJson(String key, String json) {
        load();
        cache.put(key, json);
        try {
            File tmp = new File(dir, name(key) + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            out.write((key + "\n" + json).getBytes(StandardCharsets.UTF_8));
            out.close();
            tmp.renameTo(new File(dir, name(key)));
        } catch (Exception ignored) {
        }
    }

    synchronized void delete(String key) {
        load();
        cache.remove(key);
        new File(dir, name(key)).delete();
    }

    synchronized String listJson() {
        load();
        JSONArray a = new JSONArray();
        for (String k : cache.keySet()) a.put(k);
        return a.toString();
    }

    private static String name(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private static String read(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int off = 0, n;
        while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
        in.close();
        return new String(buf, 0, off, StandardCharsets.UTF_8);
    }
}
