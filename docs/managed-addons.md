# Fixed TV content sources

The managed build uses exactly two preconfigured addons:

| Addon | Purpose | Endpoint |
| --- | --- | --- |
| Cinemeta | Movie/series browsing, search, title and episode metadata | `https://v3-cinemeta.strem.io/manifest.json` |
| Comet | Movie/episode stream discovery | `https://comet.elfhosted.com/manifest.json` |

The owner-controlled manifest snapshots are in `assets/managed-addons.json`.
Cinemeta's `addon_catalog` resource and addon-directory listings are removed from
the snapshot. Both sources are marked protected and their generic configuration
controls are disabled. No addon installation or removal controls are exposed to
users. The managed Comet source has a dedicated Settings → Real-Debrid section.

## Enforcement

`scripts/addon-lock-patch.py` applies several layers to the pinned upstream APK:

- Removes the Addons navigation tab, even while profile state is loading.
- Disables the upstream announcement feed, promotion queue, install callback,
  and Home announcement dialog, including seasonal addon advertisements. This
  does not depend on dismissing a campaign or on per-install preferences.
- Replaces playback/discovery messages that tell users to install addons with
  content availability messages in XML resources and compiled translations.
  These fork-specific replacements currently use English in every locale.
- Disables addon-management permissions in the profile models.
- Replaces addon-management navigation destinations with Home and removes their
  graph deep links and dedicated manifest intent filters. Android may still
  launch the app for these URLs through its other broad `stremio:` filters, but
  the URLs cannot open an installation/removal screen.
- Rejects install, uninstall, upgrade, sync, and Trakt-install facade operations.
- Rejects addon mutations and addon API imports/exports at the dispatch boundary
  before JNI, including operations affecting individual profiles or all profiles.
- Replaces the profile's addon list with the fixed snapshots whenever native
  profile storage is read or written. This provisions new guest profiles and
  migrates existing ones without rewriting library records or player preferences.

The fixed list is packaged with the app. Changing it is a build/owner operation;
users cannot add manifest URLs, remove providers, or replace the managed list
through account addon synchronization.

## Real-Debrid settings

Settings → Real-Debrid appears between General and Subtitles. Users can enter a
Real-Debrid API key in a masked editor and set results per resolution, maximum file size,
maximum resolution (all, 1080p, or 720p), cached-only streams, release filtering,
and duplicate filtering. Zero result/size limits mean unlimited. Configuration is
restricted to the owner-selected hosted Comet instance; it cannot change the
source list or point at another server.

The page reuses the app's existing Compose sections, rows, menus, and counters.
The account editor and removal confirmation use the same themed `ModalRow` as
other settings. Key entry reuses the stock text-input row with password masking
and a password keyboard. Results per resolution uses `CounterRow`; maximum file
size uses the same `Counter` with 1 GB steps, retaining fractional values already
saved. Zero means unlimited. There are no numeric keyboard dialogs. Validation
progress/errors appear inline. Draft changes remain in composition state until
Save. General no longer displays the upstream Stremio Account section.
The Real-Debrid counter values reserve 110 dp and stay on one line, including
"Unlimited" and GB units. The account modal labels its masked field "API key"
to fit the available space. QR setup also uses the themed Compose modal;
its image is generated locally, and key receipt appears inline without an
additional Android alert.

**Set up with your phone → Scan QR code** uses the same one-time transfer service
as the previous `jedflix-tv` app. Scan the QR with your phone, paste the key on
`https://borseth.ddns.net/tv/rd/:code`, and choose **Send to TV**. The received key
fills the masked settings draft without changing the other stream options. Choose
**Save and restart** to persist and apply it. The web page does not automatically
read a saved Jedflix account key, and this transfer is not account sign-in.

The TV generates a fresh 256-bit random code, opens the existing HTTPS
`/backend/api/v1/tv/rd-key/:code` slot, then long-polls for the one-time response.
The QR contains the phone-page URL, never the debrid key. Codes expire after ten
minutes; expired codes and connection failures offer instructions to close and
retry with a new QR. Closing the dialog or backgrounding/destroying the activity
cancels polling and ignores late callbacks. The QR dialog disables screenshots;
keys and pairing URLs are not logged. QR images are generated locally with
checksum-pinned ZXing Core 3.5.3; its Apache 2.0 license is bundled in the APK.
The UI regression check compares alignment and text height with General, walks
every row using the D-pad, checks focus after closing the key editor and selecting
a resolution, and verifies that switching an option does not move the layout.

The **Real-Debrid account** row displays **Authenticated as username**, using
account data cached alongside the key in the same encrypted file. It opens the
masked key editor when selected. **Save and restart** checks new or changed keys
against `GET https://api.real-debrid.com/rest/1.0/user` with a Bearer token before
saving anything. Only the username and validation timestamp are retained; the
email and other profile fields returned by Real-Debrid are discarded. The cache
is reused when opening Settings, launching the app, and saving stream options.
It represents the last successful validation; account changes or later key
revocation are not automatically refreshed. Keys saved before this feature show
**Save to verify account** until the next save. A draft replacement key hides the
old account name until validated. Failed validation or cancellation preserves the
previous saved configuration. Removing the key also removes the cached identity.

Save and restart Jedflix persists the configuration and restarts the app because
the native core reads its managed source list at startup. Remove Real-Debrid key
requires confirmation, clears the Comet configuration, and restarts. A completed
empty stream result has a Real-Debrid settings button that opens the same tab.
The button is hidden while requests are loading or streams are available.

