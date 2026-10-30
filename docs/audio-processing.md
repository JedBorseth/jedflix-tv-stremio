# Realtime audio leveling and dialogue clarity

Research and local APK inspection, 2026-10-30. No application behavior was changed.

This is the preimplementation research report. The current build implements
[FFmpeg Night mode](audio-night-mode.md); references below to disabled filters
describe the original upstream APK.

## Recommendation

Add an optional **Night mode** that compresses loud passages, restores useful listening level with makeup gain, and limits peaks. Treat dialogue enhancement and matching average volume between different titles as separate controls. For this APK, evaluate the existing mpv engine first; use its in-process PCM audio path rather than a separate FFmpeg command or a transcoding server. This is an engineering recommendation, not a tested implementation.

The important qualification: the bundled FFmpeg build has filters disabled. A filter setting alone cannot enable them. First verify runtime filter availability; expect to rebuild compatible FFmpeg libraries with selected filters enabled, preserving their ABI dependencies and Android packaging/alignment. Compare that work with a native Android DynamicsProcessing proof of concept on the actual playback engine and target TV. Android's effect is a plausible smaller experiment if a stable audio session ID is available, but its routing and audible results need device validation.

## What the app contains

This repository patches a compiled Stremio Android TV APK rather than owning its player source; see [README](../README.md) and [feasibility report](feasibility.md). The decoded upstream APK in `.local/decoded/lib/arm64-v8a/` contains `libavcodec.so`, `libavformat.so`, `libavfilter.so`, `libavutil.so`, `libswresample.so`, `libmpv.so`, `libvlc.so`, and the Media3 extension library `libmedia3ext.so`.

`.local/decoded/smali_classes6/com/stremio/common/players/MpvPlayer.smali` constructs `dev.jdtech.mpv.MPVLib`, calls `setOptionString`, configures hardware video decoding, and chooses `audiotrack,opensles` audio output. It provides a plausible option-injection seam. This establishes available code paths, not which engine every playback session selects.

Local binary inspection found `--disable-programs` and `--disable-filters` in the bundled FFmpeg configuration string, without filter-enabling overrides. The strings `acompressor`, `alimiter`, `loudnorm`, `dynaudnorm`, `speechnorm`, and `dialoguenhance` are absent from the small bundled `libavfilter.so`. Presence of `libavfilter.so` therefore does **not** mean these filters are usable. No standalone FFmpeg executable is needed for a future in-process implementation.

The preset [default-player-settings.json](../assets/default-player-settings.json) sets `audioPassthrough` and `surroundSound` to false. Persisted user settings can differ. The FFmpeg command in the existing feasibility document runs on the development computer to generate test media; it is not evidence of a TV command-line FFmpeg installation.

## Three different problems

- **Average loudness between titles:** loudness measurement and normalization target a perceptual level. A single fixed gain preserves the difference between dialogue and explosions.
- **Explosions versus quiet speech within a title:** dynamic range compression reduces loud peaks relative to quieter passages. Makeup gain can then raise the listening level. This most closely matches the reported complaint.
- **Speech buried under music/effects at the same instant:** dialogue enhancement requires channel-aware mixing or separation. Compression of the whole mix cannot selectively recover speech.

The distinction follows from the documented controls of FFmpeg loudness normalization and compressor filters, and Android's separate gain, equalization, compression, and limiter stages. [FFmpeg filter documentation](https://www.ffmpeg.org/ffmpeg-filters.html), [Android DynamicsProcessing](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing).

## FFmpeg choices

- `acompressor` plus `alimiter`: a direct starting point for Night mode; tune linked channel compression, attack/release, makeup gain, and peak limiting by listening. It affects the whole mix.
- `loudnorm`: supports single-pass live use and two-pass files, with integrated loudness, loudness range, and true-peak targets. Dynamic mode upsamples to 192 kHz. Two-pass analysis is unsuitable for immediate playback of an unscanned torrent.
- `dynaudnorm`: adaptive gain across neighboring sections; useful for leveling but can raise ambience and has buffering/smoothing tradeoffs.
- `speechnorm`: modifies waveform half-cycles; its name does not imply speech separation from a film soundtrack.
- `dialoguenhance`: accepts stereo and produces 3.0 with an enhanced derived center. It needs deliberate output/downmix handling and is not an existing 5.1 center-channel boost.

