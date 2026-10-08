package com.karaokeflow.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private static final int CSV = 1, FOLDER = 2, SF2 = 3;
    private static final int MUTED = 0xffa5aabe;
    private static final int SCREEN_HOME = 0, SCREEN_STAGE = 1, SCREEN_SEARCH = 2;
    private static final int NAVY_BG = 0xff060913;
    private static final int STAGE_TOP = 0xff0e4aa8, STAGE_BOTTOM = 0xff061a3a;
    private static final int CHIP_RED = 0xffd23b2e;
    private static final int CHIP_GREEN = 0xff2fae4e;
    private static final int KEY_BLUE = 0xff1e4fa8;
    private static final int KEY_DARK = 0xff1c2230;
    private static final int KEY_PURPLE = 0xff6a2fb3;
    private static final int DIGIT_YELLOW = 0xffffc21a;
    private static final int SINGER_YELLOW = 0xffffd21f;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private int searchGeneration;
    private List<SongCatalog.Song> songs = new ArrayList<>();
    private final List<SongCatalog.Song> queue = new ArrayList<>();
    private boolean showQueue;
    private int screen = SCREEN_HOME;
    private final StringBuilder entryDigits = new StringBuilder();
    private LinearLayout homeRoot, stageRoot, searchRoot;
    private LinearLayout list;
    private EditText search;
    private TextView setupStatus, playerStatus, selectedTitle, currentLyric, nextLyric, clock, results, queueLabel;
    private TextView libraryTab, queueTab;
    private TextView stageTitle, stageSinger, numberDisplay, reserveBadge;
    private SeekBar progress;
    private Button play, pause, stop, next;
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
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(NAVY_BG);
        setContentView(shell);
        homeRoot = buildHome();
        stageRoot = buildStage();
        searchRoot = buildSearch();
        shell.addView(homeRoot, new FrameLayout.LayoutParams(-1, -1));
        shell.addView(stageRoot, new FrameLayout.LayoutParams(-1, -1));
        shell.addView(searchRoot, new FrameLayout.LayoutParams(-1, -1));
        showScreen(SCREEN_HOME);
        updateSetup(); updateControls(); render("");
        refreshEntry();
        String catalog = preferences.getString("catalog", null);
        if (catalog != null) importCatalog(Uri.parse(catalog));
    }

    private LinearLayout screenRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(NAVY_BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        return root;
    }

    private LinearLayout buildHome() {
        LinearLayout root = screenRoot();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER);
        top.setPadding(dp(12), dp(10), dp(12), dp(2));
        TextView date = text("KaraokeFlow MIDI Karaoke", 12, 0xffc9d4ea);
        date.setGravity(Gravity.CENTER);
        top.addView(date, new LinearLayout.LayoutParams(-1, -2));
        root.addView(top);
        TextView brand = text("KARAOKEFLOW", 30, Color.WHITE);
        brand.setTypeface(null, 1); brand.setGravity(Gravity.CENTER);
        root.addView(brand);
        TextView sub = text("MIDI KARAOKE PLATFORM", 13, 0xff9fb4d8);
        sub.setTypeface(null, 1); sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(6));
        root.addView(sub);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(2), dp(16), dp(16));
        scroll.addView(body, new LinearLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(menuTile("Enter Karaoke", 0xff9c2b6e, 0xffd34a4a, v -> showScreen(SCREEN_STAGE)));
        LinearLayout songbook = menuTile("Songbook", 0xff5b2a86, 0xff8b3fb3,
                v -> { showQueue = false; showScreen(SCREEN_SEARCH); });
        LinearLayout.LayoutParams songbookParams = new LinearLayout.LayoutParams(-1, -2);
        songbookParams.setMargins(0, dp(8), 0, 0);
        body.addView(songbook, songbookParams);
        setupStatus = text("", 11, MUTED);
        setupStatus.setMaxLines(3);
        setupStatus.setGravity(Gravity.CENTER);
        setupStatus.setPadding(0, dp(10), 0, 0);
        body.addView(setupStatus);
        LinearLayout setup = new LinearLayout(this);
        setup.setGravity(Gravity.CENTER_VERTICAL);
        setup.setPadding(0, dp(8), 0, 0);
        setup.addView(setupButton("Catalog", v -> openPicker(CSV)), new LinearLayout.LayoutParams(0, dp(48), 1));
        setup.addView(setupButton("MIDI folder", v -> openPicker(FOLDER)), new LinearLayout.LayoutParams(0, dp(48), 1));
        setup.addView(setupButton("SoundFont", v -> openPicker(SF2)), new LinearLayout.LayoutParams(0, dp(48), 1));
        setup.addView(setupButton("Refresh", v -> refreshSources()), new LinearLayout.LayoutParams(0, dp(48), 1));
        body.addView(setup);
        return root;
    }
    private LinearLayout menuTile(String label, int start, int end, View.OnClickListener click) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(12), dp(20), dp(12), dp(20));
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        shape.setCornerRadius(dp(18));
        tile.setBackground(shape);
        tile.setClickable(true); tile.setFocusable(true);
        tile.setOnClickListener(click);
        TextView icon = text("\u266A", 44, 0xfff2e8f2);
        icon.setGravity(Gravity.CENTER);
        tile.addView(icon);
        TextView name = text(label, 15, Color.WHITE);
        name.setTypeface(null, 1); name.setGravity(Gravity.CENTER);
        tile.addView(name);
        LinearLayout wrap = new LinearLayout(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        wrap.addView(tile, params);
        return wrap;
    }

    private Button setupButton(String label, View.OnClickListener click) {
        Button b = button(label, click);
        b.setBackground(background(0xff232c44, 12));
        return b;
    }

    private LinearLayout buildStage() {
        LinearLayout root = screenRoot();
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setGravity(Gravity.CENTER_HORIZONTAL);
        stage.setPadding(dp(16), dp(16), dp(16), dp(12));
        stage.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{STAGE_TOP, STAGE_BOTTOM}));
        stageTitle = text("Select a Song", 24, Color.WHITE);
        stageTitle.setTypeface(null, 1); stageTitle.setGravity(Gravity.CENTER);
        stageTitle.setShadowLayer(dp(3), 0, 2, 0xff000000);
        stage.addView(stageTitle);
        numberDisplay = text("000000", 40, DIGIT_YELLOW);
        numberDisplay.setTypeface(null, 1); numberDisplay.setGravity(Gravity.CENTER);
        numberDisplay.setShadowLayer(dp(4), 0, 2, 0xff000000);
        stage.addView(numberDisplay);
        stageSinger = text("Singer: -", 14, SINGER_YELLOW);
        stageSinger.setTypeface(null, 1); stageSinger.setGravity(Gravity.CENTER);
        stage.addView(stageSinger);
        selectedTitle = text("Choose a song to begin", 13, 0xffdbe6ff);
        selectedTitle.setGravity(Gravity.CENTER); selectedTitle.setMaxLines(1);
        stage.addView(selectedTitle);
        currentLyric = text("Your lyrics will appear here", 20, Color.WHITE);
        currentLyric.setGravity(Gravity.CENTER); currentLyric.setMinLines(1); currentLyric.setMaxLines(2);
        currentLyric.setTypeface(null, 1);
        currentLyric.setShadowLayer(dp(3), 0, 2, 0xff000000);
        stage.addView(currentLyric);
        nextLyric = text("", 13, 0xffbcd0f5); nextLyric.setGravity(Gravity.CENTER);
        nextLyric.setMaxLines(1); stage.addView(nextLyric);
        progress = new SeekBar(this); progress.setMax(1000); progress.setProgress(0);
        progress.setEnabled(false);
        stage.addView(progress, new LinearLayout.LayoutParams(-1, dp(22)));
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        clock = text("0:00 / 0:00", 11, 0xffdbe6ff); clock.setGravity(Gravity.START);
        timeRow.addView(clock, new LinearLayout.LayoutParams(0, -2, 1));
        playerStatus = text("Ready when you are", 11, 0xffdbe6ff); playerStatus.setGravity(Gravity.END);
        timeRow.addView(playerStatus, new LinearLayout.LayoutParams(0, -2, 2));
        stage.addView(timeRow);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1.35f));
        LinearLayout deck = new LinearLayout(this);
        deck.setOrientation(LinearLayout.VERTICAL);
        deck.setBackgroundColor(0xff05070d);
        deck.setPadding(dp(8), dp(8), dp(8), dp(8));
        deck.addView(transportRow());
        deck.addView(keypadGrid());
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(6), 0, 0);
        bottom.addView(deckButton("Home", KEY_BLUE, v -> showScreen(SCREEN_HOME)),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        reserveBadge = text("RSV: 0", 12, Color.WHITE);
        reserveBadge.setGravity(Gravity.CENTER);
        reserveBadge.setBackground(background(KEY_PURPLE, 8));
        reserveBadge.setOnClickListener(v -> { showQueue = true; showScreen(SCREEN_SEARCH); });
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        badgeParams.setMargins(dp(6), 0, dp(6), 0);
        bottom.addView(reserveBadge, badgeParams);
        bottom.addView(deckButton("Search", KEY_BLUE, v -> showScreen(SCREEN_SEARCH)),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        deck.addView(bottom);
        root.addView(deck, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private Button deckButton(String label, int color, View.OnClickListener click) {
        Button b = button(label, click);
        b.setBackground(background(color, 8));
        return b;
    }

    private LinearLayout transportRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(2));
        Button replayButton = deckButton("Replay", KEY_BLUE, v -> replayCurrent());
        pause = deckButton("\u275A\u275A", KEY_BLUE, v -> engine.pause());
        play = deckButton("\u25B6", KEY_BLUE, v -> playSelected());
        next = deckButton("Next", KEY_BLUE, v -> playNext());
        stop = deckButton("\u25A0", KEY_BLUE, v -> stopPlayback());
        replayButton.setContentDescription("Replay current song from the beginning");
        pause.setContentDescription("Pause playback");
        play.setContentDescription("Play or resume the selected song");
        next.setContentDescription("Play the next reserved song");
        stop.setContentDescription("Stop playback and return to the beginning");
        row.addView(replayButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(pause, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(play, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(next, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(stop, new LinearLayout.LayoutParams(0, dp(48), 1));
        return row;
    }

    private LinearLayout keypadGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"RES", "0", "CAN"}};
        for (String[] keys : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (String key : keys) {
                int color = "RES".equals(key) ? CHIP_GREEN : "CAN".equals(key) ? CHIP_RED : KEY_DARK;
                Button b = deckButton(key, color, v -> onKeypad(key));
                b.setTextSize(18); b.setTypeface(null, 1);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1);
                params.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(b, params);
            }
            grid.addView(row);
        }
        return grid;
    }

    private void onKeypad(String key) {
        if ("CAN".equals(key)) {
            if (entryDigits.length() > 0) entryDigits.deleteCharAt(entryDigits.length() - 1);
            else stopPlayback();
        } else if ("RES".equals(key)) {
            reserveEntry();
        } else if (key.matches("[0-9]")) {
            if (entryDigits.length() >= 6) entryDigits.delete(0, 1);
            entryDigits.append(key);
            if (entryDigits.length() == 6) reserveEntry();
        }
        refreshEntry();
    }

    private void refreshEntry() {
        if (numberDisplay == null) return;
        String shown = entryDigits.length() == 0 ? "000000"
                : String.format(Locale.ROOT, "%6s", entryDigits.toString()).replace(' ', '0');
        numberDisplay.setText(shown);
    }

    private void updateReserveBadge() {
        if (reserveBadge != null) reserveBadge.setText("RSV: " + queue.size());
        if (queueTab != null) queueTab.setText("Up next (" + queue.size() + ")");
    }

    private void reserveEntry() {
        if (entryDigits.length() == 0) { playNext(); return; }
        while (entryDigits.length() < 6) entryDigits.insert(0, "0");
        String number = entryDigits.toString();
        entryDigits.setLength(0);
        SongCatalog.Song match = null;
        for (SongCatalog.Song song : songs) {
            if (song.songNumber().equals(number)) { match = song; break; }
        }
        if (match != null) {
            queue.add(match);
            if (playerStatus != null) playerStatus.setText("Reserved #" + number + ". Tap Next to play it.");
            updateControls();
            updateReserveBadge();
            refreshEntry();
        } else if (playerStatus != null) {
            playerStatus.setText("No song #" + number + ". Search the Songbook.");
            refreshEntry();
        }
    }

    private LinearLayout buildSearch() {
        LinearLayout root = screenRoot();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(2));
        Button back = deckButton("<", KEY_BLUE, v -> showScreen(SCREEN_STAGE));
        top.addView(back, new LinearLayout.LayoutParams(dp(56), dp(48)));
        TextView title = text("Songbook", 22, Color.WHITE);
        title.setTypeface(null, 1);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMargins(dp(10), 0, dp(10), 0);
        top.addView(title, titleParams);
        results = text("", 12, MUTED);
        top.addView(results);
        root.addView(top);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(12), 0, dp(12), 0);
        libraryTab = text("Library", 14, Color.WHITE);
        libraryTab.setPadding(dp(10), dp(8), dp(10), dp(8));
        queueTab = text("Up next (0)", 14, MUTED);
        queueTab.setPadding(dp(10), dp(8), dp(10), dp(8));
        tabs.addView(libraryTab);
        tabs.addView(queueTab);
        root.addView(tabs);
        libraryTab.setOnClickListener(v -> setTab(false));
        queueTab.setOnClickListener(v -> setTab(true));
        search = new EditText(this);
        search.setHint("Search songs, artists or song #"); search.setHintTextColor(0xff777e95);
        search.setTextColor(Color.WHITE); search.setSingleLine();
        search.setBackground(background(0xff181c2a, 16)); search.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(50));
        searchParams.setMargins(dp(12), dp(4), dp(12), dp(6));
        root.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { scheduleRender(s.toString()); }
            public void afterTextChanged(Editable editable) { }
        });
        queueLabel = text("", 11, MUTED);
        queueLabel.setPadding(dp(12), dp(2), dp(12), 0);
        root.addView(queueLabel);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(6), dp(12), dp(16));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void showScreen(int next) {
        screen = next;
        if (homeRoot != null) homeRoot.setVisibility(next == SCREEN_HOME ? View.VISIBLE : View.GONE);
        if (stageRoot != null) stageRoot.setVisibility(next == SCREEN_STAGE ? View.VISIBLE : View.GONE);
        if (searchRoot != null) searchRoot.setVisibility(next == SCREEN_SEARCH ? View.VISIBLE : View.GONE);
        if (next == SCREEN_SEARCH && list != null && search != null) render(search.getText().toString());
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
            if (code == SF2) {
                selectedFont = null;
                engine.invalidateSoundFont();
                try { files.invalidateSoundFont(uri); }
                catch (Exception error) { playerStatus.setText("SoundFont cache refresh failed: " + reason(error)); }
            }
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

    private void refreshSources() {
        stopPlayback();
        engine.invalidateSoundFont();
        prepared = null;
        timeline = null;
        selectedFont = null;
        final SongFiles sourceFiles = files;
        final String font = preferences.getString("sf2", null);
        playerStatus.setText("Clearing file caches…");
        io.execute(() -> {
            try {
                sourceFiles.clearFolderCache();
                if (font != null) sourceFiles.invalidateSoundFont(Uri.parse(font));
                runOnUiThread(() -> {
                    if (destroyed) return;
                    updateSetup();
                    playerStatus.setText("File caches cleared. The next Play will reload the selected files.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    updateSetup();
                    playerStatus.setText("Cache refresh failed: " + reason(error));
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
        showScreen(SCREEN_STAGE);
        stopPlayback(); selected = song; prepared = null; timeline = null;
        String name = song.title.isEmpty() ? "Untitled" : song.title;
        selectedTitle.setText("#" + song.songNumber() + "  " + name);
        stageTitle.setText(name);
        stageSinger.setText("Singer: " + (song.artist.isEmpty() ? "Unknown artist" : song.artist));
        updateControls(); playSelected();
    }
    private void setTab(boolean toQueue) {
        showQueue = toQueue;
        if (libraryTab != null) libraryTab.setTextColor(toQueue ? MUTED : Color.WHITE);
        if (queueTab != null) queueTab.setText("Up next (" + queue.size() + ")");
        if (queueTab != null) queueTab.setTextColor(toQueue ? Color.WHITE : MUTED);
        if (search != null) render(search.getText().toString());
    }
    private void playNext() {
        if (queue.isEmpty()) return;
        SongCatalog.Song song = queue.remove(0);
        updateReserveBadge();
        if (screen == SCREEN_SEARCH) render(search.getText().toString());
        selectSong(song);
    }

    private void playReservedAt(int index) {
        if (index < 0 || index >= queue.size()) return;
        SongCatalog.Song song = queue.remove(index);
        updateReserveBadge();
        if (screen == SCREEN_SEARCH) render(search.getText().toString());
        selectSong(song);
    }

    private void replayCurrent() { if (selected != null) selectSong(selected); }

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
        if (state == PlaybackEngine.State.PLAYING) {
            entryDigits.setLength(0);
            if (numberDisplay != null) numberDisplay.setText("");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        if (play == null || pause == null || stop == null) return;
        play.setEnabled(selected != null && state != PlaybackEngine.State.LOADING && state != PlaybackEngine.State.PLAYING);
        pause.setEnabled(state == PlaybackEngine.State.PLAYING);
        stop.setEnabled(state == PlaybackEngine.State.LOADING || state == PlaybackEngine.State.PLAYING
                || state == PlaybackEngine.State.PAUSED || state == PlaybackEngine.State.COMPLETED);
    }
    private void scheduleRender(String query) {
        if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
        final int token = ++searchGeneration;
        pendingSearch = () -> {
            if (!destroyed && token == searchGeneration) render(query);
        };
        ui.postDelayed(pendingSearch, 180);
    }

    private void updateTabs() {
        if (libraryTab != null) libraryTab.setTextColor(showQueue ? MUTED : Color.WHITE);
        if (queueTab != null) {
            queueTab.setText("Up next (" + queue.size() + ")");
            queueTab.setTextColor(showQueue ? Color.WHITE : MUTED);
        }
        if (queueLabel != null) queueLabel.setText(showQueue && queue.isEmpty()
                ? "Reserve songs with the keypad or Songbook." : "");
    }
    private void render(String query) {
        if (list == null || results == null) return;
        list.removeAllViews(); String needle = query.trim().toLowerCase(Locale.ROOT); int count = 0;
        List<SongCatalog.Song> source = showQueue ? queue : songs;
        for (int sourceIndex = 0; sourceIndex < source.size(); sourceIndex++) {
            SongCatalog.Song song = source.get(sourceIndex);
            final int rowIndex = sourceIndex;
            if (!showQueue && !needle.isEmpty() && !song.title.toLowerCase(Locale.ROOT).contains(needle)
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
            Button reserve = button(showQueue ? "Play now" : "RSV", v -> {
                if (showQueue) playReservedAt(rowIndex);
                else { queue.add(song); updateReserveBadge(); updateTabs(); playerStatus.setText("Reserved #" + song.songNumber() + "."); }
            });
            reserve.setBackground(background(KEY_PURPLE, 8));
            card.addView(reserve, new LinearLayout.LayoutParams(-1, dp(40)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, 0, 0, dp(8)); list.addView(card, params);
        }
        if (showQueue) results.setText(queue.size() + " reserved");
        else results.setText(count > 100 ? "First 100 of " + count + " matches. Search to narrow the list." : count + " matching songs");
        if (showQueue && queue.isEmpty()) list.addView(text("No reservations yet. Type a number on the keypad.", 14, MUTED));
        else if (songs.isEmpty()) list.addView(text("Import your catalog to begin.", 14, MUTED));
        else if (count == 0) list.addView(text("No matching songs.", 14, MUTED));
        updateTabs();
    }
    @Override protected void onResume() { super.onResume(); foreground = true; }
    @Override protected void onPause() {
        foreground = false;
        if (preparation != null) stopPlayback();
        engine.pause(); super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; catalogRequest++; searchGeneration++; if (pendingSearch != null) ui.removeCallbacks(pendingSearch); cancelPreparation(); engine.close(); io.shutdownNow(); super.onDestroy();
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(dp(4), dp(4), dp(4), dp(4)); return view;
    }
    private Button button(String value, View.OnClickListener click) {
        Button b = new Button(this); b.setText(value); b.setTextSize(11); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setOnClickListener(click); return b;
    }
    // Second transport row is built inline in onCreate.
    private static String time(long micros) {
        long seconds = Math.max(0, micros / 1000000L);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    private static String reason(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
