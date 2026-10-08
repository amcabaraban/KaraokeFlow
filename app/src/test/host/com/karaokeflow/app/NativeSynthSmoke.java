package com.karaokeflow.app;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the actual JNI implementation used by the APK, without Android mocks. */
public final class NativeSynthSmoke {
    private static final int RATE = 44100;
    private static final NativeSynth.Cancellation NEVER = () -> false;
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void event(NativeSynth synth, int status, int data1, int data2) {
        synth.event(new MidiSequence.Event(0, status, data1, data2));
    }

    private static short[] render(NativeSynth synth, int frames) {
        short[] result = new short[frames * 2];
        synth.render(result, 0, frames);
        return result;
    }

    private static long energy(short[] samples) {
        long total = 0;
        for (short sample : samples) total += Math.abs((int) sample);
        return total;
    }

    private static void advance(NativeSynth synth, int frames) {
        while (frames > 0) {
            int block = Math.min(1024, frames);
            render(synth, block);
            frames -= block;
        }
    }

    private static int upwardCrossings(short[] pcm) {
        int result = 0;
        for (int i = 2; i < pcm.length; i += 2) if (pcm[i - 2] <= 0 && pcm[i] > 0) result++;
        return result;
    }

    private static short[] tone(File font, int program, boolean octavePitch) throws Exception {
        try (NativeSynth synth = NativeSynth.load(font, NEVER)) {
            event(synth, 0xb0, 0, 0); // GM bank 0 via MSB and LSB.
            event(synth, 0xb0, 32, 0);
            event(synth, 0xc0, program, 0);
            if (octavePitch) {
                event(synth, 0xb0, 101, 0); // RPN 0: pitch-bend sensitivity.
                event(synth, 0xb0, 100, 0);
                event(synth, 0xb0, 6, 12);
                event(synth, 0xb0, 38, 0);
                event(synth, 0xe0, 127, 127);
            }
            event(synth, 0x90, 69, 100);
            advance(synth, 2048); // Past the envelope attack.
            return render(synth, 8192);
        }
    }

    private static void rejects(File font, NativeSynth.Cancellation cancellation) throws Exception {
        try (NativeSynth ignored = NativeSynth.load(font, cancellation)) {
            throw new AssertionError("Invalid/cancelled SoundFont unexpectedly loaded: " + font.getName());
        } catch (IOException expected) { assertions++; }
    }

    public static void main(String[] args) throws Exception {
        File folder = new File(args[0]);
        File font = new File(folder, "original.sf2");
        short[] sine = tone(font, 0, false), square = tone(font, 1, false), octave = tone(font, 0, true);
        check(energy(sine) > 1_000_000, "Selected SF2 must generate nonzero sine PCM.");
        check(energy(square) > 1_000_000 && !Arrays.equals(sine, square), "Program change must select the other preset.");
        int baseCrossings = upwardCrossings(sine), octaveCrossings = upwardCrossings(octave);
        check(baseCrossings > 70 && baseCrossings < 95, "Base sine frequency must be near A440.");
        check(octaveCrossings > baseCrossings * 1.8 && octaveCrossings < baseCrossings * 2.2,
                "RPN pitch range plus pitch bend must raise the tone by an octave.");
        try (NativeSynth synth = NativeSynth.load(font, NEVER)) {
            event(synth, 0x99, 36, 100);
            check(energy(render(synth, 2048)) > 10000, "GM percussion channel 9 must generate PCM.");
            synth.releaseNotes();
            advance(synth, RATE);
            check(synth.activeVoices() == 0 && energy(render(synth, 512)) == 0, "Released drum voices must become silent.");

            event(synth, 0xb0, 64, 127);
            event(synth, 0x90, 69, 100);
            advance(synth, 1024);
            event(synth, 0x80, 69, 0);
            advance(synth, RATE / 2);
            check(synth.activeVoices() > 0 && energy(render(synth, 512)) > 10000, "Sustain must hold a released note.");
            event(synth, 0xb0, 64, 0);
            advance(synth, RATE);
            check(synth.activeVoices() == 0, "Dropping sustain must release the held voice.");

            event(synth, 0x90, 69, 100);
            advance(synth, 1024);
            event(synth, 0x90, 69, 0);
            advance(synth, RATE);
            check(synth.activeVoices() == 0, "Velocity-zero note-on must act as note-off.");
            event(synth, 0xb0, 64, 127);
            event(synth, 0x90, 69, 100);
            advance(synth, 1024);
            synth.releaseNotes();
            advance(synth, RATE);
            check(synth.activeVoices() == 0, "EOF release must clear sustain and missing note-offs.");

            short[] guarded = new short[2056];
            Arrays.fill(guarded, (short) 1234);
            event(synth, 0x90, 69, 100);
            synth.render(guarded, 2, 1024);
            check(guarded[0] == 1234 && guarded[3] == 1234 && guarded[2052] == 1234 && guarded[2055] == 1234,
                    "Segment rendering must preserve array guards outside its output region.");
            try { synth.render(new short[2], 0, 1024); throw new AssertionError("Bad render bounds accepted."); }
            catch (IllegalArgumentException expected) { assertions++; }
            try { event(synth, 0x90, 128, 1); throw new AssertionError("Bad MIDI data accepted."); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        for (String invalid : new String[]{"invalid.sf2", "truncated.sf2", "bad-reference.sf2", "bad-link.sf2"})
            rejects(new File(folder, invalid), NEVER);
        rejects(font, () -> true);
        AtomicInteger checks = new AtomicInteger();
        rejects(font, () -> checks.incrementAndGet() >= 20);
        check(checks.get() >= 20, "Mid-load cancellation callback must be honored.");
        AtomicInteger decodingChecks = new AtomicInteger();
        rejects(font, () -> decodingChecks.incrementAndGet() >= 120);
        check(decodingChecks.get() >= 120, "Cancellation during native table decoding must be honored.");

        NativeSynth closed = NativeSynth.load(font, NEVER);
        closed.close(); closed.close();
        try { render(closed, 1); throw new AssertionError("Closed native handle unexpectedly rendered."); }
        catch (IllegalStateException expected) { assertions++; }

        // Independent engines may load concurrently; the opaque handle registry
        // must never release another engine's font or expose a dangling pointer.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable repeated = () -> {
            try {
                for (int i = 0; i < 8; i++) {
                    try (NativeSynth synth = NativeSynth.load(font, NEVER)) {
                        event(synth, 0x90, 69, 100);
                        if (energy(render(synth, 1024)) == 0) throw new AssertionError("Replay returned silence.");
                    }
                }
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        };
        Thread one = new Thread(repeated), two = new Thread(repeated);
        one.start(); two.start(); one.join(); two.join();
        check(failure.get() == null, "Concurrent independent synth lifecycle failed: " + failure.get());
        System.out.println("Native synthesis smoke tests passed: " + assertions + " assertions, original temporary SF2, actual JNI bridge.");
    }
}
