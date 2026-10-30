# Jedflix updates and legacy migration

Settings → About identifies the app as a Jedflix fork of Stremio, shows the
installed Jedflix version, credits Stremio, and offers manual update checks and
the updater. The first MainActivity resume checks GitHub after eight seconds,
at most once per process and once per day after a successful check. A newer
release offers View update / Later. Dismissal applies to that version code;
manual checks always remain available.

## Release contract

The source is the latest **stable published release** of
`JedBorseth/jedflix-tv-stremio`. Drafts and prereleases are excluded. Each release
must have these assets:

- `jedflix-update.json`
- `jedflix-VERSION-arm64-v8a.apk` for physical ARM64 TVs
- `jedflix-VERSION-x86_64.apk` for the shared Windows emulator

Tags use `vMAJOR.MINOR.PATCH`. `assets/update-config.json` owns the Jedflix
version name, integer version code, and repository. The code must increase for
every distributed release, be identical across architectures, and exceed both
original Stremio codes (maximum 34194308). This prevents accidentally downgrading
the x86_64 upstream build when switching to Jedflix versioning. Upstream Stremio
1.10.4 is recorded separately from the Jedflix version.

To prepare a release:

1. Increment `versionName` and `versionCode` in `assets/update-config.json`.
2. Build both architectures sequentially (the build staging directory is shared):

   ```bash
   ./scripts/build.sh x86_64
   ./scripts/build.sh arm64
   ```

3. Generate the release files, substituting the configured version:

   ```bash
   python3 scripts/prepare-update.py \
     dist/jedflix-1.0.1-arm64-v8a.apk \
     dist/jedflix-1.0.1-x86_64.apk
   ```

   Optionally pass `--notes path/to/plain-text-notes.txt`. The helper verifies
   signatures, alignment, package identity, ABI, version consistency and the
   install permission. It writes `dist/jedflix-update.json` with exact file sizes
   and SHA-256 hashes. One ABI is allowed for local testing, but production
   releases should contain both. Unsupported devices receive an explicit error.

4. Push the source and create a **draft** release tagged with that version.
   Upload the manifest and both versioned APKs, then publish the complete release.
   Pushing source alone does not make an update available. Do not replace assets
   on an existing release: publish a higher version code for corrections.

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

Android must grant Jedflix permission to install unknown apps. Returning after
granting permission continues with the verified download; denying permission or
cancelling the installer leaves it available to retry. Closing the updater
cancels an active download and removes its partial file. The standard Android
installer owns the final confirmation, using a read-only FileProvider URI scoped
to `cache/updates/`. No install-session callbacks or alternate launcher activity
are registered. After replacement, open Jedflix from the normal TV launcher.

The verified release metadata is saved before opening Android Settings. If an
install-permission change kills the app, reopening Manage updates restores and
revalidates the cached APK without downloading it again.

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
python3 scripts/verify-updates.py --candidate dist/jedflix-1.0.1-x86_64.apk
python3 scripts/verify-updates.py --phase install
# Confirm permissions and installation on the TV. Self-replacement kills instrumentation.
python3 scripts/verify-updates.py --phase after
```

The separately installed test APK supplies deterministic GitHub responses to
the same downloader and validates actual signed APKs. It tests architecture,
versioning, rate limits, absent metadata, unsafe redirects, checksum corruption,
truncation, cancellation, cache reuse, wrong certificates, and provider scope.
The install phase opens the real updater activity and standard Android installer.
The after phase verifies private preferences and encrypted Comet settings survive.
No test transport, fixture URLs or probe classes are packaged in Jedflix.

## Moving users from jedflix-tv

The inspected legacy code is at commit
`fab4d75c1f97171311e2c9f55cf987301aabcd78` in
[jedflix-tv](https://github.com/JedBorseth/jedflix-tv/tree/fab4d75c1f97171311e2c9f55cf987301aabcd78/app/src/main/java/com/jedflix/tv/data/update).
It fetches its own latest GitHub release, compares version labels, picks an APK,
downloads it and opens the Android installer. It switched away from install
sessions after Google TV launcher/resume problems. It caches by file size and
checks a hardcoded certificate for the installed app rather than validating the
downloaded APK before opening the installer. Those assumptions are improved in
this fork's updater.

**A direct replacement of the old Android package is not supported.** Legacy
Jedflix is `com.jedflix.tv`; the Stremio fork is `com.stremio.one`. Keep the new
package unchanged to preserve its compiled/native integrations. Android treats
these as two independent apps, even if their labels match. See Android's
[app-signing documentation](https://developer.android.com/studio/publish/app-signing).

The safe rollout is:

1. Publish and verify this fork's stable release, including an actual update
   from a lower version code to a higher version code on a physical ARM64 TV.
2. Ship a bridge update **of the old app**, using the old package ID and its
   existing signing key. Its current updater must continue finding a legacy
   bridge APK in the old repository. Do not simply publish this fork's APK as
   the old app's next self-update.
3. In that bridge, offer **Move to the new Jedflix**. Fetch this repository's
   release manifest, select the right ABI, validate the new package and a pinned
   fork signing certificate, install it alongside the old app, and provide an
   Open new Jedflix action. Android 11+ package visibility needs a query for
   `com.stremio.one`.
4. Keep the old app until installation and onboarding succeed. Offer removal
   only as a separate user action. The new app currently has no importer for the
   old app's account, library/history or Real-Debrid settings; importing them
   requires an explicit export/import bridge. Android does not share app-private
   storage merely because labels or owners match.

No legacy releases, manifests or user installations are redirected by this work.
