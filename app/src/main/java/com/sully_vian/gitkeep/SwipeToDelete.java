package com.sully_vian.gitkeep;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import java.util.function.IntConsumer;

/**
 * Swiping a row of the note list left or right deletes it. A colored background
 * with a trash icon shows in the space the row leaves as it moves.
 */
final class SwipeToDelete extends ItemTouchHelper.SimpleCallback {

    private final IntConsumer onSwiped;
    private final Paint background = new Paint();
    private final Drawable icon;

    /** @param onSwiped called with the position of the row swiped away */
    SwipeToDelete(Context context, IntConsumer onSwiped) {
        super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        this.onSwiped = onSwiped;
        background.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorErrorContainer, SwipeToDelete.class.getName()));
        icon = AppCompatResources.getDrawable(context, R.drawable.ic_delete).mutate();
        icon.setTint(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnErrorContainer, SwipeToDelete.class.getName()));
    }

    /** Rows can't be dragged to another place. */
    @Override
    public boolean onMove(@NonNull RecyclerView list, @NonNull RecyclerView.ViewHolder row,
            @NonNull RecyclerView.ViewHolder target) {
        return false;
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder row, int direction) {
        int position = row.getBindingAdapterPosition();
        if (position != RecyclerView.NO_POSITION) {
            onSwiped.accept(position);
        }
    }

    @Override
    public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView list,
            @NonNull RecyclerView.ViewHolder row, float dX, float dY, int actionState, boolean active) {
        if (dX != 0) {
            drawBehind(canvas, row.itemView, dX);
        }
        super.onChildDraw(canvas, list, row, dX, dY, actionState, active);
    }

    /** Only in the space left by the row, since the row itself is transparent. */
    private void drawBehind(Canvas canvas, View row, float dX) {
        int top = row.getTop();
        int bottom = row.getBottom();
        int margin = (row.getHeight() - icon.getIntrinsicHeight()) / 2;

        float left;
        float right;
        int iconLeft;
        if (dX > 0) {
            left = row.getLeft();
            right = row.getLeft() + dX;
            iconLeft = row.getLeft() + margin;
        } else {
            left = row.getRight() + dX;
            right = row.getRight();
            iconLeft = row.getRight() - margin - icon.getIntrinsicWidth();
        }

        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        canvas.drawRect(left, top, right, bottom, background);
        icon.setBounds(iconLeft, top + margin,
                iconLeft + icon.getIntrinsicWidth(), top + margin + icon.getIntrinsicHeight());
        icon.draw(canvas);
        canvas.restore();
    }
}
