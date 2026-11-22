package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Checks that the tests can reach the shared fixtures/ folder (used by the note format tests). */
class FixturesTest {

    @Test
    void fixturesFolderContainsNotes() throws IOException {
        Path dir = Path.of(System.getProperty("fixtures.dir"));
        assertTrue(Files.isDirectory(dir), "missing fixtures folder: " + dir);

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> notes = files.filter(p -> p.toString().endsWith(".md")).toList();
            assertFalse(notes.isEmpty(), "no .md files in " + dir);
        }
    }
}
