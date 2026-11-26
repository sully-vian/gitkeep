package com.sully_vian.gitkeep;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.sully_vian.gitkeep.core.GitRepo;
import com.sully_vian.gitkeep.core.NoteStore;
import com.sully_vian.gitkeep.core.SyncManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jspecify.annotations.Nullable;

/** Where the notes live, their git repository, and the thread that reads and writes them. */
final class Notes {

    private static final Logger LOG = Logger.getLogger(Notes.class.getName());

    /**
     * Every file read or write of the app, git included, off the main thread. A
     * single thread for the whole app: tasks run one at a time, in the order they
     * were given, even across activities (a note saved when leaving the editor is
     * written and committed before the list reloads).
     */
    static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /**
     * The network calls of a sync (fetch, push), so that saving notes on
     * {@link #IO} never waits for the network.
     */
    static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();

    /** Opened on first use, then kept for the whole process. Only used on {@link #IO}. */
    private static @Nullable GitRepo git;

    /** Made with {@link #git} on first use. Only used on {@link #IO}. */
    private static @Nullable SyncManager syncManager;

    /** The sync running now, if any. Only used on the main thread. */
    private static @Nullable CompletableFuture<SyncManager.Result> currentSync;

    private Notes() {
    }

    /** The app's private notes folder: files/notes. */
    static Path dir(Context context) {
        return context.getFilesDir().toPath().resolve("notes");
    }

    static NoteStore store(Context context) {
        return new NoteStore(dir(context));
    }

    /**
     * The repository of the notes folder, opened or created on first use.
     * Call it on {@link #IO} only.
     */
    static GitRepo git(Context context) throws IOException, GitAPIException {
        GitRepo repo = git;
        if (repo == null) {
            repo = GitRepo.open(dir(context), Settings.authorName(context), Settings.authorEmail(context),
                    Clock.systemDefaultZone());
            git = repo;
        }
        return repo;
    }

    /**
     * Starts a sync with the remote of the settings, or returns the one running.
     * Its result is saved in {@link SyncStatus} before the future completes. The
     * future never fails. Call it on the main thread.
     */
    static CompletableFuture<SyncManager.Result> sync(Context context, SyncManager.Remote remote) {
        CompletableFuture<SyncManager.Result> running = currentSync;
        if (running != null && !running.isDone()) {
            return running;
        }
        Context app = context.getApplicationContext();
        CompletableFuture<SyncManager.Result> started;
        if (!isOnline(app)) {
            LOG.info("sync skipped: offline");
            started = CompletableFuture.completedFuture(new SyncManager.Result(
                    SyncManager.Outcome.OFFLINE, false, new ArrayList<>(), ""));
        } else {
            started = CompletableFuture
                    .supplyAsync(() -> syncManager(app), IO)
                    .thenCompose(manager -> manager.sync(remote))
                    .exceptionally(error -> {
                        LOG.log(Level.SEVERE, "sync not started", error);
                        return new SyncManager.Result(SyncManager.Outcome.ERROR, false, new ArrayList<>(),
                                "repository not opened");
                    });
        }
        CompletableFuture<SyncManager.Result> saved = started.thenApply(result -> {
            SyncStatus.save(app, result);
            return result;
        });
        currentSync = saved;
        return saved;
    }

    /** The sync running now, or null. Call it on the main thread. */
    static @Nullable CompletableFuture<SyncManager.Result> runningSync() {
        CompletableFuture<SyncManager.Result> running = currentSync;
        return running == null || running.isDone() ? null : running;
    }

    /** On {@link #IO} only. */
    private static SyncManager syncManager(Context context) {
        SyncManager manager = syncManager;
        if (manager == null) {
            try {
                manager = new SyncManager(git(context), IO, NETWORK);
            } catch (IOException | GitAPIException e) {
                throw new CompletionException(e);
            }
            syncManager = manager;
        }
        return manager;
    }

    /** Whether the phone has a network that reaches the internet. */
    private static boolean isOnline(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            return true;
        }
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Commits whatever changed in the notes folder. A failure is only logged:
     * the notes are saved anyway, and the next commit takes the changes. Call it
     * on {@link #IO} only.
     */
    static void commit(Context context) {
        try {
            git(context).commit();
        } catch (IOException | GitAPIException | RuntimeException e) {
            LOG.log(Level.SEVERE, "changes not committed", e);
        }
    }
}
