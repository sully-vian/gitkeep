package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitDatesTest {

    @TempDir
    Path dir;

    private final TestGit.StepClock clock = new TestGit.StepClock(TestGit.T0);

    @BeforeAll
    static void isolate() {
        TestGit.isolate();
    }

    @AfterAll
    static void restore() {
        TestGit.restore();
    }

    private GitRepo open() throws IOException, GitAPIException {
        return GitRepo.open(dir, TestGit.NAME, TestGit.EMAIL, clock);
    }

    private void write(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Moves the clock, then commits. */
    private static Instant commitLater(GitRepo repo, TestGit.StepClock clock) throws Exception {
        Instant at = clock.advance(60);
        assertNotNull(repo.commit());
        return at;
    }

    @Test
    void addedAndLastEdited() throws Exception {
        try (GitRepo repo = open()) {
            write("a.md", "1\n");
            write("b.md", "1\n");
            Instant added = commitLater(repo, clock);
            write("a.md", "2\n");
            Instant edited = commitLater(repo, clock);

            Map<String, GitDates.FileDates> dates = repo.dates();
            assertEquals(new GitDates.FileDates(added, edited), dates.get("a.md"));
            assertEquals(new GitDates.FileDates(added, added), dates.get("b.md"));
            // the setup commit's files too
            assertEquals(new GitDates.FileDates(TestGit.T0, TestGit.T0), dates.get(GitRepo.ATTRIBUTES));
            assertEquals(4, dates.size());

            assertEquals(edited, repo.lastEdits().get("a.md"));
        }
    }

    @Test
    void uncommittedFilesHaveNoDates() throws Exception {
        try (GitRepo repo = open()) {
            write("a.md", "1\n");
            assertFalse(repo.lastEdits().containsKey("a.md"));
        }
    }

    @Test
    void deletedFilesAreForgotten() throws Exception {
        try (GitRepo repo = open()) {
            write("a.md", "1\n");
            commitLater(repo, clock);
            Files.delete(dir.resolve("a.md"));
            commitLater(repo, clock);
            assertFalse(repo.dates().containsKey("a.md"));

            write("a.md", "again\n");
            Instant again = commitLater(repo, clock);
            assertEquals(new GitDates.FileDates(again, again), repo.dates().get("a.md"));
        }
    }

    @Test
    void subfoldersHaveNoDates() throws Exception {
        try (GitRepo repo = open()) {
            write("a.md", "1\n");
            commitLater(repo, clock);
            // committed by hand: the app never stages subfolders
            write("sub/b.md", "1\n");
            Git.wrap(repo.repository()).add().addFilepattern("sub").call();
            Git.wrap(repo.repository()).commit().setMessage("sub").setSign(false).call();
            Map<String, GitDates.FileDates> dates = repo.dates();
            assertFalse(dates.containsKey("sub"));
            assertFalse(dates.containsKey("sub/b.md"));
        }
    }

    @Test
    void cacheMatchesFreshWalk() throws Exception {
        Map<String, GitDates.FileDates> cached;
        try (GitRepo repo = open()) {
            write("a.md", "1\n");
            write("b.md", "1\n");
            commitLater(repo, clock);
            // read once: later updates only walk the new commits
            repo.dates();
            write("a.md", "2\n");
            Files.delete(dir.resolve("b.md"));
            write("c.md", "1\n");
            commitLater(repo, clock);
            write("c.md", "2\n");
            commitLater(repo, clock);
            cached = repo.dates();
        }
        try (GitRepo repo = open()) {
            assertEquals(repo.dates(), cached);
        }
    }
}
