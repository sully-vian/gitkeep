package com.sully_vian.gitkeep;

import android.content.Context;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The app's log file, for troubleshooting: everything logged with
 * java.util.logging under com.sully_vian.gitkeep (app and core) goes to logcat
 * as usual, and also to files/logs/gitkeep.0.log (newest) and gitkeep.1.log
 * (older), at most {@link #FILE_LIMIT} bytes each.
 *
 * <p>
 * Only file names are logged, never note content, titles or credentials, so the
 * file can be shared.
 */
final class Logs {

    private static final int FILE_LIMIT = 512 * 1024;
    private static final int FILE_COUNT = 2;

    /**
     * Kept here: java.util.logging only holds loggers weakly, and a collected
     * logger would lose the file handler.
     */
    private static final Logger APP = Logger.getLogger("com.sully_vian.gitkeep");

    private Logs() {
    }

    /** The folder of the log files: files/logs. */
    static Path dir(Context context) {
        return context.getFilesDir().toPath().resolve("logs");
    }

    /**
     * Starts writing the log file, and logs crashes to it. Call once, when the
     * app starts. If the file can't be opened, logs still go to logcat.
     */
    static void init(Context context) {
        try {
            Path dir = dir(context);
            Files.createDirectories(dir);
            // %g: 0 for the current file, 1 for the previous one
            FileHandler file = new FileHandler(dir.resolve("gitkeep.%g.log").toString(),
                    FILE_LIMIT, FILE_COUNT, true);
            file.setFormatter(new LineFormatter());
            file.setLevel(Level.INFO);
            APP.addHandler(file);
        } catch (IOException e) {
            APP.log(Level.SEVERE, "log file not opened", e);
        }

        // a crash is logged first, then handled as usual (the app closes)
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            APP.log(Level.SEVERE, "crash in thread " + thread.getName(), error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /**
     * One line per event, then the stack trace if any:
     * "2026-11-24 01:45:05.203 INFO    NoteStore  listed 12 notes (0 skipped)".
     */
    private static final class LineFormatter extends Formatter {

        private static final DateTimeFormatter TIME = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord record) {
            String logger = record.getLoggerName();
            String source = logger == null ? "" : logger.substring(logger.lastIndexOf('.') + 1);
            StringBuilder line = new StringBuilder()
                    .append(TIME.format(Instant.ofEpochMilli(record.getMillis())))
                    .append(' ')
                    .append(String.format("%-7s", record.getLevel().getName()))
                    .append(' ')
                    .append(source)
                    .append("  ")
                    .append(formatMessage(record))
                    .append('\n');
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                line.append(trace);
            }
            return line.toString();
        }
    }
}
