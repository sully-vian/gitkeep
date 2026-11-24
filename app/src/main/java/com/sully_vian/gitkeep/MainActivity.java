package com.sully_vian.gitkeep;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "gitkeep";

    /** File I/O, off the main thread. One thread: tasks run in order. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.note1).setOnClickListener(v -> openNote(getString(R.string.note_1)));
        findViewById(R.id.note2).setOnClickListener(v -> openNote(getString(R.string.note_2)));

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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private void openNote(String title) {
        Intent intent = new Intent(this, NoteActivity.class);
        intent.putExtra(NoteActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }
}
