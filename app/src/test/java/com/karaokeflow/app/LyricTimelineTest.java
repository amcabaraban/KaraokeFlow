package com.karaokeflow.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class LyricTimelineTest {
    private static MidiSequence.Lyric lyric(long micros, String text) { return new MidiSequence.Lyric(micros, text); }

    @Test public void keepsEarlySilenceAndHighlightsSyllablesAtTheirTimestamps() {
        LyricTimeline timeline = new LyricTimeline(Arrays.asList(lyric(1_000_000, "\\Hel"),
                lyric(1_500_000, "lo "), lyric(2_000_000, "world"), lyric(3_000_000, "/Next line")));
        LyricTimeline.Cue before = timeline.at(999_999);
        assertEquals("", before.line);
        assertEquals("Hello world", before.nextLine);
        assertEquals(0, before.sungCharacters);
        assertEquals("Hello world", timeline.at(1_000_000).line);
        assertEquals("Next line", timeline.at(1_000_000).nextLine);
        assertEquals(3, timeline.at(1_000_000).sungCharacters);
        assertEquals(6, timeline.at(1_500_000).sungCharacters);
        assertEquals(11, timeline.at(2_000_000).sungCharacters);
        assertEquals("Next line", timeline.at(3_000_000).line);
        assertEquals("", timeline.at(3_000_000).nextLine);
    }

    @Test public void handlesCrLfSeparateMarkersAndLiteralSpacesWithoutInventingWords() {
        LyricTimeline timeline = new LyricTimeline(Arrays.asList(lyric(0, "\\"), lyric(0, " "),
                lyric(100, "One"), lyric(200, " "), lyric(300, "word\r\n"),
                lyric(400, "\r"), lyric(500, "Two"), lyric(600, "words")));
        assertEquals("", timeline.at(99).line);
        assertEquals(" One word", timeline.at(100).line);
        assertEquals(4, timeline.at(100).sungCharacters);
        assertEquals(5, timeline.at(200).sungCharacters);
        assertEquals("Twowords", timeline.at(500).line);
        assertEquals(3, timeline.at(500).sungCharacters);
        assertEquals(8, timeline.at(600).sungCharacters);
    }

    @Test public void sortsInputAndSupportsMovingPlaybackClockBackwards() {
        LyricTimeline timeline = new LyricTimeline(Arrays.asList(lyric(200, " world"), lyric(100, "Hello")));
        assertEquals(11, timeline.at(200).sungCharacters);
        assertEquals(5, timeline.at(100).sungCharacters);
        assertEquals("", timeline.at(0).line);
    }

    @Test public void sameTimestampFragmentsAreAllHighlightedTogether() {
        LyricTimeline timeline = new LyricTimeline(Arrays.asList(lyric(100, "Hel"), lyric(100, "lo")));
        assertEquals("Hello", timeline.at(100).line);
        assertEquals(5, timeline.at(100).sungCharacters);
    }

    @Test public void countsUtf16CharactersForAndroidSpans() {
        LyricTimeline timeline = new LyricTimeline(Arrays.asList(lyric(100, "\ud83c\udfb5 "), lyric(200, "Sing")));
        assertEquals(3, timeline.at(100).sungCharacters);
        assertEquals(7, timeline.at(200).sungCharacters);
    }

    @Test public void emptyOrFormattingOnlyStreamsHaveNoLyrics() {
        LyricTimeline empty = new LyricTimeline(Collections.emptyList());
        assertFalse(empty.hasLyrics());
        assertEquals("", empty.at(0).line);
        assertEquals("", empty.at(Long.MAX_VALUE).nextLine);
        assertEquals(0, empty.at(10).sungCharacters);
        assertFalse(new LyricTimeline(Arrays.asList(lyric(0, "\\/\r\n "))).hasLyrics());
    }
}
