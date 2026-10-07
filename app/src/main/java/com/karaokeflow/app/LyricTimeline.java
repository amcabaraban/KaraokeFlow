package com.karaokeflow.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Displays whole lines and highlights only fragments whose MIDI timestamps have arrived. */
public final class LyricTimeline {
    private static final Cue EMPTY = new Cue("", "", 0);
    private final List<Line> lines;

    public static final class Cue {
        public final String line, nextLine;
        /** UTF-16 character count, suitable for Android Spannable indexes. */
        public final int sungCharacters;

        public Cue(String line, String nextLine, int sungCharacters) {
            this.line = line;
            this.nextLine = nextLine;
            this.sungCharacters = sungCharacters;
        }
    }

    private static final class Line {
        final StringBuilder text = new StringBuilder();
        final ArrayList<Long> times = new ArrayList<>();
        final ArrayList<Integer> lengths = new ArrayList<>();
        long startMicros = Long.MAX_VALUE;
        String value;

        void add(String fragment, long time) {
            text.append(fragment);
            if (!fragment.trim().isEmpty() && startMicros == Long.MAX_VALUE) startMicros = time;
            times.add(time);
            lengths.add(text.length());
        }
    }

    public LyricTimeline(MidiSequence sequence) { this(sequence.lyrics); }

    public LyricTimeline(List<MidiSequence.Lyric> lyrics) {
        ArrayList<MidiSequence.Lyric> ordered = new ArrayList<>(lyrics);
        Collections.sort(ordered, Comparator.comparingLong(lyric -> lyric.timeMicros));
        ArrayList<Line> result = new ArrayList<>();
        Line current = null;
        for (MidiSequence.Lyric lyric : ordered) {
            String text = lyric.text;
            int fragmentStart = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (i < text.length() && !MidiSequence.isLineMarker(text.charAt(i))) continue;
                if (i > fragmentStart) {
                    if (current == null) current = new Line();
                    current.add(text.substring(fragmentStart, i), lyric.timeMicros);
                }
                if (i < text.length()) {
                    finish(current, result);
                    current = null;
                    if (text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
                fragmentStart = i + 1;
            }
        }
        finish(current, result);
        lines = Collections.unmodifiableList(result);
    }

    private static void finish(Line line, List<Line> result) {
        if (line == null || line.startMicros == Long.MAX_VALUE) return;
        line.value = line.text.toString();
        result.add(line);
    }

    public boolean hasLyrics() { return !lines.isEmpty(); }

    public Cue at(long micros) {
        if (lines.isEmpty()) return EMPTY;
        int low = 0, high = lines.size() - 1, active = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).startMicros <= micros) { active = middle; low = middle + 1; }
            else high = middle - 1;
        }
        if (active < 0) return new Cue("", lines.get(0).value, 0);
        Line line = lines.get(active);
        low = 0;
        high = line.times.size() - 1;
        int fragment = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (line.times.get(middle) <= micros) { fragment = middle; low = middle + 1; }
            else high = middle - 1;
        }
        return new Cue(line.value, active + 1 < lines.size() ? lines.get(active + 1).value : "",
                fragment < 0 ? 0 : line.lengths.get(fragment));
    }
}
