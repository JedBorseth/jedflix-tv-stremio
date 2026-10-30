# FFmpeg audio filter native build

The existing Stremio 1.10.4 player ships FFmpeg n8.1 but disables all optional
filters. Night audio replaces **only** `libavfilter.so`, built from the
unmodified official FFmpeg 8.1 source. The original `libmpv.so`, `libavutil.so`,
`libswresample.so`, audio/video decoders, and video acceleration remain in use.
This does not start an FFmpeg executable or transcode video.

## Build

```sh
python3 scripts/build-audio-filters.py --abi x86_64
python3 scripts/build-audio-filters.py --abi arm64-v8a
```

`arm64` is an alias for `arm64-v8a`. Outputs per ABI:

* `.local/audio-filters/<abi>/libavfilter.so`: stripped replacement for packaging.
* `.local/audio-filters/<abi>/manifest.json`: source/toolchain/upstream/output
  SHA-256 pins, exact configure arguments, and ABI validation results.
* `.local/audio-filters/<abi>/audio-filter-probe`: native executable for device
  verification, excluded from the app.
* `.local/audio-filters/<abi>/original/`: unchanged libraries extracted directly
  from the architecture's checksum-verified upstream APK.

The script bootstraps the official Android NDK r28c on macOS by downloading and
verifying the DMG, then mounting it read-only at `.local/audio-filters/ndk-mount`.
It executes tools directly from that mount to avoid extracting several GB.
`hdiutil detach .local/audio-filters/ndk-mount` unmounts it when no build is
running. It remounts automatically when needed. To use a separately installed
NDK on macOS or Linux, set `ANDROID_NDK_HOME`; the exact r28c revision is checked.
Builds need Python 3.12+, make, and internet access on first use. Automatic
Linux NDK downloading is not implemented.

Toolchain: Android NDK `28.2.13676358`, Clang, API 23. FFmpeg builds out of tree
with shared libraries, GPL v3 enabled to match upstream, no external optional
dependencies, and only `acompressor`, `alimiter`, `volume`, `aformat`,
`aresample`, `pan`, and `anull` enabled. Audio/video buffer endpoints are FFmpeg
core facilities. On x86, optional NASM routines are disabled so no separate
assembler is needed. ARM compiler/inline optimizations remain available.

FFmpeg's build also creates temporary avutil/swresample libraries for linking;
these **must not be copied into the APK**. Only the resulting avfilter is used.
Every strong undefined import in that replacement is checked against either the
actual original APK's libraries or the NDK's API 23 system library stubs,
including symbol versions. All original avfilter exports must remain available
under their original names and versions. Validation also enforces the expected
ELF machine, `LIBAVFILTER_11`, unsuffixed `libavfilter.so` SONAME, permitted
unsuffixed dependencies, and 16 KB load-segment alignment. Zip alignment is a
separate APK packaging check performed by the app build.

Checked cached builds are reused only while recipe, probe source, configure
arguments, upstream inputs, and output hashes match. Cached libraries still
undergo ABI validation. `--force` reconfigures and rebuilds. Configure/make logs
are under `.local/audio-filters/`.

## Pins and corresponding source

* FFmpeg: <https://ffmpeg.org/releases/ffmpeg-8.1.tar.xz>
  SHA-256 `b072aed6871998cce9b36e7774033105ca29e33632be5b6347f3206898e0756a`.
* NDK: <https://dl.google.com/android/repository/android-ndk-r28c-darwin.dmg>
  SHA-256 `a7a2513d4c229f814e1a3bc528fe04ed0807b63f57a6d21f09d75e8362e44a20`.
  Published official SHA-1 also checked during initial bootstrap:
  `60e8efb121aa7ce9857afbffa17b2da4c37b515a`.
* Upstream APK URL and SHA-256 for each ABI are pinned in the recipe and
  reproduced in its manifest. Both original architectures report `FFmpeg
  version n8.1` for avfilter, avutil, and swresample.

The replacement reports GPL-3.0-or-later. FFmpeg's license text is at
`.local/audio-filters/src/ffmpeg-8.1/COPYING.GPLv3`; include it and the native
build notice with the APK. When distributing builds, make the exact FFmpeg
source archive and this build recipe available to recipients with the build.
There are no FFmpeg source changes; the configure arguments enable filters.

## Device DSP verification

The probe in `scripts/native/audio-filter-probe.c` is linked to the replacement
and original upstream dependencies. Run it beside copies of the packaged
`libavfilter.so`, `libavutil.so`, and `libswresample.so`, using
`LD_LIBRARY_PATH` pointing to that directory. Device access must use
`scripts/adb.sh`; the parent verification script manages deployment.

With no arguments, it applies the production compressor/limiter graph and an
`anull` baseline to five seconds of synthetic 48 kHz audio in stereo, 5.1, and
7.1. It includes quiet material, sustained loud material, a high transient, and
a silence tail. It verifies sample count, channels, sample rate, finite samples,
quiet gain close to +6 dB, loud attenuation, more than 10 dB reduction in the
quiet/loud gap, and the -1 dBFS peak ceiling. It emits one JSON result per case
and exits nonzero on any failed assertion. An optional first argument supplies
an alternate graph for focused experiments; the production assertions still
apply.

These are objective DSP and loader checks. They do not establish perceived
dialogue clarity on every soundtrack, performance on every TV, or real-hardware
audio routing; the release candidate still needs listening on the target TV.
