package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
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
}
