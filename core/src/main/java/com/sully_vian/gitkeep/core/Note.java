package com.sully_vian.gitkeep.core;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A note in memory. The file format is handled by {@link NoteFormat} only.
 *
 * <p>
 * Immutable: the lists and maps given to the constructor are copied, and the
 * accessors return read-only views. Values inside {@code extra} (lists or maps
 * from the header) are not copied, and must not be modified.
 *
 * @param path       file name, e.g. "20261117-143052.md": the note's identity
 * @param title      empty when the header has no title (null becomes empty)
 * @param tags       without '#', empty when the header has no tags (null
 *                   becomes empty)
 * @param createdAt  null when the header has no usable `created` value
 * @param modifiedAt from git, never written to the file; null when unknown
 * @param extra      header keys the app doesn't use, in their original order,
 *                   written back unchanged; also holds title, tags or created
 *                   when their value couldn't be read, so that it isn't lost
 *                   (null becomes empty)
 * @param content    the body, in Markdown (null becomes empty)
 */
public record Note(
        String path,
        String title,
        List<String> tags,
        OffsetDateTime createdAt,
        OffsetDateTime modifiedAt,
        Map<String, Object> extra,
        String content) {

    public Note {
        if (path == null) {
            throw new IllegalArgumentException("a note needs a path");
        }
        if (title == null) {
            title = "";
        }
        if (tags == null)
            tags = Collections.emptyList();
        else
            tags = Collections.unmodifiableList(new ArrayList<>(tags));

        if (extra == null)
            extra = Collections.emptyMap();
        else
            extra = Collections.unmodifiableMap(new LinkedHashMap<>(extra));

        if (content == null) {
            content = "";
        }
    }

    /**
     * Newest first: by creation time, then by file name, both descending. Notes
     * without a creation time come last, sorted by file name too.
     *
     * <p>
     * File names are compared without their ".md", so that "20261116-200000-2"
     * (created after "20261116-200000" in the same second) comes first: with the
     * extension, "-" sorts before ".", and the order would be reversed.
     */
    public static final Comparator<Note> NEWEST_FIRST = Comparator
            .comparing(Note::createdAt, Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder()))
            .thenComparing(Note::pathWithoutExtension, Comparator.reverseOrder());

    /** An empty note: no header, no body. */
    public Note(String path) {
        this(path, null, null, null, null, null, null);
    }

    /** The title, or the file name when the title is empty, so that it's never blank in a list. */
    public String displayTitle() {
        if (title.isEmpty()) {
            return path;
        }
        return title;
    }

    private String pathWithoutExtension() {
        int dot = path.lastIndexOf('.');
        if (dot == -1) {
            return path;
        }
        return path.substring(0, dot);
    }
}
