package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NoteStoreTest {

    private static final Path FIXTURES = Path.of(System.getProperty("fixtures.dir"));

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
            names = files.map(p -> p.getFileName().toString()).sorted().toList();
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
}
