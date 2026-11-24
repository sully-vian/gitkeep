package com.sully_vian.gitkeep;

import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.sully_vian.gitkeep.core.Note;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;

/** The note editor: the title and the Markdown body of one note. */
public class NoteActivity extends AppCompatActivity {

    /** The file name of the note to open, e.g. "20261117-143052.md". */
    public static final String EXTRA_PATH = "com.sully_vian.gitkeep.extra.PATH";

    private static final String TAG = "gitkeep";

    private EditText title;
    private EditText body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note);
        title = findViewById(R.id.title);
        body = findViewById(R.id.body);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        // after a rotation, the fields already hold what was typed: don't
        // overwrite it with the file
        if (savedInstanceState == null) {
            NoteStore store = Notes.store(this);
            Notes.IO.execute(() -> loadNote(store, path));
        }
    }

    /** Runs on the I/O thread; the fields are filled on the main thread. */
    private void loadNote(NoteStore store, String path) {
        try {
            Note note = store.read(path);
            runOnUiThread(() -> {
                title.setText(note.title());
                body.setText(note.content());
            });
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "note not read: " + path, e);
            runOnUiThread(() -> {
                Toast.makeText(this, R.string.note_not_read, Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }
}
