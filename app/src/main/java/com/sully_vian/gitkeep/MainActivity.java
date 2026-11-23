package com.sully_vian.gitkeep;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.note1).setOnClickListener(v -> openNote(getString(R.string.note_1)));
        findViewById(R.id.note2).setOnClickListener(v -> openNote(getString(R.string.note_2)));
    }

    private void openNote(String title) {
        Intent intent = new Intent(this, NoteActivity.class);
        intent.putExtra(NoteActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }
}
