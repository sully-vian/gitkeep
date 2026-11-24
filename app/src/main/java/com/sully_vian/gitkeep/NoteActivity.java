package com.sully_vian.gitkeep;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.sully_vian.gitkeep.core.Note;
import com.sully_vian.gitkeep.core.NoteStore;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The note editor: the title and the Markdown body of one note. Saves by
 * itself: a moment after the last keystroke, and when leaving the screen.
 *
 * <p>
 * Opened without {@link #EXTRA_PATH}, it starts a new note: its file name and
 * creation time are chosen right away, but the file is only written once
 * something is typed, so a new note left empty never exists on disk.
 */
public class NoteActivity extends AppCompatActivity {

    /**
     * The file name of the note to open, e.g. "20261117-143052.md". Without it,
     * a new note is started.
     */
    public static final String EXTRA_PATH = "com.sully_vian.gitkeep.extra.PATH";

    /**
     * Saved state across a rotation: the note's file name, so that a new note
     * stays the same note instead of starting another one, and its creation time
     * while it isn't written yet.
     */
    private static final String STATE_PATH = "path";
    private static final String STATE_NEW_CREATED = "newCreated";

    private static final Logger LOG = Logger.getLogger(NoteActivity.class.getName());

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

    /** False for a new note until its first write. Only used on the main thread. */
    private boolean onDisk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note);
        title = findViewById(R.id.title);
        body = findViewById(R.id.body);

        store = Notes.store(this);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        OffsetDateTime createdIfNew = null;
        if (savedInstanceState != null && savedInstanceState.getString(STATE_PATH) != null) {
            path = savedInstanceState.getString(STATE_PATH);
            String created = savedInstanceState.getString(STATE_NEW_CREATED);
            if (created != null) {
                createdIfNew = OffsetDateTime.parse(created);
            }
        }
        if (path == null) {
            Notes.IO.execute(this::startNewNote);
        } else {
            // after a rotation, the fields already hold what was typed: read the
            // note only to know what's on disk, without overwriting the fields
            String notePath = path;
            OffsetDateTime created = createdIfNew;
            boolean fillFields = savedInstanceState == null;
            Notes.IO.execute(() -> loadNote(notePath, created, fillFields));
        }

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

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (saved == null) {
            return;
        }
        state.putString(STATE_PATH, saved.path());
        if (!onDisk) {
            state.putString(STATE_NEW_CREATED, saved.createdAt().toString());
        }
    }

    /**
     * Runs on the I/O thread: picks a free file name from the current time, and
     * starts from an empty note with that name and creation time. Nothing is
     * written.
     */
    private void startNewNote() {
        // seconds only: the file stores no fractions
        OffsetDateTime created = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        String path = store.newPath(created);
        LOG.info("new note " + path);
        Note empty = new Note(path, null, null, created, null, null, null);
        runOnUiThread(() -> {
            saved = empty;
            onDisk = false;
            title.requestFocus();
        });
    }

    /**
     * Runs on the I/O thread; the fields are filled on the main thread.
     *
     * @param createdIfNew for a new note that may not be written yet: when its
     *                     file doesn't exist, start from an empty note with that
     *                     creation time instead of failing. Null otherwise.
     */
    private void loadNote(String path, OffsetDateTime createdIfNew, boolean fillFields) {
        try {
            Note note;
            boolean exists = true;
            try {
                note = store.read(path);
                LOG.info("opened " + path);
            } catch (NoSuchFileException e) {
                if (createdIfNew == null) {
                    throw e;
                }
                note = new Note(path, null, null, createdIfNew, null, null, null);
                exists = false;
            }
            Note loaded = note;
            boolean loadedOnDisk = exists;
            runOnUiThread(() -> {
                saved = loaded;
                onDisk = loadedOnDisk;
                if (fillFields) {
                    title.setText(loaded.title());
                    body.setText(loaded.content());
                }
            });
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.SEVERE, "note not read: " + path, e);
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
        onDisk = true;
        Notes.IO.execute(() -> {
            try {
                store.write(edited);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "note not saved: " + edited.path(), e);
                runOnUiThread(() -> Toast.makeText(this, R.string.note_not_saved, Toast.LENGTH_LONG).show());
            }
        });
    }
}
