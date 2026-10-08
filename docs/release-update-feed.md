# Release Update Feed Contract

Status: contract for change `app-auto-update`, PR 1 slice (feed contract + mock fixtures).
Updated 2026-09-28: signing prerequisites DECIDED (section 6) — release 0.3.2
ships signed with working updater feeds. Pre-0.3.2 builds and local/dev builds
(with an empty feed URL) stay on the mock/staging feed or disabled.
Scope: defines the single feed convention both update clients consume.

Related capability: `release-update-feed`.
Source versions at contract time: Android `0.3.0` / versionCode `300`
(`android/app/build.gradle.kts`), desktop `0.3.0`
(`desktop/package.json`, `desktop/src-tauri/tauri.conf.json`).

## 1. Feed URL pattern

One feed URL per platform build, configured as an injectable value (never hardcoded
in UI code), following the GitHub Releases download pattern with a mock/staging
override for pre-production verification.

```text
https://github.com/<owner>/<repo>/releases/download/<tag>/latest-<platform>.json
```

`<platform>` is one of `android | windows | macOS | linux`.

- Production feed URL resolves per platform: the update client uses the
  platform-specific feed URL defined by the release convention above.
- Desktop carve-out (updated 2026-10-01, updater feed fix): tauri-action
  publishes ONE `latest.json` per `desktop-v<version>` release (its `platforms`
  map covers windows/macOS/linux). That per-version file is the **historical
  record**, not the endpoint: the updater endpoint is baked into each build at
  build time, so a per-version URL can only ever report its own version and the
  app concludes "up to date" forever.
- The desktop production feed is the **stable, versionless** location
  `https://github.com/<owner>/<repo>/releases/download/desktop-latest/latest.json`,
  mirrored in `tauri.conf.json` updater endpoints and in the build-time
  `VITE_UPDATE_FEED_URL`. `desktop-latest` is a dedicated **prerelease** whose
  only asset is `latest.json`, re-uploaded with `--clobber` after every
  `desktop-v*` release by the `desktop-checksums` fan-in job in
  `release-builds.yml`.
  - `releases/latest` MUST NOT be used: android and desktop releases are both
    non-prerelease, so `latest` can resolve to the Android release, which
    carries no `latest.json`. Marking the stable feed a prerelease keeps it off
    the repository "Latest" badge and keeps `releases/latest` from resolving to
    it.
  - The stable feed's `platforms.*.url` values are **browser download URLs**
    (`releases/download/<tag>/<file>`), rewritten from the per-version file's
    GitHub API asset URLs. A plain GET on an API asset URL returns asset
    metadata (`application/json`), not the binary; a plain GET on a browser
    download URL returns the binary.
- The per-platform `latest-<platform>.json` pattern above applies to Android
  (`latest-android.json` under the `android-v<version>` tag).
- Mock feed override: a build configured with a staging/mock feed URL fetches the
  mock feed instead of the production feed. Production feed infrastructure is not
  required for the check to succeed.
- Disabled fallback: a build with an empty or disabled feed URL attempts no check,
  shows no dialog, and renders version display only.

## 2. Desktop feed JSON shape (tauri-action `latest.json` compatible)

Top-level `version`, `notes`, `pub_date`, and a per-platform `platforms` map where
each entry carries a download `url` and a cryptographic `signature`.

```json
{
  "version": "0.5.0",
  "notes": "Example release notes.",
  "pub_date": "2026-09-28T12:00:00Z",
  "channel": "stable",
  "platforms": {
    "windows-x86_64": {
      "url": "https://github.com/<owner>/<repo>/releases/download/desktop-v0.5.0/Nexo.Desktop_0.5.0_x64-setup.exe",
      "signature": "<tauri-action signature>"
    }
  }
}
```

Rules:

- Parsing succeeds only when `version`, `notes`, `pub_date`, and at least one
  `platforms` entry with both `url` and `signature` are present.
- A desktop platform entry without a `signature` field is invalid; no update is
  offered from it.
- Unknown `channel` values are ignored (no update offered). `stable` is the
  default; staging/beta channel behavior is undecided and only the field is
  reserved ([NEEDS-USER-CONFIRMATION: stable-only default]).

## 3. Android feed JSON shape (equivalent fields)

Top-level `version` (semver string), `versionCode` (integer), `notes`, `pubDate`,
`channel` (reserved), and an `assets` list with per-artifact download `url`,
ABI/arch qualifier `abi`, and file `size` in bytes.

