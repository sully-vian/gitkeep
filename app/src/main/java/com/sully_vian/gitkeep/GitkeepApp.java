package com.sully_vian.gitkeep;

import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

/** Runs once when the app's process starts, before any screen. */
public class GitkeepApp extends Application {

    private static final Logger LOG = Logger.getLogger(GitkeepApp.class.getName());

    @Override
    public void onCreate() {
        super.onCreate();
        Logs.init(this);
        LOG.info("app started, version " + versionName() + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);
        // first task of the I/O thread: the repository is ready before any screen uses the notes
        Notes.IO.execute(() -> {
            try {
                Notes.git(this);
            } catch (IOException | GitAPIException | RuntimeException e) {
                LOG.log(Level.SEVERE, "repository not opened", e);
            }
        });
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
}
