# Retaining Stremio TV playback with Jedflix services

Tested 2026-10-24 on `JedFlix_Stream_Test`, Android 36 ARM64, using
Stremio Android TV 1.10.4 (the pinned upstream APK in `scripts/build.sh`).

## Result: partial pass

The existing compiled player can play a direct stream without a Stremio account.
The original TV interface and local library also work in guest mode. This is a
useful foundation, **not proof of a completed Jedflix account/library replacement**.
Do not start a full rebuild based on an assumption that cloud integration works.

### Observed on the emulator

- Replaced `LoginViewModel.createLinkCode()` with the app's existing guest entry.
  A cold start reached the original home screen without account linking.
- Passed a locally generated 90-second H.264/AAC MP4 to the original player through
  its existing player deep link. Video rendered successfully.
- Pause and resume worked. Android media-session evidence showed PAUSED at
  13,146 ms, followed by PLAYING at 17,385 ms. Other playback controls and engines
  have not been established by this test.
- Instrumented the `StremioApi` facade. The `loadPlayer` and `addLibraryItem`
  probes executed; Big Buck Bunny appeared in the local library after a cold
  restart. Library mutations still execute the original guest-library behavior.
  The remove/sync probes are present but were not exercised.
- Compared ZIP entries: all 25 native `.so` libraries were byte-for-byte identical
  to upstream; only `classes6.dex` changed among the nine DEX files. This DEX also
  contains player classes; those were reassembled, with the only intended player
  change being a log at the `loadPlayer` facade entry.
- APK signature and 16 KB ZIP alignment checks passed.

Raw emulator XML, media-session dumps, screenshot, probe logs, and binary comparison
are in ignored `.local/spike-*` files. No user credentials were needed or read.

## What remains unproven

The prototype enters **unauthenticated guest mode**, not a Jedflix account. It
does not implement Jedflix device linking, read an account's Real-Debrid settings,
resolve a real title through the Jedflix service, or synchronize watchlists/history.
It still uses upstream catalog/addon behavior and contains upstream links/settings.
It must not be treated as the finished replacement app.

The account and library facade dispatches protobuf actions into
`libstremio_core_kotlin.so`. Native account/link endpoints and core state survive
resource/Kotlin URL changes. Intercepting a Kotlin method is possible, but that
alone does not replace core synchronization or unsolicited native-side updates.

## Existing Jedflix integration points

Repositories are accessible locally:

- `/Users/jedborseth/Documents/2026/jedflix`: React/Vite, Convex Auth, Go media API.
- `/Users/jedborseth/Documents/2026/jedflix-tv`: the previous source-owned TV app.

The web app has authenticated per-user My List and Real-Debrid settings. Its media
service returns direct playback URLs. Those can be encoded for the retained
player, but the actual authenticated end-to-end chain was not tested here.
Existing `/tv/rd/:code` pairing transfers an RD key and is not account authentication.

Web history and resume currently share a record. Keeping web/TV resume independent
requires separate client-scoped resume state and shared viewing history. The
Stremio core also uses IMDb/video IDs while the web library uses TMDB IDs, so an
explicit title/episode mapping is required.

## Next integration gate

Before a full implementation, prove one real account flow end-to-end:

1. Approve an expiring TV device code on the signed-in Jedflix website; give the TV
   a revocable device session, with no Stremio account token.
2. Load that account's My List into the TV, and reflect an add/remove operation
   back into Jedflix. Exercise both directions after restart and logout.
3. Resolve one title through the account's Jedflix sources and launch its direct
   URL in the retained player. Verify account revocation and failed/expired pairing.
4. Publish history separately from TV resume, with correct movie/episode ID mapping.
5. Establish that native account/library traffic cannot bypass the Jedflix adapter.

If native state cannot be cleanly controlled, evaluate the open-source core/JNI
boundary separately before committing to protocol emulation or a new client.

## Repeat the playback probe

Prerequisites: run `./scripts/build.sh` once, have the emulator connected, and have
`ffmpeg` on PATH. These commands install an experimental APK over this workspace's
locally signed `com.stremio.one` build; the old `com.jedflix.tv` app is separate.
The regular APK in `dist/` remains available to restore the previous build.

```bash
python3 scripts/spike-build.py
adb install -r dist/jedflix-feasibility-spike.apk
adb shell am force-stop com.stremio.one
adb shell am start -W -n com.stremio.one/com.stremio.tv.MainActivity
mkdir -p .local/spike-media
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=640x360:rate=24 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 \
  -t 90 -c:v libx264 -preset ultrafast -crf 28 -pix_fmt yuv420p \
  -c:a aac -movflags +faststart -y .local/spike-media/test.mp4
adb reverse tcp:8765 tcp:8765
python3 scripts/spike-player.py serve
# In another terminal:
python3 scripts/spike-player.py play
adb logcat -d -s JedflixSpike:I AndroidRuntime:E
```

The stream payload is JSON compressed with zlib, standard-base64 encoded, and
percent-encoded in `stremio:///player/{streamData}`. Reference:
[Stremio's stream encoding source](https://stremio.github.io/stremio-core/src/stremio_core/types/resource/stream.rs.html).
The original player deep-link contract also appears in this APK's
`res/navigation/main_navigation.xml`.
