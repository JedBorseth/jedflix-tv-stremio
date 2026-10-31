# Jedflix updates and legacy migration

Settings → About identifies the app as a Jedflix fork of Stremio, shows the
installed Jedflix version, credits Stremio, and has one **Software update** row.
Its button changes with the state: Check for updates → Download X → Install X,
and Cancel download while a download runs. There is no separate updater screen.
The first MainActivity resume checks GitHub after eight seconds, at most once
per process and once per day after a successful check. A newer release offers
Update now / Later. Dismissal applies to that version code; the About row always
remains available.

## Release contract

The source is the latest **stable published release** of
`JedBorseth/jedflix-tv-stremio`. Drafts and prereleases are excluded. Each release
must have these assets:

- `jedflix-app-update.json` for `com.jedflix.stremio`
- `jedflix-app-VERSION-arm64-v8a.apk` for physical ARM64 TVs
- `jedflix-app-VERSION-armeabi-v7a.apk` for TVs running 32-bit ARM Android
- `jedflix-app-VERSION-x86_64.apk` for the shared Windows emulator
- `jedflix-update.json` plus `jedflix-VERSION-ABI.apk` for all three architectures:
  signed handoff updates of the original `com.stremio.one` fork. Keep these
  compatibility assets in every stable release while original fork installs exist.

Tags use `vMAJOR.MINOR.PATCH`. `assets/update-config.json` owns the Jedflix
version name, integer version code, package ID, manifest name, artifact prefix,
and repository. The code must increase for
every distributed release, be identical across architectures, and exceed both
original Stremio codes (maximum 34194308). This prevents accidentally downgrading
the x86_64 upstream build when switching to Jedflix versioning. Upstream Stremio
1.10.4 is recorded separately from the Jedflix version.

To prepare a release:

1. Increment `versionName` and `versionCode` in `assets/update-config.json`.
2. Build all three architectures sequentially (the build staging directory is shared):

   ```bash
   ./scripts/build.sh x86_64
   python3 scripts/spike-build.py --lock-addons --compat
   ./scripts/build.sh arm64
   python3 scripts/spike-build.py --lock-addons --compat
   ./scripts/build.sh arm
   python3 scripts/spike-build.py --lock-addons --compat
   ```

3. Generate the release files, substituting the configured version:

   ```bash
   python3 scripts/prepare-update.py --release \
     dist/jedflix-app-1.0.6-arm64-v8a.apk \
     dist/jedflix-app-1.0.6-armeabi-v7a.apk \
     dist/jedflix-app-1.0.6-x86_64.apk
   ```

   Optionally pass `--notes path/to/plain-text-notes.txt`. The helper verifies
   signatures, alignment, package identity, ABI, version consistency and the
   install permission. It writes `dist/jedflix-app-update.json` with exact file sizes
   and SHA-256 hashes. One ABI is allowed for local testing, but production
   releases must use `--release`, which rejects a missing architecture before
   writing the manifest. Selection follows the Android OS ABI, not CPU marketing:
   64-bit hardware running 32-bit Android needs `armeabi-v7a`.

   Run the helper again with `--release --compat` and the three `dist/jedflix-1.0.6-ABI.apk`
   handoff files to generate `dist/jedflix-update.json`. Both manifests and all
   six APKs must agree on the release version and signing key.

4. Push the source and create a **draft** release tagged with that version.
   Upload both manifests and all six versioned APKs, then publish the complete release.
   Pushing source alone does not make an update available. Do not replace assets
   on an existing release: publish a higher version code for corrections.

The legacy bridge's manifest parser can also be checked against the exact release
metadata. In the legacy `jedflix-tv` checkout, run:

```bash
JEDFLIX_RELEASE_MANIFEST=/absolute/path/to/jedflix-app-update.json \
  ./gradlew :app:testDebugUnitTest --tests '*NewJedflixManifestTest'
```

Its release coverage test selects both ARM variants on API 24. Version 1.0.4
reproduces the migration screen's "no build for this TV's processor" failure for
32-bit ARM. Adding the real ARM APK to the next stable release lets the existing
legacy 0.9.3 bridge retry successfully without another bridge installation.

Always preserve and securely back up `.local/jedflix-local.jks`. This is the key
used by the installed fork; losing or changing it breaks updates for those
installations. The updater compares downloaded certificates against the actual
installed app and never accepts a missing certificate. The legacy app's signing
key and the stock Stremio key are separate.

## Download and installation behavior

The updater chooses an asset from Android's supported ABI list. It requires a
higher Android version code, a matching tag/version name, the same package,
compatible minimum Android version, and the correct native libraries. Downloads
use trusted GitHub HTTPS hosts, bounded redirects and timeouts, size limits, a
private cache, and a `.part` file. A download becomes usable only after checking
its exact size, SHA-256, APK identity, version and signing certificates. Cached
files are checked again before reuse; equal file size alone is insufficient.

