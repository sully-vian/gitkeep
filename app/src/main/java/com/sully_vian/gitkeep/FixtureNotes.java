package com.sully_vian.gitkeep;

import android.content.res.AssetManager;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sample notes for development: debug builds bundle the repository's fixtures as
 * assets/fixtures/, release builds bundle nothing (see app/build.gradle).
 */
final class FixtureNotes {

    private static final String ASSETS_DIR = "fixtures";

    private FixtureNotes() {
    }

    /**
     * Copies the bundled fixtures into the notes folder when it has no notes, so
     * that a fresh debug install has something to show. Does nothing in release
     * builds (no fixtures bundled), or when there already are notes.
     *
     * <p>
     * The files are copied byte for byte, not through {@link NoteStore#write}:
     * some fixtures deliberately don't follow the writing rules.
     *
     * <p>
     * Blocking file I/O: call it on {@link Notes#IO}.
     *
     * @return the number of fixtures copied
     */
    static int copyIfNoNotes(AssetManager assets, NoteStore store, Path notesDir) throws IOException {
        String[] names = assets.list(ASSETS_DIR);
        if (names == null || names.length == 0 || !store.list().isEmpty()) {
            return 0;
        }
        Files.createDirectories(notesDir);
        int copied = 0;
        for (String name : names) {
            Path file = notesDir.resolve(name);
            // an unreadable file with that name is left alone, never overwritten
            if (Files.exists(file)) {
                continue;
            }
            try (InputStream in = assets.open(ASSETS_DIR + "/" + name)) {
                Files.copy(in, file);
            }
            copied++;
        }
        return copied;
    }
}
