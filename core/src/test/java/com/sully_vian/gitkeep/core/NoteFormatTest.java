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
