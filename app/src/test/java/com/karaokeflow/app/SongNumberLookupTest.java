package com.karaokeflow.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class SongNumberLookupTest {
    private SongCatalog.Song song(int id) { return new SongCatalog.Song(id, "Song " + id, "", ""); }
    @Test public void paddedExactMatchComesBeforePrefixSuggestions() {
        SongNumberLookup lookup = new SongNumberLookup(Arrays.asList(song(1), song(100001), song(100002), song(123456)));
        List<SongCatalog.Song> preview = lookup.preview("1");
        assertEquals(3, preview.size());
        assertEquals(1, preview.get(0).id);
        assertEquals(100001, preview.get(1).id);
        assertEquals(100002, preview.get(2).id);
        assertEquals(123456, lookup.exact("123456").id);
    }
    @Test public void fullNumberSelectsOnlyItsExactSong() {
        SongNumberLookup lookup = new SongNumberLookup(Arrays.asList(song(12), song(123456)));
        assertEquals(12, lookup.exact("000012").id);
        assertEquals(1, lookup.preview("123456").size());
        assertTrue(lookup.preview("").isEmpty());
        assertTrue(lookup.preview("999999").isEmpty());
        assertNull(lookup.exact("1234567"));
        assertNull(lookup.exact("-1"));
    }
    @Test public void deletingDigitsExpandsSuggestionsWithoutSelectingAnything() {
        SongNumberLookup lookup = new SongNumberLookup(Arrays.asList(song(123400), song(123456), song(124000)));
        assertEquals(1, lookup.preview("12345").size());
        assertEquals(2, lookup.preview("1234").size());
        assertEquals(3, lookup.preview("12").size());
        assertNull(lookup.exact("12345"));
    }
}
