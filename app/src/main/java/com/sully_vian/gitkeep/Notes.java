package com.sully_vian.gitkeep;

import android.content.Context;
import com.sully_vian.gitkeep.core.NoteStore;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Where the notes live, and the thread that reads and writes them. */
final class Notes {

    /**
     * Every file read or write of the app, off the main thread. A single thread
     * for the whole app: tasks run one at a time, in the order they were given,
     * even across activities (a note saved when leaving the editor is written
     * before the list reloads).
     */
    static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private Notes() {
    }

    /** The app's private notes folder: files/notes. */
    static Path dir(Context context) {
        return context.getFilesDir().toPath().resolve("notes");
    }

    static NoteStore store(Context context) {
        return new NoteStore(dir(context));
    }
}
