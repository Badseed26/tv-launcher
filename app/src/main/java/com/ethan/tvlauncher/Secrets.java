package com.ethan.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Logins and keys, encrypted with a key that lives in the TV's Android Keystore.
 * The raw values never get written to disk.
 */
final class Secrets {

    static final String MS_USER = "ms_user";
    static final String MS_PASS = "ms_pass";
    static final String AROFLO_USER = "aroflo_user";
    static final String AROFLO_PASS = "aroflo_pass";
    static final String CLAUDE_KEY = "claude_key";
    static final String TODOIST_TOKEN = "todoist_token";

    static final String[] ALL = {MS_USER, MS_PASS, AROFLO_USER, AROFLO_PASS, CLAUDE_KEY, TODOIST_TOKEN};

    private static final String ALIAS = "filer_secrets";
    private static final String PREFS = "secrets";

    private Secrets() {}

    static synchronized void put(Context c, String name, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ct, Base64.NO_WRAP);
        prefs(c).edit().putString(name, stored).commit();
    }

    static synchronized String get(Context c, String name) {
        String stored = prefs(c).getString(name, null);
        if (stored == null) return null;
        try {
            String[] parts = stored.split(":");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean has(Context c, String name) {
        return prefs(c).contains(name);
    }

    static synchronized void clear(Context c) {
        prefs(c).edit().clear().commit();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
    }
}
