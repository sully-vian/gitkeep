package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepoTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void isolate() {
        TestGit.isolate();
    }

    @AfterAll
    static void restore() {
        TestGit.restore();
    }

    private GitRepo open() throws IOException, GitAPIException {
        return GitRepo.open(dir, TestGit.NAME, TestGit.EMAIL, TestGit.clock(TestGit.T0));
    }

    private static List<RevCommit> log(GitRepo repo) throws IOException, GitAPIException {
        List<RevCommit> commits = new ArrayList<>();
        for (RevCommit commit : Git.wrap(repo.repository()).log().call()) {
            commits.add(commit);
        }
        return commits;
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
    }

    // --- open

    @Test
    void createsRepositoryWithSetupCommit() throws Exception {
        try (GitRepo repo = open()) {
            Repository repository = repo.repository();
            assertEquals(GitRepo.BRANCH, repository.getBranch());
            assertEquals(dir.toFile(), repository.getWorkTree());

            List<RevCommit> commits = log(repo);
            assertEquals(1, commits.size());
            RevCommit setup = commits.get(0);
            assertEquals(GitRepo.SETUP_MESSAGE, setup.getFullMessage());
            assertEquals(TestGit.NAME, setup.getAuthorIdent().getName());
            assertEquals(TestGit.EMAIL, setup.getAuthorIdent().getEmailAddress());
            assertEquals(TestGit.T0, setup.getAuthorIdent().getWhen().toInstant());

            assertTrue(Git.wrap(repository).status().call().isClean());
        }
        assertEquals(GitRepo.ATTRIBUTES_TEXT, read(GitRepo.ATTRIBUTES));
        assertEquals(GitRepo.IGNORE_TEXT, read(GitRepo.IGNORE));
    }

    @Test
    void createsMissingFolder() throws Exception {
        dir = dir.resolve("notes");
        try (GitRepo repo = open()) {
            assertEquals(1, log(repo).size());
        }
        assertTrue(Files.isDirectory(dir.resolve(".git")));
    }

    @Test
    void reopeningAddsNoCommit() throws Exception {
        try (GitRepo repo = open()) {
            assertEquals(1, log(repo).size());
        }
        try (GitRepo repo = open()) {
            assertEquals(1, log(repo).size());
            assertTrue(Git.wrap(repo.repository()).status().call().isClean());
        }
    }

    @Test
    void existingNotesAreNotCommittedByOpen() throws Exception {
        Files.write(dir.resolve("a.md"), "a\n".getBytes(StandardCharsets.UTF_8));
        try (GitRepo repo = open()) {
            assertEquals(1, log(repo).size());
            assertEquals(1, Git.wrap(repo.repository()).status().call().getUntracked().size());
        }
    }

    @Test
    void writesBackMissingSetupFiles() throws Exception {
        open().close();
        Files.delete(dir.resolve(GitRepo.ATTRIBUTES));
        try (GitRepo repo = open()) {
            assertEquals(GitRepo.ATTRIBUTES_TEXT, read(GitRepo.ATTRIBUTES));
            // committed with the next commit, not by open
            assertEquals(1, log(repo).size());
        }
    }

    @Test
    void keepsExistingSetupFiles() throws Exception {
        Files.write(dir.resolve(GitRepo.IGNORE), "mine\n".getBytes(StandardCharsets.UTF_8));
        open().close();
        assertEquals("mine\n", read(GitRepo.IGNORE));
    }

    @Test
    void turnsOffGc() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            assertEquals(-1, git.getRepository().getConfig().getInt("gc", "auto", -1));
        }
        try (GitRepo repo = open()) {
            assertEquals(0, repo.repository().getConfig().getInt("gc", "auto", -1));
        }
    }

    @Test
    void removesStaleIndexLock() throws Exception {
        open().close();
        Path lock = dir.resolve(".git/index.lock");
        Files.write(lock, new byte[0]);
        open().close();
        assertFalse(Files.exists(lock));
    }

    // --- commit

    private void write(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    /** The text of a file in the last commit. */
    private static String committed(GitRepo repo, String path) throws IOException {
        Repository repository = repo.repository();
        ObjectId tree = repository.resolve("HEAD^{tree}");
        try (TreeWalk walk = TreeWalk.forPath(repository, path, tree)) {
            assertNotNull(walk, "not committed: " + path);
            return new String(repository.open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isCommitted(GitRepo repo, String path) throws IOException {
        Repository repository = repo.repository();
        try (TreeWalk walk = TreeWalk.forPath(repository, path, repository.resolve("HEAD^{tree}"))) {
            return walk != null;
        }
    }

    @Test
    void nothingChangedCommitsNothing() throws Exception {
        try (GitRepo repo = open()) {
            assertNull(repo.commit());
            assertEquals(1, log(repo).size());
        }
    }

    @Test
    void addEditDelete() throws Exception {
        try (GitRepo repo = open()) {
            write("a.md", "one\n");
            RevCommit add = repo.commit();
            assertNotNull(add);
            assertEquals("Add a.md", add.getFullMessage());
            assertEquals("one\n", committed(repo, "a.md"));

            assertNull(repo.commit());

            write("a.md", "two\n");
            RevCommit edit = repo.commit();
            assertNotNull(edit);
            assertEquals("Edit a.md", edit.getFullMessage());
            assertEquals("two\n", committed(repo, "a.md"));

            Files.delete(dir.resolve("a.md"));
            RevCommit delete = repo.commit();
            assertNotNull(delete);
            assertEquals("Delete a.md", delete.getFullMessage());
            assertFalse(isCommitted(repo, "a.md"));

            assertEquals(4, log(repo).size());
            assertTrue(Git.wrap(repo.repository()).status().call().isClean());
        }
    }

    @Test
    void severalFiles() throws Exception {
        try (GitRepo repo = open()) {
            write("b.md", "b\n");
            write("c.md", "c\n");
            write("d.md", "d\n");
            assertNotNull(repo.commit());

            write("a.md", "a\n");
            write("b.md", "b2\n");
            write("c.md", "c2\n");
            Files.delete(dir.resolve("d.md"));
            RevCommit commit = repo.commit();
            assertNotNull(commit);
            assertEquals("Add 1 note, edit 2, delete 1\n\n"
                    + "Add a.md\nEdit b.md\nEdit c.md\nDelete d.md\n", commit.getFullMessage());
        }
    }

    @Test
    void messages() {
        List<String> none = new ArrayList<>();
        assertEquals("Edit 2 notes, delete 1\n\nEdit a.md\nEdit b.md\nDelete c.md\n",
                GitRepo.message(none, Arrays.asList("a.md", "b.md"), Arrays.asList("c.md")));
        assertEquals("Add 2 files\n\nAdd .gitattributes\nAdd a.md\n",
                GitRepo.message(Arrays.asList(".gitattributes", "a.md"), none, none));
        assertEquals("Delete x.txt", GitRepo.message(none, none, Arrays.asList("x.txt")));
    }

    @Test
    void otherRootFilesAreCommittedToo() throws Exception {
        try (GitRepo repo = open()) {
            write("todo.txt", "x\n");
            RevCommit commit = repo.commit();
            assertNotNull(commit);
            assertEquals("Add todo.txt", commit.getFullMessage());
        }
    }

    @Test
    void writtenBackSetupFileIsCommitted() throws Exception {
        open().close();
        Files.delete(dir.resolve(GitRepo.ATTRIBUTES));
        try (GitRepo repo = open()) {
            assertNull(repo.commit());
            // unchanged: written back with the same text
            assertEquals(1, log(repo).size());
        }
        write(GitRepo.IGNORE, "changed\n");
        try (GitRepo repo = open()) {
            RevCommit commit = repo.commit();
            assertNotNull(commit);
            assertEquals("Edit .gitignore", commit.getFullMessage());
        }
    }

    @Test
    void subfoldersAreNeverStaged() throws Exception {
        try (GitRepo repo = open()) {
            write("sub/a.md", "a\n");
            assertNull(repo.commit());

            write("b.md", "b\n");
            assertNotNull(repo.commit());
            assertFalse(isCommitted(repo, "sub/a.md"));
        }
    }

    @Test
    void tempFilesAreIgnored() throws Exception {
        try (GitRepo repo = open()) {
            write(".123456.tmp", "half a note");
            assertNull(repo.commit());
        }
    }

    @Test
    void crlfIsStoredAsLf() throws Exception {
        try (GitRepo repo = open()) {
            write("a.md", "one\r\ntwo\r\n");
            assertNotNull(repo.commit());
            assertEquals("one\ntwo\n", committed(repo, "a.md"));
        }
    }

    @Test
    void finishesInterruptedCommit() throws Exception {
        try (GitRepo repo = open()) {
            write("a.md", "a\n");
            write("b.md", "b\n");
            // staged by a commit killed before committing
            Git.wrap(repo.repository()).add().addFilepattern("a.md").call();
            RevCommit commit = repo.commit();
            assertNotNull(commit);
            assertEquals("Add 2 notes\n\nAdd a.md\nAdd b.md\n", commit.getFullMessage());
        }
    }

    @Test
    void stagedThenDeletedIsJustUnstaged() throws Exception {
        try (GitRepo repo = open()) {
            write("a.md", "a\n");
            Git.wrap(repo.repository()).add().addFilepattern("a.md").call();
            Files.delete(dir.resolve("a.md"));
            assertNull(repo.commit());
            assertTrue(Git.wrap(repo.repository()).status().call().isClean());
        }
    }

    @Test
    void authorAndTimeFromSettings() throws Exception {
        Instant later = TestGit.T0.plusSeconds(3600);
        open().close();
        write("a.md", "a\n");
        try (GitRepo repo = GitRepo.open(dir, "Someone", "someone@example.com", TestGit.clock(later))) {
            RevCommit commit = repo.commit();
            assertNotNull(commit);
            PersonIdent author = commit.getAuthorIdent();
            assertEquals("Someone", author.getName());
            assertEquals("someone@example.com", author.getEmailAddress());
            assertEquals(later, author.getWhen().toInstant());
            assertEquals(TestGit.ZONE.getRules().getOffset(later).getTotalSeconds() / 60,
                    author.getTimeZoneOffset());
            assertEquals(author, commit.getCommitterIdent());
        }
    }
}
