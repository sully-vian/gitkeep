package com.sully_vian.gitkeep;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.sully_vian.gitkeep.core.Note;
import java.util.function.Consumer;

/**
 * Rows of the note list, one title per note. {@link #submitList} compares the
 * new list with the old one, so that only the changed rows are redrawn.
 */
final class NoteAdapter extends ListAdapter<Note, NoteAdapter.Row> {

    private final Consumer<Note> onClick;

    /** @param onClick called with the tapped note */
    NoteAdapter(Consumer<Note> onClick) {
        super(new DiffUtil.ItemCallback<Note>() {
            @Override
            public boolean areItemsTheSame(@NonNull Note a, @NonNull Note b) {
                // the file name is the note's identity
                return a.path().equals(b.path());
            }

            @Override
            public boolean areContentsTheSame(@NonNull Note a, @NonNull Note b) {
                return a.equals(b);
            }
        });
        this.onClick = onClick;
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_note, parent, false);
        Row row = new Row(view);
        view.setOnClickListener(v -> {
            // the row's current position: rows are reused for other notes
            int position = row.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION) {
                onClick.accept(getItem(position));
            }
        });
        return row;
    }

    @Override
    public void onBindViewHolder(@NonNull Row row, int position) {
        row.title.setText(getItem(position).displayTitle());
    }

    static final class Row extends RecyclerView.ViewHolder {
        final TextView title;

        Row(View view) {
            super(view);
            title = view.findViewById(R.id.title);
        }
    }
}
