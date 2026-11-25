package com.sully_vian.gitkeep.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A note in memory. The file format is handled by {@link NoteFormat} only.
 *
 * <p>
 * Immutable: the lists and maps given to the constructor are copied, and the
 * accessors return read-only views. Values inside {@code extra} (lists or maps
 * from the header) are not copied, and must not be modified. To change a note,
 * build a new one, e.g. with {@link #withTitle}.
 *
 * <p>
 * No dates: a note made by the app is named after its creation time
 * ("20261117-143052.md"), and the dates of edits come from git.
 *
 * @param path    file name, e.g. "20261117-143052.md": the note's identity
 * @param title   empty when the header has no title
 * @param tags    without '#', empty when the header has no tags
 * @param extra   header keys the app doesn't use, in their original order,
 *                written back unchanged; also holds title or tags when their
 *                value couldn't be read, so that it isn't lost. A value is null
 *                only for an explicit `!!null` in the header
 * @param content the body, in Markdown
 */
public record Note(
        String path,
        String title,
        List<String> tags,
        Map<String, @Nullable Object> extra,
        String content) {

    /** A file name made by the app: its creation time, maybe with a "-2" suffix. */
    private static final Pattern TIMESTAMP_NAME = Pattern.compile("\\d{8}-\\d{6}(-\\d+)?\\.md");

    /** @throws NullPointerException when anything is null */
    public Note {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        tags = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(tags, "tags")));
        // explicit types: NullAway can't infer that the copy keeps the @Nullable values
        extra = Collections.<String, @Nullable Object>unmodifiableMap(
                new LinkedHashMap<String, @Nullable Object>(Objects.requireNonNull(extra, "extra")));
    }

    /**
     * Newest first, by file name: names made by the app are creation times, so
     * they come first, newest first. Other names follow, in alphabetical order.
     *
     * <p>
     * Names are compared without their ".md", so that "20261116-200000-2"
     * (created after "20261116-200000" in the same second) comes first: with the
     * extension, "-" sorts before ".", and the order would be reversed.
     */
    public static final Comparator<Note> NEWEST_FIRST = (a, b) -> {
        boolean aTimestamp = a.hasTimestampName();
        boolean bTimestamp = b.hasTimestampName();
        if (aTimestamp != bTimestamp) {
            return aTimestamp ? -1 : 1;
        }
        if (aTimestamp) {
            return b.pathWithoutExtension().compareTo(a.pathWithoutExtension());
        }
        return a.path.compareTo(b.path);
    };

    /** An empty note: no header, no body. */
    public Note(String path) {
        this(path, "", Collections.emptyList(), Collections.emptyMap(), "");
    }

    /** This note with another title. */
    public Note withTitle(String title) {
        return new Note(path, title, tags, extra, content);
    }

    /** This note with another body. */
    public Note withContent(String content) {
        return new Note(path, title, tags, extra, content);
    }

    /** The title, or the file name when the title is empty, so that it's never blank in a list. */
    public String displayTitle() {
        if (title.isEmpty()) {
            return path;
        }
        return title;
    }

    /** Whether the file name is a creation time, as the app names notes. */
    private boolean hasTimestampName() {
        return TIMESTAMP_NAME.matcher(path).matches();
    }

    private String pathWithoutExtension() {
        int dot = path.lastIndexOf('.');
        if (dot == -1) {
            return path;
        }
        return path.substring(0, dot);
    }
}
