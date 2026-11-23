package com.sully_vian.gitkeep.core;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
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

    /** An empty note: no header, no body. */
    public Note(String path) {
        this(path, null, null, null, null, null, null);
    }
}
