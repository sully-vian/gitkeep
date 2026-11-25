package com.sully_vian.gitkeep.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoteTest {

    // --- displayTitle

    @Test
    void displayTitleIsTheTitle() {
        assertEquals("Hello", new Note("a.md").withTitle("Hello").displayTitle());
    }

    @Test
    void emptyTitleShowsTheFileName() {
        assertEquals("note with spaces.md", new Note("note with spaces.md").displayTitle());
    }

    // --- NEWEST_FIRST

    private static List<String> sorted(String... paths) {
        List<Note> notes = new ArrayList<>();
        for (String path : paths) {
            notes.add(new Note(path));
        }
        notes.sort(Note.NEWEST_FIRST);
        return notes.stream().map(Note::path).toList();
    }

    @Test
    void newestCreatedFirst() {
        assertEquals(List.of("20261120-100000.md", "20261110-100000.md", "20261101-100000.md"),
                sorted("20261110-100000.md", "20261101-100000.md", "20261120-100000.md"));
    }

    @Test
    void sameSecondSuffixIsNewer() {
        assertEquals(List.of("20261116-200000-2.md", "20261116-200000.md"),
                sorted("20261116-200000.md", "20261116-200000-2.md"));
    }

    @Test
    void otherNamesComeLastAlphabetically() {
        assertEquals(List.of("20200101-000000.md", "a.md", "note with spaces.md", "z.md"),
                sorted("z.md", "note with spaces.md", "20200101-000000.md", "a.md"));
    }

    @Test
    void almostTimestampsAreOtherNames() {
        // a date without a time, a time with a letter: not names made by the app
        assertEquals(List.of("20261116-200000.md", "20261116-20000x.md", "20261116.md"),
                sorted("20261116-20000x.md", "20261116.md", "20261116-200000.md"));
    }
}
