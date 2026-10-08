# KaraokeFlow playback beta

KaraokeFlow is an Android 8.0+ foreground karaoke player. The 0.4.2 beta plays user-supplied Standard MIDI files through a user-selected SF2 SoundFont and highlights synchronized lyrics.

## Try the beta

1. Install `app-debug.apk` from the **KaraokeFlow-Beta-APK** artifact on a successful GitHub Actions run.
2. Tap **MIDI folder** and grant access to your `SongHub_Extracted` folder (or another folder containing your own `.mid`, `.midi`, or `.kar` files).
3. Tap **SoundFont** and select a real `.sf2` file. This beta accepts files up to 256 MiB and caches a validated copy privately. The cache is keyed by the selected document URI and can be reused after an app restart. Use Refresh files after replacing the provider file in place. SF3 is unsupported. No font is bundled.
4. Open **Settings → Catalog CSV** and select `mkmd_catalog_probe.csv` or a compatible UTF-8 catalog.
5. Search by title, artist, or permanent six-digit song number, such as `012185`, and tap a song to play.
6. Use **Pause**, **Resume**, and **Stop**. Stop resets to the beginning; Play replays the song. The current lyric line highlights each timed fragment, with the next line below it.
7. Rotate to landscape for the floating control panel. Tap the keypad toggle to show or hide it; the selected song, queue, search, and lyric position survive rotation.

Folder, font, and catalog selections persist when the document provider supports persistent grants. The catalog reloads on app restart. If a provider revokes access, select the input again. A MIDI without usable lyric/text events still plays and displays an instrumental message. Choosing a song opens Stage so its loading and playback status is visible. Catalog and refresh notices also appear on the current screen. Use Refresh files after adding or replacing MIDI files or replacing a SoundFont in place. This clears cached file metadata and the selected copied font; the next playback rebuilds what it needs.

Playback pauses when the app leaves the foreground, loses audio focus, or headphones disconnect. Return to the app and press Resume. Reserve songs with the number keypad or a Songbook RSV button; Next plays the first reservation, while Play now selects that particular reserved entry. Reservations are kept for the current app session. Background playback, seeking, favorites, and key/tempo adjustment controls are not included. Audio is streamed rather than pre-rendering the entire song.

## Data architecture

The extracted six-digit MIDI sequence remains the permanent KaraokeFlow song ID. Import does not renumber records or infer IDs from catalog row order. The original six-column CSV layout uses ID at column 0, title at 2, artist at 3, and MIDI filename/path at 5. Explicit named columns also support reordered catalogs, for example:

```csv
song_number,title,artist,midi_file
012185,Example song,Example artist,012185.mid
```

The parser accepts BOMs, quoted commas, escaped quotes, and multiline fields. Duplicate or invalid IDs fail the import and preserve the previously loaded catalog. Catalog filenames resolve inside the selected Android Storage Access Framework tree, including nested relative paths and extraction prefixes. If the catalog path is unavailable, an exact six-digit MIDI basename is used only when unambiguous. No unrestricted storage permission is requested.

The app reads only the user's selected catalog, MIDI folder, and SoundFont. It includes no proprietary SongHub application code, database, songs, recordings, artwork, or SoundFont assets. It uses public MIDI and SF2 formats and Android's document APIs; it neither decrypts nor extracts proprietary files. Supply inputs you have permission to use. The old uploaded `KaraokeFlow_Beta_GitHub.zip` is a historical source snapshot; GitHub Actions builds the current repository files.

## Playback implementation

- `MidiSequence` parses SMF format 0/1, running status, channel messages, a merged tempo map, PPQN/SMPTE timing, lyric events (0x05), and text-event karaoke fallback (0x01). Unsupported format 2 and malformed/truncated files produce clear errors.
- `LyricTimeline` preserves syllable fragments, spaces, slash/backslash and CR/LF line markers. Dedicated lyric events take priority; common karaoke metadata is omitted.
- `PlaybackEngine` schedules MIDI channel events against a 44.1 kHz stereo PCM stream. Positions come from AudioTrack's audible playback head, so lyrics freeze with Pause and resume with buffered audio. One worker owns all synth/audio resources, and generation tokens discard stale work when switching songs or stopping. The stage hides secondary singer/catalog text during playback and rebuilds its portrait or landscape controls without interrupting the session.
- `NativeSynth` is a small JNI bridge to TinySoundFont. It handles MIDI programs/banks, channel 10 percussion, velocity, controllers including sustain, and pitch bend. The audio worker keeps an unchanged SoundFont loaded across song changes, Stop, and replay. Each fresh song resets voices and channel/controller state; choosing or refreshing a SoundFont invalidates that bank. Destroying the Activity closes the synth. SoundFonts load locally; synthesis is offline.
- `SongFiles` keeps directory listings and the numeric fallback index in memory for the current Activity. Refresh files clears that metadata and the selected SoundFont cache. A valid catalog path avoids a full folder traversal; a missing path may require the first traversal of the selected folder. File lookup, index building, and SF2 copying run off the UI thread.
- Songbook search uses a short debounce and a dedicated worker. Generation checks discard results from older searches, tabs, or catalogs.

