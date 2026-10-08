package com.karaokeflow.app;

import java.util.*;

/** Sorted number index: bounded prefix previews never scan the whole catalog on a key press. */
public final class SongNumberLookup {
    private final NavigableMap<String, SongCatalog.Song> numbers = new TreeMap<>();
    public SongNumberLookup(List<SongCatalog.Song> songs) {
        for (SongCatalog.Song song : songs) numbers.putIfAbsent(song.songNumber(), song);
    }
    public SongCatalog.Song exact(String digits) {
        if (digits == null || !digits.matches("[0-9]{1,6}")) return null;
        return numbers.get(String.format(Locale.ROOT, "%6s", digits).replace(' ', '0'));
    }
    public List<SongCatalog.Song> preview(String digits) {
        List<SongCatalog.Song> result = new ArrayList<>();
        if (digits == null || !digits.matches("[0-9]{1,6}")) return result;
        SongCatalog.Song exact = exact(digits);
        if (exact != null) result.add(exact);
        for (Map.Entry<String, SongCatalog.Song> entry : numbers.tailMap(digits, true).entrySet()) {
            if (!entry.getKey().startsWith(digits) || result.size() >= 3) break;
            if (entry.getValue() != exact) result.add(entry.getValue());
        }
        return result;
    }
}
