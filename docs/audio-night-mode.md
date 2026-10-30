# FFmpeg Night mode

Version 1.0.2 adds **Player → Audio → Night mode → On / Off**. It defaults to Off
and saves the choice locally. On switches the current playback to mpv using the
existing engine selector and preserves the scene position. Selecting another
engine explicitly turns Night mode off. External-player playback stays external.
Turning Night mode Off leaves mpv selected; the player's Player menu can select
Exo again, including for encoded passthrough supported by that engine.

While enabled, precise seeking avoids an initial jump back to the nearest video
keyframe when switching engines. Off and failure recovery restore the original
seeking option as well as the audio settings.

The filter uses channel-linked compression at a −22 dBFS threshold, 4:1 ratio,
10 ms attack, 300 ms release, a soft knee, and +6 dB makeup gain, followed by a
−1 dBFS sample-peak limiter with 5 ms lookahead and latency compensation. The
limiter's automatic makeup is disabled. This reduces the difference between loud
scenes and quieter dialogue; it cannot separate speech from simultaneous effects.
The peak limiter is not a measured intersample true-peak guarantee.

Processing runs inside mpv on decoded audio. Video decoding stays unchanged.
Night mode temporarily clears audio passthrough and reopens an already-active
passthrough audio decoder as PCM. Off removes only Jedflix's labelled filter and
restores the prior passthrough setting. Original encoded Atmos/DTS:X transport
is therefore unavailable while processing is on. Actual surround PCM output
depends on the TV and HDMI audio route.

The Audio menu reports On only when PCM output and the actual initialized filter
are verified. It waits during buffering, pause, and seeking. Native graph errors
disable the preference, remove the failed filter, restore audio settings, and
show Unavailable. Releasing the player removes its callbacks and observers.

## Build and test

```bash
./scripts/build.sh x86_64
JEDFLIX_SERIAL=emulator-5556 python3 scripts/verify-audio.py --install \
  --apk dist/jedflix-1.0.2-x86_64.apk
./scripts/build.sh arm64
```

The verification script uses `scripts/adb.sh`, honors `JEDFLIX_SERIAL`, preserves
app data, serializes installs, and removes its separate instrumentation APK. Its
generated fixtures do not require a Real-Debrid account. It checks the packaged
native library and installed Java graph, normal playback, live toggling,
unrelated-filter preservation, seek, speed, pause, surround input, real AC-3
decoding, passthrough-option restoration, and failure recovery. The pinned
upstream FFmpeg disables muxers, including the SPDIF wrapper, so this mpv build
decodes AC-3 to PCM even with its passthrough option set. Actual encoded
passthrough transitions and physical HDMI/soundbar compatibility have not been
established. The probe can require those transitions with a capable native build.

The native probe tests five seconds of 48 kHz stereo, 5.1, and 7.1 input with
quiet and loud passages, abrupt peaks, and a silence tail. On the Windows x86_64
test emulator the preset raised quiet passages by 6.0 dB, attenuated sustained
loud passages by 8.7 dB, reduced their gap by 14.7 dB, and limited peaks to
0.891251 amplitude (−1 dBFS). Sample count, channel count, sample rate, and finite
output checks passed. These are synthetic DSP measurements, not speech
intelligibility or real-TV performance measurements.

Final validation on the separate Windows Android 36 x86_64 emulator passed 40
audio integration checks and 12 real player UI checks. The UI flow verified
Exo → Night mode MPV, remote focus, On after process death/relaunch, and live Off.
The precise-resume check measured 27,384 ms before switching and 27,506 ms after.
Both APKs passed signature and 16 KB zip alignment verification; their helper
DEX is identical, and the only changed native library is `libavfilter.so`.

Native source, checksums, licensing, and reproducible build details are in
[audio-native-build.md](audio-native-build.md). Test results and real player UI
evidence are kept under ignored `.local/` while developing. Hardware listening
should compare Off and On using quiet speech followed by action on the intended
TV speakers or HDMI receiver, including pause/seek and audio-track changes.
