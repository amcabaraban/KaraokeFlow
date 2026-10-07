package com.karaokeflow.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;

/** Foreground-only beta. Provider I/O and MIDI parsing run off the UI thread. */
public class MainActivity extends Activity implements PlaybackEngine.Listener {
    private static final int CSV = 1, FOLDER = 2, SF2 = 3, MUTED = 0xffa5aabe;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private List<SongCatalog.Song> songs = new ArrayList<>();
    private LinearLayout list;
    private EditText search;
    private TextView setupStatus, playerStatus, selectedTitle, currentLyric, nextLyric, clock, results;
    private SeekBar progress;
    private Button play, pause, stop;
    private SharedPreferences preferences;
    private SongFiles files;
    private PlaybackEngine engine;
    private PlaybackEngine.State state = PlaybackEngine.State.STOPPED;
    private SongCatalog.Song selected;
    private MidiSequence prepared;
    private File selectedFont;
    private LyricTimeline timeline;
    private Future<?> preparation;
    private int request, catalogRequest;
    private boolean destroyed, foreground;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getPreferences(MODE_PRIVATE);
        files = new SongFiles(this);
        engine = new PlaybackEngine(this, this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        root.setBackgroundColor(0xff080b12);
        // targetSdk 35 enforces edge-to-edge; keep controls clear of bars and keyboard.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(18) + insets.getSystemWindowInsetLeft(),
                    dp(12) + insets.getSystemWindowInsetTop(),
                    dp(18) + insets.getSystemWindowInsetRight(),
                    dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("KaraokeFlow", 28, Color.WHITE);
        brand.setTypeface(null, 1);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mode = text("LIVE\nSTAGE", 10, 0xffc8b8ff);
        mode.setGravity(Gravity.CENTER);
        mode.setTypeface(null, 1);
        mode.setBackground(background(0xff251d45, 12));
        header.addView(mode, new LinearLayout.LayoutParams(dp(62), dp(42)));
        root.addView(header);
        TextView subtitle = text("Your songs. Your stage.", 13, MUTED);
        subtitle.setPadding(dp(4), 0, dp(4), dp(8));
        root.addView(subtitle);

        LinearLayout player = new LinearLayout(this);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setGravity(Gravity.CENTER_HORIZONTAL);
        player.setPadding(dp(18), dp(14), dp(18), dp(14));
        player.setBackground(stageBackground());
        TextView nowPlaying = text("NOW PLAYING", 10, 0xffb9a9e9);
        nowPlaying.setTypeface(null, 1); player.addView(nowPlaying);
        selectedTitle = text("Choose a song to begin", 19, Color.WHITE);
        selectedTitle.setTypeface(null, 1); selectedTitle.setGravity(Gravity.CENTER);
        selectedTitle.setMaxLines(2); player.addView(selectedTitle);
        currentLyric = text("Your lyrics will appear here", 25, Color.WHITE);
        currentLyric.setGravity(Gravity.CENTER); currentLyric.setMinLines(2);
        currentLyric.setTypeface(null, 1); player.addView(currentLyric);
        nextLyric = text("", 15, 0xff9994ac); nextLyric.setGravity(Gravity.CENTER);
        nextLyric.setMaxLines(2); player.addView(nextLyric);
        progress = new SeekBar(this); progress.setMax(1000); progress.setProgress(0);
        progress.setEnabled(false); player.addView(progress, new LinearLayout.LayoutParams(-1, dp(28)));
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        clock = text("0:00 / 0:00", 12, MUTED); clock.setGravity(Gravity.CENTER);
        timeRow.addView(clock, new LinearLayout.LayoutParams(0, -2, 1));
        playerStatus = text("Ready when you are", 12, 0xffc1bbd1); playerStatus.setGravity(Gravity.CENTER);
        timeRow.addView(playerStatus, new LinearLayout.LayoutParams(0, -2, 2));
        player.addView(timeRow);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL); controls.setPadding(0, dp(8), 0, 0);
        play = button("▶  Play", v -> playSelected());
        play.setTextSize(16); play.setTypeface(null, 1); play.setBackground(background(0xff8b5cf6, 24));
        play.setMinHeight(dp(52));
        pause = button("Pause", v -> engine.pause());
        stop = button("Stop", v -> stopPlayback());
        play.setContentDescription("Play or resume the selected song");
        pause.setContentDescription("Pause playback");
        stop.setContentDescription("Stop playback and return to the beginning");
        controls.addView(stop, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(play, new LinearLayout.LayoutParams(0, dp(54), 1.5f));
        controls.addView(pause, new LinearLayout.LayoutParams(0, dp(48), 1));
        player.addView(controls);
        root.addView(player, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout sources = new LinearLayout(this);
        sources.setGravity(Gravity.CENTER_VERTICAL); sources.setPadding(dp(10), dp(8), dp(10), dp(8));
        sources.setBackground(background(0xff141824, 16));
        Button catalogButton = button("Catalog", v -> openPicker(CSV));
        Button folderButton = button("MIDI folder", v -> openPicker(FOLDER));
        Button soundFontButton = button("SoundFont", v -> openPicker(SF2));
        sources.addView(catalogButton, weightedButton()); sources.addView(folderButton, weightedButton());
        sources.addView(soundFontButton, weightedButton()); root.addView(sources);
        setupStatus = text("", 11, MUTED); setupStatus.setMaxLines(2); root.addView(setupStatus);

        LinearLayout libraryHeader = new LinearLayout(this);
        libraryHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView libraryTitle = text("Your library", 21, Color.WHITE); libraryTitle.setTypeface(null, 1);
        libraryHeader.addView(libraryTitle, new LinearLayout.LayoutParams(0, -2, 1));
        results = text("", 12, MUTED); libraryHeader.addView(results);
        root.addView(libraryHeader);
        search = new EditText(this);
        search.setHint("Search songs, artists or song #"); search.setHintTextColor(0xff777e95);
        search.setTextColor(Color.WHITE); search.setSingleLine();
        search.setBackground(background(0xff181c2a, 16)); search.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(50));
        searchParams.setMargins(0, dp(4), 0, dp(8)); root.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(s.toString()); }
            public void afterTextChanged(Editable editable) { }
        });
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        updateSetup(); updateControls(); render("");
        String catalog = preferences.getString("catalog", null);
        if (catalog != null) importCatalog(Uri.parse(catalog));
    }

    private Button picker(String label, int code) {
        return button(label, v -> openPicker(code));
    }

    private void openPicker(int code) {
        Intent intent;
        if (code == FOLDER) intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // Providers often label CSV and SF2 as application/octet-stream.
            intent.setType("*/*");
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, code);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) {
            playerStatus.setText("Temporary file access: choose the files again after restarting.");
        }
        if (code == CSV) importCatalog(uri);
        else if (code == FOLDER || code == SF2) {
            stopPlayback(); prepared = null; timeline = null;
            if (code == FOLDER) files = new SongFiles(this);
            if (code == SF2) selectedFont = null;
            String key = code == FOLDER ? "midi" : "sf2";
            preferences.edit().putString(key, uri.toString()).putString(key + "Name", displayName(uri)).apply();
            updateSetup();
            playerStatus.setText(code == FOLDER ? "MIDI folder selected. Tap a song or press Play."
                    : "SoundFont selected. It will load when you play a song.");
            showLyrics(0);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        String segment = uri.getLastPathSegment();
        return segment == null ? "Selected" : segment.substring(segment.lastIndexOf(':') + 1);
    }

    private void importCatalog(Uri uri) {
        int token = ++catalogRequest;
        setupStatus.setText("Loading catalog…");
        io.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("Cannot open the catalog.");
                List<SongCatalog.Song> loaded = SongCatalog.parse(input);
                runOnUiThread(() -> {
                    if (destroyed || token != catalogRequest) return;
                    songs = loaded; preferences.edit().putString("catalog", uri.toString()).apply();
                    updateSetup(); render(search.getText().toString());
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed || token != catalogRequest) return;
                    updateSetup(); playerStatus.setText("Catalog import failed: " + reason(error) + " Choose Import CSV to retry.");
                });
            }
        });
    }

    private void updateSetup() {
        String folder = preferences.getString("midiName", preferences.contains("midi") ? "Selected" : "Choose folder");
        String font = preferences.getString("sf2Name", preferences.contains("sf2") ? "Selected" : "Choose .sf2");
        setupStatus.setText("MIDI: " + folder + "\nSF2: " + font + "  •  " + songs.size() + " songs");
    }

    private void selectSong(SongCatalog.Song song) {
        stopPlayback(); selected = song; prepared = null; timeline = null;
        selectedTitle.setText("#" + song.songNumber() + "  " + (song.title.isEmpty() ? "Untitled" : song.title));
        updateControls(); playSelected();
    }

    private void playSelected() {
        if (selected == null || !foreground || state == PlaybackEngine.State.LOADING || state == PlaybackEngine.State.PLAYING) return;
        if (state == PlaybackEngine.State.PAUSED) { engine.resume(); return; }
        String treeValue = preferences.getString("midi", null), fontValue = preferences.getString("sf2", null);
        if (treeValue == null || fontValue == null) {
            playerStatus.setText("Choose a MIDI folder and a .sf2 SoundFont before playing."); return;
        }
        if (prepared != null && selectedFont != null && selectedFont.exists()) {
            state = PlaybackEngine.State.LOADING; updateControls(); showLyrics(0);
            engine.start(prepared, selectedFont); return;
        }
        cancelPreparation();
        final int token = request;
        final SongCatalog.Song song = selected;
        final Uri tree = Uri.parse(treeValue), font = Uri.parse(fontValue);
        final File cachedFont = selectedFont;
        final SongFiles sourceFiles = files;
        state = PlaybackEngine.State.LOADING; playerStatus.setText("Finding MIDI and loading SoundFont…"); updateControls();
        preparation = io.submit(() -> {
            try {
                Uri midi = sourceFiles.findMidi(tree, song);
                MidiSequence sequence;
                try (InputStream input = sourceFiles.openMidi(midi)) { sequence = MidiSequence.parse(input); }
                if (Thread.currentThread().isInterrupted()) return;
                File soundFont = cachedFont != null && cachedFont.exists() ? cachedFont : sourceFiles.cacheSoundFont(font);
                runOnUiThread(() -> {
                    if (destroyed || token != request || !foreground) return;
                    preparation = null; prepared = sequence; selectedFont = soundFont;
                    timeline = new LyricTimeline(sequence); showLyrics(0); engine.start(sequence, soundFont);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed || token != request) return;
                    preparation = null; state = PlaybackEngine.State.ERROR;
                    playerStatus.setText("Cannot play: " + reason(error)); updateControls();
                });
            }
        });
    }

    private void cancelPreparation() {
        request++;
        if (preparation != null) { preparation.cancel(true); preparation = null; }
    }
    private void stopPlayback() {
        cancelPreparation(); engine.stop(); state = PlaybackEngine.State.STOPPED;
        showLyrics(0); playerStatus.setText("Stopped. Press Play to start from the beginning."); updateControls();
    }
    @Override public void onStateChanged(PlaybackEngine.State newState, String message) {
        if (destroyed || (newState == PlaybackEngine.State.STOPPED && preparation != null)) return;
        state = newState; playerStatus.setText(message);
        if (state == PlaybackEngine.State.STOPPED) showLyrics(0);
        if (state == PlaybackEngine.State.PLAYING) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateControls();
    }
    @Override public void onPosition(long micros) { if (!destroyed) showLyrics(micros); }
    @Override public void onError(String message) {
        if (destroyed) return;
        state = PlaybackEngine.State.ERROR; playerStatus.setText("Playback failed: " + message);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateControls();
    }
    private void showLyrics(long micros) {
        if (clock == null) return;
        long duration = prepared == null ? 0 : prepared.durationMicros;
        clock.setText(time(micros) + " / " + time(duration));
        progress.setProgress(duration <= 0 ? 0 : (int) Math.min(1000, micros * 1000L / duration));
        if (timeline == null) { currentLyric.setText("Your lyrics will appear here"); nextLyric.setText(""); }
        else if (prepared.lyrics.isEmpty()) { currentLyric.setText("Instrumental / no lyrics in this MIDI"); nextLyric.setText(""); }
        else {
            LyricTimeline.Cue cue = timeline.at(micros);
            SpannableString line = new SpannableString(cue.line.isEmpty() ? "Music intro…" : cue.line);
            int sung = Math.min(cue.sungCharacters, line.length());
            if (sung > 0) line.setSpan(new ForegroundColorSpan(0xffb7a0ff), 0, sung, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            currentLyric.setText(line); nextLyric.setText(cue.nextLine);
        }
    }
    private void updateControls() {
        if (play == null) return;
        play.setText(state == PlaybackEngine.State.PAUSED ? "▶  Resume" : "▶  Play");
        play.setEnabled(selected != null && state != PlaybackEngine.State.LOADING && state != PlaybackEngine.State.PLAYING);
        pause.setEnabled(state == PlaybackEngine.State.PLAYING);
        stop.setEnabled(state == PlaybackEngine.State.LOADING || state == PlaybackEngine.State.PLAYING
                || state == PlaybackEngine.State.PAUSED || state == PlaybackEngine.State.COMPLETED);
    }
    private void render(String query) {
        if (list == null) return;
        list.removeAllViews(); String needle = query.trim().toLowerCase(Locale.ROOT); int count = 0;
        for (SongCatalog.Song song : songs) {
            if (!needle.isEmpty() && !song.title.toLowerCase(Locale.ROOT).contains(needle)
                    && !song.artist.toLowerCase(Locale.ROOT).contains(needle) && !song.songNumber().contains(needle)) continue;
            if (++count > 100) continue;
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(7), dp(14), dp(7)); card.setBackground(background(0xff141824, 14));
            TextView title = text(song.title.isEmpty() ? "Untitled" : song.title, 17, Color.WHITE);
            title.setTypeface(null, 1); card.addView(title);
            card.addView(text("#" + song.songNumber() + "  •  " + (song.artist.isEmpty() ? "Unknown artist" : song.artist), 13, MUTED));
            card.setClickable(true); card.setFocusable(true);
            card.setContentDescription("Play " + song.title + ", song " + song.songNumber());
            card.setOnClickListener(v -> selectSong(song));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, 0, 0, dp(8)); list.addView(card, params);
        }
        results.setText(count > 100 ? "First 100 of " + count + " matches. Search to narrow the list." : count + " matching songs");
        if (songs.isEmpty()) list.addView(text("Import your catalog to begin.", 14, MUTED));
        else if (count == 0) list.addView(text("No matching songs.", 14, MUTED));
    }
    @Override protected void onResume() { super.onResume(); foreground = true; }
    @Override protected void onPause() {
        foreground = false;
        if (preparation != null) stopPlayback();
        engine.pause(); super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; catalogRequest++; cancelPreparation(); engine.close(); io.shutdownNow(); super.onDestroy();
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private GradientDrawable stageBackground() {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff211b3e, 0xff151a2e, 0xff10131f});
        shape.setCornerRadius(dp(24));
        shape.setStroke(dp(1), 0xff3c315f);
        return shape;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(dp(4), dp(4), dp(4), dp(4)); return view;
    }
    private Button button(String value, View.OnClickListener click) {
        Button b = new Button(this); b.setText(value); b.setTextSize(11); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setOnClickListener(click); return b;
    }
    private LinearLayout.LayoutParams weightedButton() { return new LinearLayout.LayoutParams(0, dp(48), 1); }
    private static String time(long micros) {
        long seconds = Math.max(0, micros / 1000000L);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    private static String reason(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
