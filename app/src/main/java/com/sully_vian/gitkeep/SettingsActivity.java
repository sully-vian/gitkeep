package com.sully_vian.gitkeep;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jspecify.annotations.Nullable;

/** The settings: the remote repository and its credentials, and the author of the commits. */
public class SettingsActivity extends AppCompatActivity {

    private static final Logger LOG = Logger.getLogger(SettingsActivity.class.getName());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            setPreferencesFromResource(R.xml.settings, rootKey);
            Context app = requireContext().getApplicationContext();

            EditTextPreference url = findPreference(Settings.URL);
            if (url != null) {
                url.setOnBindEditTextListener(field -> field.setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
            }
            EditTextPreference email = findPreference(Settings.AUTHOR_EMAIL);
            if (email != null) {
                email.setOnBindEditTextListener(field -> field.setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
            }

            EditTextPreference token = findPreference(Settings.TOKEN);
            if (token != null) {
                // never in the default preferences: in the Keystore-encrypted store
                token.setPreferenceDataStore(new PreferenceDataStore() {
                    @Override
                    public void putString(String key, @Nullable String value) {
                        TokenStore.set(app, value == null ? "" : value.trim());
                    }

                    @Override
                    public @Nullable String getString(String key, @Nullable String defaultValue) {
                        String saved = TokenStore.get(app);
                        return saved == null ? defaultValue : saved;
                    }
                });
                token.setOnBindEditTextListener(field -> field.setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                // whether one is saved, never the token itself
                token.setSummaryProvider(preference -> getString(
                        TokenStore.get(app) == null ? R.string.token_not_set : R.string.token_set));
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
            if (prefs != null) {
                prefs.registerOnSharedPreferenceChangeListener(this);
            }
        }

        @Override
        public void onPause() {
            super.onPause();
            SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
            if (prefs != null) {
                prefs.unregisterOnSharedPreferenceChangeListener(this);
            }
        }

        /** A new author signs the next commits. */
        @Override
        public void onSharedPreferenceChanged(@Nullable SharedPreferences prefs, @Nullable String key) {
            if (!Settings.AUTHOR_NAME.equals(key) && !Settings.AUTHOR_EMAIL.equals(key)) {
                return;
            }
            Context app = requireContext().getApplicationContext();
            String name = Settings.authorName(app);
            String email = Settings.authorEmail(app);
            Notes.IO.execute(() -> {
                try {
                    Notes.git(app).setAuthor(name, email);
                    LOG.info("author changed");
                } catch (IOException | GitAPIException | RuntimeException e) {
                    LOG.log(Level.SEVERE, "author not changed", e);
                }
            });
        }
    }
}
