# KaraokeFlow playback beta

KaraokeFlow is an Android 8.0+ foreground karaoke player. The 0.3.1 beta plays user-supplied Standard MIDI files through a user-selected SF2 SoundFont and highlights synchronized lyrics.

## Try the beta

1. Install `app-debug.apk` from the **KaraokeFlow-Beta-APK** artifact on a successful GitHub Actions run.
2. Tap **MIDI folder** and grant access to your `SongHub_Extracted` folder (or another folder containing your own `.mid`, `.midi`, or `.kar` files).
3. Tap **SoundFont** and select a real `.sf2` file. This beta accepts files up to 256 MiB and caches the selected font privately. Its URI and available provider size/modification metadata identify reusable copies, including after an app restart. SF3 is unsupported. No font is bundled.
4. Tap **Import CSV** and select `mkmd_catalog_probe.csv` or a compatible UTF-8 catalog.
5. Search by title, artist, or permanent six-digit song number, such as `012185`, and tap a song to play.
6. Use **Pause**, **Resume**, and **Stop**. Stop resets to the beginning; Play replays the song. The current lyric line highlights each timed fragment, with the next line below it.

Folder, font, and catalog selections persist when the document provider supports persistent grants. The catalog reloads on app restart. If a provider revokes access, select the input again. A MIDI without usable lyric/text events still plays and displays an instrumental message. Choosing a song opens Stage so its loading and playback status is visible. Catalog and refresh notices also appear on the current screen. Use Refresh files after adding or replacing MIDI files or replacing a SoundFont in place. This clears cached file metadata and the selected copied font; the next playback rebuilds what it needs.

Playback pauses when the app leaves the foreground, loses audio focus, or headphones disconnect. Return to the app and press Resume. Reserve songs with the number keypad or a Songbook RSV button; Next plays the first reservation, while Play now selects that particular reserved entry. Reservations are kept for the current app session. Background playback, seeking, and favorites are not included. The key, tempo, and melody labels are informational; this build does not provide those adjustment controls. Audio is streamed rather than pre-rendering the entire song.

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
- `PlaybackEngine` schedules MIDI channel events against a 44.1 kHz stereo PCM stream. Positions come from AudioTrack's audible playback head, so lyrics freeze with Pause and resume with buffered audio. One worker owns all synth/audio resources, and generation tokens discard stale work when switching songs or stopping.
- `NativeSynth` is a small JNI bridge to TinySoundFont. It handles MIDI programs/banks, channel 10 percussion, velocity, controllers including sustain, and pitch bend. The audio worker keeps an unchanged SoundFont loaded across song changes, Stop, and replay. Each fresh song resets voices and channel/controller state; choosing or refreshing a SoundFont invalidates that bank. Destroying the Activity closes the synth. SoundFonts load locally; synthesis is offline.
- `SongFiles` persists completed directory listings and completed numeric indexes in bounded private snapshots. Valid catalog paths still resolve first. A missing path may require the first full folder traversal, but subsequent launches can reuse the saved snapshot. Corrupt snapshots fall back to live provider queries. File lookup, snapshot reading/writing, and SF2 copying run off the UI thread.
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
