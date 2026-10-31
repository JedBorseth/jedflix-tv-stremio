# Agent notes

## Development device

The default dev target is the shared Windows emulator, not a local Mac emulator.

- SSH host: `10.0.0.223` (user `jedborseth`, key auth; no password prompt)
- AVD: `GoogleTV_1080p`, Android 36 Google TV, **x86_64**, NVIDIA RTX 4070 GPU
- Serial: `emulator-5554`

Access it only through `scripts/adb.sh`. It opens an SSH tunnel to the Windows
adb server and points `ADB_SERVER_SOCKET` there, so the remote device behaves
like a local one:

```bash
./scripts/adb.sh devices
./scripts/adb.sh -s emulator-5554 shell getprop ro.product.cpu.abi   # x86_64
```

Do not call bare `adb` for dev work: it talks to the Mac's own (empty) adb
server. Overrides: `JEDFLIX_DEVICE_HOST`, `JEDFLIX_DEVICE_USER`,
`JEDFLIX_ADB_TUNNEL_PORT`, `JEDFLIX_SERIAL`.

## Build and deploy

```bash
./scripts/dev.sh            # build x86_64, install, cold start
./scripts/dev.sh --no-build # reinstall the existing APK only
./scripts/build.sh x86_64   # build only -> dist/jedflix-android-tv-1.10.4-x86_64.apk
./scripts/build.sh arm64    # release build -> dist/jedflix-android-tv-1.10.4-arm64.apk
./scripts/build.sh arm      # 32-bit Android -> dist/jedflix-android-tv-1.10.4-arm.apk
```

For an explicitly requested local AVD, use `JEDFLIX_DEVICE_HOST=local` with
`scripts/adb.sh`; match the APK to its ABI. The local `JedFlix_Stream_Test` and
`Jedflix_Login_Repro` AVDs are ARM64. `scripts/dev.sh` accepts
`JEDFLIX_ARCH=arm64` for those AVDs.

The Windows AVD is x86_64, so the ARM64 artifact cannot be installed there. Use
the x86_64 build for every dev/test cycle. The patch scripts are
architecture-agnostic; `scripts/build.sh` pins the matching upstream APK and
SHA-256 for each architecture.

Package: `com.jedflix.stremio`
Activity: `com.jedflix.stremio/com.stremio.tv.MainActivity`

## Seeing and driving the device

```bash
./scripts/adb.sh -s emulator-5554 exec-out screencap -p > /tmp/screen.png
./scripts/adb.sh -s emulator-5554 shell input tap 960 540
./scripts/adb.sh -s emulator-5554 shell input keyevent KEYCODE_DPAD_DOWN
./scripts/adb.sh -s emulator-5554 shell uiautomator dump /sdcard/ui.xml
./scripts/adb.sh -s emulator-5554 exec-out cat /sdcard/ui.xml
```

Read `/tmp/screen.png` with the Read tool to inspect the screen.

The emulator is shared by every thread. Serialize installs and avoid powering
it off or rebooting it; another agent may be mid-test. If `scripts/adb.sh`
reports no device, check that the Windows emulator is running rather than
starting a Mac emulator.

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
