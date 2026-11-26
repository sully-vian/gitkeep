package com.sully_vian.gitkeep;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.sully_vian.gitkeep.core.Note;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jspecify.annotations.Nullable;

/**
 * The note list, newest first. Tapping a note opens it, "New" starts one, and
 * swiping a note deletes it.
 *
 * <p>
 * A swiped note leaves the list at once, but its file is only deleted when the
 * "Undo" message goes away, or when the list leaves the screen. Undo then has
 * nothing to restore, and if the app is killed meanwhile, the note survives.
 */
public class MainActivity extends AppCompatActivity {

    private static final Logger LOG = Logger.getLogger(MainActivity.class.getName());

    /** How long "Undo" is offered after a delete. */
    private static final int UNDO_MS = 5000;

    private final NoteAdapter adapter = new NoteAdapter(this::openNote, this::deleteWithUndo);

    /** The notes as last read, sorted. Only used on the main thread. */
    private List<Note> notes = new ArrayList<>();

    /** When each note was last committed, by path, as last read. Only used on the main thread. */
    private Map<String, Instant> lastEdits = new HashMap<>();

    /** Swiped away, but still on disk: "Undo" is showing. Null when there's none. */
    private @Nullable Note pendingDelete;
    private @Nullable Snackbar undoMessage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RecyclerView list = findViewById(R.id.notes);
        list.setAdapter(adapter);
        new ItemTouchHelper(new SwipeToDelete(this, position -> deleteWithUndo(adapter.noteAt(position))))
                .attachToRecyclerView(list);
        // a note added at the top (a new note) would otherwise stay above the
        // visible rows: RecyclerView keeps showing the rows that were there
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                if (positionStart == 0) {
                    list.scrollToPosition(0);
                }
            }
        });
        // no note path: the editor starts a new note
        findViewById(R.id.new_note).setOnClickListener(
                v -> startActivity(new Intent(this, NoteActivity.class)));

        NoteStore store = Notes.store(this);
        Notes.IO.execute(() -> {
            try {
                int copied = FixtureNotes.copyIfNoNotes(getAssets(), store, Notes.dir(this));
                if (copied > 0) {
                    LOG.info("fixtures copied: " + copied);
                }
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "fixtures not copied", e);
            }
            // the fixtures, and anything left uncommitted (a crash, notes from before git)
            Notes.commit(this);
        });
    }

    /**
     * Reloads the list each time it's back in front, e.g. after editing a note.
     * onResume rather than onStart: an editor that closes at once (a note that
     * can't be read) never fully hides the list, and onStart wouldn't run. Same
     * I/O thread as the rest: runs after the fixtures are copied, and after the
     * editor's last save.
     */
    @Override
    protected void onResume() {
        super.onResume();
        NoteStore store = Notes.store(this);
        Notes.IO.execute(() -> loadNotes(store));
    }

    /** Leaving the list (a note opened, home, rotation) ends the chance to undo. */
    @Override
    protected void onStop() {
        super.onStop();
        commitDelete();
    }

    /** Runs on the I/O thread; the list is updated on the main thread. */
    private void loadNotes(NoteStore store) {
        try {
            List<Note> loaded = store.list();
            loaded.sort(Note.NEWEST_FIRST);
            Map<String, Instant> edits = lastEdits();
            runOnUiThread(() -> {
                notes = loaded;
                lastEdits = edits;
                showNotes();
            });
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "notes not listed", e);
            runOnUiThread(() -> Toast.makeText(this, R.string.notes_not_listed, Toast.LENGTH_LONG).show());
        }
    }

    /**
     * When each note was last edited, from git. Without the repository, the list
     * still shows, without dates. Runs on the I/O thread.
     */
    private Map<String, Instant> lastEdits() {
        try {
            return Notes.git(this).lastEdits();
        } catch (IOException | GitAPIException | RuntimeException e) {
            LOG.log(Level.SEVERE, "dates not read", e);
            return new HashMap<>();
        }
    }

    /** Shows every note except the one waiting to be deleted. */
    private void showNotes() {
        String hidden = pendingDelete == null ? null : pendingDelete.path();
        List<NoteRow> shown = new ArrayList<>();
        for (Note note : notes) {
            if (!note.path().equals(hidden)) {
                shown.add(new NoteRow(note, lastEdits.get(note.path())));
            }
        }
        adapter.submitList(shown);
    }

    /** Takes the note off the list and offers to undo. Nothing is deleted yet. */
    private void deleteWithUndo(Note note) {
        // only the last delete can be undone
        commitDelete();
        pendingDelete = note;
        showNotes();

        undoMessage = Snackbar.make(findViewById(R.id.notes), R.string.note_deleted, UNDO_MS)
                .setAnchorView(R.id.new_note)
                .setAction(R.string.undo, v -> {
                    // too late if a newer delete is replacing this message
                    if (pendingDelete == note) {
                        LOG.info("delete undone: " + note.path());
                        pendingDelete = null;
                        showNotes();
                    }
                })
                .addCallback(new BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    @Override
                    public void onDismissed(Snackbar message, int event) {
                        // not after "Undo", nor for a message replaced by a newer delete's
                        if (pendingDelete == note) {
                            commitDelete();
                        }
                    }
                });
        undoMessage.show();
    }

    /** Deletes the file of the note swiped away, if any, on the I/O thread. */
    private void commitDelete() {
        if (pendingDelete == null) {
            return;
        }
        String path = pendingDelete.path();
        pendingDelete = null;
        notes.removeIf(note -> note.path().equals(path));
        if (undoMessage != null) {
            undoMessage.dismiss();
        }

        NoteStore store = Notes.store(this);
        Notes.IO.execute(() -> {
            try {
                store.delete(path);
                Notes.commit(this);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "note not deleted: " + path, e);
                runOnUiThread(() -> Toast.makeText(this, R.string.note_not_deleted, Toast.LENGTH_LONG).show());
                // back in the list, since it's still on disk
                loadNotes(store);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openNote(Note note) {
        Intent intent = new Intent(this, NoteActivity.class);
        intent.putExtra(NoteActivity.EXTRA_PATH, note.path());
        startActivity(intent);
    }
}
