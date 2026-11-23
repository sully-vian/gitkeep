package com.sully_vian.gitkeep;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class NoteActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "com.sully_vian.gitkeep.extra.TITLE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        TextView text = findViewById(R.id.title);
        text.setText(title);
    }
}
