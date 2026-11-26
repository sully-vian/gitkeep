package com.sully_vian.gitkeep;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateUtils;
import com.sully_vian.gitkeep.core.SyncManager;

/**
 * How the last sync ended, kept across restarts, and the status line that tells
 * it: "Synced 2 minutes ago", "Offline", "Error: …".
 */
final class SyncStatus {

    private static final String FILE = "sync";
    private static final String LAST_OK = "last_ok";
    private static final String OUTCOME = "outcome";
    private static final String DETAIL = "detail";

    private SyncStatus() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static void save(Context context, SyncManager.Result result) {
        SharedPreferences.Editor edit = prefs(context).edit()
                .putString(OUTCOME, result.outcome().name())
                .putString(DETAIL, detail(result));
        if (result.outcome() == SyncManager.Outcome.OK) {
            edit.putLong(LAST_OK, System.currentTimeMillis());
        }
        edit.apply();
    }

    private static String detail(SyncManager.Result result) {
        if (result.outcome() == SyncManager.Outcome.CONFLICT) {
            return String.join(", ", result.conflicts());
        }
        return result.detail();
    }

    /** The status line, for the list's app bar. */
    static String line(Context context, boolean syncing) {
        if (syncing) {
            return context.getString(R.string.sync_running);
        }
        if (Settings.remote(context) == null) {
            return context.getString(R.string.sync_not_set_up);
        }
        SharedPreferences prefs = prefs(context);
        String outcome = prefs.getString(OUTCOME, null);
        String detail = prefs.getString(DETAIL, "");
        long lastOk = prefs.getLong(LAST_OK, 0);
        if (outcome == null) {
            return context.getString(R.string.sync_never);
        }
        switch (SyncManager.Outcome.valueOf(outcome)) {
            case OK:
                return context.getString(R.string.sync_ok, DateUtils.getRelativeTimeSpanString(lastOk,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            case OFFLINE:
                return context.getString(R.string.sync_offline);
            case AUTH:
                return context.getString(R.string.sync_auth);
            case CONFLICT:
                return context.getString(R.string.sync_conflict, detail);
            default:
                return context.getString(R.string.sync_error, detail);
        }
    }
}
