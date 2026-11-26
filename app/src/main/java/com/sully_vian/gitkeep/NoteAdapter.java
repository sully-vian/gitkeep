package com.sully_vian.gitkeep;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.sully_vian.gitkeep.core.Note;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Rows of the note list: each note's title, and when it was last edited. {@link #submitList} compares the
 * new list with the old one, so that only the changed rows are redrawn.
 */
final class NoteAdapter extends ListAdapter<NoteRow, NoteAdapter.Row> {

    private final Consumer<Note> onClick;
    private final Consumer<Note> onDelete;

    /**
     * @param onClick  called with the tapped note
     * @param onDelete called with the note to delete, when chosen from a screen
     *                 reader's actions (swiping is hard to do with one)
     */
    NoteAdapter(Consumer<Note> onClick, Consumer<Note> onDelete) {
        super(new DiffUtil.ItemCallback<NoteRow>() {
            @Override
            public boolean areItemsTheSame(@NonNull NoteRow a, @NonNull NoteRow b) {
                // the file name is the note's identity
                return a.note().path().equals(b.note().path());
            }

            @Override
            public boolean areContentsTheSame(@NonNull NoteRow a, @NonNull NoteRow b) {
                return a.equals(b);
            }
        });
        this.onClick = onClick;
        this.onDelete = onDelete;
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_note, parent, false);
        Row row = new Row(view);
        view.setOnClickListener(v -> withNote(row, onClick));
        ViewCompat.addAccessibilityAction(view, parent.getContext().getString(R.string.delete),
                (v, arguments) -> {
                    withNote(row, onDelete);
                    return true;
                });
        return row;
    }

    /** Calls the action with the note the row shows now: rows are reused for other notes. */
    private void withNote(Row row, Consumer<Note> action) {
        int position = row.getBindingAdapterPosition();
        if (position != RecyclerView.NO_POSITION) {
            action.accept(getItem(position).note());
        }
    }

    Note noteAt(int position) {
        return getItem(position).note();
    }

    @Override
    public void onBindViewHolder(@NonNull Row row, int position) {
        NoteRow item = getItem(position);
        row.title.setText(item.note().displayTitle());
        Instant edited = item.edited();
        if (edited == null) {
            row.edited.setVisibility(View.GONE);
        } else {
            // "5 minutes ago", "Yesterday", "12 Nov"
            row.edited.setText(DateUtils.getRelativeTimeSpanString(edited.toEpochMilli(),
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            row.edited.setVisibility(View.VISIBLE);
        }
    }

    static final class Row extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView edited;

        Row(View view) {
            super(view);
            title = view.findViewById(R.id.title);
            edited = view.findViewById(R.id.edited);
        }
    }
}
