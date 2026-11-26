package com.sully_vian.gitkeep;

import android.content.Context;
import com.sully_vian.gitkeep.core.GitRepo;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jspecify.annotations.Nullable;

/** Where the notes live, their git repository, and the thread that reads and writes them. */
final class Notes {

    private static final Logger LOG = Logger.getLogger(Notes.class.getName());

    /** The author of the app's commits, until it can be set in the settings. */
    private static final String AUTHOR_NAME = "gitkeep";
    private static final String AUTHOR_EMAIL = "gitkeep@localhost";

    /**
     * Every file read or write of the app, git included, off the main thread. A
     * single thread for the whole app: tasks run one at a time, in the order they
     * were given, even across activities (a note saved when leaving the editor is
     * written and committed before the list reloads).
     */
    static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** Opened on first use, then kept for the whole process. Only used on {@link #IO}. */
    private static @Nullable GitRepo git;

    private Notes() {
    }

    /** The app's private notes folder: files/notes. */
    static Path dir(Context context) {
        return context.getFilesDir().toPath().resolve("notes");
    }

    static NoteStore store(Context context) {
        return new NoteStore(dir(context));
    }

    /**
     * The repository of the notes folder, opened or created on first use.
     * Call it on {@link #IO} only.
     */
    static GitRepo git(Context context) throws IOException, GitAPIException {
        GitRepo repo = git;
        if (repo == null) {
            repo = GitRepo.open(dir(context), AUTHOR_NAME, AUTHOR_EMAIL, Clock.systemDefaultZone());
            git = repo;
        }
        return repo;
    }

    /**
     * Commits whatever changed in the notes folder. A failure is only logged:
     * the notes are saved anyway, and the next commit takes the changes. Call it
     * on {@link #IO} only.
     */
    static void commit(Context context) {
        try {
            git(context).commit();
        } catch (IOException | GitAPIException | RuntimeException e) {
            LOG.log(Level.SEVERE, "changes not committed", e);
        }
    }
}
