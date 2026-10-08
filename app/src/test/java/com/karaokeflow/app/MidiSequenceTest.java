package com.karaokeflow.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Fixtures are generated here from the public SMF format; no song assets are bundled. */
public class MidiSequenceTest {
    private static final class Track {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Track midi(int delta, int... message) { variable(bytes, delta); for (int b : message) bytes.write(b); return this; }
        Track meta(int delta, int type, byte[] payload) {
            variable(bytes, delta); bytes.write(0xff); bytes.write(type);
            variable(bytes, payload.length); bytes.write(payload, 0, payload.length); return this;
        }
        Track text(int delta, int type, String text) { return meta(delta, type, text.getBytes(StandardCharsets.UTF_8)); }
        Track tempo(int delta, int micros) {
            return meta(delta, 0x51, new byte[] {(byte) (micros >> 16), (byte) (micros >> 8), (byte) micros});
        }
        Track end(int delta) { return meta(delta, 0x2f, new byte[0]); }
    }

    private static void variable(ByteArrayOutputStream output, int value) {
        int shift = 21;
        while (shift > 0 && (value >>> shift) == 0) shift -= 7;
        for (; shift > 0; shift -= 7) output.write((value >>> shift & 0x7f) | 0x80);
        output.write(value & 0x7f);
    }