The hosted instance's current `debridServices` format is used, with resolution
keys such as `r2160p` and file-size limits in bytes. Credentials are encrypted with
an Android Keystore AES-GCM key in the app's no-backup directory. The native
profile is encrypted too, because the Comet transport URL embeds its key.
Unrelated library storage is unaffected. The key editor disables screenshots and does
not save the password in Android view state. Saving checks input syntax and
ranges, and validates new or changed keys with Real-Debrid. Validation establishes
account access, not a paid subscription or successful Comet playback.

## Build and verification

```bash
# First build the standard rebrand once, if this checkout lacks .local/decoded:
./scripts/build.sh
python3 scripts/spike-build.py --lock-addons
python3 scripts/verify-addon-lock.py

# Optional fresh-install verification in a separate temporary Android package:
python3 scripts/spike-build.py --lock-addons --fresh-test
python3 scripts/verify-addon-lock.py --fresh-test
# Comet configuration, encryption, empty-state, and process-restart checks:
python3 scripts/verify-addon-lock.py --fresh-test --comet-test
# Compare layout to General and verify D-pad traversal and editing focus:
python3 scripts/verify-comet-settings-ui.py
# One-time QR transfer, response validation, and cancellation with synthetic keys:
python3 scripts/verify-addon-lock.py --fresh-test --qr-test
# Account API responses, identity cache binding, encryption, and cancellation:
python3 scripts/verify-addon-lock.py --fresh-test --account-test
```

Output: `dist/jedflix-managed-addons.apk`. It updates this workspace's locally
signed `com.stremio.one` installation. The old `com.jedflix.tv` app remains separate.
The normal `./scripts/build.sh` command also applies this policy and copies the
managed build to `dist/jedflix-android-tv-1.10.4-arm64.apk`; both distribution APKs
use the same owner-managed sources and guest-based prototype.
The verification script installs a separate instrumentation APK and removes that
test APK afterward; the fresh-install test also removes its isolated app package.

Verified on `JedFlix_Stream_Test` on 2026-10-24:

- 32 instrumentation checks pass for both an upgrade and a clean installation.
- The actual native installed-source catalog contains exactly Cinemeta and Comet,
  before and after rejected mutation attempts.
- Direct and per-profile install/remove operations are rejected; native all-profile
  mutations, addon upgrade, and remote addon import/export are blocked.
- Existing settings and unrelated library storage are preserved by normalization.
- Navigation contains Search, Home, Discover, Library, and Settings, with no
  Addons tab. Addon/manifest links cannot open addon management.
- The retained player rendered the generated H.264/AAC clip; pause/resume worked.
  All 25 native libraries are unchanged from the pinned upstream APK.
- Adding a title to the guest library and reopening it after a cold restart
  retained the saved title.

The instrumentation also seeds a synthetic addon promotion and verifies that it
cannot become visible or invoke an install callback, independent of the live feed.
Test output and emulator evidence are in ignored `.local/addon-lock-*` files.

Promotion suppression was verified on 2026-10-30 with 35 instrumentation checks
on the emulator. A cold launch reached Home without the seasonal promotion.
The updated build and UI logs are in `.local/addon-promotion-*`.

Real-Debrid configuration was verified on 2026-10-30: 52 isolated configuration,
encryption, empty-state, and policy checks passed before restart; 41 checks passed
after restart. An external UI test clicked the real Save button, observed a new
main app process automatically resume, and verified the persisted configuration.
The restart uses a private foreground handoff activity in a separate process,
since Android 16 blocks the attempted background alarm launch. The handoff skips
native initialization and is not exported to other apps. The instrumentation
runner stays outside the app during the restart check so its own shutdown cannot
force-stop the handoff process. A real empty result also showed the shortcut and
opened the correct settings tab. Logs are in `.local/comet-settings-*`.

QR setup was verified on 2026-10-30 with 27 checks in the disposable test package.
The test decodes the displayed QR bitmap, submits a synthetic key to the live
Jedflix service, and verifies that the actual settings draft receives it while
preserving stream options and leaving encrypted storage untouched until Save.
It also checks pending/expired responses, malformed/oversized data, server errors,
and cancellation during long-polling and activity backgrounding. Logs are in
`.local/rd-qr-test.log` and `.local/addon-lock-instrumentation-fresh-qr.txt`.
The existing 52 configuration/policy checks, automatic save/restart, and 41
post-restart checks also passed. D-pad navigation opens the QR row and restores
its focus on dismissal; the other settings rows retain their existing behavior.

Account validation/cache checks passed on 2026-10-30: 29 checks cover mocked
Real-Debrid responses, Bearer authentication, response validation, encrypted
username/key storage, cache reuse, stale-identity removal, and cancellation.
The save/restart regression also passed, with 52 checks before restart and 42
afterward, including cached username persistence. No paid Real-Debrid credentials
were used. Evidence is in `.local/rd-account-test.log` and
`.local/rd-account-settings-regression.log`.

## Remaining account integration

This is still the guest-based prototype from the feasibility test. The Comet
snapshot uses its public hosted endpoint with no account credentials in the APK.
Real-Debrid configuration is entered manually or transferred by QR; Jedflix device
authentication and automatic account provisioning are not implemented.
Debrid-backed Comet playback has not been verified with a live paid account.
The remaining account bridge is described in
[the feasibility report](feasibility.md).

The existing player/native libraries are retained. Local test-clip playback is
separate evidence from successful playback through a configured Comet account.

Provider references: [Cinemeta manifest](https://v3-cinemeta.strem.io/manifest.json),
[Comet project](https://github.com/g0ldyy/comet),
[Real-Debrid user API](https://api.real-debrid.com/),
[Stremio addon protocol](https://stremio.github.io/stremio-addon-sdk/protocol.html).
