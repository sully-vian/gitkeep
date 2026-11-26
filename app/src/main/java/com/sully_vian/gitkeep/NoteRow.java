package com.sully_vian.gitkeep;

import com.sully_vian.gitkeep.core.Note;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One row of the note list: the note, and when it was last edited according to
 * git. A record, so that the list redraws a row when either changes.
 *
 * @param edited the time of the last commit that changed the note; null when it
 *               was never committed
 */
record NoteRow(Note note, @Nullable Instant edited) {
}
