package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
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