These are documented filter behaviors, not validated settings for this APK. [FFmpeg filters](https://www.ffmpeg.org/ffmpeg-filters.html).

Default `dynaudnorm` uses 500 ms frames and a 31-frame smoothing filter. Its source queues audio and gain histories; startup/seek delay must be measured instead of assuming realtime processing means negligible latency. `loudnorm` likewise allocates and handles an initial analysis buffer. A modest compressor/limiter is the recommended first experiment for a TV player. [dynaudnorm implementation](https://github.com/FFmpeg/FFmpeg/blob/master/libavfilter/af_dynaudnorm.c), [loudnorm implementation](https://github.com/FFmpeg/FFmpeg/blob/master/libavfilter/af_loudnorm.c).

## Existing player and platform alternatives

**mpv:** `af` supports audio filters and its `lavfi` wrapper passes audio through FFmpeg's libavfilter. Embedded libraries suffice; no command-line process is necessary. mpv also has `ad-lavc-ac3drc=1` for authored AC-3 dynamic-range metadata. That is a useful narrow experiment, not a universal movie-audio fix: streams must contain the metadata. [mpv manual](https://mpv.io/manual/stable/).

**Media3:** a custom `AudioProcessor` can transform PCM in the audio sink. This could implement a compressor or call native DSP without introducing a separate streaming pipeline. A FFmpeg decoder extension provides decoding; it does not automatically expose arbitrary FFmpeg filters. [AudioProcessor API](https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor), [FFmpeg decoder extension](https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg).

**Android DynamicsProcessing:** API 28 provides configurable multiband compression, EQ, and a limiter attached to a player's audio session. This directly fits Night mode and is worth comparing with a custom filter build. Parameters and device output must be tested; attaching an effect requires the actual playback session and correct lifecycle management. [DynamicsProcessing API](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing).

**Android LoudnessEnhancer:** API 19 increases gain and compresses samples outside the supported range. It is a simple boost effect, not a dialogue detector or perceptual loudness target. [LoudnessEnhancer API](https://developer.android.com/reference/android/media/audiofx/LoudnessEnhancer).

**Dialogue control:** for multichannel content, assess center-channel gain before stereo downmix, with peak protection. This is a practical inference based on available channel-mixing facilities, not a guarantee that every film places speech exclusively in the center. Stereo speech enhancement is more difficult; do not claim an EQ or normalizer isolates speech. [Media3 channel mixing facilities](https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor).

## Output constraints and Android TV assumptions

Audio sample processing requires decoded PCM. Media3 explicitly excludes passthrough and offload from processor chains, and warns that float-output mode disables audio processing. Processing modes must ensure the selected engine supplies a supported PCM format and communicate when passthrough is disabled. Multichannel PCM can preserve surround where the actual HDMI route supports it; processing compressed bitstreams requires decoding first. This can lose the original encoded Atmos/DTS:X transport, so test the target soundbar/receiver route. [DefaultAudioSink.Builder](https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder), [mpv output and passthrough options](https://mpv.io/manual/stable/).

Do **not** assume Android TVs ship an accessible FFmpeg executable. FFmpeg is not an Android app-platform contract. AOSP's standard shell/utility package does not include it; vendor internals or another app's private libraries are not a supported dependency. Bundle controlled libraries or use Android APIs. This does not establish the contents of every vendor TV. [AOSP shell and utilities build definition](https://android.googlesource.com/platform/system/core/+/HEAD/shell_and_utilities/Android.bp), [AOSP utility inventory](https://android.googlesource.com/platform/system/core/+/HEAD/shell_and_utilities/README.md).

## Suggested validation gate

Before changing a default, prove one explicit engine on a real low-end Android TV: quiet dialogue followed by a sudden peak; stereo and 5.1; AAC, AC-3/E-AC-3, and DTS as supported; pause/resume, seek, audio-track switching, and playback speed; TV speakers and HDMI soundbar. Compare loudness-matched samples with processing off/on, monitor clipping, startup/seek time, A/V sync, CPU use, and dropped frames. Keep hardware video decoding and avoid audio re-encoding when outputting PCM. Choose conservative Off / Night / stronger Night presets only after listening tests.
