package com.karaokeflow.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Foreground-only playback; all synthesizer and AudioTrack operations use one worker. */
public final class PlaybackEngine implements AutoCloseable {
    public enum State { LOADING, PLAYING, PAUSED, STOPPED, COMPLETED, ERROR }
    public interface Listener {
        void onStateChanged(State state, String message);
        void onPosition(long micros);
        void onError(String message);
    }

    private static final int RATE = 44100;
    private static final int BLOCK_FRAMES = 1024;
    private static final long MAX_RELEASE_FRAMES = 5L * RATE;
    private final Listener listener;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("KaraokeFlow audio", Process.THREAD_PRIORITY_AUDIO);
    private final Handler worker;
    private final AudioManager audioManager;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile State state = State.STOPPED;
    private volatile boolean pauseRequested;

    // Everything below, including lifecycle changes, is owned by the audio worker.
    private long session;
    private NativeSynth synth;
    private AudioTrack track;
    private AudioFocusRequest focus;
    private long focusSerial;
    private MidiSequence sequence;
    private final short[] pcm = new short[BLOCK_FRAMES * 2];
    private int pendingOffset, pendingSamples, eventIndex;
    private long generatedFrames, writtenFrames, durationFrames, releaseAtFrame;
    private long headWrap, previousHead, lastPositionAt, lastHeadAdvancedAt;
    private boolean releasing, generatedComplete;
    private final Runnable pump = this::pump;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause();
        }
    };

    public PlaybackEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        thread.start();
        worker = new Handler(thread.getLooper());
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else this.context.registerReceiver(noisy, filter);
    }

    public synchronized void start(MidiSequence sequence, File soundFont) {
        if (closed.get()) return;
        long token = generation.incrementAndGet();
        pauseRequested = false;
        state = State.LOADING;
        reportState(token, State.LOADING, "Loading SoundFont…");
        worker.post(() -> begin(token, sequence, soundFont));
    }

    public synchronized void pause() {
        if (closed.get()) return;
        // An in-flight load cannot finish and start audio after the Activity leaves.
        if (state == State.LOADING) { stop(); return; }
        pauseRequested = true;
        long token = generation.get();
        worker.post(() -> { if (current(token)) pauseOnWorker(token, "Paused"); });
    }

    public synchronized void resume() {
        if (closed.get()) return;
        long token = generation.get();
        pauseRequested = false;
        worker.post(() -> {
            if (!current(token) || state != State.PAUSED || track == null) return;
            try {
                if (!requestFocus(token)) {
                    setState(token, State.PAUSED, "Audio focus unavailable. Keep the app open and tap Play again.");
                    return;
                }
                if (pauseRequested || !current(token)) { abandonFocus(); return; }
                track.play();
                lastHeadAdvancedAt = SystemClock.uptimeMillis();
                setState(token, State.PLAYING, "Playing");
                worker.post(pump);
            } catch (RuntimeException error) { fail(token, error); }
        });
    }

    public synchronized void stop() {
        if (closed.get()) return;
        long token = generation.incrementAndGet();
        pauseRequested = true;
        state = State.STOPPED;
        worker.post(() -> {
            if (!current(token)) return;
            cleanup();
            setState(token, State.STOPPED, "Stopped");
            reportPosition(token, 0);
        });
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        generation.incrementAndGet();
        pauseRequested = true;
        context.unregisterReceiver(noisy);
        worker.post(() -> { cleanup(); thread.quitSafely(); });
    }

    private boolean current(long token) { return !closed.get() && token == generation.get(); }

    private void begin(long token, MidiSequence sequence, File soundFont) {
        // Do not clear a newer session if a queued start has already become stale.
        if (!current(token)) return;
        cleanup();
        session = token;
        try {
            if (sequence == null) throw new IllegalArgumentException("Select a MIDI song first.");
            this.sequence = sequence;
            synth = NativeSynth.load(soundFont, () -> !current(token) || pauseRequested);
            if (!current(token) || pauseRequested) { cleanup(); return; }
            durationFrames = frameAt(sequence.durationMicros);
            if (!sequence.events.isEmpty()) durationFrames = Math.max(durationFrames,
                    frameAt(sequence.events.get(sequence.events.size() - 1).timeMicros));
            int minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("This phone cannot initialize stereo audio.");
            // Keep queued audio bounded; events are rendered at their exact sample frame.
            int bufferBytes = Math.max(minimum, BLOCK_FRAMES * 4 * 3);
            track = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferBytes).build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("Audio output did not initialize.");
            if (!requestFocus(token)) throw new IllegalStateException("Audio focus unavailable. Keep KaraokeFlow open and try Play again.");
            if (!current(token) || pauseRequested) { cleanup(); return; }
            track.play();
            lastHeadAdvancedAt = SystemClock.uptimeMillis();
            setState(token, State.PLAYING, "Playing");
            reportPosition(token, 0);
            worker.post(pump);
        } catch (Exception | LinkageError error) {
            if (!current(token) || pauseRequested) cleanup();
            else fail(token, error);
        }
    }

    private boolean requestFocus(long token) {
        abandonFocus();
        long requestSerial = ++focusSerial;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {
                    boolean shouldPause;
                    synchronized (PlaybackEngine.this) {
                        shouldPause = current(token) && requestSerial == focusSerial && change != AudioManager.AUDIOFOCUS_GAIN;
                        if (shouldPause) pauseRequested = true;
                    }
                    if (shouldPause) {
                        pauseOnWorker(token, "Paused because another app needs audio. Tap Play to resume.");
                    }
                }, worker).build();
        boolean granted = audioManager != null && audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!granted) abandonFocus();
        return granted;
    }

    private void abandonFocus() {
        ++focusSerial; // Invalidate focus callbacks already queued for an abandoned request.
        if (focus != null) {
            if (audioManager != null) audioManager.abandonAudioFocusRequest(focus);
            focus = null;
        }
    }

    private void pauseOnWorker(long token, String message) {
        if (!current(token) || track == null || state != State.PLAYING) return;
        try {
            worker.removeCallbacks(pump);
            track.pause(); // Retains queued PCM and freezes the audible playback head.
            reportPosition(token, positionMicros(playbackHead()));
            abandonFocus();
            setState(token, State.PAUSED, message);
        } catch (RuntimeException error) { fail(token, error); }
    }

    private void pump() {
        final long token = session;
        if (!current(token) || state != State.PLAYING || pauseRequested || track == null) return;
        try {
            long head = playbackHead();
            long now = SystemClock.uptimeMillis();
            if (now - lastPositionAt >= 33) {
                lastPositionAt = now;
                reportPosition(token, positionMicros(head));
            }
            // Finish only once the generated release tail has actually left AudioTrack.
            if (generatedComplete && pendingSamples == 0 && head >= writtenFrames) {
                long end = sequence.durationMicros;
                cleanup();
                setState(token, State.COMPLETED, "Finished");
                reportPosition(token, end);
                return;
            }
            if (writtenFrames > head && now - lastHeadAdvancedAt > 10000) {
                throw new IllegalStateException("Audio output stopped advancing. Tap Play to try again.");
            }
            // Bound work per pump so pause/stop/focus commands can always run promptly.
            for (int attempt = 0; attempt < 4 && current(token) && !pauseRequested; attempt++) {
                if (pendingSamples == 0 && !generatedComplete) {
                    pendingOffset = 0;
                    pendingSamples = renderBlock() * 2;
                }
                if (pendingSamples == 0) break;
                int written = track.write(pcm, pendingOffset, pendingSamples, AudioTrack.WRITE_NON_BLOCKING);
                if (written < 0) throw new IllegalStateException("Audio output write failed (" + written + ").");
                if (written == 0) break;
                if ((written & 1) != 0) throw new IllegalStateException("Audio output returned an incomplete stereo frame.");
                pendingOffset += written;
                pendingSamples -= written;
                writtenFrames += written / 2;
            }
            if (current(token) && !pauseRequested) worker.postDelayed(pump, 5);
        } catch (Exception | LinkageError error) { fail(token, error); }
    }

    private int renderBlock() {
        int rendered = 0;
        while (rendered < BLOCK_FRAMES) {
            while (eventIndex < sequence.events.size()
                    && frameAt(sequence.events.get(eventIndex).timeMicros) <= generatedFrames) {
                synth.event(sequence.events.get(eventIndex++));
            }
            if (!releasing && eventIndex == sequence.events.size() && generatedFrames >= durationFrames) {
                synth.releaseNotes(); // Also drops sustain, including files missing final note-off.
                releaseAtFrame = generatedFrames;
                releasing = true;
            }
            if (releasing && (synth.activeVoices() == 0 || generatedFrames - releaseAtFrame >= MAX_RELEASE_FRAMES)) {
                generatedComplete = true;
                break;
            }
            long boundary = generatedFrames + BLOCK_FRAMES - rendered;
            if (eventIndex < sequence.events.size()) boundary = Math.min(boundary,
                    frameAt(sequence.events.get(eventIndex).timeMicros));
            if (!releasing) boundary = Math.min(boundary, durationFrames);
            else boundary = Math.min(boundary, releaseAtFrame + MAX_RELEASE_FRAMES);
            int frames = (int) (boundary - generatedFrames);
            if (frames <= 0) throw new IllegalStateException("MIDI event timing is inconsistent.");
            synth.render(pcm, rendered, frames);
            generatedFrames += frames;
            rendered += frames;
        }
        return rendered;
    }

    private static long frameAt(long micros) {
        // Avoid multiplication overflow and choose the nearest output sample (<= 12 us error).
        return (micros / 1000000L) * RATE + ((micros % 1000000L) * RATE + 500000L) / 1000000L;
    }

    private long playbackHead() {
        long head = track.getPlaybackHeadPosition() & 0xffffffffL;
        if (head < previousHead) headWrap += 1L << 32;
        if (head != previousHead) lastHeadAdvancedAt = SystemClock.uptimeMillis();
        previousHead = head;
        return headWrap + head;
    }

    private long positionMicros(long frames) {
        return Math.min(sequence.durationMicros, (frames / RATE) * 1000000L + (frames % RATE) * 1000000L / RATE);
    }

    private void fail(long token, Throwable error) {
        cleanup();
        if (!current(token)) return;
        String message = error.getMessage();
        if (message == null || message.isEmpty()) message = "Playback failed. Check your MIDI and SoundFont selections.";
        setState(token, State.ERROR, message);
        final String detail = message;
        main.post(() -> { if (current(token)) listener.onError(detail); });
    }

    private void cleanup() {
        worker.removeCallbacks(pump);
        abandonFocus();
        if (track != null) {
            try { track.pause(); track.flush(); track.stop(); }
            catch (RuntimeException ignored) { /* Release is still required after device disconnects. */ }
            finally { track.release(); track = null; }
        }
        if (synth != null) { synth.close(); synth = null; }
        sequence = null;
        pendingOffset = pendingSamples = eventIndex = 0;
        generatedFrames = writtenFrames = durationFrames = releaseAtFrame = 0;
        headWrap = previousHead = lastPositionAt = lastHeadAdvancedAt = 0;
        releasing = generatedComplete = false;
    }

    private synchronized void setState(long token, State state, String message) {
        if (!current(token)) return;
        this.state = state;
        reportState(token, state, message);
    }

    private void reportState(long token, State state, String message) {
        main.post(() -> { if (current(token)) listener.onStateChanged(state, message); });
    }

    private void reportPosition(long token, long micros) {
        main.post(() -> { if (current(token)) listener.onPosition(micros); });
    }
}
