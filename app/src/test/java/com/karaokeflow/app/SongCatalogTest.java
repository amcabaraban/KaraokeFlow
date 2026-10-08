package com.karaokeflow.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SongCatalogTest {
    private List<SongCatalog.Song> parse(String csv) throws IOException {
        return SongCatalog.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void originalLayoutRetainsPermanentNumbersWithoutHeader() throws Exception {
        List<SongCatalog.Song> songs = parse("012185,unused,A title,An artist,unused,012185.mid\n"
                + "000007,unused,Second,Other,unused,nested/000007.kar\n");
        assertEquals(2, songs.size());
        assertEquals(12185, songs.get(0).id);
        assertEquals("012185", songs.get(0).songNumber());
        assertEquals("000007", songs.get(1).songNumber());
        assertEquals("nested/000007.kar", songs.get(1).file);
    }

    @Test public void namedReorderedHeaderSupportsBomQuotedCommasAndNewlines() throws Exception {
        List<SongCatalog.Song> songs = parse("\ufeffartist,midi_path,song_number,title\r\n"
                + "\"Last, First\",\"C:\\SongHub_Extracted\\012185.mid\",012185,"
                + "\"Hello \"\"World\"\"\r\nSecond line\"\r\n");
        assertEquals(1, songs.size());
        assertEquals("Last, First", songs.get(0).artist);
        assertEquals("Hello \"World\"\nSecond line", songs.get(0).title);
        assertEquals("C:\\SongHub_Extracted\\012185.mid", songs.get(0).file);
    }

    @Test public void namedHeaderDoesNotMistakeUnrelatedPathForMidi() throws Exception {
        SongCatalog.Song song = parse("song_id,title,artist,original_path,source,notes\n"
                + "1,Song,Artist,C:/unrelated/file,Other,notes\n").get(0);
        assertEquals("", song.file);
        assertEquals("000001", song.songNumber());
    }

    @Test public void establishedPositionalHeaderRemainsSupported() throws Exception {
        SongCatalog.Song song = parse("probe_no,unused,title,artist,unused,extracted_file\n"
                + "12,x,Title,Artist,x,000012.mid\n").get(0);
        assertEquals("000012.mid", song.file);
        assertEquals("Title", song.title);
    }

    @Test public void unfamiliarOriginalHeaderRetainsSixColumnLayout() throws Exception {
        SongCatalog.Song song = parse("probe_entry,unused,label,credit,unused,extracted_name\n"
                + "12,x,Title,Artist,x,000012.mid\n").get(0);
        assertEquals("000012.mid", song.file);
        assertEquals("Title", song.title);
    }

    @Test public void blankRowsAndMissingOptionalColumnsAreSupported() throws Exception {
        List<SongCatalog.Song> songs = parse("\n\nsong_number,title\n42,A title\n\n");
        assertEquals(1, songs.size());
        assertEquals("", songs.get(0).artist);
        assertEquals("", songs.get(0).file);
    }

    @Test public void duplicateNumbersFailInsteadOfRenumbering() throws Exception {
        expectFailure("id,title\n000007,First\n7,Second\n", "line 3", "Duplicate", "000007");
    }

    @Test public void malformedNumbersAndIncompleteRowsFailWithLocations() throws Exception {
        expectFailure("id,title\n-1,Song\n", "line 2", "Invalid");
        expectFailure("id,title\n1000000,Song\n", "line 2", "Invalid");
        expectFailure("id,title,artist,midi_file\n1,Song\n", "line 2", "columns");
        expectFailure("1000000,x,Title,Artist,x,000001.mid\n", "line 1", "Invalid");
        expectFailure("12x,x,Title,Artist,x,000001.mid\n", "line 1", "Invalid");
    }

    @Test public void malformedQuotesAreNotSilentlyImported() throws Exception {
        expectFailure("id,title\n1,\"Unfinished", "line 2", "Unterminated");
        expectFailure("id,title\n1,\"Title\"junk\n", "line 2", "closing quote");
        expectFailure("id,title\n1,Unquoted\"text\n", "line 2", "unquoted");
    }

    @Test public void emptyAndAmbiguousHeadersFail() throws Exception {
        expectFailure("id,title\n", "no song records");
        expectFailure("id,song_number,title\n1,1,Song\n", "Multiple song-number");
    }

    @Test public void cancelledImportPreservesInterruption() throws Exception {
        Thread.currentThread().interrupt();
        try {
            expectFailure("id,title\n1,Song\n", "cancelled");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private void expectFailure(String csv, String... messageParts) throws Exception {
        try {
            parse(csv);
            fail("Expected import failure");
        } catch (IOException expected) {
            for (String part : messageParts) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(part));
            }
        }
    }
}
