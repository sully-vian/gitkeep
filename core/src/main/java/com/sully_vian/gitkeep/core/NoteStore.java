package com.sully_vian.gitkeep.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The notes of one folder: every ".md" file directly in it is a note, and its
 * file name is the note's path. Subfolders, hidden files (".x.md") and other
 * files are ignored.
 *
 * <p>
 * Blocking file I/O: call it off the main thread.
 */
public final class NoteStore {

    private static final String EXTENSION = ".md";

    private final Path dir;

    public NoteStore(Path dir) {
        this.dir = dir;
    }

    /**
     * Reads every note of the folder, sorted by file name. A file that can't be
     * read (I/O error, not UTF-8) is left out rather than failing the whole list.
     * A missing folder has no notes.
     *
     * @throws IOException when the folder itself can't be listed
     */
    public List<Note> list() throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (isNoteName(name) && Files.isRegularFile(file)) {
                    names.add(name);
                }
            }
        } catch (NoSuchFileException e) {
            return new ArrayList<>();
        }
        Collections.sort(names);

        List<Note> notes = new ArrayList<>();
        for (String name : names) {
            try {
                notes.add(read(name));
            } catch (IOException e) {
                // left out: one bad file must not hide the other notes
            }
        }
        return notes;
    }

    /**
     * Reads one note.
     *
     * @param path the note's file name, e.g. "20261117-143052.md"
     * @throws IllegalArgumentException when the path isn't the name of a note
     *                                  of this folder (e.g. "../x.md", ".x.md")
     * @throws IOException              when the file can't be read, or isn't
     *                                  valid UTF-8 (reading it anyway would
     *                                  corrupt it on the next save)
     */
    public Note read(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(resolve(path));
        return NoteFormat.parse(path, decodeUtf8(bytes));
    }

    /**
     * A ".md" file name directly in the folder: no separator (so nothing like
     * "../x.md"), and not hidden (".x.md").
     */
    private static boolean isNoteName(String name) {
        return name.endsWith(EXTENSION)
                && !name.startsWith(".")
                && !name.contains("/")
                && !name.contains("\\");
    }

    /** The file of a note, refusing anything outside the folder. */
    private Path resolve(String path) {
        if (!isNoteName(path)) {
            throw new IllegalArgumentException("not a note file name: " + path);
        }
        return dir.resolve(path);
    }

    /** Strict: invalid UTF-8 throws instead of being replaced with '�'. */
    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }
}
