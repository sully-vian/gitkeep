package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NoteFormatTest {

    private static final Path FIXTURES = Path.of(System.getProperty("fixtures.dir"));

    private static Note parseFixture(String name) throws IOException {
        String text = Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8);
        return NoteFormat.parse(name, text);
    }

    static Stream<String> fixtureNames() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    // --- every fixture

    @ParameterizedTest
    @MethodSource("fixtureNames")
    void everyFixtureParses(String name) throws IOException {
        Note note = parseFixture(name);
        assertEquals(name, note.path());
        assertNotNull(note.title());
        assertNotNull(note.tags());
        assertNotNull(note.extra());
        assertNotNull(note.content());
        assertNull(note.modifiedAt());
    }

    // --- regular fixtures

    @Test
    void regularNote() throws IOException {
        Note note = parseFixture("20261101-090000.md");
        assertEquals("Welcome to gitkeep", note.title());
        assertEquals(List.of("gitkeep"), note.tags());
        assertEquals(OffsetDateTime.parse("2026-11-01T09:00:00+01:00"), note.createdAt());
        assertTrue(note.extra().isEmpty());
        assertTrue(note.content().startsWith("Notes are plain **Markdown** files"), note.content());
    }

    @Test
    void unicodeTitleAndTags() throws IOException {
        Note note = parseFixture("20261112-081045.md");
        assertEquals("Accents et Unicode : « été » 📝", note.title());
        assertEquals(List.of("français", "tests"), note.tags());
    }

    @Test
    void htmlInTitleIsJustText() throws IOException {
        Note note = parseFixture("20261114-120000.md");
        assertEquals("XSS test <img src=x onerror=alert('xss:title')>", note.title());
    }

    // --- edge-case fixtures

    @Test
    void emptyFile() throws IOException {
        Note note = parseFixture("20261115-075959.md");
        assertEquals("", note.title());
        assertTrue(note.tags().isEmpty());
        assertNull(note.createdAt());
        assertEquals("", note.content());
    }

    @Test
    void noHeader() throws IOException {
        String name = "note with spaces.md";
        Note note = parseFixture(name);
        assertEquals("", note.title());
        assertTrue(note.tags().isEmpty());
        assertNull(note.createdAt());
        assertTrue(note.extra().isEmpty());
        assertEquals(Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8), note.content());
    }

    @Test
    void unquotedValuesAreStrings() throws IOException {
        Note note = parseFixture("20261116-200000-2.md");
        assertEquals("no", note.title());
        assertEquals(List.of("yes", "2026", "todo"), note.tags());
        // a date without a time isn't a usable creation time, but is kept as written
        assertNull(note.createdAt());
        assertEquals("2026-11-16", note.extra().get("created"));
    }

    @Test
    void unknownKeysAndHorizontalRules() throws IOException {
        Note note = parseFixture("20261116-200000.md");
        assertEquals("Unknown keys and a horizontal rule", note.title());
        assertEquals(List.of("aliases", "status"), List.copyOf(note.extra().keySet()));
        assertEquals(List.of("extra-keys", "hr test"), note.extra().get("aliases"));
        assertEquals("draft", note.extra().get("status"));
        assertTrue(note.content().startsWith("The header has keys"), note.content());
        assertTrue(note.content().endsWith("Text after a horizontal rule.\n\n---\n"), note.content());
    }

    // --- serialize

    /** Fixtures that don't follow the writing rules, tested separately below. */
    private static final List<String> NOT_ROUND_TRIP = List.of(
            "20261116-200000.md", // header comment
            "20261116-200000-2.md"); // unquoted values

    @ParameterizedTest
    @MethodSource("fixtureNames")
    void roundTripIsIdentical(String name) throws IOException {
        if (NOT_ROUND_TRIP.contains(name)) {
            return;
        }
        String text = Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8);
        assertEquals(text, NoteFormat.serialize(NoteFormat.parse(name, text)));
    }

    @Test
    void headerCommentIsDropped() throws IOException {
        String name = "20261116-200000.md";
        String text = Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8);
        String expected = text.replace("# A comment written by hand in the header\n", "");
        assertEquals(expected, NoteFormat.serialize(NoteFormat.parse(name, text)));
    }

    @Test
    void unquotedValuesAreWrittenQuoted() throws IOException {
        String serialized = NoteFormat.serialize(parseFixture("20261116-200000-2.md"));
        assertTrue(serialized.startsWith("---\n"
                + "title: \"no\"\n"
                + "tags: [\"yes\", \"2026\", \"todo\"]\n"
                + "created: \"2026-11-16\"\n"
                + "---\n\n"), serialized);
    }

    @Test
    void newNote() {
        Note note = new Note(
                "20261117-143052.md",
                "Say \"hi\" \\ to 📝",
                List.of("work"),
                OffsetDateTime.parse("2026-11-17T14:30:00Z"),
                null,
                Map.of("my key", "x"),
                "Body without final newline");
        assertEquals("---\n"
                + "title: \"Say \\\"hi\\\" \\\\ to 📝\"\n"
                + "tags: [\"work\"]\n"
                + "created: \"2026-11-17T14:30:00Z\"\n"
                + "\"my key\": \"x\"\n"
                + "---\n\n"
                + "Body without final newline\n", NoteFormat.serialize(note));
    }

    @Test
    void emptyNoteHasNoHeader() {
        assertEquals("", NoteFormat.serialize(new Note("a.md")));
    }

    @Test
    void multilineValueStaysOnOneLine() {
        Note note = new Note("a.md", "two\nlines", null, null, null, null, null);
        String serialized = NoteFormat.serialize(note);
        assertEquals("---\ntitle: \"two\\nlines\"\n---\n", serialized);
        assertEquals("two\nlines", NoteFormat.parse("a.md", serialized).title());
    }

    @Test
    void settingTitleReplacesUnreadableOne() {
        Note parsed = NoteFormat.parse("a.md", "---\ntitle: [a, b]\n---\n");
        Note note = new Note(parsed.path(), "New", parsed.tags(), parsed.createdAt(),
                parsed.modifiedAt(), parsed.extra(), parsed.content());
        assertEquals("---\ntitle: \"New\"\n---\n", NoteFormat.serialize(note));
    }

    @Test
    void noteIsImmutable() {
        List<String> tags = new ArrayList<>(List.of("work"));
        Note note = new Note("a.md", null, tags, null, null, null, null);
        tags.add("later");
        assertEquals(List.of("work"), note.tags());
        assertThrows(UnsupportedOperationException.class, () -> note.tags().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> note.extra().put("k", "v"));
    }

    @Test
    void nullsBecomeEmpty() {
        Note note = new Note("a.md");
        assertEquals("", note.title());
        assertTrue(note.tags().isEmpty());
        assertTrue(note.extra().isEmpty());
        assertEquals("", note.content());
    }

    // --- inline cases, not worth a fixture

    @Test
    void emptyHeader() {
        Note note = NoteFormat.parse("a.md", "---\n---\n\nBody\n");
        assertEquals("", note.title());
        assertEquals("Body\n", note.content());
    }

    @Test
    void unclosedHeaderIsBody() {
        String text = "---\ntitle: \"x\"\n\nBody\n";
        Note note = NoteFormat.parse("a.md", text);
        assertEquals("", note.title());
        assertEquals(text, note.content());
    }

    @Test
    void headerNotOnFirstLineIsBody() {
        String text = "\n---\ntitle: \"x\"\n---\n";
        Note note = NoteFormat.parse("a.md", text);
        assertEquals("", note.title());
        assertEquals(text, note.content());
    }

    @Test
    void invalidYamlIsBody() {
        String text = "---\ntitle: [unclosed\n---\nBody\n";
        Note note = NoteFormat.parse("a.md", text);
        assertEquals("", note.title());
        assertEquals(text, note.content());
    }

    @Test
    void delimiterAloneIsBody() {
        Note note = NoteFormat.parse("a.md", "---");
        assertEquals("---", note.content());
    }

    @Test
    void closingLineMustBeExactlyDelimiter() {
        String text = "---\ntitle: \"x\"\n----\n --- \n";
        Note note = NoteFormat.parse("a.md", text);
        assertEquals("", note.title());
        assertEquals(text, note.content());
    }

    @Test
    void carriageReturnIsNotALineEnd() {
        String text = "---\r\ntitle: \"x\"\r\n---\r\n";
        Note note = NoteFormat.parse("a.md", text);
        assertEquals("", note.title());
        assertEquals(text, note.content());
    }

    @Test
    void headerAtEndOfFileWithoutNewline() {
        Note note = NoteFormat.parse("a.md", "---\ntitle: \"x\"\n---");
        assertEquals("x", note.title());
        assertEquals("", note.content());
    }

    @Test
    void onlyOneBlankLineIsRemoved() {
        Note note = NoteFormat.parse("a.md", "---\ntitle: \"x\"\n---\n\n\nBody\n");
        assertEquals("\nBody\n", note.content());
    }

    @Test
    void wrongTypesAreKeptInExtra() {
        Note note = NoteFormat.parse("a.md", "---\ntitle: [a, b]\ntags: work\n---\n");
        assertEquals("", note.title());
        assertTrue(note.tags().isEmpty());
        assertEquals(List.of("a", "b"), note.extra().get("title"));
        assertEquals("work", note.extra().get("tags"));
    }
}
