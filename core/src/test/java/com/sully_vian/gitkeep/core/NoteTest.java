package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoteTest {

    private static Note note(String path, String title, String createdAt) {
        OffsetDateTime created = createdAt == null ? null : OffsetDateTime.parse(createdAt);
        return new Note(path, title, null, created, null, null, null);
    }

    // --- displayTitle

    @Test
    void displayTitleIsTheTitle() {
        assertEquals("Hello", note("a.md", "Hello", null).displayTitle());
    }

    @Test
    void emptyTitleShowsTheFileName() {
        assertEquals("note with spaces.md", note("note with spaces.md", "", null).displayTitle());
    }

    // --- NEWEST_FIRST

    private static List<String> sortedPaths(Note... notes) {
        List<Note> list = new ArrayList<>(List.of(notes));
        list.sort(Note.NEWEST_FIRST);
        return list.stream().map(Note::path).toList();
    }

    @Test
    void newestCreatedFirst() {
        assertEquals(List.of("new.md", "mid.md", "old.md"), sortedPaths(
                note("mid.md", "", "2026-11-10T10:00:00+01:00"),
                note("old.md", "", "2026-11-01T10:00:00+01:00"),
                note("new.md", "", "2026-11-20T10:00:00+01:00")));
    }

    @Test
    void comparesInstantsAcrossOffsets() {
        // 10:00 at -05:00 is 15:00 UTC, later than 12:00 at +00:00
        assertEquals(List.of("ny.md", "london.md"), sortedPaths(
                note("london.md", "", "2026-11-17T12:00:00+00:00"),
                note("ny.md", "", "2026-11-17T10:00:00-05:00")));
    }

    @Test
    void sameTimeSortsByFileNameDescending() {
        String time = "2026-11-16T20:00:00+01:00";
        assertEquals(List.of("20261116-200000-2.md", "20261116-200000.md"), sortedPaths(
                note("20261116-200000.md", "", time),
                note("20261116-200000-2.md", "", time)));
    }

    @Test
    void notesWithoutCreationTimeComeLast() {
        assertEquals(List.of("dated.md", "z.md", "a.md"), sortedPaths(
                note("a.md", "", null),
                note("dated.md", "", "2020-01-01T00:00:00Z"),
                note("z.md", "", null)));
    }
}