    private static byte[] file(int format, int division, Track... tracks) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(0x4d546864); data.writeInt(6); data.writeShort(format);
        data.writeShort(tracks.length); data.writeShort(division);
        for (Track track : tracks) {
            data.writeInt(0x4d54726b); data.writeInt(track.bytes.size()); track.bytes.writeTo(data);
        }
        return bytes.toByteArray();
    }

    private static MidiSequence parse(byte[] bytes) throws IOException { return MidiSequence.parse(new ByteArrayInputStream(bytes)); }

    private static void rejects(byte[] bytes, String reason) {
        IOException failure = assertThrows(IOException.class, () -> parse(bytes));
        assertTrue(failure.getMessage(), failure.getMessage().contains(reason));
    }

    @Test public void formatZeroSupportsRunningStatusAndAllChannelMessageLengths() throws Exception {
        Track track = new Track().midi(0, 0xc3, 12).midi(0, 13).midi(0, 0xd3, 70)
                .midi(0, 0xb3, 7, 100).midi(0, 0xe3, 0, 64).midi(0, 0xa3, 60, 25)
                .midi(480, 0x93, 60, 90).midi(480, 60, 0).end(480);
        MidiSequence sequence = parse(file(0, 480, track));
        assertEquals(8, sequence.events.size());
        assertEquals(0xc3, sequence.events.get(1).status);
        assertEquals(13, sequence.events.get(1).data1);
        assertEquals(0, sequence.events.get(1).data2);
        assertEquals(500_000, sequence.events.get(6).timeMicros);
        assertEquals(1_000_000, sequence.events.get(7).timeMicros);
        assertEquals(1_500_000, sequence.durationMicros);
        assertTrue(sequence.lyrics.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> sequence.events.clear());
    }

    @Test public void mergesTracksUsingGlobalTempoAndKeepsEarlyLyricSilence() throws Exception {
        Track conductor = new Track().tempo(0, 500_000).tempo(480, 1_000_000).end(960);
        Track notes = new Track().midi(0, 0xc0, 5).midi(480, 0x90, 60, 100).midi(480, 0x80, 60, 0).end(0);
        Track lyrics = new Track().text(720, 0x05, "\\Hel").text(240, 0x05, "lo").end(480);
        MidiSequence sequence = parse(file(1, 480, conductor, notes, lyrics));
        assertEquals(500_000, sequence.events.get(1).timeMicros);
        assertEquals(1_500_000, sequence.events.get(2).timeMicros);
        assertEquals(1_000_000, sequence.lyrics.get(0).timeMicros);
        assertEquals(1_500_000, sequence.lyrics.get(1).timeMicros);
        assertEquals(2_500_000, sequence.durationMicros);
        LyricTimeline timeline = new LyricTimeline(sequence);
        assertEquals("", timeline.at(999_999).line);
        assertEquals(3, timeline.at(1_000_000).sungCharacters);
    }

    @Test public void retainsFractionalTicksAcrossManyTempoAndIgnoredEvents() throws Exception {
        Track track = new Track().tempo(0, 500_000);
        for (int i = 0; i < 6; i++) track.meta(1, 0x7f, new byte[0]).tempo(0, 500_000);
        track.midi(1, 0x90, 60, 1).end(0);
        assertEquals(500_000, parse(file(0, 7, track)).events.get(0).timeMicros);
    }

    @Test public void dedicatedLyricEventsWinEvenIfKaraokeTextOccursEarlier() throws Exception {
        Track track = new Track().text(0, 0x01, "@KMIDI KARAOKE FILE").text(0, 0x01, "@TExample title")
                .text(100, 0x01, "\\Wrong ").text(100, 0x01, "words")
                .text(0, 0x05, "@TAlso metadata").text(100, 0x05, "\\Right ")
                .text(100, 0x05, "words").end(0);
        MidiSequence sequence = parse(file(0, 100, track));
        assertEquals(2, sequence.lyrics.size());
        assertEquals("\\Right ", sequence.lyrics.get(0).text);
        assertEquals(1_500_000, sequence.lyrics.get(0).timeMicros);
        assertEquals("Right words", new LyricTimeline(sequence).at(1_500_000).line);
    }

    @Test public void textKaraokeFallbackFiltersMetadataAndSelectsLyricTrack() throws Exception {
        Track metadata = new Track().text(0, 0x01, "An unmarked song title")
                .text(100, 0x01, "Verse").end(300);
        Track lyricTrack = new Track().text(0, 0x01, "@KMIDI KARAOKE FILE")
                .text(0, 0x01, "Another unmarked title").text(0, 0x01, "https://example.invalid")
                .text(0, 0x05, "@TMetadata is not a lyric")
                .text(200, 0x01, "\\Sing ").text(100, 0x01, "along").end(100);
        MidiSequence sequence = parse(file(1, 100, metadata, lyricTrack));
        assertEquals(2, sequence.lyrics.size());
        assertEquals("\\Sing ", sequence.lyrics.get(0).text);
        assertEquals("along", sequence.lyrics.get(1).text);
    }

    @Test public void isolatedTextMetadataNeverBecomesALyric() throws Exception {
        Track track = new Track().text(0, 0x01, "Example title").text(0, 0x01, "Example author")
                .text(100, 0x01, "Copyright example").text(100, 0x01, "Intro").end(0);
        assertTrue(parse(file(0, 100, track)).lyrics.isEmpty());
    }

    @Test public void decodesUtf8AndFallsBackToLegacyLatinOne() throws Exception {
        Track track = new Track().text(100, 0x05, "\\Caf\u00e9 ")
                .meta(100, 0x05, new byte[] {'o', 'l', (byte) 0xe9}).end(0);
        MidiSequence sequence = parse(file(0, 100, track));
        assertEquals("\\Caf\u00e9 ", sequence.lyrics.get(0).text);
        assertEquals("ol\u00e9", sequence.lyrics.get(1).text);
    }

    @Test public void smpteUsesFixedTimingAndIgnoresTempo() throws Exception {
        Track track = new Track().tempo(0, 1_000_000).midi(2500, 0x90, 60, 1).end(0);
        assertEquals(1_000_000, parse(file(0, 0xe764, track)).durationMicros); // -25 fps, 100 ticks/frame
    }

    @Test public void dropFrameDivisionMeans2997FramesPerSecond() throws Exception {
        Track track = new Track().midi(3000, 0x90, 60, 1).end(0);
        assertEquals(1_001_000, parse(file(0, 0xe364, track)).durationMicros); // -29, 100 ticks/frame
    }

    @Test public void equalTickTrackOrderIsStableAndTempoChangesApplyAfterTheirTick() throws Exception {
        Track first = new Track().midi(0, 0xc0, 1).tempo(100, 1_000_000).end(100);
        Track second = new Track().midi(0, 0xc1, 2).midi(100, 0x91, 60, 1).end(100);
        MidiSequence sequence = parse(file(1, 100, first, second));
        assertEquals(0xc0, sequence.events.get(0).status);
        assertEquals(0xc1, sequence.events.get(1).status);
        assertEquals(500_000, sequence.events.get(2).timeMicros);
        assertEquals(1_500_000, sequence.durationMicros);
    }

    @Test public void sysexIsSkippedAndClearsRunningStatus() throws Exception {
        Track track = new Track().midi(0, 0x90, 60, 1).midi(0, 0xf0, 3, 0x7e, 0, 0xf7)
                .midi(100, 0x80, 60, 0).end(0);
        assertEquals(2, parse(file(0, 100, track)).events.size());
        Track broken = new Track().midi(0, 0x90, 60, 1).midi(0, 0xf7, 0).midi(0, 60, 0).end(0);
        rejects(file(0, 100, broken), "running status");
    }

    @Test public void metaEventsClearRunningStatus() throws Exception {
        Track track = new Track().midi(0, 0x90, 60, 1).text(0, 0x05, "hello").midi(0, 60, 0).end(0);
        rejects(file(0, 100, track), "running status");
    }

    @Test public void rejectsUnsupportedFormatsAndInvalidTiming() throws Exception {
        Track track = new Track().end(0);
        rejects(file(2, 100, track), "format 2");
        rejects(file(0, 0, track), "quarter note");
        rejects(file(0, 0xe664, track), "SMPTE");
        rejects(file(0, 0xe700, track), "SMPTE");
        rejects(file(0, 100, track, track), "track count");
    }

    @Test public void rejectsTruncatedOrMalformedTracksWithHelpfulIoErrors() throws Exception {
        byte[] valid = file(0, 100, new Track().midi(0, 0x90, 60, 1).end(0));
        rejects(Arrays.copyOf(valid, valid.length - 1), "Truncated");
        rejects(file(0, 100, new Track().midi(0, 0x90, 60, 1)), "end marker");
        rejects(file(0, 100, new Track().midi(0, 0x90, 0x80, 1).end(0)), "data byte");
        rejects(file(0, 100, new Track().tempo(0, 0).end(0)), "tempo cannot be zero");
        rejects(file(0, 100, new Track().meta(0, 0x51, new byte[2]).end(0)), "tempo event");
        rejects(file(0, 100, new Track().end(0).midi(0, 0x90, 60, 1)), "follow the end");
        rejects(new byte[] {0, 1, 2, 3}, "MThd");
    }

    @Test public void rejectsAnOverlongVariableLengthQuantity() throws Exception {
        Track track = new Track();
        track.bytes.write(new byte[] {(byte) 0x81, (byte) 0x81, (byte) 0x81, (byte) 0x81, 0}, 0, 5);
        track.end(0);
        rejects(file(0, 100, track), "four bytes");
    }

    @Test public void enforcesDurationAndTextLimitsBeforeLargeAllocations() throws Exception {
        rejects(file(0, 1, new Track().midi(0x0fffffff, 0x90, 60, 1).end(0)), "24-hour");
        rejects(file(0, 100, new Track().meta(0, 0x05, new byte[16 * 1024 + 1]).end(0)), "excessive lyric");
    }

    @Test public void interruptedPreparationStopsAndPreservesInterruptFlag() throws Exception {
        byte[] midi = file(0, 100, new Track().end(0));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> parse(midi));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void cancellationDuringInputReadIsObservedAtNextChunk() throws Exception {
        byte[] midi = file(0, 100, new Track().end(0));
        ByteArrayInputStream input = new ByteArrayInputStream(midi) {
            @Override public synchronized int read(byte[] bytes, int offset, int count) {
                int read = super.read(bytes, offset, count);
                Thread.currentThread().interrupt();
                return read;
            }
        };
        try {
            assertThrows(InterruptedIOException.class, () -> MidiSequence.parse(input));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void capsTotalMidiEventsAndFileBytes() throws Exception {
        Track excessive = new Track();
        for (int i = 0; i < 250_001; i++) excessive.midi(0, 0xc0, 0);
        excessive.end(0);
        rejects(file(0, 100, excessive), "too many events");
        rejects(new byte[16 * 1024 * 1024 + 1], "16 MB");
    }

    @Test public void acceptsUnknownChunksWithoutConfusingTrackCount() throws Exception {
        byte[] midi = file(0, 100, new Track().midi(100, 0x90, 60, 1).end(0));
        ByteArrayOutputStream extended = new ByteArrayOutputStream();
        extended.write(midi, 0, 14);
        DataOutputStream data = new DataOutputStream(extended);
        data.writeInt(0x74657374); data.writeInt(3); data.write(new byte[] {1, 2, 3});
        extended.write(midi, 14, midi.length - 14);
        assertEquals(500_000, parse(extended.toByteArray()).events.get(0).timeMicros);
    }
}
