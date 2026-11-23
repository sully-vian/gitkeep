package com.sully_vian.gitkeep.core;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * created: "2026-11-17T14:30:52+01:00"
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
     * <li>`title` must be a string, `tags` a list of strings, and `created` a
     * timestamp with a time and an offset. A value that doesn't fit is kept in
     * {@link Note#extra}, like the keys the app doesn't use.
     * <li>Never fails: a file without a valid header (none, unclosed, invalid
     * YAML, not a mapping) is a note without a header, with the whole text as its
     * body, so that nothing is lost.
     * </ul>
     */
    public static Note parse(String path, String text) {
        Note withoutHeader = new Note(path, null, null, null, null, null, text);

        Split split = splitHeader(text);
        if (split == null) {
            return withoutHeader;
        }
        Map<String, Object> header = loadHeader(split.header());
        if (header == null) {
            return withoutHeader;
        }

        String title = null;
        List<String> tags = null;
        OffsetDateTime createdAt = null;
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : header.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            boolean used = false;
            if (key.equals("title")) {
                title = readTitle(value);
                used = title != null;
            } else if (key.equals("tags")) {
                tags = readTags(value);
                used = tags != null;
            } else if (key.equals("created")) {
                createdAt = readCreated(value);
                used = createdAt != null;
            }
            if (!used) {
                extra.put(key, value);
            }
        }
        return new Note(path, title, tags, createdAt, null, extra, split.body());
    }

    /** A note file's header (its YAML text, without the "---" lines) and body. */
    private record Split(String header, String body) {
    }

    /**
     * Returns null when the text has no header: it must open with a "---" line
     * on the very first line, and close at the next line that is exactly "---".
     * The blank line after the header isn't part of the body.
     */
    private static Split splitHeader(String text) {
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
     * Returns null when the header isn't a YAML mapping. An empty header is an
     * empty map.
     */
    private static Map<String, Object> loadHeader(String yamlText) {
        Object loaded;
        try {
            loaded = yaml().load(yamlText);
        } catch (YAMLException e) {
            return null;
        }
        Map<String, Object> header = new LinkedHashMap<>();
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
    private static String readTitle(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        return (String) value;
    }

    /** Returns null when the value isn't a list of strings. */
    private static List<String> readTags(Object value) {
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
     * Returns null when the value isn't a timestamp with a time and an offset,
     * e.g. a date alone ("2026-11-16", written by hand).
     */
    private static OffsetDateTime readCreated(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        try {
            return OffsetDateTime.parse((String) value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * A Yaml instance that reads every scalar as a string: without implicit
     * resolvers, unquoted
     * `no`, `2026` or `2026-11-16` stay strings instead of becoming a boolean, a
     * number or a date.
     * Not thread-safe, hence one instance per call.
     */
    private static Yaml yaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        DumperOptions dumperOptions = new DumperOptions();
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