Android must grant Jedflix permission to install unknown apps. The Install action
opens that permission screen when needed; returning to Jedflix continues with
the verified download. Denying permission or cancelling the installer leaves the
download ready, and the same row retries. Cancel download removes the partial
file. The standard Android installer owns the final confirmation, using a
read-only FileProvider URI scoped to `cache/updates/`. Jedflix opens it with the
application context, so no updater activity or install-session callbacks exist.
After replacement, open Jedflix from the normal TV launcher.

The verified release metadata is saved before opening Android Settings. If an
install-permission change kills the app, the next start restores and revalidates
the cached APK without downloading it again.

Failures are shown distinctly from a successful check with no newer published
release. Failed checks do not advance the daily-check timestamp. Updates never
clear app data or change the app's Android package identity.

## Verification

Use an installed lower-code x86_64 build and a newer candidate. Version overrides
are for local test builds only:

```bash
JEDFLIX_VERSION_NAME=1.0.0 JEDFLIX_VERSION_CODE=34194309 ./scripts/build.sh x86_64
./scripts/dev.sh --no-build
./scripts/build.sh x86_64
python3 scripts/verify-updates.py --candidate dist/jedflix-app-1.0.4-x86_64.apk
python3 scripts/verify-updates.py --candidate dist/jedflix-app-1.0.4-x86_64.apk --phase install
# Confirm permissions and installation on the TV. Self-replacement kills instrumentation.
python3 scripts/verify-updates.py --candidate dist/jedflix-app-1.0.4-x86_64.apk --phase after
```

The separately installed test APK supplies deterministic GitHub responses to
the same downloader and validates actual signed APKs. It tests architecture,
versioning, rate limits, absent metadata, unsafe redirects, checksum corruption,
truncation, cancellation, cache reuse, wrong certificates, and provider scope.
The install phase requests the same install path as Settings → About → Install
and opens the standard Android installer.
The after phase verifies private preferences and encrypted Comet settings survive.
No test transport, fixture URLs or probe classes are packaged in Jedflix.

## Moving users from jedflix-tv

The legacy app is `com.jedflix.tv`. Version 0.9.3 and later fetch
`jedflix-app-update.json`, select the right ABI, verify the package and pinned
Jedflix signing certificate, and install `com.jedflix.stremio` alongside the old
app and stock Stremio. Android's installer owns confirmation. The bridge checks
the installed app's signature before opening it. Its package-visibility query
includes the new ID. No stock Stremio install needs to be removed.

The legacy app and the new app have separate private data. History, My List, and
Real-Debrid settings are not imported. Keep the old app until onboarding succeeds.

## Moving original fork installations

Fork versions 1.0.2 and earlier used `com.stremio.one`, so Android cannot replace
them with `com.jedflix.stremio` as an ordinary self-update. Their existing updater
is immutable and looks for `jedflix-update.json` and `jedflix-VERSION-ABI.apk` on
the latest stable release. Those compatibility assets therefore remain available.

The compatibility APK has the original package and Jedflix signing key and a
higher code. After updating, its launcher is labelled **Jedflix migration** and opens a handoff
screen. This bridge uses a plain Android Application and exposes only the handoff
activity and private APK provider; it never starts a second native core or server. That screen
reads `jedflix-app-update.json` and validates the new package, version, ABI, hash,
size, and certificate before installing the separate app. It then opens only the
correctly signed new Jedflix. The old fork stays installed and retains its private
data; Real-Debrid settings and library are not copied automatically. Users may
remove the old fork after setting up the new app, then install stock Stremio in
its freed app slot. Removal is a separate Android settings action.

Stock Stremio cannot accept the compatibility APK because its signing key differs.
The new legacy bridge never downloads or opens those compatibility artifacts.

## Coexistence details

The new package, provider authorities, compiled search UriMatcher, searchable XML,
compiled application ID, and declared signature permissions all use the separate
Jedflix identity. Release preparation rejects any stock Stremio permission or
provider references left in a separate-package APK. Upstream Java
and native class names remain intact for JNI. The private APK provider is
`com.jedflix.stremio.updates`, derived from the installed package at runtime.

Jedflix's local streaming server uses HTTP 11480 (fallback through 11484) and HTTPS
12480. Its default core settings and proxy/subtitle client use those ports too.
Stock Stremio keeps 11470/12470, so background services cannot cross-route playback
or subtitle requests. Existing custom streaming-server settings remain explicit.

Changing the package creates a separate installation. Later updates of
`com.jedflix.stremio` keep its package and signing key and preserve its settings,
Android Keystore, and library normally.

For a live GitHub update on a disposable TV, run the validation checks against a
newer candidate, then `--phase mark` to seed a real encrypted settings fixture.
After publishing, `scripts/verify-update-ui.py --expect-version VERSION` drives
Settings → About, install permission, and Android’s installer. Run `--phase after`
to verify the settings digest and private marker survived. `--compat` on the
validation helper targets the original fork and its handoff APK.

Version 1.0.3 was withdrawn to prerelease after an on-device update exposed a
stock AndroidX permission name. Version 1.0.4 fixes the declaration and usage;
coexistence verification must include updating Jedflix with stock Stremio already
installed, because a fresh install in the opposite order can hide this collision.
