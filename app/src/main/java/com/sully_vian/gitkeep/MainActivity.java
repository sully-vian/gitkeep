package com.sully_vian.gitkeep;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.note1).setOnClickListener(v -> openNote());
        findViewById(R.id.note2).setOnClickListener(v -> openNote());
    }

    private void openNote() {
        startActivity(new Intent(this, NoteActivity.class));
    }
}
