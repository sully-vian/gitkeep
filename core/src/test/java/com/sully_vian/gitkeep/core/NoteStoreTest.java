package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NoteStoreTest {

    private static final Path FIXTURES = TestFiles.fixturesDir();

    @TempDir
    Path dir;

    private void file(String name, String text) throws IOException {
        Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
    }

    private static List<String> paths(List<Note> notes) {
        return notes.stream().map(Note::path).toList();
    }

    // --- list

    @Test
    void emptyFolder() throws IOException {
        assertTrue(new NoteStore(dir).list().isEmpty());
    }

    @Test
    void missingFolderHasNoNotes() throws IOException {
        assertTrue(new NoteStore(dir.resolve("missing")).list().isEmpty());
    }

    @Test
    void listsEveryFixture() throws IOException {
        List<String> names;
        try (Stream<Path> files = Files.list(FIXTURES)) {
            names = files.map(TestFiles::name).sorted().toList();
        }
        for (String name : names) {
            Files.copy(FIXTURES.resolve(name), dir.resolve(name));
        }

        List<Note> notes = new NoteStore(dir).list();
        assertEquals(names, paths(notes));
        Note welcome = notes.get(names.indexOf("20261101-090000.md"));
        assertEquals("Welcome to gitkeep", welcome.title());
    }

    @Test
    void sortedByFileName() throws IOException {
        file("b.md", "");
        file("a.md", "");
        file("20261101-090000.md", "");
        assertEquals(List.of("20261101-090000.md", "a.md", "b.md"), paths(new NoteStore(dir).list()));
    }

    @Test
    void onlyNoteFilesAreListed() throws IOException {
        file("note.md", "");
        file("notes.txt", "");
        file("README", "");
        file(".hidden.md", "");
        Files.createDirectory(dir.resolve("folder.md"));
        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/inside.md"), "");
        assertEquals(List.of("note.md"), paths(new NoteStore(dir).list()));
    }

    @Test
    void unreadableFileIsLeftOut() throws IOException {
        file("a.md", "fine");
        Files.write(dir.resolve("b.md"), new byte[] { 'x', (byte) 0xC3 }); // cut UTF-8
        file("c.md", "fine too");
        assertEquals(List.of("a.md", "c.md"), paths(new NoteStore(dir).list()));
    }

    @Test
    void folderThatIsAFileFails() throws IOException {
        file("not-a-folder", "");
        assertThrows(IOException.class, () -> new NoteStore(dir.resolve("not-a-folder")).list());
    }

    // --- read

    @Test
    void readsOneNote() throws IOException {
        file("20261117-143052.md", "---\ntitle: \"Hello\"\n---\n\nBody\n");
        Note note = new NoteStore(dir).read("20261117-143052.md");
        assertEquals("20261117-143052.md", note.path());
        assertEquals("Hello", note.title());
        assertEquals("Body\n", note.content());
    }

    @Test
    void readsUnicode() throws IOException {
        file("a.md", "été 📝\n");
        assertEquals("été 📝\n", new NoteStore(dir).read("a.md").content());
    }

    @Test
    void missingNoteFails() {
        assertThrows(IOException.class, () -> new NoteStore(dir).read("missing.md"));
    }

    @Test
    void invalidUtf8Fails() throws IOException {
        Files.write(dir.resolve("a.md"), new byte[] { (byte) 0xFF });
        assertThrows(IOException.class, () -> new NoteStore(dir).read("a.md"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "../a.md", "sub/a.md", "sub\\a.md", ".hidden.md", "a.txt", "a" })
    void pathOutsideTheNotesIsRefused(String path) {
        assertThrows(IllegalArgumentException.class, () -> new NoteStore(dir).read(path));
    }

    // --- write

    private static Note note(String path, String title, String content) {
        return new Note(path).withTitle(title).withContent(content);
    }

    /** Every file and folder in the notes folder, hidden ones included. */
    private List<String> allFiles() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(TestFiles::name).sorted().toList();
        }
    }

    @Test
    void writtenNoteReadsBack() throws IOException {
        NoteStore store = new NoteStore(dir);
        Note note = new Note("20261117-143052.md", "Été 📝", List.of("work"),
                Map.of("status", "draft"), "Body\n");
        store.write(note);
        assertEquals(note, store.read("20261117-143052.md"));
    }

    @Test
    void writesTheSerializedText() throws IOException {
        Note note = note("a.md", "Été", "Body\n");
        new NoteStore(dir).write(note);
        assertArrayEquals(NoteFormat.serialize(note).getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(dir.resolve("a.md")));
    }

    @Test
    void writeCreatesTheFolder() throws IOException {
        Path notes = dir.resolve("notes");
        new NoteStore(notes).write(note("a.md", "x", ""));
        assertTrue(Files.isRegularFile(notes.resolve("a.md")));
    }

    @Test
    void writeReplacesTheFile() throws IOException {
        NoteStore store = new NoteStore(dir);
        store.write(note("a.md", "old", "old body\n"));
        store.write(note("a.md", "new", "new body\n"));
        assertEquals("new", store.read("a.md").title());
        assertEquals(List.of("a.md"), allFiles());
    }

    @Test
    void failedWriteLeavesNoTempFile() throws IOException {
        // a folder where the note's file should be: the final rename fails
        Files.createDirectory(dir.resolve("a.md"));
        Files.writeString(dir.resolve("a.md/inside"), "");
        assertThrows(IOException.class, () -> new NoteStore(dir).write(note("a.md", "x", "")));
        assertEquals(List.of("a.md"), allFiles());
    }

    @ParameterizedTest
    @ValueSource(strings = { "../a.md", ".hidden.md", "a.txt" })
    void writeOutsideTheNotesIsRefused(String path) throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> new NoteStore(dir).write(note(path, "x", "")));
        assertTrue(allFiles().isEmpty());
    }

    // --- delete

    @Test
    void deleteRemovesTheFile() throws IOException {
        file("a.md", "");
        file("b.md", "");
        new NoteStore(dir).delete("a.md");
        assertEquals(List.of("b.md"), allFiles());
    }

    @Test
    void deleteMissingNoteDoesNothing() throws IOException {
        new NoteStore(dir).delete("missing.md");
        assertTrue(allFiles().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "../a.md", ".hidden.md", "a.txt" })
    void deleteOutsideTheNotesIsRefused(String path) {
        assertThrows(IllegalArgumentException.class, () -> new NoteStore(dir).delete(path));
    }

    // --- newPath

    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-11-17T14:30:52+01:00");

    @Test
    void newPathIsTheCreationTime() {
        assertEquals("20261117-143052.md", new NoteStore(dir).newPath(CREATED));
    }

    @Test
    void newPathPadsWithZeros() {
        OffsetDateTime early = OffsetDateTime.parse("2026-01-02T03:04:05+01:00");
        assertEquals("20260102-030405.md", new NoteStore(dir).newPath(early));
    }

    @Test
    void newPathKeepsTheOffsetsLocalTime() {
        // 23:30 in New York is already the next day in UTC: the name keeps 23:30
        OffsetDateTime evening = OffsetDateTime.parse("2026-11-17T23:30:00-05:00");
        assertEquals("20261117-233000.md", new NoteStore(dir).newPath(evening));
    }

    @Test
    void newPathInMissingFolder() {
        assertEquals("20261117-143052.md", new NoteStore(dir.resolve("missing")).newPath(CREATED));
    }

    @Test
    void takenNameGetsASuffix() throws IOException {
        file("20261117-143052.md", "");
        assertEquals("20261117-143052-2.md", new NoteStore(dir).newPath(CREATED));
    }

    @Test
    void suffixesCountUp() throws IOException {
        file("20261117-143052.md", "");
        file("20261117-143052-2.md", "");
        file("20261117-143052-3.md", "");
        assertEquals("20261117-143052-4.md", new NoteStore(dir).newPath(CREATED));
    }

    @Test
    void freeBaseNameWinsOverSuffixes() throws IOException {
        file("20261117-143052-2.md", "");
        assertEquals("20261117-143052.md", new NoteStore(dir).newPath(CREATED));
    }

    @Test
    void folderTakesTheName() throws IOException {
        Files.createDirectory(dir.resolve("20261117-143052.md"));
        assertEquals("20261117-143052-2.md", new NoteStore(dir).newPath(CREATED));
    }

    @Test
    void newPathIsAValidNotePath() throws IOException {
        NoteStore store = new NoteStore(dir);
        String path = store.newPath(CREATED);
        store.write(new Note(path).withTitle("x"));
        assertEquals("x", store.read(path).title());
    }
}
