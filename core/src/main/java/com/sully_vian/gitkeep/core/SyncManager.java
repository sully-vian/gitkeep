package com.sully_vian.gitkeep.core;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;

/**
 * Syncs the notes repository with a remote: commit, fetch, merge, push.
 *
 * <p>
 * The steps that use the network (fetch, push) run on the network executor,
 * the steps that touch the notes folder (commit, merge) on the I/O executor,
 * the same one that saves the notes: saving never waits for the network, and a
 * merge never runs while a note is being written.
 *
 * <p>
 * One sync at a time: a sync asked while one runs gets the running one's result.
 *
 * <p>
 * Logs name the remote's host only, never the token.
 */
public final class SyncManager {

    private static final Logger LOG = Logger.getLogger(SyncManager.class.getName());

    static final String REMOTE = Constants.DEFAULT_REMOTE_NAME;
    static final String MERGE_MESSAGE = "Merge remote changes";

    /** Seconds before a fetch or a push without an answer gives up. */
    private static final int TIMEOUT_S = 30;

    /** Where to sync, and with which credentials. */
    public record Remote(String url, String username, String token) {

        /** Without the token, so that it never ends up in a log. */
        @Override
        public String toString() {
            return "Remote[" + host(url) + "]";
        }
    }

    /** What a sync ended with. */
    public enum Outcome {
        OK,
        /** No network, or the host can't be reached. */
        OFFLINE,
        /** The host refused the username or token. */
        AUTH,
        /** Both sides changed the same file; nothing was merged nor pushed. */
        CONFLICT,
        ERROR
    }

    /**
     * @param outcome   how it ended
     * @param changed   whether the files of the notes folder changed (the list
     *                  should be reloaded)
     * @param conflicts the files in conflict, for {@link Outcome#CONFLICT}
     * @param detail    a short explanation, for {@link Outcome#ERROR}; never
     *                  holds the token
     */
    public record Result(Outcome outcome, boolean changed, List<String> conflicts, String detail) {

        public Result {
            conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
        }

        static Result ok(boolean changed) {
            return new Result(Outcome.OK, changed, new ArrayList<>(), "");
        }

        static Result of(Outcome outcome, String detail) {
            return new Result(outcome, false, new ArrayList<>(), detail);
        }
    }

    /** Thrown inside a sync to end it with a given result. */
    private static final class SyncFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final Result result;

