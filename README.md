# KaraokeFlow Beta
Phone-first Android karaoke application.

Beta inputs:
- `mkmd_catalog_probe.csv`
- `SongHub_Extracted` MIDI folder
- user-selected `.sf2` SoundFont

The extracted six-digit MIDI sequence is the permanent KaraokeFlow song ID. This first APK milestone validates install, CSV catalog import/search, SAF MIDI-folder selection, and SF2 selection. MIDI synthesis and synchronized lyrics follow in the next milestone.

GitHub Actions automatically builds `app-debug.apk` and publishes it as the `KaraokeFlow-Beta-APK` workflow artifact.
