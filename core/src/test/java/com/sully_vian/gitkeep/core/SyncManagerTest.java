package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jspecify.annotations.Nullable;

/**
 * Two "phones", a and b, synced through a bare repository standing for the git
 * host. Every step runs at once on the calling thread.
 */
class SyncManagerTest {

    private static final Executor NOW = Runnable::run;

    @TempDir
    Path tmp;

    private final TestGit.StepClock clock = new TestGit.StepClock(TestGit.T0);

    private Path remoteDir;
    private SyncManager.Remote remote;
    private final List<GitRepo> opened = new ArrayList<>();

    @BeforeAll
    static void isolate() {
        TestGit.isolate();
    }

    @AfterAll
    static void restore() {
        TestGit.restore();
    }

    @BeforeEach
    void createRemote() throws Exception {
        remoteDir = tmp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remoteDir.toFile()).setInitialBranch("main").call().close();
        remote = new SyncManager.Remote(remoteDir.toUri().toString(), "user", "token");
    }

    @AfterEach
    void close() {
        for (GitRepo repo : opened) {
            repo.close();
        }
    }

    private GitRepo phone(String name) throws Exception {
        GitRepo repo = GitRepo.open(tmp.resolve(name), TestGit.NAME, TestGit.EMAIL, clock);
        opened.add(repo);
        return repo;
    }

    private static Path dir(GitRepo repo) {
        return repo.repository().getWorkTree().toPath();
    }

    private void write(GitRepo repo, String name, String text) throws IOException {
        clock.advance(60);
        Files.write(dir(repo).resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(GitRepo repo, String name) throws IOException {
        return new String(Files.readAllBytes(dir(repo).resolve(name)), StandardCharsets.UTF_8);
    }

    private SyncManager.Result sync(GitRepo repo) {
        return sync(repo, remote);
    }

    private static SyncManager.Result sync(GitRepo repo, SyncManager.Remote remote) {
        CompletableFuture<SyncManager.Result> future = new SyncManager(repo, NOW, NOW).sync(remote);
        assertTrue(future.isDone());
        return future.join();
    }

    private static void assertOk(SyncManager.Result result) {
        assertEquals(SyncManager.Outcome.OK, result.outcome(), result.detail());
    }

    private ObjectId remoteHead() throws IOException {
        try (Repository bare = Git.open(remoteDir.toFile()).getRepository()) {
            ObjectId id = bare.resolve("refs/heads/main");
            assertNotNull(id, "nothing pushed");
            return id;
        }
    }

    private static ObjectId head(GitRepo repo) throws IOException {
        ObjectId id = repo.repository().resolve("HEAD");
        assertNotNull(id);
        return id;
    }

    private static RevCommit lastCommit(GitRepo repo) throws Exception {
        return Git.wrap(repo.repository()).log().setMaxCount(1).call().iterator().next();
    }

    // --- connected repositories

    @Test
    void firstPhonePushesToEmptyRemote() throws Exception {
        GitRepo a = phone("a");
        write(a, "a.md", "a\n");
        SyncManager.Result result = sync(a);
        assertOk(result);
        assertFalse(result.changed());
        assertEquals(head(a), remoteHead());
        // the note was committed by the sync
        assertTrue(Git.wrap(a.repository()).status().call().isClean());
    }

    @Test
    void editsTravelBetweenPhones() throws Exception {
        GitRepo a = phone("a");
        GitRepo b = phone("b");
        write(a, "a.md", "a\n");
        write(b, "b.md", "b\n");
        assertOk(sync(a));
        SyncManager.Result fromA = sync(b);
        assertOk(fromA);
        assertTrue(fromA.changed());
        assertEquals("a\n", read(b, "a.md"));
        assertEquals(head(b), remoteHead());

        // a only has to fast-forward: no merge commit of its own
        SyncManager.Result fromB = sync(a);
        assertOk(fromB);
        assertTrue(fromB.changed());
        assertEquals("b\n", read(a, "b.md"));
        assertEquals(head(b), head(a));

        write(a, "a.md", "a2\n");
        assertOk(sync(a));
        assertOk(sync(b));
        assertEquals("a2\n", read(b, "a.md"));
        assertEquals(head(a), head(b));
    }

    @Test
    void editsOnBothSidesAreMerged() throws Exception {
        GitRepo a = phone("a");
        GitRepo b = phone("b");
        write(a, "a.md", "a\n");
        write(b, "b.md", "b\n");
        assertOk(sync(a));
        assertOk(sync(b));
        assertOk(sync(a));

        write(a, "a.md", "a2\n");
        write(b, "b.md", "b2\n");
        assertOk(sync(a));
        assertOk(sync(b));

        RevCommit merge = lastCommit(b);
        assertEquals(2, merge.getParentCount());
        assertEquals(SyncManager.MERGE_MESSAGE, merge.getFullMessage());
        assertEquals(TestGit.NAME, merge.getAuthorIdent().getName());
        assertEquals(clock.instant(), merge.getCommitterIdent().getWhen().toInstant());
        assertEquals("a2\n", read(b, "a.md"));
        assertEquals(head(b), remoteHead());

        assertOk(sync(a));
        assertEquals("b2\n", read(a, "b.md"));
    }

    @Test
    void nothingToDo() throws Exception {
        GitRepo a = phone("a");
        write(a, "a.md", "a\n");
        assertOk(sync(a));
        ObjectId before = head(a);
        SyncManager.Result again = sync(a);
        assertOk(again);
        assertFalse(again.changed());
        assertEquals(before, head(a));
        assertEquals(before, remoteHead());
    }

    @Test
    void datesFollowMerges() throws Exception {
        GitRepo a = phone("a");
        GitRepo b = phone("b");
        write(a, "a.md", "a\n");
        write(b, "b.md", "b\n");
        assertOk(sync(a));
        assertOk(sync(b));
        assertTrue(b.lastEdits().containsKey("a.md"));
        assertTrue(b.lastEdits().containsKey("b.md"));
    }

    @Test
    void oneSyncAtATime() throws Exception {
        GitRepo a = phone("a");
        Queue<Runnable> tasks = new ArrayDeque<>();
        Executor later = tasks::add;
        SyncManager sync = new SyncManager(a, later, later);
        CompletableFuture<SyncManager.Result> first = sync.sync(remote);
        assertSame(first, sync.sync(remote));
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
        assertOk(first.join());
        // a new one once it's over
        CompletableFuture<SyncManager.Result> second = sync.sync(remote);
        assertFalse(first == second);
    }

    @Test
    void pushRefusedIsTriedAgain() throws Exception {
        GitRepo a = phone("a");
        GitRepo b = phone("b");
        write(a, "a.md", "a\n");
        assertOk(sync(a));
        write(b, "b.md", "b\n");
        assertOk(sync(b));
        assertOk(sync(a));

        // b pushes between a's fetch and a's push: a's first push is refused
        write(a, "a.md", "a2\n");
        write(b, "b.md", "b2\n");
        // network tasks: start of the network steps, fetch, push
        int[] networkTasks = {0};
        Executor network = task -> {
            networkTasks[0]++;
            if (networkTasks[0] == 3) {
                assertOk(sync(b));
            }
            task.run();
        };
        List<String> logs = new ArrayList<>();
        Logger log = Logger.getLogger(SyncManager.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(@Nullable LogRecord record) {
                if (record != null) {
                    logs.add(String.valueOf(record.getMessage()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(handler);
        SyncManager.Result result;
        try {
            result = new SyncManager(a, NOW, network).sync(remote).join();
        } finally {
            log.removeHandler(handler);
        }
        assertTrue(logs.contains("push refused, the remote moved: once more"), logs.toString());
        assertOk(result);
        assertTrue(result.changed());
        assertEquals("b2\n", read(a, "b.md"));
        assertEquals(head(a), remoteHead());
    }

    // --- first connect

    /** A remote made on a computer, with plain git: one note, on the given branch, no setup files. */
    private SyncManager.Remote computerRemote(String name, String branch) throws Exception {
        Path bare = tmp.resolve(name + ".git");
        Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch(branch).call().close();
        Path work = tmp.resolve(name + "-computer");
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch(branch).call()) {
            Files.write(work.resolve("c.md"), "c\n".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern("c.md").call();
            git.commit().setMessage("Add c.md").setSign(false).setAuthor("Computer", "c@localhost")
                    .setCommitter("Computer", "c@localhost").call();
            git.push().setRemote(bare.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                    .call();
        }
        return new SyncManager.Remote(bare.toUri().toString(), "user", "token");
    }

    private static ObjectId remoteBranch(SyncManager.Remote remote, String branch) throws Exception {
        try (Repository bare = Git.open(new File(new URI(remote.url()))).getRepository()) {
            ObjectId id = bare.resolve("refs/heads/" + branch);
            assertNotNull(id, "no " + branch + " on the remote");
            return id;
        }
    }

    @Test
    void freshPhoneTakesTheRemoteAndItsBranch() throws Exception {
        SyncManager.Remote computer = computerRemote("computer", "master");
        GitRepo a = phone("a");
        SyncManager.Result result = sync(a, computer);
        assertOk(result);
        assertTrue(result.changed());
        assertEquals("master", a.repository().getBranch());
        assertEquals("c\n", read(a, "c.md"));
        // no merge of two histories: the remote's commit, then the setup files on top
        RevCommit last = lastCommit(a);
        assertEquals(1, last.getParentCount());
        assertEquals("Add 2 files\n\nAdd .gitattributes\nAdd .gitignore\n", last.getFullMessage());
        assertEquals("Add c.md", last.getParent(0).getShortMessage());
        assertEquals(head(a), remoteBranch(computer, "master"));
        assertEquals("origin", a.repository().getConfig().getString("branch", "master", "remote"));
    }

    @Test
    void phoneWithNotesMergesWithTheRemote() throws Exception {
        SyncManager.Remote computer = computerRemote("computer", "master");
        GitRepo a = phone("a");
        write(a, "a.md", "a\n");
        assertOk(sync(a, computer));
        assertEquals("master", a.repository().getBranch());
        assertEquals("c\n", read(a, "c.md"));
        assertEquals("a\n", read(a, "a.md"));
        assertEquals(2, lastCommit(a).getParentCount());
        assertEquals(head(a), remoteBranch(computer, "master"));
    }

    @Test
    void newUrlIsAnotherRemote() throws Exception {
        GitRepo a = phone("a");
        write(a, "a.md", "a\n");
        assertOk(sync(a));

        SyncManager.Remote computer = computerRemote("computer", "trunk");
        assertOk(sync(a, computer));
        assertEquals("trunk", a.repository().getBranch());
        assertEquals("c\n", read(a, "c.md"));
        assertEquals(head(a), remoteBranch(computer, "trunk"));
        // nothing left of the first remote
        assertEquals(Collections.singletonList("refs/remotes/origin/trunk"),
                a.repository().getRefDatabase().getRefsByPrefix("refs/remotes/").stream().map(Ref::getName).toList());
        assertEquals(Collections.singleton("trunk"), a.repository().getConfig().getSubsections("branch"));
    }

    @Test
    void defaultBranchWithoutSymref() {
        ObjectId one = ObjectId.fromString("1111111111111111111111111111111111111111");
        ObjectId two = ObjectId.fromString("2222222222222222222222222222222222222222");
        List<Ref> refs = new ArrayList<>();
        refs.add(new ObjectIdRef.PeeledNonTag(Ref.Storage.NETWORK, "HEAD", two));
        refs.add(new ObjectIdRef.PeeledNonTag(Ref.Storage.NETWORK, "refs/heads/main", one));
        refs.add(new ObjectIdRef.PeeledNonTag(Ref.Storage.NETWORK, "refs/heads/notes", two));
        assertEquals("notes", SyncManager.defaultBranch(refs));
        assertEquals(null, SyncManager.defaultBranch(new ArrayList<>()));
    }

    // --- conflicts

    /** a and b synced, both with a.md. */
    private GitRepo[] syncedPhones() throws Exception {
        GitRepo a = phone("a");
        GitRepo b = phone("b");
        write(a, "a.md", "a\n");
        write(b, "b.md", "b\n");
        assertOk(sync(a));
        assertOk(sync(b));
        assertOk(sync(a));
        return new GitRepo[] {a, b};
    }

    private static void assertConflict(SyncManager.Result result, String... paths) {
        assertEquals(SyncManager.Outcome.CONFLICT, result.outcome());
        assertEquals(Arrays.asList(paths), result.conflicts());
        assertFalse(result.changed());
    }

    @Test
    void conflictIsUndone() throws Exception {
        GitRepo[] phones = syncedPhones();
        GitRepo a = phones[0];
        GitRepo b = phones[1];
        write(a, "a.md", "from a\n");
        write(b, "a.md", "from b\n");
        assertOk(sync(a));
        ObjectId remoteBefore = remoteHead();

        SyncManager.Result result = sync(b);
        assertConflict(result, "a.md");
        // b as before the sync: its edit committed, nothing merged, nothing pushed
        assertEquals("from b\n", read(b, "a.md"));
        assertEquals("Edit a.md", lastCommit(b).getFullMessage());
        assertEquals(RepositoryState.SAFE, b.repository().getRepositoryState());
        assertTrue(Git.wrap(b.repository()).status().call().isClean());
        assertEquals(remoteBefore, remoteHead());

        // still usable: the same conflict again, then fine once b gives up its edit
        assertConflict(sync(b), "a.md");
        write(b, "a.md", "from a\n");
        assertOk(sync(b));
        assertEquals(head(b), remoteHead());
    }

    @Test
    void editAgainstDeleteIsAConflict() throws Exception {
        GitRepo[] phones = syncedPhones();
        GitRepo a = phones[0];
        GitRepo b = phones[1];
        Files.delete(dir(a).resolve("a.md"));
        write(b, "a.md", "kept\n");
        assertOk(sync(a));
        assertConflict(sync(b), "a.md");
        assertEquals("kept\n", read(b, "a.md"));
        assertEquals(RepositoryState.SAFE, b.repository().getRepositoryState());
    }

    @Test
    void noteSavedDuringFetchSurvivesConflict() throws Exception {
        GitRepo[] phones = syncedPhones();
        GitRepo a = phones[0];
        GitRepo b = phones[1];
        write(a, "a.md", "from a\n");
        write(b, "a.md", "from b\n");
        assertOk(sync(a));

        // a note saved on b while it fetches
        Executor network = task -> {
            try {
                write(b, "b.md", "saved meanwhile\n");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            task.run();
        };
        assertConflict(new SyncManager(b, NOW, network).sync(remote).join(), "a.md");
        assertEquals("saved meanwhile\n", read(b, "b.md"));
        assertTrue(Git.wrap(b.repository()).status().call().isClean());
    }

    @Test
    void unfinishedMergeIsUndoneAtOpen() throws Exception {
        GitRepo[] phones = syncedPhones();
        GitRepo a = phones[0];
        GitRepo b = phones[1];
        write(a, "a.md", "from a\n");
        write(b, "a.md", "from b\n");
        assertOk(sync(a));
        b.commit();
        // a sync killed in the middle of its merge
        Git git = Git.wrap(b.repository());
        git.fetch().setRemote("origin").call();
        git.merge().include(b.repository().exactRef("refs/remotes/origin/main")).call();
        assertEquals(RepositoryState.MERGING, b.repository().getRepositoryState());
        b.close();

        try (GitRepo reopened = GitRepo.open(dir(b), TestGit.NAME, TestGit.EMAIL, clock)) {
            assertEquals(RepositoryState.SAFE, reopened.repository().getRepositoryState());
            assertEquals("from b\n", read(reopened, "a.md"));
            assertTrue(Git.wrap(reopened.repository()).status().call().isClean());
        }
    }

    // --- errors

    @Test
    void unknownHostIsOffline() throws Exception {
        GitRepo a = phone("a");
        SyncManager.Remote nowhere = new SyncManager.Remote("https://nowhere.invalid/notes.git", "u", "t");
        assertEquals(SyncManager.Outcome.OFFLINE, sync(a, nowhere).outcome());
    }

    @Test
    void credentialsInUrlAreRefused() throws Exception {
        GitRepo a = phone("a");
        SyncManager.Remote inUrl = new SyncManager.Remote("https://u:secret@example.com/notes.git", "u", "t");
        SyncManager.Result result = sync(a, inUrl);
        assertEquals(SyncManager.Outcome.ERROR, result.outcome());
        assertFalse(result.detail().contains("secret"));
    }

    @Test
    void tokenNeverInToString() {
        assertFalse(new SyncManager.Remote("https://example.com/n.git", "u", "secret").toString().contains("secret"));
    }
}
