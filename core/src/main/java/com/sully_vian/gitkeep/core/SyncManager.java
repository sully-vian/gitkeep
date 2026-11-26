package com.sully_vian.gitkeep.core;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
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
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
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
    private static final String TRACKING_PREFIX = Constants.R_REMOTES + REMOTE + "/";

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

    /** What the fetch step leaves for the merge step: the remote's default branch, null when it has none. */
    private record Fetched(@Nullable String defaultBranch) {
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
                .thenComposeAsync(firstConnect -> fetchMergePush(credentials, firstConnect, false), network)
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
     * Runs on io: commits what's pending, and points the remote at the URL. A new
     * URL is another remote: what was known of the old one is forgotten.
     *
     * @return whether this is the first sync with this remote
     */
    private Boolean prepare(String url) {
        try {
            if (hasUserInfo(url)) {
                throw new SyncFailure(Result.of(Outcome.ERROR,
                        "put the username and token in their own fields, not in the URL"));
            }
            repo.commit();
            Repository repository = repository();
            StoredConfig config = repository.getConfig();
            if (!url.equals(config.getString("remote", REMOTE, "url"))) {
                forgetRemote(repository);
                config.setString("remote", REMOTE, "url", url);
                config.setString("remote", REMOTE, "fetch", "+refs/heads/*:refs/remotes/" + REMOTE + "/*");
                config.save();
                LOG.info("remote set to " + host(url));
            }
            return repository.getRefDatabase().getRefsByPrefix(TRACKING_PREFIX).isEmpty();
        } catch (IOException | GitAPIException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Network, then io, then network: fetch, merge, push. A push refused
     * because the remote moved since the fetch is tried once more.
     */
    private CompletableFuture<Result> fetchMergePush(CredentialsProvider credentials, boolean firstConnect,
            boolean retry) {
        return CompletableFuture.supplyAsync(() -> fetch(credentials), network)
                .thenApplyAsync(fetched -> merge(firstConnect, fetched.defaultBranch()), io)
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
                    return fetchMergePush(credentials, false, true).thenApply(again -> merged.changed() && !again.changed()
                            ? new Result(again.outcome(), true, again.conflicts(), again.detail())
                            : again);
                }, network);
    }

    /** Runs on network. */
    private Fetched fetch(CredentialsProvider credentials) {
        try {
            FetchResult result = Git.wrap(repository()).fetch()
                    .setRemote(REMOTE)
                    .setCredentialsProvider(credentials)
                    .setRemoveDeletedRefs(true)
                    .setTimeout(TIMEOUT_S)
                    .call();
            LOG.info("fetched");
            return new Fetched(defaultBranch(result.getAdvertisedRefs()));
        } catch (GitAPIException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Runs on io: brings the remote branch into the local one, as a fast-forward
     * when the local one has nothing new, else as a merge commit by the app's
     * author. On the first sync with a remote, connects to it first.
     *
     * @param remoteDefault the remote's default branch; null when it has none
     */
    private Merged merge(boolean firstConnect, @Nullable String remoteDefault) {
        try {
            Repository repository = repository();
            // a note saved while fetching: committed, so that the merge, or its
            // undoing after a conflict, never touches an uncommitted file
            repo.commit();
            ObjectId before = repository.resolve(Constants.HEAD);
            if (firstConnect) {
                connect(repository, remoteDefault);
            }
            String branch = branch(repository);
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
                } else if (status == MergeResult.MergeStatus.CONFLICTING) {
                    List<String> conflicts = new ArrayList<>(Git.wrap(repository).status().call().getConflicting());
                    Collections.sort(conflicts);
                    undoMerge(repository);
                    LOG.warning("conflict in " + conflicts + ": merge undone");
                    throw new SyncFailure(new Result(Outcome.CONFLICT, false, conflicts, ""));
                } else if (status != MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                    undoMerge(repository);
                    throw new SyncFailure(Result.of(Outcome.ERROR, "merge " + status.toString().toLowerCase(Locale.ROOT)));
                }
            }
            if (firstConnect) {
                // the remote's files may lack them, after taking the remote's files
                repo.writeMissingSetupFiles();
                repo.commit();
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

    /**
     * The first sync with a remote: the local branch takes the name of the
     * remote's default branch, so that both sides talk about the same branch. A
     * phone with nothing of its own yet (only the setup commit's files) takes the
     * remote's files as they are, instead of merging two histories; otherwise the
     * two histories are merged, by the caller.
     */
    private void connect(Repository repository, @Nullable String remoteDefault) throws IOException, GitAPIException {
        String branch = branch(repository);
        if (remoteDefault == null) {
            LOG.info("first sync, the remote is empty");
            setUpstream(repository, branch);
            return;
        }
        Git git = Git.wrap(repository);
        if (!branch.equals(remoteDefault)) {
            git.branchRename().setOldName(branch).setNewName(remoteDefault).call();
            LOG.info("branch renamed to " + remoteDefault + ", as on the remote");
        }
        setUpstream(repository, remoteDefault);
        if (onlySetupFiles(repository)) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(trackingRef(remoteDefault)).call();
            LOG.info("first sync, nothing local yet: took the remote's files");
        } else {
            LOG.info("first sync, files on both sides: merging");
        }
    }

    /**
     * Back to the last commit, as before the merge: everything was committed just
     * before it, so nothing of the phone's is lost.
     */
    static void undoMerge(Repository repository) throws GitAPIException {
        Git.wrap(repository).reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();
    }

    /** Whether the last commit holds nothing but the setup commit's files. */
    private static boolean onlySetupFiles(Repository repository) throws IOException {
        ObjectId tree = repository.resolve(Constants.HEAD + "^{tree}");
        if (tree == null) {
            return true;
        }
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(tree);
            walk.setRecursive(false);
            while (walk.next()) {
                String path = walk.getPathString();
                if (!path.equals(GitRepo.ATTRIBUTES) && !path.equals(GitRepo.IGNORE)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** So that git on a computer, given this repository, knows what to pull. */
    private static void setUpstream(Repository repository, String branch) throws IOException {
        StoredConfig config = repository.getConfig();
        config.setString("branch", branch, "remote", REMOTE);
        config.setString("branch", branch, "merge", Constants.R_HEADS + branch);
        config.save();
    }

    /** Deletes the remote-tracking branches and upstream settings of the old remote. */
    private static void forgetRemote(Repository repository) throws IOException {
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(TRACKING_PREFIX)) {
            RefUpdate delete = repository.updateRef(ref.getName());
            delete.setForceUpdate(true);
            RefUpdate.Result result = delete.delete();
            if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
                throw new IOException("old remote branch not deleted: " + result);
            }
        }
        StoredConfig config = repository.getConfig();
        for (String branch : config.getSubsections("branch")) {
            config.unsetSection("branch", branch);
        }
    }

    /**
     * The remote's default branch, from what it advertised: the branch its HEAD
     * points to; when that can't be told, "main", "master", or the only branch.
     * Null when the remote has no branch at all.
     */
    static @Nullable String defaultBranch(Collection<Ref> advertised) {
        List<String> branches = new ArrayList<>();
        ObjectId headId = null;
        String headTarget = null;
        for (Ref ref : advertised) {
            if (ref.getName().equals(Constants.HEAD)) {
                headId = ref.getObjectId();
                if (ref.isSymbolic()) {
                    headTarget = ref.getTarget().getName();
                }
            } else if (ref.getName().startsWith(Constants.R_HEADS)) {
                branches.add(ref.getName().substring(Constants.R_HEADS.length()));
            }
        }
        if (branches.isEmpty()) {
            return null;
        }
        if (headTarget != null && headTarget.startsWith(Constants.R_HEADS)) {
            return headTarget.substring(Constants.R_HEADS.length());
        }
        Collections.sort(branches);
        List<String> atHead = new ArrayList<>();
        for (Ref ref : advertised) {
            if (ref.getName().startsWith(Constants.R_HEADS) && headId != null && headId.equals(ref.getObjectId())) {
                atHead.add(ref.getName().substring(Constants.R_HEADS.length()));
            }
        }
        List<String> candidates = atHead.isEmpty() ? branches : atHead;
        for (String preferred : new String[] {"main", "master"}) {
            if (candidates.contains(preferred)) {
                return preferred;
            }
        }
        Collections.sort(candidates);
        return candidates.get(0);
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
        return TRACKING_PREFIX + branch;
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
