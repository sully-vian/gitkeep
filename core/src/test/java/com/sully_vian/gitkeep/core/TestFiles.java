package com.sully_vian.gitkeep.core;

import java.nio.file.Path;
import java.util.Objects;

/** File helpers shared by the tests. */
final class TestFiles {

    private TestFiles() {
    }

    /** The top-level fixtures/ folder, given by Gradle (core/build.gradle). */
    static Path fixturesDir() {
        String dir = System.getProperty("fixtures.dir");
        if (dir == null) {
            throw new IllegalStateException("fixtures.dir isn't set: run the tests with Gradle (make test)");
        }
        return Path.of(dir);
    }

    /** The file name of a path listed from a folder, which always has one. */
    static String name(Path file) {
        return Objects.requireNonNull(file.getFileName(), "no file name: " + file).toString();
    }
}
