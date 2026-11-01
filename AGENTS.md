# Agent notes

## UI styling

Reuse the existing app's components, typography, spacing, colors, and D-pad
focus styling for every UI change. Inspect the corresponding existing screen
before implementing or delegating UI work, and include these requirements in
delegated briefs. Never introduce default Android widgets or dialogs with
platform styling.

Show movie metadata inline on the detail page; do not put it behind a button,
dialog, or separate information screen.

## Development device

The default dev target on this machine is Jed's Sony TV on the LAN. The shared
Windows emulator is still available and is the fallback for other threads.

- TV: `10.0.0.93:5555`, Sony BRAVIA 4K VH22, Android 12 (SDK 31), **armeabi-v7a**
  (32-bit), so use the `arm` build
- Preferred serial when the TV is selected: `10.0.0.93:5555`

`.local/device.env` (gitignored) sets `JEDFLIX_ADB_TARGET=10.0.0.93:5555`.
`scripts/adb.sh` and `scripts/dev.sh` source it, connect over the LAN, and use
the TV as their default serial:

```bash
./scripts/adb.sh devices
./scripts/adb.sh shell getprop ro.product.cpu.abilist  # armeabi-v7a,armeabi
./scripts/dev.sh                                       # builds arm, installs, starts
```

Access any device only through `scripts/adb.sh`. With `JEDFLIX_ADB_TARGET` set
it talks directly to that device; otherwise it opens an SSH tunnel to the
Windows adb server and points `ADB_SERVER_SOCKET` there.

Do not call bare `adb` for dev work: it talks to the Mac's own adb server.
Overrides: `JEDFLIX_ADB_TARGET`, `JEDFLIX_DEVICE_HOST`, `JEDFLIX_DEVICE_USER`,
`JEDFLIX_ADB_TUNNEL_PORT`, `JEDFLIX_SERIAL`, `JEDFLIX_ARCH`.

The shared Windows emulator is `GoogleTV_1080p`, Android 36 Google TV,
**x86_64**, on SSH host `10.0.0.223` (user `jedborseth`, key auth), serial
`emulator-5554`. To use it instead, clear `JEDFLIX_ADB_TARGET`
(`JEDFLIX_ADB_TARGET= ./scripts/dev.sh`) and set `JEDFLIX_SERIAL=emulator-5554`.

## Build and deploy

```bash
./scripts/dev.sh            # build for the device's ABI, install, cold start
./scripts/dev.sh --no-build # reinstall the existing APK only
./scripts/build.sh x86_64   # build only -> dist/jedflix-android-tv-1.10.4-x86_64.apk
./scripts/build.sh arm64    # release build -> dist/jedflix-android-tv-1.10.4-arm64.apk
./scripts/build.sh arm      # 32-bit Android -> dist/jedflix-android-tv-1.10.4-arm.apk
```

`scripts/dev.sh` asks the selected device for `ro.product.cpu.abilist` and picks
the matching architecture (`arm` for the TV, `x86_64` for the Windows AVD);
pin it with `JEDFLIX_ARCH` if needed.

For an explicitly requested local AVD, use `JEDFLIX_DEVICE_HOST=local` with
`scripts/adb.sh`; match the APK to its ABI. The local `JedFlix_Stream_Test` and
`Jedflix_Login_Repro` AVDs are ARM64. `scripts/dev.sh` accepts
`JEDFLIX_ARCH=arm64` for those AVDs.

The Windows AVD is x86_64, so it cannot install the TV's `arm` or the ARM64
artifact; select the target before building. The patch scripts are
architecture-agnostic; `scripts/build.sh` pins the matching upstream APK and
SHA-256 for each architecture.

Package: `com.jedflix.stremio`
Activity: `com.jedflix.stremio/com.stremio.tv.MainActivity`

Two Jedflix packages may be on the TV by design: the new `com.jedflix.stremio`
and the legacy `com.jedflix.tv` (v0.9.x) that migrates users to it. Leaving the
legacy app installed is expected; see [docs/updates.md](docs/updates.md).

## Seeing and driving the device

```bash
./scripts/adb.sh exec-out screencap -p > /tmp/screen.png
./scripts/adb.sh shell input tap 960 540
./scripts/adb.sh shell input keyevent KEYCODE_DPAD_DOWN
./scripts/adb.sh shell uiautomator dump /sdcard/ui.xml
./scripts/adb.sh exec-out cat /sdcard/ui.xml
```

Read `/tmp/screen.png` with the Read tool to inspect the screen.

The Windows emulator is shared by every thread. Serialize installs and avoid
powering it off or rebooting it; another agent may be mid-test. If
`scripts/adb.sh` reports no device, check that the TV is awake and on the LAN,
or that the Windows emulator is running rather than starting a Mac emulator.

## T3 Code Device panel

Device support is enabled in the app, but agent device access is currently off,
so `device_*` tools are unavailable. The adb wrapper is the supported path. To
use the panel later: Settings -> Devices -> Add source -> Machine over SSH
(`jedborseth@10.0.0.223`), then grant agent access. Android video streaming in
the panel also needs `ffmpeg` with libx264 on the Windows host PATH (it is not
installed yet). The panel provides view/control only; APK installs still go
through `scripts/adb.sh`.

## Verification

- `scripts/build.sh` ends with apksigner verification and a 16 KB zipalign check.
- `dist/jedflix-android-tv-1.10.4-x86_64.apk` should contain `lib/x86_64/`.
- `dist/jedflix-android-tv-1.10.4-arm64.apk` should contain `lib/arm64-v8a/`.
- `dist/jedflix-android-tv-1.10.4-arm.apk` should contain `lib/armeabi-v7a/`.

## Release identity

Read [docs/updates.md](docs/updates.md) before publishing a release or changing
package identity, updater metadata, or legacy migration. Each stable release
keeps the old updater manifest and signed handoff APKs as well as the separate
Jedflix app assets. Keep Java/JNI class names under their upstream namespaces.
