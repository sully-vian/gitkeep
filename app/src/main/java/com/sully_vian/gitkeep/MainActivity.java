package com.sully_vian.gitkeep;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.sully_vian.gitkeep.core.Note;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The note list, newest first. Tapping a note opens it; "New" starts one. */
public class MainActivity extends AppCompatActivity {

    private static final Logger LOG = Logger.getLogger(MainActivity.class.getName());

    private final NoteAdapter adapter = new NoteAdapter(this::openNote);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RecyclerView notes = findViewById(R.id.notes);
        notes.setAdapter(adapter);
        // a note added at the top (a new note) would otherwise stay above the
        // visible rows: RecyclerView keeps showing the rows that were there
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                if (positionStart == 0) {
                    notes.scrollToPosition(0);
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

    /** Runs on the I/O thread; the list is updated on the main thread. */
    private void loadNotes(NoteStore store) {
        try {
            List<Note> notes = store.list();
            notes.sort(Note.NEWEST_FIRST);
            runOnUiThread(() -> adapter.submitList(notes));
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "notes not listed", e);
            runOnUiThread(() -> Toast.makeText(this, R.string.notes_not_listed, Toast.LENGTH_LONG).show());
        }
    }

    private void openNote(Note note) {
        Intent intent = new Intent(this, NoteActivity.class);
        intent.putExtra(NoteActivity.EXTRA_PATH, note.path());
        startActivity(intent);
    }
}
