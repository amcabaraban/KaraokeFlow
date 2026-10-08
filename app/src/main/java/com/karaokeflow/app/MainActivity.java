package com.karaokeflow.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.provider.DocumentsContract;
import android.view.TextureView;
import android.content.Intent;
import android.content.res.Configuration;
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
    private static final int CSV = 1, FOLDER = 2, SF2 = 3, ROOT_FOLDER = 4, MP3_FOLDER = 5, VIDEO_FOLDER = 6, BACKGROUND_FOLDER = 7;
    private static final int MUTED = 0xffa5aabe;
    private static final int SCREEN_HOME = 0, SCREEN_STAGE = 1, SCREEN_SEARCH = 2;
    private static final int NAVY_BG = 0xff060913;
    private static final int STAGE_TOP = 0xff0e4aa8, STAGE_BOTTOM = 0xff061a3a;
    private static final int CHIP_RED = 0xff522431;
    private static final int CHIP_GREEN = 0xff104432;
    private static final int KEY_BLUE = 0xff123758;
    private static final int KEY_DARK = 0xff091b2b;
    private static final int KEY_PURPLE = 0xff123348;
    private static final int DIGIT_YELLOW = 0xffffc21a;
    private static final int SINGER_YELLOW = 0xffffd21f;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService searchWorker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private int searchGeneration;
    private List<SongCatalog.Song> songs = new ArrayList<>();
    private List<SongCatalog.Song> midiSongs = new ArrayList<>(), mp3Songs = new ArrayList<>(), videoSongs = new ArrayList<>();
    private int mediaRequest, setupRequest;
    private boolean mediaActive;
    private MediaPlayback media, backdropVideo;
    private TextureView songVideoView, backgroundVideoView;
    private final List<SongCatalog.Song> queue = new ArrayList<>();
    private boolean showQueue;
    private int screen = SCREEN_HOME;
    private final StringBuilder entryDigits = new StringBuilder();
    private LinearLayout homeRoot, searchRoot;
    private FrameLayout stageRoot, shell;
    private LinearLayout landscapePanel, stageBody;
    private int panelWidth;
    private Button keypadToggle;
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
    private boolean landscapeKeypadVisible = true;
    private long lastPositionMicros;
    private String playerMessage = "Ready when you are";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getPreferences(MODE_PRIVATE);
        files = new SongFiles(this);
        engine = new PlaybackEngine(this, new PlaybackEngine.Listener() {
            public void onStateChanged(PlaybackEngine.State state, String message) {
                if (!mediaActive) MainActivity.this.onStateChanged(state, message);
            }
            public void onPosition(long micros) { if (!mediaActive) MainActivity.this.onPosition(micros); }
            public void onError(String message) { if (!mediaActive) MainActivity.this.onError(message); }
        });
        media = new MediaPlayback(this, this, false);
        backdropVideo = new MediaPlayback(this, new PlaybackEngine.Listener() {
            public void onStateChanged(PlaybackEngine.State state, String message) { }
            public void onPosition(long micros) { }
            public void onError(String message) {
                if (!destroyed) Toast.makeText(MainActivity.this, "Background: " + message, Toast.LENGTH_LONG).show();
            }
        }, true);
        buildUi();
        String catalog = preferences.getString("catalog", null);
        if (catalog != null) importCatalog(Uri.parse(catalog));
        reloadMedia(false);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        String query = search == null ? "" : search.getText().toString();
        super.onConfigurationChanged(newConfig);
        buildUi();
        if (search != null) {
            search.setText(query);
            search.setSelection(search.length());
            render(query);
        }
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
        root.setBackground(new StageBackdrop());
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
        TextView sub = text("Your stage. Your songs.", 15, 0xff24d3ee);
        sub.setTypeface(null, 1); sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(6));
        root.addView(sub);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(2), dp(16), dp(16));
        scroll.addView(body, new LinearLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(menuTile("Enter Karaoke", 0xff164962, 0xee071624, v -> showScreen(SCREEN_STAGE)));
        LinearLayout songbook = menuTile("Songbook", 0xff123758, 0xee071624,
                v -> { showQueue = false; showScreen(SCREEN_SEARCH); });
        LinearLayout.LayoutParams songbookParams = new LinearLayout.LayoutParams(-1, -2);
        songbookParams.setMargins(0, dp(8), 0, 0);
        body.addView(songbook, songbookParams);
        setupStatus = text("", 11, MUTED);
        setupStatus.setMaxLines(3);
        setupStatus.setGravity(Gravity.CENTER);
        setupStatus.setPadding(0, dp(10), 0, 0);
        body.addView(setupStatus);
        body.addView(deckButton("Settings", KEY_BLUE, v -> showSettings()),
                new LinearLayout.LayoutParams(-1, dp(52)));
        return root;
    }
    private LinearLayout menuTile(String label, int start, int end, View.OnClickListener click) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(12), dp(20), dp(12), dp(20));
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        shape.setCornerRadius(dp(18));
        shape.setStroke(dp(1), 0xff385367);
        tile.setBackground(shape);
        tile.setClickable(true); tile.setFocusable(true);
        tile.setOnClickListener(click);
        TextView icon = text("\u266A", 44, 0xff24d3ee);
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

    private FrameLayout buildStage() {
        boolean landscape = isLandscape();
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new StageBackdrop());
        backgroundVideoView = new TextureView(this);
        root.addView(backgroundVideoView, new FrameLayout.LayoutParams(-1, -1));
        backdropVideo.attach(backgroundVideoView);
        songVideoView = new TextureView(this);
        root.addView(songVideoView, new FrameLayout.LayoutParams(-1, -1));
        media.attach(songVideoView);
        updateVideoVisibility();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout body = new LinearLayout(this);
        stageBody = body;
        body.setOrientation(LinearLayout.VERTICAL);
        int horizontal = landscape ? 24 : 16;
        body.setPadding(dp(horizontal), dp(landscape ? 12 : 16),
                dp(horizontal), dp(landscape ? 10 : 12));
        root.addView(body, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        stageTitle = text("SELECT SONGS", landscape ? 30 : 24, Color.WHITE);
        stageTitle.setTypeface(null, 1);
        stageTitle.setGravity(Gravity.CENTER);
        stageTitle.setShadowLayer(dp(3), 0, 2, 0xff000000);
        info.addView(stageTitle, titleParams);
        stageTitle.setGravity(Gravity.CENTER);

        numberDisplay = text("000000", landscape ? 58 : 40, DIGIT_YELLOW);
        numberDisplay.setTypeface(null, 1);
        numberDisplay.setGravity(Gravity.CENTER);
        numberDisplay.setShadowLayer(dp(4), 0, 2, 0xff000000);
        info.addView(numberDisplay);

        stageSinger = text("Singer: -", landscape ? 16 : 14, SINGER_YELLOW);
        stageSinger.setTypeface(null, 1);
        stageSinger.setGravity(Gravity.CENTER);
        info.addView(stageSinger);

        selectedTitle = text("Choose a song to begin", landscape ? 15 : 13, 0xffdbe6ff);
        selectedTitle.setGravity(Gravity.CENTER);
        selectedTitle.setMaxLines(1);
        info.addView(selectedTitle);

        currentLyric = text("Your lyrics will appear here", landscape ? 34 : 26, Color.WHITE);
        currentLyric.setGravity(Gravity.CENTER);
        currentLyric.setMinLines(1);
        currentLyric.setMaxLines(3);
        currentLyric.setTypeface(null, 1);
        currentLyric.setShadowLayer(dp(3), 0, 2, 0xff000000);
        info.addView(currentLyric);

        nextLyric = text("", landscape ? 24 : 20, 0xffbcd0f5);
        nextLyric.setGravity(Gravity.CENTER);
        nextLyric.setMaxLines(1);
        info.addView(nextLyric);

        progress = new SeekBar(this);
        progress.setMax(1000);
        progress.setProgress(0);
        progress.setEnabled(false);
        info.addView(progress, new LinearLayout.LayoutParams(-1, dp(22)));

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        clock = text("0:00 / 0:00", 11, 0xffdbe6ff);
        clock.setGravity(Gravity.START);
        timeRow.addView(clock, new LinearLayout.LayoutParams(0, -2, 1));
        playerStatus = text(playerMessage, 11, 0xffdbe6ff);
        playerStatus.setGravity(Gravity.END);
        timeRow.addView(playerStatus, new LinearLayout.LayoutParams(0, -2, 2));
        info.addView(timeRow);

        body.addView(info, new LinearLayout.LayoutParams(-1, 0, 1));

        if (landscape) {
            int availableHeight = getResources().getConfiguration().screenHeightDp;
            panelWidth = dp(Math.min(320, getResources().getConfiguration().screenWidthDp / 2));

            landscapePanel = new LinearLayout(this);
            landscapePanel.setOrientation(LinearLayout.VERTICAL);
            landscapePanel.setPadding(dp(8), dp(8), dp(8), dp(8));
            GradientDrawable panelShape = new GradientDrawable();
            panelShape.setColor(0xee071224);
            panelShape.setCornerRadius(dp(18));
            landscapePanel.setBackground(panelShape);
            landscapePanel.setElevation(dp(10));
            TextView panelTitle = text("Controls", 13, 0xffdbe6ff);
            panelTitle.setTypeface(null, 1);
            panelTitle.setGravity(Gravity.CENTER);
            landscapePanel.addView(panelTitle, new LinearLayout.LayoutParams(-1, dp(28)));
            landscapePanel.addView(transportRow());
            landscapePanel.addView(keypadGrid());
            landscapePanel.addView(navigationRow());
            landscapePanel.setVisibility(landscapeKeypadVisible ? View.VISIBLE : View.GONE);
            ScrollView panelScroll = new ScrollView(this);
            panelScroll.setFillViewport(false);
            panelScroll.addView(landscapePanel);
            panelScroll.setVisibility(landscapeKeypadVisible ? View.VISIBLE : View.GONE);
            panelScroll.setTag("floatingControls");
            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(panelWidth, dp(Math.max(160, availableHeight - 100)));
            panelParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            panelParams.setMargins(0, dp(10), dp(12), dp(10));
            root.addView(panelScroll, panelParams);
            updateStageSpace();

            keypadToggle = deckButton(landscapeKeypadVisible ? "×" : "⌨", KEY_BLUE,
                    v -> toggleLandscapeKeypad());
            keypadToggle.setTextSize(18);
            keypadToggle.setContentDescription(landscapeKeypadVisible
                    ? "Hide floating keypad" : "Show floating keypad");
            FrameLayout.LayoutParams toggleParams = new FrameLayout.LayoutParams(dp(52), dp(48));
            toggleParams.gravity = Gravity.END | Gravity.TOP;
            toggleParams.setMargins(0, dp(10), dp(12), 0);
            root.addView(keypadToggle, toggleParams);
        } else {
            landscapePanel = null;
            keypadToggle = null;
            body.addView(transportRow());
            body.addView(keypadGrid());
            body.addView(navigationRow());
        }
        return root;
    }

    private LinearLayout navigationRow() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(6), 0, 0);
        bottom.addView(deckButton("Home", KEY_BLUE, v -> showScreen(SCREEN_HOME)),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        reserveBadge = text("Queue · " + queue.size(), 12, Color.WHITE);
        reserveBadge.setGravity(Gravity.CENTER);
        reserveBadge.setBackground(background(KEY_PURPLE, 8));
        reserveBadge.setOnClickListener(v -> { showQueue = true; showScreen(SCREEN_SEARCH); });
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        badgeParams.setMargins(dp(6), 0, dp(6), 0);
        bottom.addView(reserveBadge, badgeParams);
        bottom.addView(deckButton("Songbook", KEY_BLUE, v -> { showQueue = false; showScreen(SCREEN_SEARCH); }),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        bottom.addView(deckButton("Settings", KEY_BLUE, v -> showSettings()),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        return bottom;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private GradientDrawable stageBackground() {
        int[] colors = isLandscape()
                ? new int[]{0xff274d73, 0xff081323}
                : new int[]{STAGE_TOP, STAGE_BOTTOM};
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
    }

    private void toggleLandscapeKeypad() {
        if (landscapePanel == null || keypadToggle == null) return;
        landscapeKeypadVisible = !landscapeKeypadVisible;
        landscapePanel.setVisibility(landscapeKeypadVisible ? View.VISIBLE : View.GONE);
        View floating = stageRoot.findViewWithTag("floatingControls");
        if (floating != null) floating.setVisibility(landscapeKeypadVisible ? View.VISIBLE : View.GONE);
        updateStageSpace();
        keypadToggle.setText(landscapeKeypadVisible ? "×" : "⌨");
        keypadToggle.setContentDescription(landscapeKeypadVisible
                ? "Hide floating keypad" : "Show floating keypad");
    }

    private void updateStageSpace() {
        if (stageBody == null || !isLandscape()) return;
        stageBody.setPadding(dp(24), dp(12),
                landscapeKeypadVisible ? panelWidth + dp(24) : dp(24), dp(10));
    }

    private void buildUi() {
        shell = new FrameLayout(this);
        shell.setBackgroundColor(NAVY_BG);
        setContentView(shell);
        homeRoot = buildHome();
        stageRoot = buildStage();
        searchRoot = buildSearch();
        shell.addView(homeRoot, new FrameLayout.LayoutParams(-1, -1));
        shell.addView(stageRoot, new FrameLayout.LayoutParams(-1, -1));
        shell.addView(searchRoot, new FrameLayout.LayoutParams(-1, -1));
        showScreen(screen);
        updateSetup();
        updateControls();
        refreshEntry();
        restoreStageState();
    }

    private void restoreStageState() {
        if (stageTitle == null) return;
        if (selected != null) {
            String name = selected.title.isEmpty() ? "Untitled" : selected.title;
            stageTitle.setText(name);
            stageSinger.setText("Singer: " + (selected.artist.isEmpty() ? "Unknown artist" : selected.artist));
            selectedTitle.setText("#" + selected.songNumber() + "  " + name);
        }
        playerStatus.setText(playerMessage);
        updateStageMeta();
        if (prepared != null || mediaActive) {
            if (timeline == null && prepared != null) timeline = new LyricTimeline(prepared);
            showLyrics(lastPositionMicros);
        } else {
            timeline = null;
            showLyrics(0);
        }
        if (state == PlaybackEngine.State.PLAYING && numberDisplay != null) {
            numberDisplay.setText("");
        }
    }

    private void updateStageMeta() {
        if (stageSinger == null || selectedTitle == null) return;
        boolean singing = state == PlaybackEngine.State.PLAYING || state == PlaybackEngine.State.PAUSED;
        boolean loading = state == PlaybackEngine.State.LOADING;
        boolean entering = entryDigits.length() > 0;
        stageTitle.setText("SELECT SONGS");
        stageTitle.setVisibility(singing || loading ? View.GONE : View.VISIBLE);
        numberDisplay.setVisibility(entering || (!singing && !loading) ? View.VISIBLE : View.GONE);
        stageSinger.setVisibility(View.GONE);
        selectedTitle.setVisibility(entering ? View.VISIBLE : View.GONE);
        if (entering) {
            List<SongCatalog.Song> matches = entryMatches();
            StringBuilder preview = new StringBuilder();
            for (SongCatalog.Song song : matches) {
                if (preview.length() > 0) preview.append("\n");
                preview.append(song.songNumber()).append("  ").append(song.title);
            }
            selectedTitle.setMaxLines(3);
            selectedTitle.setText(preview.length() == 0 ? "No matching song" : preview.toString());
        }
        currentLyric.setVisibility(singing && !mediaActive ? View.VISIBLE : View.GONE);
        nextLyric.setVisibility(singing && !mediaActive ? View.VISIBLE : View.GONE);
        progress.setVisibility(singing || loading ? View.VISIBLE : View.GONE);
        clock.setVisibility(singing ? View.VISIBLE : View.GONE);
        playerStatus.setVisibility(loading || state == PlaybackEngine.State.ERROR ? View.VISIBLE : View.GONE);
        updateVideoVisibility();
    }

    private Button deckButton(String label, int color, View.OnClickListener click) {
        Button b = button(label, click);
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{color, 0xff061422});
        face.setCornerRadius(dp(12));
        face.setStroke(dp(1), 0xff385367);
        b.setBackground(face);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        return b;
    }

    private LinearLayout transportRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(2));
        Button replayButton = deckButton("↻", KEY_BLUE, v -> replayCurrent());
        pause = deckButton("\u275A\u275A", KEY_BLUE, v -> pauseCurrent());
        play = deckButton("\u25B6", KEY_BLUE, v -> playEntryOrSelected());
        next = deckButton("▶|", KEY_BLUE, v -> playNext());
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
                if ("CAN".equals(key)) b.setText("⌫");
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(isLandscape() ? 48 : 50), 1);
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
            
        } else if ("RES".equals(key)) {
            reserveEntry();
        } else if (key.matches("[0-9]")) {
            if (entryDigits.length() >= 6) entryDigits.delete(0, 1);
            entryDigits.append(key);

        }
        refreshEntry();
    }

    private void refreshEntry() {
        if (numberDisplay == null) return;
        String digits = entryDigits.toString();
        numberDisplay.setText(digits.isEmpty() ? "000000"
                : String.format(Locale.ROOT, "%6s", digits).replace(' ', '0'));
        updateStageMeta();
        updateControls();
    }

    private List<SongCatalog.Song> entryMatches() {
        List<SongCatalog.Song> matches = new ArrayList<>();
        if (entryDigits.length() == 0) return matches;
        String digits = entryDigits.toString();
        String exact = String.format(Locale.ROOT, "%6s", digits).replace(' ', '0');
        for (SongCatalog.Song song : songs) if (song.songNumber().equals(exact)) {
            matches.add(song); break;
        }
        for (SongCatalog.Song song : songs) {
            if (matches.size() >= 3) break;
            if (song.songNumber().startsWith(digits) && !matches.contains(song)) matches.add(song);
        }
        return matches;
    }

    private void playEntryOrSelected() {
        if (entryDigits.length() > 0) {
            int id = Integer.parseInt(entryDigits.toString());
            for (SongCatalog.Song song : songs) if (song.id == id) {
                entryDigits.setLength(0); refreshEntry(); selectSong(song); return;
            }
            Toast.makeText(this, "No song with this number", Toast.LENGTH_SHORT).show();
            return;
        }
        playSelected();
    }

    private void updateReserveBadge() {
        if (reserveBadge != null) reserveBadge.setText("Queue · " + queue.size());
        if (queueTab != null) queueTab.setText("Up next (" + queue.size() + ")");
    }

    private void reserveEntry() {
        if (entryDigits.length() == 0) return;
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
        if (backdropVideo != null) {
            if (next == SCREEN_STAGE && state == PlaybackEngine.State.PLAYING && !mediaActive) startBackdrop();
            else if (next != SCREEN_STAGE) backdropVideo.pause();
        }
    }

    private void showSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(16));
        content.setBackgroundColor(NAVY_BG);
        content.addView(text("Choose KaraokeFlow once to find its CSV, SF2 and SongHub_Extracted folder. "
                + "Override any source below. MP3 and concert filenames need a unique six-digit number "
                + "(050001 Song Title.mp3). Background filenames need no number.", 14, MUTED));
        addSetting(content, "KaraokeFlow folder", "root", ROOT_FOLDER);
        addSetting(content, "Catalog CSV", "catalog", CSV);
        addSetting(content, "MIDI folder", "midi", FOLDER);
        addSetting(content, "SoundFont SF2", "sf2", SF2);
        addSetting(content, "MP3 songs folder", "mp3", MP3_FOLDER);
        addSetting(content, "MP4 concerts folder", "video", VIDEO_FOLDER);
        addSetting(content, "MP4 backgrounds folder", "backgroundFolder", BACKGROUND_FOLDER);
        content.addView(deckButton("Choose background video", KEY_BLUE, v -> {
            String folder = preferences.getString("backgroundFolder", null);
            if (folder == null) openPicker(BACKGROUND_FOLDER);
            else chooseBackground(Uri.parse(folder));
        }), new LinearLayout.LayoutParams(-1, dp(48)));
        content.addView(deckButton("Use illustrated background", KEY_DARK, v -> {
            preferences.edit().remove("background").apply();
            backdropVideo.stop(); updateVideoVisibility();
        }), new LinearLayout.LayoutParams(-1, dp(48)));
        content.addView(deckButton("Refresh library and files", KEY_BLUE, v -> refreshSources()),
                new LinearLayout.LayoutParams(-1, dp(48)));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Settings").setView(scroll).setPositiveButton("Done", null).show();
    }

    private void addSetting(LinearLayout content, String label, String key, int code) {
        String name = preferences.getString(key + "Name", preferences.contains(key) ? "Selected" : "Not selected");
        content.addView(deckButton(label + " · " + name, KEY_BLUE, v -> openPicker(code)),
                new LinearLayout.LayoutParams(-1, dp(52)));
    }

    private void notice(String message) {
        if (destroyed) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (setupStatus != null) setupStatus.setText(message);
    }

    private void discoverRoot(Uri root) {
        final int token = ++setupRequest;
        notice("Finding catalog, SoundFont and MIDI folder…");
        io.execute(() -> {
            try {
                List<LibrarySetup.Entry> entries = LibrarySetup.children(this, root,
                        DocumentsContract.getTreeDocumentId(root));
                List<LibrarySetup.Entry> catalogs = new ArrayList<>(), fonts = new ArrayList<>();
                LibrarySetup.Entry midi = null;
                for (LibrarySetup.Entry entry : entries) {
                    String name = entry.name.toLowerCase(Locale.ROOT);
                    if (!entry.directory && name.endsWith(".csv")) catalogs.add(entry);
                    if (!entry.directory && name.endsWith(".sf2")) fonts.add(entry);
                    if (entry.directory && name.equals("songhub_extracted")) midi = entry;
                }
                final LibrarySetup.Entry midiFolder = midi;
                ui.post(() -> {
                    if (destroyed || token != setupRequest) return;
                    stopPlayback(); prepared = null; selectedFont = null; engine.invalidateSoundFont();
                    if (midiFolder != null) {
                        files = new SongFiles(this);
                        preferences.edit().putString("midi", LibrarySetup.subtree(root, midiFolder).toString())
                                .putString("midiName", midiFolder.name).apply();
                    }
                    chooseDiscovered("Catalog", catalogs, entry -> {
                        preferences.edit().putString("catalogName", entry.name).apply();
                        importCatalog(entry.uri);
                    });
                    chooseDiscovered("SoundFont", fonts, entry -> {
                        preferences.edit().putString("sf2", entry.uri.toString()).putString("sf2Name", entry.name).apply();
                        updateSetup();
                    });
                    updateSetup();
                    notice("Found " + catalogs.size() + " CSV, " + fonts.size() + " SF2; "
                            + (midiFolder == null ? "MIDI folder missing. Choose it in Settings." : "MIDI folder ready."));
                });
            } catch (Exception error) { ui.post(() -> { if (token == setupRequest) notice(reason(error)); }); }
        });
    }

    private interface EntryChoice { void choose(LibrarySetup.Entry entry); }
    private void chooseDiscovered(String title, List<LibrarySetup.Entry> entries, EntryChoice choice) {
        if (entries.size() == 1) { choice.choose(entries.get(0)); return; }
        if (entries.isEmpty()) return;
        String[] names = new String[entries.size()];
        for (int i = 0; i < names.length; i++) names[i] = entries.get(i).name;
        new AlertDialog.Builder(this).setTitle("Choose " + title).setItems(names,
                (dialog, which) -> choice.choose(entries.get(which))).setNegativeButton("Cancel", null).show();
    }

    private void reloadMedia(boolean refresh) {
        final int token = ++mediaRequest;
        final String mp3 = preferences.getString("mp3", null), video = preferences.getString("video", null);
        io.execute(() -> {
            List<SongCatalog.Song> audioSongs = new ArrayList<>(), concerts = new ArrayList<>();
            String problem = "";
            try { if (mp3 != null) audioSongs = LibrarySetup.media(this, Uri.parse(mp3), "mp3", refresh); }
            catch (Exception error) { problem = "MP3: " + reason(error); }
            try { if (video != null) concerts = LibrarySetup.media(this, Uri.parse(video), "mp4", refresh); }
            catch (Exception error) { problem += " MP4: " + reason(error); }
            final List<SongCatalog.Song> loadedAudio = audioSongs, loadedVideo = concerts;
            final String error = problem;
            ui.post(() -> {
                if (destroyed || token != mediaRequest) return;
                mp3Songs = loadedAudio; videoSongs = loadedVideo;
                mergeLibrary(); updateSetup(); refreshEntry();
                if (search != null) render(search.getText().toString());
                if (!error.isEmpty()) notice(error);
                else if (refresh) notice("Media folders refreshed: " + mp3Songs.size() + " MP3, " + videoSongs.size() + " concerts.");
            });
        });
    }

    private void mergeLibrary() {
        List<SongCatalog.Song> merged = new ArrayList<>(midiSongs);
        Set<Integer> ids = new HashSet<>();
        for (SongCatalog.Song song : midiSongs) ids.add(song.id);
        int conflicts = 0;
        for (List<SongCatalog.Song> source : Arrays.asList(mp3Songs, videoSongs)) {
            for (SongCatalog.Song song : source) {
                if (ids.add(song.id)) merged.add(song);
                else conflicts++;
            }
        }
        songs = merged;
        if (conflicts > 0) notice(conflicts + " media number conflicts. Rename MP3/concert files with unused numbers.");
    }

    private void chooseBackground(Uri folder) {
        final int token = ++setupRequest;
        io.execute(() -> {
            try {
                List<LibrarySetup.Entry> entries = LibrarySetup.children(this, folder,
                        DocumentsContract.isDocumentUri(this, folder)
                                ? DocumentsContract.getDocumentId(folder) : DocumentsContract.getTreeDocumentId(folder));
                List<LibrarySetup.Entry> videos = new ArrayList<>();
                for (LibrarySetup.Entry entry : entries)
                    if (!entry.directory && entry.name.toLowerCase(Locale.ROOT).endsWith(".mp4")) videos.add(entry);
                ui.post(() -> {
                    if (destroyed || token != setupRequest) return;
                    if (videos.isEmpty()) { notice("No MP4 backgrounds in this folder."); return; }
                    chooseDiscovered("lyric background", videos, entry -> {
                        preferences.edit().putString("background", entry.uri.toString()).apply();
                        backdropVideo.stop(); backgroundSession = null;
                        if (state == PlaybackEngine.State.PLAYING && !mediaActive) startBackdrop();
                        updateVideoVisibility();
                    });
                });
            } catch (Exception error) { ui.post(() -> { if (token == setupRequest) notice(reason(error)); }); }
        });
    }

    private String backgroundSession;
    private void startBackdrop() {
        String uri = preferences.getString("background", null);
        if (uri == null || mediaActive || !foreground || screen != SCREEN_STAGE) return;
        if (uri.equals(backgroundSession)) backdropVideo.resume();
        else { backgroundSession = uri; backdropVideo.start(Uri.parse(uri)); }
        updateVideoVisibility();
    }

    private void updateVideoVisibility() {
        if (songVideoView == null || backgroundVideoView == null) return;
        boolean video = false;
        if (mediaActive && selected != null)
            for (SongCatalog.Song song : videoSongs) if (song.file.equals(selected.file)) { video = true; break; }
        boolean active = state == PlaybackEngine.State.PLAYING || state == PlaybackEngine.State.PAUSED
                || state == PlaybackEngine.State.LOADING;
        songVideoView.setVisibility(video && active ? View.VISIBLE : View.INVISIBLE);
        backgroundVideoView.setVisibility(!mediaActive && active && preferences.contains("background")
                ? View.VISIBLE : View.INVISIBLE);
    }

    private void pauseCurrent() {
        if (mediaActive) media.pause(); else engine.pause();
        backdropVideo.pause();
    }

    private void openPicker(int code) {
        Intent intent;
        if (code >= ROOT_FOLDER || code == FOLDER) intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
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
        if (code == ROOT_FOLDER) {
            preferences.edit().putString("root", uri.toString()).putString("rootName", displayName(uri)).apply();
            discoverRoot(uri); return;
        }
        if (code == MP3_FOLDER || code == VIDEO_FOLDER || code == BACKGROUND_FOLDER) {
            String key = code == MP3_FOLDER ? "mp3" : code == VIDEO_FOLDER ? "video" : "backgroundFolder";
            preferences.edit().putString(key, uri.toString()).putString(key + "Name", displayName(uri)).apply();
            if (code == BACKGROUND_FOLDER) chooseBackground(uri);
            else reloadMedia(true);
            return;
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
                    midiSongs = loaded; mergeLibrary(); preferences.edit().putString("catalog", uri.toString()).putString("catalogName", displayName(uri)).apply();
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
        reloadMedia(true);
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
        setupStatus.setText("MIDI: " + folder + "\nSF2: " + font + "  •  " + songs.size() + " songs / concerts");
    }

    private void selectSong(SongCatalog.Song song) {
        showScreen(SCREEN_STAGE);
        stopPlayback(); selected = song; prepared = null; timeline = null;
        String name = song.title.isEmpty() ? "Untitled" : song.title;
        selectedTitle.setText("#" + song.songNumber() + "  " + name);
        stageTitle.setText(name);
        stageSinger.setText("Singer: " + (song.artist.isEmpty() ? "Unknown artist" : song.artist));
        updateStageMeta();
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
        if (state == PlaybackEngine.State.PAUSED) { if (mediaActive) media.resume(); else engine.resume(); return; }
        if (selected.file.startsWith("content://")) {
            cancelPreparation(); engine.stop(); mediaActive = true;
            prepared = null; timeline = null;
            backdropVideo.stop(); updateVideoVisibility();
            media.start(Uri.parse(selected.file)); return;
        }
        mediaActive = false; media.stop(); updateVideoVisibility();
        String treeValue = preferences.getString("midi", null), fontValue = preferences.getString("sf2", null);
        if (treeValue == null || fontValue == null) {
            state = PlaybackEngine.State.ERROR;
            playerStatus.setText("Choose a MIDI folder and a .sf2 SoundFont in Settings."); updateControls(); return;
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
        cancelPreparation(); engine.stop(); media.stop(); backdropVideo.stop(); backgroundSession = null; mediaActive = false;
        state = PlaybackEngine.State.STOPPED;
        lastPositionMicros = 0;
        showLyrics(0);
        updateStageMeta();
        playerMessage = "Stopped. Press Play to start from the beginning.";
        playerStatus.setText(playerMessage); updateControls();
    }
    @Override public void onStateChanged(PlaybackEngine.State newState, String message) {
        if (destroyed || (newState == PlaybackEngine.State.STOPPED && preparation != null)) return;
        state = newState;
        playerMessage = message;
        playerStatus.setText(message);
        if (state == PlaybackEngine.State.STOPPED) {
            lastPositionMicros = 0;
            showLyrics(0);
        }
        if (state == PlaybackEngine.State.PLAYING) {
            entryDigits.setLength(0);
            if (numberDisplay != null) numberDisplay.setText("");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (state == PlaybackEngine.State.PLAYING && !mediaActive) startBackdrop();
        else if (state == PlaybackEngine.State.PAUSED) backdropVideo.pause();
        else if (state != PlaybackEngine.State.LOADING) { backdropVideo.stop(); backgroundSession = null; }
        updateStageMeta();
        updateControls();
    }
    @Override public void onPosition(long micros) {
        if (!destroyed) {
            lastPositionMicros = micros;
            showLyrics(micros);
        }
    }
    @Override public void onError(String message) {
        if (destroyed) return;
        state = PlaybackEngine.State.ERROR;
        backdropVideo.stop(); backgroundSession = null;
        playerMessage = "Playback failed: " + message;
        playerStatus.setText(playerMessage);
        updateStageMeta();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateControls();
    }
    private void showLyrics(long micros) {
        if (clock == null) return;
        long duration = mediaActive ? media.durationMicros() : prepared == null ? 0 : prepared.durationMicros;
        clock.setText(time(micros) + " / " + time(duration));
        progress.setProgress(duration <= 0 ? 0 : (int) Math.min(1000, micros * 1000L / duration));
        if (timeline == null) { currentLyric.setText("Your lyrics will appear here"); nextLyric.setText(""); }
        else if (prepared.lyrics.isEmpty()) { currentLyric.setText("Instrumental / no lyrics in this MIDI"); nextLyric.setText(""); }
        else {
            LyricTimeline.Cue cue = timeline.at(micros);
            SpannableString line = new SpannableString(cue.line.isEmpty() ? "Music intro…" : cue.line);
            int sung = Math.min(cue.sungCharacters, line.length());
            if (sung > 0) line.setSpan(new ForegroundColorSpan(0xff24d3ee), 0, sung, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            currentLyric.setText(line); nextLyric.setText(cue.nextLine);
        }
    }
    private void updateControls() {
        updateStageMeta();
        if (play == null || pause == null || stop == null) return;
        play.setEnabled(entryDigits.length() > 0 || (selected != null && state != PlaybackEngine.State.LOADING && state != PlaybackEngine.State.PLAYING));
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
        final int token = ++searchGeneration;
        if (showQueue) { renderRows(new ArrayList<>(queue), queue.size(), true); return; }
        final List<SongCatalog.Song> catalog = songs;
        final String needle = query.trim().toLowerCase(Locale.ROOT);
        searchWorker.execute(() -> {
            List<SongCatalog.Song> matches = new ArrayList<>();
            int total = 0;
            for (SongCatalog.Song song : catalog) {
                if (Thread.currentThread().isInterrupted()) return;
                if (!needle.isEmpty() && !song.title.toLowerCase(Locale.ROOT).contains(needle)
                        && !song.artist.toLowerCase(Locale.ROOT).contains(needle)
                        && !song.songNumber().contains(needle)) continue;
                total++;
                if (matches.size() < 100) matches.add(song);
            }
            final int matchCount = total;
            ui.post(() -> {
                if (!destroyed && token == searchGeneration && !showQueue)
                    renderRows(matches, matchCount, false);
            });
        });
    }

    private void renderRows(List<SongCatalog.Song> source, int total, boolean queueRows) {
        list.removeAllViews();
        int count = 0;
        for (int sourceIndex = 0; sourceIndex < source.size(); sourceIndex++) {
            SongCatalog.Song song = source.get(sourceIndex);
            final int rowIndex = sourceIndex;
            if (++count > 100) break;
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(7), dp(14), dp(7)); card.setBackground(background(0xff141824, 14));
            TextView title = text(song.title.isEmpty() ? "Untitled" : song.title, 17, Color.WHITE);
            title.setTypeface(null, 1); card.addView(title);
            card.addView(text("#" + song.songNumber() + "  •  " + (song.artist.isEmpty() ? "Unknown artist" : song.artist), 13, MUTED));
            card.setClickable(true); card.setFocusable(true);
            card.setContentDescription("Play " + song.title + ", song " + song.songNumber());
            card.setOnClickListener(v -> { if (queueRows) playReservedAt(rowIndex); else selectSong(song); });
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
        else results.setText(total > 100 ? "First 100 of " + total + " matches. Search to narrow the list." : total + " matching songs");
        if (showQueue && queue.isEmpty()) list.addView(text("No reservations yet. Type a number on the keypad.", 14, MUTED));
        else if (songs.isEmpty()) list.addView(text("Import your catalog to begin.", 14, MUTED));
        else if (count == 0) list.addView(text("No matching songs.", 14, MUTED));
        updateTabs();
    }
    @Override protected void onResume() { super.onResume(); foreground = true; }
    @Override protected void onPause() {
        foreground = false;
        if (preparation != null) stopPlayback();
        pauseCurrent(); backdropVideo.pause(); super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; catalogRequest++; searchGeneration++; if (pendingSearch != null) ui.removeCallbacks(pendingSearch); cancelPreparation(); engine.close(); media.close(); backdropVideo.close(); io.shutdownNow(); searchWorker.shutdownNow(); super.onDestroy();
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
