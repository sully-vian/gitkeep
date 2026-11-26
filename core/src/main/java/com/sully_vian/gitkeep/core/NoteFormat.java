package com.sully_vian.gitkeep.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Converts between a note file's text and a {@link Note}.
 *
 * <p>
 * A note file is Markdown with an optional YAML header:
 *
 * <pre>
 * ---
 * title: "Meeting with the team"
 * tags: ["work", "meetings"]
 * ---
 *
 * The body, in Markdown.
 * </pre>
 */
public final class NoteFormat {

    private static final String DELIMITER = "---";

    /**
     * A line that is exactly "---". UNIX_LINES: only '\n' ends a line, not '\r'
     * or other Unicode line separators.
     */
    private static final Pattern DELIMITER_LINE = Pattern.compile(
            "^" + DELIMITER + "$", Pattern.MULTILINE | Pattern.UNIX_LINES);

    /** Keys made of simple characters are written as is, others are quoted. */
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    private NoteFormat() {
    }

    /**
     * Reads a note file.
     *
     * <ul>
     * <li>The header opens with a "---" line on the very first line, and ends at
     * the next line that is exactly "---". The blank line after it isn't part of
     * the body.
     * <li>Every header value is read as a string: unquoted `no`, `2026` or
     * `2026-11-16` don't become a boolean, a number or a date.
     * <li>`title` must be a string, and `tags` a list of strings. A value that
     * doesn't fit is kept in {@link Note#extra}, like the keys the app doesn't
     * use (`created` included: dates aren't stored in notes).
     * <li>Never fails: a file without a valid header (none, unclosed, invalid
     * YAML, not a mapping) is a note without a header, with the whole text as its
     * body, so that nothing is lost.
     * </ul>
     */
    public static Note parse(String path, String text) {
        Note withoutHeader = new Note(path).withContent(text);

        Split split = splitHeader(text);
        if (split == null) {
            return withoutHeader;
        }
        Map<String, @Nullable Object> header = loadHeader(split.header());
        if (header == null) {
            return withoutHeader;
        }

        String title = "";
        List<String> tags = Collections.emptyList();
        Map<String, @Nullable Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, @Nullable Object> entry : header.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            // a value that can't be read stays in extra, unchanged
            boolean used = false;
            if (key.equals("title")) {
                String read = readTitle(value);
                if (read != null) {
                    title = read;
                    used = true;
                }
            } else if (key.equals("tags")) {
                List<String> read = readTags(value);
                if (read != null) {
                    tags = read;
                    used = true;
                }
            }
            if (!used) {
                extra.put(key, value);
            }
        }
        return new Note(path, title, tags, extra, split.body());
    }

    /** A note file's header (its YAML text, without the "---" lines) and body. */
    private record Split(String header, String body) {
    }

    /**
     * Returns null when the text has no header: it must open with a "---" line
     * on the very first line, and close at the next line that is exactly "---".
     * The blank line after the header isn't part of the body.
     */
    private static @Nullable Split splitHeader(String text) {
        Matcher delimiter = DELIMITER_LINE.matcher(text);
        if (!delimiter.find() || delimiter.start() != 0) {
            return null;
        }
        int headerStart = delimiter.end() + 1;
        if (!delimiter.find()) {
            return null;
        }
        String header = text.substring(headerStart, delimiter.start());
        String body = text.substring(Math.min(delimiter.end() + 1, text.length()));
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        return new Split(header, body);
    }

    /**
     * Writes a note file. The rules below make the output stable: a file that
     * already follows them is written back identically after {@link #parse}, so
     * saving an unchanged note gives no git diff.
     *
     * <ul>
     * <li>Keys in a fixed order: `title`, `tags` (left out when empty), then
     * {@link Note#extra} in its order. A known key held in `extra` because it was
     * unreadable is written in its usual place, unless the note's field has been
     * set since.
     * <li>Strings always double-quoted, on a single line; lists as `["a", "b"]`.
     * <li>No header at all when the note has no title, tags or extra keys.
     * <li>A blank line between the header and a non-empty body, and a final
     * newline.
     * <li>Comments in the header are not kept: the header is rebuilt from the
     * note. A `comment` key can be used instead.
     * </ul>
     */
    public static String serialize(Note note) {
        String content = note.content();
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content += "\n";
        }

        boolean hasHeader = !note.title().isEmpty()
                || !note.tags().isEmpty()
                || !note.extra().isEmpty();
        if (!hasHeader) {
            return content;
        }

        Yaml yaml = yaml();
        StringBuilder text = new StringBuilder(DELIMITER + "\n");

        // known keys first, each from the note, or from extra when it was
        // unreadable and hasn't been set since
        Object title = note.title();
        if (note.title().isEmpty() && note.extra().containsKey("title")) {
            title = note.extra().get("title");
        }
        appendKey(text, yaml, "title", title);

        if (!note.tags().isEmpty()) {
            appendKey(text, yaml, "tags", note.tags());
        } else if (note.extra().containsKey("tags")) {
            appendKey(text, yaml, "tags", note.extra().get("tags"));
        }

        for (Map.Entry<String, Object> entry : note.extra().entrySet()) {
            String key = entry.getKey();
            if (key.equals("title") || key.equals("tags")) {
                continue;
            }
            appendKey(text, yaml, key, entry.getValue());
        }

        text.append(DELIMITER + "\n");
        if (!content.isEmpty()) {
            text.append("\n").append(content);
        }
        return text.toString();
    }

    private static void appendKey(StringBuilder text, Yaml yaml, String key, @Nullable Object value) {
        String keyText = PLAIN_KEY.matcher(key).matches() ? key : yaml.dump(key).trim();
        text.append(keyText).append(": ").append(yaml.dump(value).trim()).append("\n");
    }

    /**
     * Returns null when the header isn't a YAML mapping. An empty header is an
     * empty map.
     */
    private static @Nullable Map<String, @Nullable Object> loadHeader(String yamlText) {
        Object loaded;
        try {
            loaded = yaml().load(yamlText);
        } catch (YAMLException e) {
            return null;
        }
        Map<String, @Nullable Object> header = new LinkedHashMap<>();
        if (loaded == null) {
            return header;
        }
        if (!(loaded instanceof Map)) {
            return null;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) loaded).entrySet()) {
            header.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return header;
    }

    /** Returns null when the value isn't a string. */
    private static @Nullable String readTitle(@Nullable Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        return (String) value;
    }

    /** Returns null when the value isn't a list of strings. */
    private static @Nullable List<String> readTags(@Nullable Object value) {
        if (!(value instanceof List)) {
            return null;
        }
        List<String> tags = new ArrayList<>();
        for (Object tag : (List<?>) value) {
            if (!(tag instanceof String)) {
                return null;
            }
            tags.add((String) tag);
        }
        return tags;
    }

    /**
     * A Yaml instance that reads every scalar as a string: without implicit
     * resolvers, unquoted
     * `no`, `2026` or `2026-11-16` stay strings instead of becoming a boolean, a
     * number or a date.
     * Not thread-safe, hence one instance per call.
     * When writing: flow style (`["a", "b"]`), every string double-quoted, on a
     * single line, with Unicode written as is.
     */
    private static Yaml yaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        dumperOptions.setDefaultScalarStyle(DumperOptions.ScalarStyle.DOUBLE_QUOTED);
        dumperOptions.setAllowUnicode(true);
        dumperOptions.setSplitLines(false);
        dumperOptions.setWidth(Integer.MAX_VALUE);
        Resolver stringsOnly = new Resolver() {
            @Override
            protected void addImplicitResolvers() {
            }
        };
        return new Yaml(
                new SafeConstructor(loaderOptions),
                new Representer(dumperOptions),
                dumperOptions,
                loaderOptions,
                stringsOnly);
    }
}