        SyncFailure(Result result) {
            super(result.outcome() + ": " + result.detail());
            this.result = result;
        }
    }

    /** What the merge step leaves for the push step. */
    private record Merged(String branch, boolean changed, boolean ahead) {
    }

    private final GitRepo repo;
    private final Executor io;
    private final Executor network;

    /** The sync running now, null when there's none. Guarded by this. */
    private @Nullable CompletableFuture<Result> running;

    public SyncManager(GitRepo repo, Executor io, Executor network) {
        this.repo = repo;
        this.io = io;
        this.network = network;
    }

    /**
     * Starts a sync, or returns the one running. The future never fails: errors
     * are in its {@link Result}.
     */
    public synchronized CompletableFuture<Result> sync(Remote remote) {
        CompletableFuture<Result> current = running;
        if (current != null) {
            return current;
        }
        LOG.info("sync with " + host(remote.url()));
        CompletableFuture<Result> started = new CompletableFuture<>();
        // set before the steps run: with executors that run tasks at once, they
        // could otherwise end before this is set, and it would never be cleared
        running = started;
        CredentialsProvider credentials = new UsernamePasswordCredentialsProvider(remote.username(), remote.token());
        CompletableFuture
                .supplyAsync(() -> prepare(remote.url()), io)
                .thenComposeAsync(nothing -> fetchMergePush(credentials, false), network)
                .handle((result, error) -> error != null ? failure(error) : Objects.requireNonNull(result))
                .thenAccept(result -> {
                    done(result);
                    started.complete(result);
                });
        return started;
    }

    private synchronized void done(Result result) {
        running = null;
        LOG.info("sync ended: " + result.outcome());
    }

    /**
     * Runs on io: commits what's pending, and points the remote at the URL.
     */
    private Void prepare(String url) {
        try {
            if (hasUserInfo(url)) {
                throw new SyncFailure(Result.of(Outcome.ERROR,
                        "put the username and token in their own fields, not in the URL"));
            }
            repo.commit();
            StoredConfig config = repository().getConfig();
            if (!url.equals(config.getString("remote", REMOTE, "url"))) {
                config.setString("remote", REMOTE, "url", url);
                config.setString("remote", REMOTE, "fetch", "+refs/heads/*:refs/remotes/" + REMOTE + "/*");
                config.save();
            }
            return null;
        } catch (IOException | GitAPIException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Network, then io, then network: fetch, merge, push. A push refused
     * because the remote moved since the fetch is tried once more.
     */
    private CompletableFuture<Result> fetchMergePush(CredentialsProvider credentials, boolean retry) {
        return CompletableFuture.runAsync(() -> fetch(credentials), network)
                .thenApplyAsync(nothing -> merge(), io)
                .thenComposeAsync(merged -> {
                    if (!merged.ahead()) {
                        return CompletableFuture.completedFuture(Result.ok(merged.changed()));
                    }
                    if (push(merged.branch(), credentials)) {
                        return CompletableFuture.completedFuture(Result.ok(merged.changed()));
                    }
                    if (retry) {
                        throw new SyncFailure(Result.of(Outcome.ERROR, "the remote keeps changing, sync again"));
                    }
                    LOG.info("push refused, the remote moved: once more");
                    // the first merge's changes count too
                    return fetchMergePush(credentials, true).thenApply(again -> merged.changed() && !again.changed()
                            ? new Result(again.outcome(), true, again.conflicts(), again.detail())
                            : again);
                }, network);
    }

    /** Runs on network. */
    private void fetch(CredentialsProvider credentials) {
        try {
            Git.wrap(repository()).fetch()
                    .setRemote(REMOTE)
                    .setCredentialsProvider(credentials)
                    .setRemoveDeletedRefs(true)
                    .setTimeout(TIMEOUT_S)
                    .call();
            LOG.info("fetched");
        } catch (GitAPIException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Runs on io: brings the remote branch into the local one, as a fast-forward
     * when the local one has nothing new, else as a merge commit by the app's
     * author.
     */
    private Merged merge() {
        try {
            Repository repository = repository();
            String branch = branch(repository);
            ObjectId before = repository.resolve(Constants.HEAD);
            Ref tracking = repository.exactRef(trackingRef(branch));
            ObjectId theirs = tracking == null ? null : tracking.getObjectId();
            if (tracking != null && theirs != null && !theirs.equals(before)) {
                MergeResult result = Git.wrap(repository).merge()
                        .include(tracking)
                        .setFastForward(MergeCommand.FastForwardMode.FF)
                        // committed below, as the app's author
                        .setCommit(false)
                        .setMessage(MERGE_MESSAGE)
                        .call();
                MergeResult.MergeStatus status = result.getMergeStatus();
                if (status == MergeResult.MergeStatus.MERGED_NOT_COMMITTED) {
                    PersonIdent ident = repo.ident();
                    Git.wrap(repository).commit()
                            .setMessage(MERGE_MESSAGE)
                            .setAuthor(ident)
                            .setCommitter(ident)
                            .setSign(Boolean.FALSE)
                            .call();
                    LOG.info("merged");
                } else if (status == MergeResult.MergeStatus.FAST_FORWARD) {
                    LOG.info("fast-forwarded");
                } else if (status != MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                    throw new SyncFailure(Result.of(Outcome.ERROR, "merge " + status.toString().toLowerCase(Locale.ROOT)));
                }
            }
            ObjectId after = repository.resolve(Constants.HEAD);
            boolean changed = after != null && !after.equals(before);
            boolean ahead = after != null && !after.equals(trackingId(repository, branch));
            return new Merged(branch, changed, ahead);
        } catch (IOException | GitAPIException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Runs on network.
     *
     * @return false when the remote refused because it moved since the fetch
     */
    private boolean push(String branch, CredentialsProvider credentials) {
        try {
            String ref = Constants.R_HEADS + branch;
            Iterable<PushResult> results = Git.wrap(repository()).push()
                    .setRemote(REMOTE)
                    .setRefSpecs(new RefSpec(ref + ":" + ref))
                    .setCredentialsProvider(credentials)
                    .setTimeout(TIMEOUT_S)
                    .call();
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    RemoteRefUpdate.Status status = update.getStatus();
                    if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD
                            || status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                        return false;
                    }
                    if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                        String message = update.getMessage();
                        throw new SyncFailure(Result.of(Outcome.ERROR, "push refused: "
                                + status.toString().toLowerCase(Locale.ROOT)
                                + (message == null ? "" : " (" + message + ")")));
                    }
                }
            }
            LOG.info("pushed " + branch);
            return true;
        } catch (GitAPIException e) {
            throw new CompletionException(e);
        }
    }

    private Repository repository() {
        return repo.repository();
    }

    /** The local branch: HEAD always names one in this repository. */
    static String branch(Repository repository) throws IOException {
        String branch = repository.getBranch();
        if (branch == null) {
            throw new IOException("no HEAD");
        }
        return branch;
    }

    /** Where the remote's branch was at the last fetch or push; null when never seen. */
    static @Nullable ObjectId trackingId(Repository repository, String branch) throws IOException {
        Ref ref = repository.exactRef(trackingRef(branch));
        return ref == null ? null : ref.getObjectId();
    }

    private static String trackingRef(String branch) {
        return Constants.R_REMOTES + REMOTE + "/" + branch;
    }

    /** Sorts an error into an outcome the user can act on. */
    private static Result failure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        if (cause instanceof SyncFailure failure) {
            LOG.warning("sync failed: " + failure.result.outcome() + " " + failure.result.detail());
            return failure.result;
        }
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof NoRouteToHostException || t instanceof SocketTimeoutException) {
                LOG.log(Level.INFO, "sync failed: offline", cause);
                return Result.of(Outcome.OFFLINE, "");
            }
            String message = t.getMessage();
            if (message != null && isAuthMessage(message.toLowerCase(Locale.ROOT))) {
                LOG.log(Level.INFO, "sync failed: credentials refused", cause);
                return Result.of(Outcome.AUTH, "");
            }
        }
        LOG.log(Level.SEVERE, "sync failed", cause);
        String message = cause.getMessage();
        return Result.of(Outcome.ERROR, message == null ? cause.getClass().getSimpleName() : message);
    }

    /** JGit's messages when the host refuses the credentials (401, 403). */
    private static boolean isAuthMessage(String message) {
        return message.contains("not authorized")
                || message.contains("authentication is required")
                || message.contains("authentication not supported")
                || message.contains("403 forbidden");
    }

    /** The host of a URL, for logs: "github.com". */
    static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "local" : host;
        } catch (URISyntaxException e) {
            return "invalid URL";
        }
    }

    private static boolean hasUserInfo(String url) {
        try {
            return new URI(url).getUserInfo() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
