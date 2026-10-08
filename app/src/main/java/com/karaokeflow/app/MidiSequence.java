package com.karaokeflow.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Bounded Standard MIDI File reader. No Android or desktop MIDI APIs are required. */
public final class MidiSequence {
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TRACKS = 256;
    private static final int MAX_EVENTS = 250_000;
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TEXT_EVENT_BYTES = 16 * 1024;
    private static final long MAX_DURATION_MICROS = 24L * 60 * 60 * 1_000_000;
    private static final int CHANNEL = 0, TEMPO = 1, LYRIC = 2, TEXT = 3;

    public final List<Event> events;
    public final List<Lyric> lyrics;
    public final long durationMicros;

    private MidiSequence(List<Event> events, List<Lyric> lyrics, long durationMicros) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.lyrics = Collections.unmodifiableList(new ArrayList<>(lyrics));
        this.durationMicros = durationMicros;
    }

    public static final class Event {
        public final long timeMicros;
        public final int status, data1, data2;

        public Event(long timeMicros, int status, int data1, int data2) {
            this.timeMicros = timeMicros;
            this.status = status;
            this.data1 = data1;
            this.data2 = data2;
        }
    }

    public static final class Lyric {
        public final long timeMicros;
        public final String text;

        public Lyric(long timeMicros, String text) {
            this.timeMicros = timeMicros;
            this.text = text;
        }
    }

    private static final class Raw {
        final long tick;
        final int order, track, kind, status, data1, data2;
        final String text;

        Raw(long tick, int order, int track, int kind, int status, int data1,
                int data2, String text) {
            this.tick = tick;
            this.order = order;
            this.track = track;
            this.kind = kind;
            this.status = status;
            this.data1 = data1;
            this.data2 = data2;
            this.text = text;
        }
    }

    /** Reads SMF format 0/1, including PPQN tempo changes and SMPTE divisions. */
    public static MidiSequence parse(InputStream input) throws IOException {
        checkCanceled();
        if (input == null) throw new IOException("The MIDI file could not be opened.");
        Reader file = new Reader(readBounded(input));
        if (file.i32() != 0x4d546864) throw invalid("Missing MThd header");
        int headerLength = file.chunkLength();
        if (headerLength < 6) throw invalid("The MIDI header is too short");
        int headerEnd = file.position + headerLength;
        int format = file.u16(), trackCount = file.u16(), division = file.u16();
        if (format == 2) throw new IOException("MIDI format 2 contains independent songs and is not supported in this beta.");
        if (format != 0 && format != 1) throw invalid("Unsupported MIDI format " + format);
        if (trackCount < 1 || trackCount > MAX_TRACKS || (format == 0 && trackCount != 1)) {
            throw invalid("Invalid or excessive MIDI track count");
        }
        Clock clock = new Clock(division);
        file.position = headerEnd;
        ArrayList<Raw> raw = new ArrayList<>();
        boolean[] karaokeTrack = new boolean[trackCount];
        int parsedTracks = 0, eventCount = 0, textBytes = 0;
        long endTick = 0;
        while (file.remaining() > 0) {
            checkCanceled();
            int chunkType = file.i32();
            int length = file.chunkLength();
            int chunkEnd = file.position + length;
            if (chunkType != 0x4d54726b) {
                file.position = chunkEnd; // SMF readers must tolerate unknown chunks.
                continue;
            }
            if (parsedTracks == trackCount) throw invalid("More tracks than declared in the MIDI header");
            int trackNumber = parsedTracks++;
            Reader track = new Reader(file.bytes, file.position, chunkEnd);
            file.position = chunkEnd;
            long tick = 0;
            int runningStatus = 0;
            boolean ended = false;
            while (track.remaining() > 0) {
                if ((eventCount & 255) == 0) checkCanceled();
                if (++eventCount > MAX_EVENTS) throw invalid("The MIDI file has too many events for this beta");
                tick += track.vlq();
                int first = track.u8();
                int status;
                int firstData = -1;
                if (first < 0x80) {
                    if (runningStatus == 0) throw invalid("MIDI data has no running status");
                    status = runningStatus;
                    firstData = first;
                } else {
                    status = first;
                }
                if (status >= 0x80 && status <= 0xef) {
                    runningStatus = status;
                    int data1 = firstData >= 0 ? firstData : track.dataByte();
                    int message = status & 0xf0;
                    int data2 = message == 0xc0 || message == 0xd0 ? 0 : track.dataByte();
                    raw.add(new Raw(tick, eventCount, trackNumber, CHANNEL, status, data1, data2, null));
                } else if (status == 0xff) {
                    runningStatus = 0;
                    int type = track.u8(), payloadLength = track.vlq();
                    if (type >= 0x80) throw invalid("Invalid MIDI meta-event type");
                    track.require(payloadLength);
                    if (type == 0x2f) {
                        if (payloadLength != 0) throw invalid("Invalid end-of-track event");
                        if (track.remaining() != 0) throw invalid("Events follow the end-of-track marker");
                        ended = true;
                        break;
                    } else if (type == 0x51) {
                        if (payloadLength != 3) throw invalid("Invalid MIDI tempo event");
                        int tempo = track.u8() << 16 | track.u8() << 8 | track.u8();
                        if (tempo == 0) throw invalid("MIDI tempo cannot be zero");
                        raw.add(new Raw(tick, eventCount, trackNumber, TEMPO, tempo, 0, 0, null));
                    } else if (type == 0x01 || type == 0x05) {
                        if (payloadLength > MAX_TEXT_EVENT_BYTES || textBytes > MAX_TEXT_BYTES - payloadLength) {
                            throw invalid("The MIDI file has excessive lyric/text data");
                        }
                        textBytes += payloadLength;
                        String decoded = decode(track.bytes, track.position, payloadLength);
                        track.position += payloadLength;
                        if (decoded.trim().toUpperCase(Locale.ROOT).startsWith("@KMIDI")) {
                            karaokeTrack[trackNumber] = true;
                        }
                        String clean = cleanText(decoded);
                        if (!clean.isEmpty()) {
                            raw.add(new Raw(tick, eventCount, trackNumber,
                                    type == 0x05 ? LYRIC : TEXT, 0, 0, 0, clean));
                        }
                    } else {
                        track.position += payloadLength;
                    }
                } else if (status == 0xf0 || status == 0xf7) {
                    runningStatus = 0;
                    int payloadLength = track.vlq();
                    track.require(payloadLength);
                    // Manufacturer-specific SysEx is deliberately not sent to the software synth.
                    track.position += payloadLength;
                } else {
                    throw invalid("Unsupported status byte in a MIDI track");
                }
            }
            if (!ended) throw invalid("The MIDI track is missing its end marker");
            endTick = Math.max(endTick, tick);
        }
        if (parsedTracks != trackCount) throw invalid("The MIDI file is missing a declared track");
        checkCanceled();
        Collections.sort(raw, Comparator.comparingLong((Raw event) -> event.tick)
                .thenComparingInt(event -> event.order));
        boolean hasLyrics = false;
        int checked = 0;
        for (Raw event : raw) {
            if ((checked++ & 255) == 0) checkCanceled();
            if (event.kind == LYRIC && hasVisibleText(event.text)) {
                hasLyrics = true;
                break;
            }
        }
        int textTrack = hasLyrics ? -1 : chooseTextTrack(raw, karaokeTrack);
        ArrayList<Event> events = new ArrayList<>();
        ArrayList<Lyric> lyrics = new ArrayList<>();
        checked = 0;
        for (Raw event : raw) {
            if ((checked++ & 255) == 0) checkCanceled();
            long time = clock.advance(event.tick);
            if (event.kind == TEMPO) {
                clock.tempo = event.status;
            } else if (event.kind == CHANNEL) {
                events.add(new Event(time, event.status, event.data1, event.data2));
            } else if (hasLyrics && event.kind == LYRIC) {
                lyrics.add(new Lyric(time, event.text));
            } else if (!hasLyrics && event.kind == TEXT && event.track == textTrack
                    && (event.tick > 0 || hasLineMarker(event.text))) {
                // Unmarked text at tick zero is commonly a title/author, not a lyric.
                lyrics.add(new Lyric(time, event.text));
            }
        }
        return new MidiSequence(events, lyrics, clock.advance(endTick));
    }

    private static int chooseTextTrack(List<Raw> raw, boolean[] karaokeTrack) throws IOException {
        int[] timed = new int[karaokeTrack.length], marked = new int[karaokeTrack.length];
        int checked = 0;
        for (Raw event : raw) {
            if ((checked++ & 255) == 0) checkCanceled();
            if (event.kind == TEXT && hasVisibleText(event.text)) {
                if (event.tick > 0) timed[event.track]++;
                if (hasLineMarker(event.text)) marked[event.track]++;
            }
        }
        int best = -1;
        long bestScore = -1;
        for (int i = 0; i < karaokeTrack.length; i++) {
            if (marked[i] == 0 && timed[i] < 2 && !(karaokeTrack[i] && timed[i] > 0)) continue;
            long score = timed[i] + marked[i] * 1_000_000L + (karaokeTrack[i] ? 100_000_000L : 0);
            if (score > bestScore) { best = i; bestScore = score; }
        }
        return best;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            checkCanceled();
            int count = input.read(buffer);
            if (count == -1) break;
            if (count == 0) {
                int single = input.read();
                if (single == -1) break;
                if (output.size() == MAX_FILE_BYTES) throw invalid("The MIDI file exceeds the 16 MB beta limit");
                output.write(single);
            } else {
                if (output.size() > MAX_FILE_BYTES - count) throw invalid("The MIDI file exceeds the 16 MB beta limit");
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static String decode(byte[] bytes, int start, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, length)).toString();
        } catch (CharacterCodingException ignored) {
            return new String(bytes, start, length, StandardCharsets.ISO_8859_1);
        }
    }

    private static String cleanText(String source) {
        if (isMetadata(source)) return "";
        StringBuilder result = new StringBuilder();
        StringBuilder part = new StringBuilder();
        for (int i = 0; i <= source.length(); i++) {
            char c = i == source.length() ? '\0' : source.charAt(i);
            if (i == source.length() || isLineMarker(c)) {
                if (!isMetadata(part.toString())) result.append(part);
                part.setLength(0);
                if (i < source.length()) result.append(c);
            } else if (c == '\t') {
                part.append(' ');
            } else if (c >= 0x20 && c != 0x7f) {
                part.append(c);
            }
        }
        return result.toString();
    }

    private static boolean isMetadata(String text) {
        String lower = text.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("@") || lower.startsWith("copyright") || lower.startsWith("(c)")
                || lower.startsWith("\u00a9") || lower.startsWith("title:") || lower.startsWith("artist:")
                || lower.startsWith("composer:") || lower.startsWith("arranger:")
                || lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("www.");
    }

    private static boolean hasVisibleText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!isLineMarker(c) && !Character.isWhitespace(c)) return true;
        }
        return false;
    }

    private static boolean hasLineMarker(String text) {
        for (int i = 0; i < text.length(); i++) if (isLineMarker(text.charAt(i))) return true;
        return false;
    }

    static boolean isLineMarker(char c) { return c == '/' || c == '\\' || c == '\r' || c == '\n'; }

    private static void checkCanceled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Song loading was canceled.");
    }

    private static IOException invalid(String detail) { return new IOException("Invalid MIDI file: " + detail + "."); }

    private static final class Reader {
        final byte[] bytes;
        final int end;
        int position;
        Reader(byte[] bytes) { this(bytes, 0, bytes.length); }
        Reader(byte[] bytes, int position, int end) { this.bytes = bytes; this.position = position; this.end = end; }
        int remaining() { return end - position; }
        void require(int count) throws IOException {
            if (count < 0 || count > remaining()) throw invalid("Truncated MIDI data");
        }
        int u8() throws IOException { require(1); return bytes[position++] & 0xff; }
        int dataByte() throws IOException {
            int value = u8();
            if (value >= 0x80) throw invalid("A MIDI data byte has its status bit set");
            return value;
        }
        int u16() throws IOException { return u8() << 8 | u8(); }
        int i32() throws IOException { return u8() << 24 | u8() << 16 | u8() << 8 | u8(); }
        int chunkLength() throws IOException {
            long count = i32() & 0xffffffffL;
            if (count > remaining()) throw invalid("Truncated or oversized MIDI chunk");
            return (int) count;
        }
        int vlq() throws IOException {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int b = u8();
                value = value << 7 | b & 0x7f;
                if ((b & 0x80) == 0) return value;
            }
            throw invalid("A variable-length MIDI value exceeds four bytes");
        }
    }

    private static final class Clock {
        final long denominator, numerator;
        final boolean ppqn;
        long tick, micros, remainder;
        int tempo = 500_000;

        Clock(int division) throws IOException {
            ppqn = (division & 0x8000) == 0;
            if (ppqn) {
                if (division == 0) throw invalid("MIDI ticks per quarter note cannot be zero");
                denominator = division;
                numerator = 0;
            } else {
                int frameCode = (byte) (division >> 8), ticksPerFrame = division & 0xff;
                if (ticksPerFrame == 0 || (frameCode != -24 && frameCode != -25
                        && frameCode != -29 && frameCode != -30)) {
                    throw invalid("Unsupported SMPTE timing division");
                }
                // SMPTE -29 means 30,000/1,001 frames per second, not 29 fps.
                denominator = (frameCode == -29 ? 30_000L : -frameCode) * ticksPerFrame;
                numerator = frameCode == -29 ? 1_001_000_000L : 1_000_000L;
            }
        }

        long advance(long nextTick) throws IOException {
            long delta = nextTick - tick, rate = ppqn ? tempo : numerator;
            if (delta < 0) throw invalid("MIDI events are out of order");
            long wholeTicks = delta / denominator;
            if (wholeTicks > MAX_DURATION_MICROS / rate) throw invalid("The MIDI song exceeds the 24-hour beta limit");
            long fraction = (delta % denominator) * rate + remainder;
            long elapsed = wholeTicks * rate + fraction / denominator;
            if (elapsed > MAX_DURATION_MICROS - micros) throw invalid("The MIDI song exceeds the 24-hour beta limit");
            micros += elapsed;
            remainder = fraction % denominator;
            tick = nextTick;
            return micros;
        }
    }
}