```json
{
  "version": "0.4.0",
  "versionCode": 400,
  "notes": "Example release notes.",
  "pubDate": "2026-09-28T12:00:00Z",
  "channel": "stable",
  "assets": [
    {
      "url": "https://github.com/<owner>/<repo>/releases/download/android-v0.4.0/nexo-android-v0.4.0.apk",
      "abi": "universal",
      "size": 12345678
    }
  ]
}
```

Rules:

- Parsing succeeds only when `version`, `versionCode`, `notes`, and at least one
  asset entry with `url` are present.
- A document without a `versionCode` field is invalid; no update is offered.
- Unknown `channel` values are ignored (no update offered). `stable` is the
  default; staging/beta behavior is undecided and only the field is reserved
  ([NEEDS-USER-CONFIRMATION: stable-only default]).

## 4. Asset-naming convention

Release assets encode product, platform/target, and version. Feed URLs MUST
reference exactly these names.

- Android APK/AAB: `nexo-android-v{version}.apk` / `nexo-android-v{version}.aab`
  (example: `nexo-android-v0.4.0.apk`). Enforced by the release workflow
  (`nexo-${GITHUB_REF_NAME}.apk/.aab`).
- Desktop bundles: uploaded under tauri-action default names, derived from
  `productName` (`Nexo Desktop`) and the version. The released asset name
  replaces the space in the local bundle name with a dot (the release asset's
  `label` keeps the space), so a bundle built as
  `Nexo Desktop_0.5.0_amd64.AppImage` is published as
  `Nexo.Desktop_0.5.0_amd64.AppImage`. Published shapes:
  `Nexo.Desktop_0.5.0_x64_en-US.msi`, `Nexo.Desktop_0.5.0_x64-setup.exe`,
  `Nexo.Desktop_0.5.0_universal.dmg`, `Nexo.Desktop_0.5.0_amd64.AppImage`,
  `Nexo.Desktop_0.5.0_amd64.deb`, `Nexo.Desktop-0.5.0-1.x86_64.rpm`.
  The desktop feed (`latest.json`) is authoritative for desktop download URLs —
  clients MUST follow the feed's per-platform `url` fields, not a filename
  convention.

A release with version X publishes feed download URLs that resolve to assets whose
filenames follow the convention for that platform and version X.

## 5. Shared semantics annex

### 5.1 Version-compare rules

Update available if and only if the feed version is greater than the installed
version:

- Android: feed `versionCode` greater than installed `BuildConfig.VERSION_CODE`.
  `VERSION_NAME` is display-only.
- Desktop: feed semver greater than the single source-of-truth app version
  (desktop derives it via `getVersion()` from `@tauri-apps/api/app`).

Equal or older feed versions report no update available.

### 5.2 Check-trigger policy ([NEEDS-USER-CONFIRMATION: startup + manual; periodic deferred])

- Startup check runs once settled, at most once per launch; a dialog appears only
  if an update is available and no remind-later suppression is active.
- Manual check always runs on demand, bypasses suppression, and always reports
  its outcome (available, up-to-date, or error).
- No background periodic polling in this change.

### 5.3 Offline and metered guards ([NEEDS-USER-CONFIRMATION: auto deferred offline/metered; manual always attempts])

- Automatic (startup) checks are deferred while offline or on a metered
  connection; skipped silently and retried on the next manual request or launch.
- Manual checks are always attempted and report connectivity failures plainly
  instead of claiming up-to-date.

### 5.4 Remind-later store semantics ([NEEDS-USER-CONFIRMATION: optional updates with remind-later; interval undecided])

- Persisted record: `{ dismissedVersion, dismissedAtEpochMs }`, durable across
  restarts, keyed per dismissed version.
- Suppression applies only to the dismissed version; a newer feed version ignores
  it and prompts normally.
- Suppression expires after the shared interval constant
  `REMIND_LATER_INTERVAL_MS` (single named constant, value undecided until the
  user confirms); after expiry the dialog may appear again.

### 5.5 Error and empty states

Clients distinguish at least: up-to-date, update available, feed unreachable,
malformed feed, and feed disabled. Each state has a user-facing message and a
client MUST never present a successful up-to-date claim on error. A feed response
that fails schema validation surfaces a feed-error state with no update action.

### 5.6 Shared EN/ES string key namespace

Every update-related user-facing string ships in English and Spanish with parity
enforced. Namespace (canonical keys; platform bindings map onto these):

```text
update.check, update.checking, update.upToDate,
update.availableTitle, update.availableBody, update.notes,
update.now, update.later,
update.meteredConsent, update.installGuidance, update.relaunchConfirm,
update.errorUnreachable, update.errorMalformed, update.errorOffline
```

Coverage: check entry, checking progress, up-to-date confirmation, update
available title/body, release notes, update-now, remind-later, metered consent,
install guidance, relaunch confirmation, error states.

