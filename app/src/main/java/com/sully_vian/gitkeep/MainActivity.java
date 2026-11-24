package com.sully_vian.gitkeep;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.sully_vian.gitkeep.core.Note;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;
import java.util.List;

/** The note list, newest first. Tapping a note opens it. */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "gitkeep";

    private final NoteAdapter adapter = new NoteAdapter(this::openNote);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RecyclerView notes = findViewById(R.id.notes);
        notes.setAdapter(adapter);

        NoteStore store = Notes.store(this);
        Notes.IO.execute(() -> {
            try {
                int copied = FixtureNotes.copyIfNoNotes(getAssets(), store, Notes.dir(this));
                Log.i(TAG, "fixtures copied: " + copied);
            } catch (IOException e) {
                Log.e(TAG, "fixtures not copied", e);
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
            Log.e(TAG, "notes not listed", e);
            runOnUiThread(() -> Toast.makeText(this, R.string.notes_not_listed, Toast.LENGTH_LONG).show());
        }
    }

    private void openNote(Note note) {
        Intent intent = new Intent(this, NoteActivity.class);
        intent.putExtra(NoteActivity.EXTRA_PATH, note.path());
        startActivity(intent);
    }
}
