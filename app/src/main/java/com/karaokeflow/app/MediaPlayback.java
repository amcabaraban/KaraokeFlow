package com.karaokeflow.app;

import android.content.*;
import android.graphics.SurfaceTexture;
import android.graphics.Matrix;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.view.*;

/** One asynchronous media session, with a replaceable surface for rotation. */
public final class MediaPlayback implements AutoCloseable {
    private final Context context;
    private final PlaybackEngine.Listener listener;
    private final boolean background;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Surface surface;
    private TextureView view;
    private AudioFocusRequest focus;
    private final AudioManager audio;
    private boolean prepared, playing, resumeAfterPrepare;
    private int generation, videoWidth, videoHeight;
    private final View.OnLayoutChangeListener layout = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> fitVideo();
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(); }
    };
    private final Runnable tick = new Runnable() {
        public void run() {
            if (player == null || !prepared || !playing) return;
            try { if (listener != null) listener.onPosition(player.getCurrentPosition() * 1000L); }
            catch (IllegalStateException ignored) { return; }
            main.postDelayed(this, 100);
        }
    };
    public MediaPlayback(Context context, PlaybackEngine.Listener listener, boolean background) {
        this.context = context.getApplicationContext(); this.listener = listener; this.background = background;
        audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        if (!background) {
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
            else this.context.registerReceiver(noisy, filter);
        }
    }
    public void attach(TextureView next) {
        if (view != null) { view.setSurfaceTextureListener(null); view.removeOnLayoutChangeListener(layout); }
        if (player != null) player.setSurface(null);
        if (surface != null) { surface.release(); surface = null; }
        view = next;
        view.addOnLayoutChangeListener(layout);
        fitVideo();
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                surface = new Surface(texture);
                if (player != null) player.setSurface(surface);
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                if (player != null) player.setSurface(null);
                if (surface != null) { surface.release(); surface = null; }
                return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
        if (view.isAvailable()) {
            surface = new Surface(view.getSurfaceTexture());
            if (player != null) player.setSurface(surface);
        }
    }
    public void start(Uri uri) {
        stop();
        int token = ++generation;
        resumeAfterPrepare = true;
        report(PlaybackEngine.State.LOADING, "Loading media…");
        try {
            MediaPlayer next = new MediaPlayer();
            player = next;
            next.setAudioAttributes(attributes);
            next.setSurface(surface);
            next.setLooping(background);
            next.setOnVideoSizeChangedListener((p, width, height) -> {
                if (p != player) return;
                videoWidth = width; videoHeight = height; fitVideo();
            });
            if (background) next.setVolume(0, 0);
            next.setOnPreparedListener(p -> {
                if (token != generation || p != player) return;
                prepared = true;
                if (resumeAfterPrepare) resume();
                else report(PlaybackEngine.State.PAUSED, "Paused");
            });
            next.setOnCompletionListener(p -> {
                if (p != player) return;
                playing = false; main.removeCallbacks(tick); abandonFocus();
                report(PlaybackEngine.State.COMPLETED, "Finished");
            });
            next.setOnErrorListener((p, what, extra) -> {
                if (p == player) fail("This media cannot be played on this device (" + what + "/" + extra + ").");
                return true;
            });
            next.setDataSource(context, uri);
            next.prepareAsync();
        } catch (Exception error) { fail(error.getMessage() == null ? "Cannot open media." : error.getMessage()); }
    }
    private void fitVideo() {
        if (view == null || videoWidth <= 0 || videoHeight <= 0 || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        float width = view.getWidth(), height = view.getHeight();
        float scale = background ? Math.max(width / videoWidth, height / videoHeight)
                : Math.min(width / videoWidth, height / videoHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2, height / 2);
        view.setTransform(matrix);
    }
    public void resume() {
        resumeAfterPrepare = true;
        if (!prepared || player == null) return;
        try {
            if (!background) {
                abandonFocus();
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(change -> {
                            if (change != AudioManager.AUDIOFOCUS_GAIN) pause();
                        }, main).build();
                if (audio == null || audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    abandonFocus(); report(PlaybackEngine.State.PAUSED, "Audio focus unavailable."); return;
                }
            }
            if (player.getCurrentPosition() >= player.getDuration()) player.seekTo(0);
            player.start(); playing = true;
            report(PlaybackEngine.State.PLAYING, "Playing");
            main.removeCallbacks(tick); main.post(tick);
        } catch (RuntimeException error) { fail("Media playback failed."); }
    }
    public void pause() {
        resumeAfterPrepare = false;
        if (player == null) return;
        if (prepared && playing) {
            try { player.pause(); } catch (IllegalStateException ignored) { }
        }
        playing = false; main.removeCallbacks(tick); abandonFocus();
        report(PlaybackEngine.State.PAUSED, "Paused");
    }
    public long durationMicros() {
        try { return prepared && player != null ? player.getDuration() * 1000L : 0; }
        catch (IllegalStateException ignored) { return 0; }
    }
    public void stop() {
        generation++; main.removeCallbacks(tick);
        prepared = playing = resumeAfterPrepare = false;
        if (player != null) { player.release(); player = null; }
        abandonFocus();
    }
    private void abandonFocus() {
        if (focus != null && audio != null) audio.abandonAudioFocusRequest(focus);
        focus = null;
    }
    private void report(PlaybackEngine.State state, String message) {
        if (listener != null) listener.onStateChanged(state, message);
    }
    private void fail(String message) {
        stop(); report(PlaybackEngine.State.ERROR, message);
        if (listener != null) listener.onError(message);
    }
    public void close() {
        stop();
        if (view != null) view.setSurfaceTextureListener(null);
        if (surface != null) { surface.release(); surface = null; }
        if (!background) context.unregisterReceiver(noisy);
    }
}
