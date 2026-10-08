package com.karaokeflow.app;

import java.io.File;
import java.io.IOException;

/** Worker-confined bridge. No audio, MIDI, or SoundFont assets are bundled. */
final class NativeSynth implements AutoCloseable {
    static final long MAX_FONT_BYTES = 256L * 1024 * 1024;
    interface Cancellation { boolean isCancelled(); }

    static { System.loadLibrary("karaokeflow_synth"); }
    private long handle;

    private NativeSynth(long handle) { this.handle = handle; }

    static NativeSynth load(File file, Cancellation cancellation) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Select a readable .sf2 SoundFont.");
        if (file.length() > MAX_FONT_BYTES) throw new IOException("This beta supports SoundFonts up to 256 MB.");
        long handle = nativeLoad(file.getAbsolutePath(), cancellation);
        if (handle == 0) throw new IOException("Could not load this SoundFont.");
        return new NativeSynth(handle);
    }

    void event(MidiSequence.Event event) { nativeEvent(handle, event.status, event.data1, event.data2); }
    void render(short[] output, int offsetFrames, int frames) { nativeRender(handle, output, offsetFrames, frames); }
    void releaseNotes() { nativeReleaseNotes(handle); }
    int activeVoices() { return nativeActiveVoices(handle); }

    @Override public void close() {
        if (handle != 0) {
            long old = handle;
            handle = 0;
            nativeClose(old);
        }
    }

    private static native long nativeLoad(String path, Cancellation cancellation) throws IOException;
    private static native void nativeEvent(long handle, int status, int data1, int data2);
    private static native void nativeRender(long handle, short[] output, int offsetFrames, int frames);
    private static native void nativeReleaseNotes(long handle);
    private static native int nativeActiveVoices(long handle);
    private static native void nativeClose(long handle);
}
