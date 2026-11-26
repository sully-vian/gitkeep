package com.sully_vian.gitkeep;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.sully_vian.gitkeep.core.SyncManager;
import org.jspecify.annotations.Nullable;

/**
 * What the settings screen saves. Everything but the token is in the default
 * preferences, in clear: none of it is secret. The token is in {@link TokenStore}.
 */
final class Settings {

    static final String URL = "remote_url";
    static final String USERNAME = "remote_username";
    static final String TOKEN = "remote_token";
    static final String AUTHOR_NAME = "author_name";
    static final String AUTHOR_EMAIL = "author_email";

    /** The author of the app's commits when none is set. */
    static final String DEFAULT_AUTHOR_NAME = "gitkeep";
    static final String DEFAULT_AUTHOR_EMAIL = "gitkeep@localhost";

    private Settings() {
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static String get(Context context, String key) {
        String value = prefs(context).getString(key, null);
        return value == null ? "" : value.trim();
    }

    static String authorName(Context context) {
        String name = get(context, AUTHOR_NAME);
        return name.isEmpty() ? DEFAULT_AUTHOR_NAME : name;
    }

    static String authorEmail(Context context) {
        String email = get(context, AUTHOR_EMAIL);
        return email.isEmpty() ? DEFAULT_AUTHOR_EMAIL : email;
    }

    /** Where to sync, or null until the URL, username and token are all set. */
    static SyncManager.@Nullable Remote remote(Context context) {
        String url = get(context, URL);
        String username = get(context, USERNAME);
        String token = TokenStore.get(context);
        if (url.isEmpty() || username.isEmpty() || token == null) {
            return null;
        }
        return new SyncManager.Remote(url, username, token);
    }
}
