package com.sully_vian.gitkeep;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.sully_vian.gitkeep.core.Note;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    /** Swiped away, but still on disk: "Undo" is showing. Null when there's none. */
    private Note pendingDelete;
    private Snackbar undoMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
        });
    }

    /**
     * Reloads the list each time it comes back on screen, e.g. after editing a
     * note. Same I/O thread as the rest: runs after the fixtures are copied, and
     * after the editor's last save.
     */
    @Override
    protected void onStart() {
        super.onStart();
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
            runOnUiThread(() -> {
                notes = loaded;
                showNotes();
            });
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "notes not listed", e);
            runOnUiThread(() -> Toast.makeText(this, R.string.notes_not_listed, Toast.LENGTH_LONG).show());
        }
    }

    /** Shows every note except the one waiting to be deleted. */
    private void showNotes() {
        List<Note> shown = new ArrayList<>(notes);
        if (pendingDelete != null) {
            String hidden = pendingDelete.path();
            shown.removeIf(note -> note.path().equals(hidden));
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
        undoMessage.dismiss();

        NoteStore store = Notes.store(this);
        Notes.IO.execute(() -> {
            try {
                store.delete(path);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "note not deleted: " + path, e);
                runOnUiThread(() -> Toast.makeText(this, R.string.note_not_deleted, Toast.LENGTH_LONG).show());
                // back in the list, since it's still on disk
                loadNotes(store);
            }
        });
    }

    private void openNote(Note note) {
        Intent intent = new Intent(this, NoteActivity.class);
        intent.putExtra(NoteActivity.EXTRA_PATH, note.path());
        startActivity(intent);
    }
}