[TinySoundFont](https://github.com/schellingb/TinySoundFont) is MIT licensed and vendored at commit `853a0a171759f1ddba0de1442133a75912bbeffa`. Its original license is retained in the source and APK assets. It has no external native dependency. AndroidX AppCompat and DocumentFile retain their existing Apache 2.0 dependencies. TinySoundFont does not implement every SF2 effect/modulator, so the result may differ from a full desktop synthesizer.

## Build and validation

The existing **Build KaraokeFlow Beta APK** workflow continues to build on `main` pushes and manual dispatch. Pull requests also run it, including synthetic parser/catalog tests and native synthesis verification before APK generation. Java 17, Gradle 8.9, Android Gradle Plugin 8.7.3, compile/target SDK 35 and minimum SDK 26 are retained. NDK `28.2.13676358` and CMake `3.22.1` build `armeabi-v7a`, `arm64-v8a`, and `x86_64` native libraries; the current NDK supports 16 KiB page alignment.

With those tools installed:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

Before treating the beta as phone-verified, test your own MIDI and SF2 on an Android device:

- Play a known song and confirm audible instruments, percussion, tempo changes, and syllable highlighting.
- Pause during a lyric, wait, then Resume: music and lyrics should continue together.
- Stop and replay, then rapidly select another song: old audio/lyrics should end.
- Leave the app, disconnect headphones, and interrupt with another audio app: playback should pause.
- Restart the app and verify saved inputs; try a missing MIDI, bad SF2, ambiguous song ID, and instrumental MIDI.

Automated builds and generated test fixtures verify code and synthesis behavior. They cannot verify the user's actual 42,770-song collection, their selected SoundFont, or physical-phone audio latency.

## Midnight stage design

The stage uses original scalable aurora, mountain and lake artwork, cyan sung lyrics, and navy controls. Landscape reserves space beside the floating keypad; hiding it restores the full lyric width. The panel scrolls on short displays. Backspace edits number entry; Stop is the dedicated playback stop action. Phone layout and rotation still require device verification.

## Large library and stage update

MIDI directory metadata and the complete numeric lookup index are now saved in private cache and reused across launches. The initial lookup still queries folder metadata, but never parses the entire MIDI collection. Cache eviction may require a new scan. Use Refresh after adding, renaming, moving or deleting MIDI files; refresh removes the saved index. Catalog search runs in a background worker and displays at most 100 matching cards.

Home uses the original aurora scene and navy/cyan theme. Song title, singer and duplicate details are hidden while playing or paused. In landscape, Home, Queue and Songbook are inside the toggled keypad panel, leaving the stage clear when controls are hidden.

Performance with a 42,772-file provider folder and physical-device layout testing still require device verification; no loading-time claim is made.

## Settings, automatic setup and media (0.4.0)

Open Settings from Home or the stage control deck. Choose the folder `/storage/emulated/0/KaraokeFlow` once in Android's folder picker. The app finds CSV and SF2 files directly inside that granted folder and the `SongHub_Extracted` child folder. If several CSV or SF2 files exist, it asks which to use. Selections are remembered; no all-files storage permission is required. Android still requires the initial folder grant. Settings also lets you override the catalog, MIDI folder and SoundFont separately.

Choose an MP3 songs folder, an MP4 concerts folder, and an MP4 backgrounds folder independently. Song and concert files use unique six-digit numbers, for example `050001 My Song.mp3` and `060001 Live Concert.mp4`. Subfolders are included. Unnumbered media is excluded; titles come from the filenames. Numbers already used by MIDI or another source are excluded with a conflict notice. Use unused numbers for media; existing catalog numbers are never reassigned. Media folder indexes are cached across restarts; Refresh rescans them after changes.

MP3 and MP4 songs play through Android MediaPlayer without requiring an SF2. Concert video keeps its aspect ratio. Choose an MP4 lyric background in Settings; it loops silently behind MIDI lyrics, while MIDI supplies the audio. Backgrounds are selected from MP4 files directly inside the chosen backgrounds folder. Choose "Use illustrated background" to return to the aurora artwork. MP3 has no synthesized or external synchronized lyrics in this beta, and concert lyrics must be embedded in the video. Codec support depends on the Android device.

The idle stage shows SELECT SONGS and 000000. Each digit updates up to three catalog matches, including the exact zero-padded number if it exists. Prefix lookup uses a sorted number index rather than scanning the full library on each key. Play starts the exact entered number; RES reserves it. Entering six digits no longer reserves automatically. Backspace edits the number. Playback hides the idle label and empty number; MIDI displays its lyrics and concerts display video. Loading and error messages remain visible when needed.

All audio pauses when the app loses foreground or audio focus, or headphones disconnect. Media surfaces reconnect after rotation without restarting the audio session. Physical-device tests for initial permission discovery, decoder compatibility, video rotation, background rendering and the 42,772-file library are still required.

## Reservation stage behavior (0.4.1)

Reserved six-digit song numbers appear at the top of the stage in queue order, separated by dots. The strip scrolls horizontally when it is longer than the screen and stays available with the landscape keypad hidden. Stop and Next consume and start the first reservation. If there is no reservation, Stop stops playback. Internal lifecycle/settings stops never consume the queue. Each newly selected song displays its title above the stage for at least 1.2 seconds and throughout loading; the title disappears when playback starts. Stop/Next during this introduction cancels the pending song before switching. Leaving the app cancels a pending introduction. Rotation preserves the introduction timer. Song number hash markers are replaced with dots.

## Compact live reservations (0.4.2)

While a song is playing or paused, typed reservation numbers and matching titles appear in a compact panel near the top, below the queued numbers and separated from the larger lyric area. The live number uses 18–20 sp and the title 13–14 sp, with a two-line preview. Idle selection retains the larger number display. A cyan indeterminate progress bar animates during title introductions and song loading, then disappears when playback starts. Repeated hash placeholders in MIDI lyric lines display as dots; one-for-one character replacement preserves timed highlight offsets. Single hash characters in actual words remain unchanged. Device layout verification remains required.
