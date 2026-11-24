package com.sully_vian.gitkeep.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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

    /** A new note's file name, without extension: "20261117-143052". */
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

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
     * Writes a note to its file, creating the folder if needed, and replacing the
     * file if it exists.
     *
     * <p>
     * Atomic: the text goes to a hidden temp file in the same folder, forced to
     * the disk, then renamed over the note. A crash or a full disk leaves either
     * the old file or the new one, never half a note. The temp file starts with
     * "." so that {@link #list()} never takes it for a note.
     *
     * @throws IllegalArgumentException when the note's path isn't the name of a
     *                                  note of this folder
     * @throws IOException              when the file can't be written; the old
     *                                  file, if any, is then unchanged
     */
    public void write(Note note) throws IOException {
        Path file = resolve(note.path());
        byte[] bytes = NoteFormat.serialize(note).getBytes(StandardCharsets.UTF_8);

        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            // only still there when something failed
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Deletes a note's file. Deleting a note that doesn't exist does nothing.
     *
     * @throws IllegalArgumentException when the path isn't the name of a note of
     *                                  this folder
     * @throws IOException              when the file can't be deleted
     */
    public void delete(String path) throws IOException {
        Files.deleteIfExists(resolve(path));
    }

    /**
     * A free file name for a new note: its creation time, "YYYYMMDD-HHMMSS.md",
     * then "-2", "-3"… before the extension when that name is taken (by any file
     * or folder).
     *
     * <p>
     * The time is read as written in {@code createdAt}, in its own offset, not
     * converted: a note created at 23:30 local time is named 23:30 wherever the
     * name is read.
     *
     * <p>
     * The name is free when this returns; write the note right away.
     */
    public String newPath(OffsetDateTime createdAt) {
        String base = NAME_FORMAT.format(createdAt);
        String name = base + EXTENSION;
        for (int suffix = 2; Files.exists(dir.resolve(name)); suffix++) {
            name = base + "-" + suffix + EXTENSION;
        }
        return name;
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