## 6. Signing and publishing (DECIDED 2026-09-28 — release 0.3.2 cutover)

Key material lives OUTSIDE the repo in `~/.nexo-keys/` (README.txt there
holds fingerprints + dates only, never secrets). Repo root `.gitignore` bans
`*.key`, `*.jks`, `*.keystore`, `.nexo-keys/` belt-and-braces.

Reference: `.github/workflows/release-builds.yml` — desktop builds are
updater-signed via `TAURI_SIGNING_PRIVATE_KEY`, Android release builds use
`signingConfigs.release` decoded from `ANDROID_KEYSTORE_BASE64`.

Decided work package (producer: `release-builds.yml`):

1. Android release key. RSA-4096 keystore, alias `nextpage-release`, created
   2026-09-28, valid 2026-09-28 → 2056-09-20. This is the FIRST release key —
   keep the `~/.nexo-keys/` backup forever; a future rotation keeps this
   key alongside the new one.
   SHA-256 cert fingerprint:
   `E7:30:AE:35:71:9C:3A:45:D4:F9:78:7C:D6:2E:1F:87:42:4D:A2:FC:A8:0E:34:96:25:D9:29:07:D5:6D:53:BB`
   SHA-1 cert fingerprint:
   `3E:0A:F2:0A:D7:01:C0:B8:4A:72:86:3D:16:D0:7D:A4:F3:F5:E9:43`
   (register the SHA-1 in the Google Cloud "Nexo Android" OAuth client
   alongside the debug fingerprint). CI secrets: `ANDROID_KEYSTORE_BASE64`,
   `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
   (PKCS12 forces store and key passwords equal — both secrets hold the same
   value, kept separate for Gradle compatibility). The android job fails loud
   when any of them is missing so a release never ships debug-signed.
2. Tauri updater keypair. Minisign keypair generated 2026-09-28; the public
   key is published in `desktop/src-tauri/tauri.conf.json`
   (`plugins.updater.pubkey`):
   `dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IEE4OUREODBFNDZFMkNDQTIKUldTaXpPSkdEdGlkcUhCNlpscmZuZHZtYVhoY2JOOUUzMmxpY2lyT095M0FTMGpvZEt4bHVneloK`
   CI secrets: `TAURI_SIGNING_PRIVATE_KEY`,
   `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`. The desktop job signs every bundle
   with them; without them the build now fails (real pubkey in config).
   Note: this authenticates UPDATES, not the OS publisher — Windows
   SmartScreen / macOS Gatekeeper warnings remain a separate work item.
3. Feed publishing. Desktop: tauri-action auto-publishes one per-version
   `latest.json` per `desktop-v*` release (`uploadUpdaterJson: true`, set
   explicitly). The `desktop-checksums` fan-in job then copies that file to the
   stable `desktop-latest` prerelease as `latest.json` (`--clobber`),
   rewriting its `platforms.*.url` values from API asset URLs to browser
   download URLs (`scripts/release/publish-desktop-updater-feed.sh`) and keeping
   every signature byte-identical. The copy runs only after all three matrix
   legs succeed, so the stable feed can never be published missing a platform.
   Android: the explicit `latest-android.json` publish step (section 3 shape,
   `versionCode` mirroring the major*10000+minor*100+patch formula) uploads
   alongside the signed APK/AAB.
4. Forward-only cutover. The endpoint is baked at build time, so no build
   shipped before the stable feed existed can benefit: a 0.3.2 or 0.4.0 install
   will never auto-update and must be re-downloaded by hand once. Updates work
   automatically **from the first build that ships the stable endpoint onward**.
   `release-builds.yml` prepends this warning to the desktop release notes.

Production cutover rule: 0.3.2+ release builds run against the production
feeds (`VITE_UPDATE_FEED_URL` baked at release time for desktop,
`-PupdateFeedUrl` for Android). Pre-0.3.2 builds and local/dev builds (empty
feed URL) stay on the mock/staging feed or disabled.

Post-0.3.2 verification (open): confirm the published `latest.json`
contains all three platform entries — the windows/macOS/linux matrix legs
each upload it, and it is only a complete feed if the action merges (rather
than last-writer-wins). If incomplete, add a merge fan-in step.

## 7. Mock/staging fixtures

Static per-platform fixtures, schema-valid against the production shapes, usable
by tests and manual verification without network access to GitHub Releases:

- `mocks/update-feed/android-latest.json` — Android shape (section 3).
- `mocks/update-feed/desktop-latest.json` — desktop shape (section 2).

Mock fixture validation passes for every required field of the contracted shape.
Mock `signature` values are clearly labeled placeholders, not real signatures.
