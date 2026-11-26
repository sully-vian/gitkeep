package com.sully_vian.gitkeep.core;

import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.jspecify.annotations.Nullable;

/**
 * Git helpers shared by the tests: JGit without the machine's git configs (a
 * ~/.gitconfig with commit signing or autocrlf would change what the tests see),
 * and a fixed clock.
 */
final class TestGit {

    static final String NAME = "Test";
    static final String EMAIL = "test@localhost";
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    private TestGit() {
    }

    /** Call before the tests (@BeforeAll); {@link #restore} after them. */
    static void isolate() {
        SystemReader.setInstance(new NoConfigs(SystemReader.getInstance()));
    }

    static void restore() {
        // null: back to JGit's default reader
        SystemReader.setInstance(null);
    }

    static Clock clock(Instant at) {
        return Clock.fixed(at, ZONE);
    }

    /** A clock that only moves when told to. */
    static final class StepClock extends Clock {

        private Instant now;

        StepClock(Instant start) {
            now = start;
        }

        /** Moves the clock forward, and returns the new time. */
        Instant advance(long seconds) {
            now = now.plusSeconds(seconds);
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The machine's reader, but with empty user, system and JGit configs. */
    private static final class NoConfigs extends SystemReader {

        private final SystemReader machine;

        NoConfigs(SystemReader machine) {
            this.machine = machine;
        }

        @Override
        public String getHostname() {
            return machine.getHostname();
        }

        @Override
        public @Nullable String getenv(String variable) {
            return machine.getenv(variable);
        }

        @Override
        public @Nullable String getProperty(String key) {
            return machine.getProperty(key);
        }

        @Override
        public FileBasedConfig openUserConfig(@Nullable Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }

        @Override
        public FileBasedConfig openSystemConfig(@Nullable Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }

        @Override
        public FileBasedConfig openJGitConfig(@Nullable Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }

        @Override
        public long getCurrentTime() {
            return machine.getCurrentTime();
        }

        @Override
        public int getTimezone(long when) {
            return machine.getTimezone(when);
        }
    }

    /** A config that is never read from nor written to a file. */
    private static final class EmptyConfig extends FileBasedConfig {

        EmptyConfig(@Nullable Config parent, FS fs) {
            super(parent, new File("/nonexistent/gitconfig"), fs);
        }

        @Override
        public void load() {
            clear();
        }

        @Override
        public void save() {
        }

        @Override
        public boolean isOutdated() {
            return false;
        }
    }
}
