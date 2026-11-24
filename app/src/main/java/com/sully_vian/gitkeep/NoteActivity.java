package com.sully_vian.gitkeep;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.sully_vian.gitkeep.core.Note;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;

/**
 * The note editor: the title and the Markdown body of one note. Saves by
 * itself: a moment after the last keystroke, and when leaving the screen.
 */
public class NoteActivity extends AppCompatActivity {

    /** The file name of the note to open, e.g. "20261117-143052.md". */
    public static final String EXTRA_PATH = "com.sully_vian.gitkeep.extra.PATH";

    private static final String TAG = "gitkeep";

    /** How long after the last keystroke the note is saved. */
    private static final long SAVE_DELAY_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveLater = this::save;

    private NoteStore store;
    private EditText title;
    private EditText body;

    /**
     * The note as it is on disk (as last read or written). Null until the note
     * is read: nothing is saved before that. Only used on the main thread.
     */
    private Note saved;

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
        store = Notes.store(this);
        // after a rotation, the fields already hold what was typed: read the
        // note only to know what's on disk, without overwriting the fields
        boolean fillFields = savedInstanceState == null;
        Notes.IO.execute(() -> loadNote(path, fillFields));

        TextWatcher saveWhenIdle = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(saveLater);
                handler.postDelayed(saveLater, SAVE_DELAY_MS);
            }
        };
        title.addTextChangedListener(saveWhenIdle);
        body.addTextChangedListener(saveWhenIdle);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // leaving the screen (back, home, another app, rotation): save now
        handler.removeCallbacks(saveLater);
        save();
    }

    /** Runs on the I/O thread; the fields are filled on the main thread. */
    private void loadNote(String path, boolean fillFields) {
        try {
            Note note = store.read(path);
            runOnUiThread(() -> {
                saved = note;
                if (fillFields) {
                    title.setText(note.title());
                    body.setText(note.content());
                }
            });
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "note not read: " + path, e);
            runOnUiThread(() -> {
                Toast.makeText(this, R.string.note_not_read, Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }

    /**
     * Writes the fields to the note's file, on the I/O thread, when they differ
     * from what's on disk. Everything else in the note (tags, creation time,
     * other header keys) is kept as it was read.
     */
    private void save() {
        if (saved == null) {
            return;
        }
        Note edited = new Note(saved.path(), title.getText().toString(), saved.tags(),
                saved.createdAt(), saved.modifiedAt(), saved.extra(), body.getText().toString());
        if (edited.equals(saved)) {
            return;
        }
        saved = edited;
        Notes.IO.execute(() -> {
            try {
                store.write(edited);
            } catch (IOException e) {
                Log.e(TAG, "note not saved: " + edited.path(), e);
                runOnUiThread(() -> Toast.makeText(this, R.string.note_not_saved, Toast.LENGTH_LONG).show());
            }
        });
    }
}
