package com.sully_vian.gitkeep;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.sully_vian.gitkeep.core.Note;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The note list, newest first. */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "gitkeep";

    /** File I/O, off the main thread. One thread: tasks run in order. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final NoteAdapter adapter = new NoteAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RecyclerView notes = findViewById(R.id.notes);
        notes.setAdapter(adapter);

        Path notesDir = getFilesDir().toPath().resolve("notes");
        NoteStore store = new NoteStore(notesDir);
        io.execute(() -> {
            try {
                int copied = FixtureNotes.copyIfNoNotes(getAssets(), store, notesDir);
                Log.i(TAG, "fixtures copied: " + copied);
            } catch (IOException e) {
                Log.e(TAG, "fixtures not copied", e);
            }
        });
        // same thread: runs after the fixtures are copied
        io.execute(() -> loadNotes(store));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
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
}
