package com.sully_vian.gitkeep;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.jspecify.annotations.Nullable;

/**
 * The access token of the git host, encrypted with a key of the Android
 * Keystore. The key never leaves the Keystore (on most phones, a secure chip),
 * and only this app can use it; what is saved in the app's files is useless
 * without it.
 *
 * <p>
 * Logs never hold the token, only whether something went wrong.
 */
final class TokenStore {

    private static final Logger LOG = Logger.getLogger(TokenStore.class.getName());

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "gitkeep-token";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    /** Not the default preferences: nothing here is shown by the settings screen. */
    private static final String FILE = "secrets";
    private static final String KEY_IV = "token_iv";
    private static final String KEY_TOKEN = "token";

    private TokenStore() {
    }

    /**
     * The token, or null when there's none, or when it can't be decrypted (e.g.
     * the app's files were restored on another phone, without the key): it then
     * has to be typed again.
     */
    static @Nullable String get(Context context) {
        SharedPreferences prefs = prefs(context);
        String iv = prefs.getString(KEY_IV, null);
        String encrypted = prefs.getString(KEY_TOKEN, null);
        if (iv == null || encrypted == null) {
            return null;
        }
        try {
            SecretKey key = existingKey();
            if (key == null) {
                LOG.warning("token not readable: no key");
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] token = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP));
            return new String(token, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // the exception names the problem, never the token
            LOG.log(Level.WARNING, "token not readable", e);
            return null;
        }
    }

    /** Saves the token, encrypted; an empty one deletes it. */
    static void set(Context context, String token) {
        if (token.isEmpty()) {
            prefs(context).edit().remove(KEY_IV).remove(KEY_TOKEN).apply();
            LOG.info("token deleted");
            return;
        }
        try {
            SecretKey key = existingKey();
            if (key == null) {
                key = newKey();
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            // a new random IV each time, chosen by the Keystore
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            prefs(context).edit()
                    .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .apply();
            LOG.info("token saved");
        } catch (GeneralSecurityException e) {
            LOG.log(Level.SEVERE, "token not saved", e);
            throw new IllegalStateException("token not saved", e);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static @Nullable SecretKey existingKey() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        try {
            store.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        return entry instanceof KeyStore.SecretKeyEntry secret ? secret.getSecretKey() : null;
    }

    private static SecretKey newKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
