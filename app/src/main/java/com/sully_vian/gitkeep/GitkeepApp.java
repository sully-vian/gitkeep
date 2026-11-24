package com.sully_vian.gitkeep;

import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.logging.Logger;

/** Runs once when the app's process starts, before any screen. */
public class GitkeepApp extends Application {

    private static final Logger LOG = Logger.getLogger(GitkeepApp.class.getName());

    @Override
    public void onCreate() {
        super.onCreate();
        Logs.init(this);
        LOG.info("app started, version " + versionName() + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
}
