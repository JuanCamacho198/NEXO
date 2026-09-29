# Release Update Feed Contract

Status: contract for change `app-auto-update`, PR 1 slice (feed contract + mock fixtures).
Scope: defines the single feed convention both update clients consume. No client code
in this slice. Clients MUST run against the mock/staging feed until the signing
prerequisites (blocked-until-decided) land.

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
  "version": "0.4.0",
  "notes": "Example release notes.",
  "pub_date": "2026-09-28T12:00:00Z",
  "channel": "stable",
  "platforms": {
    "windows-x86_64": {
      "url": "https://github.com/<owner>/<repo>/releases/download/v0.4.0/nextpage-desktop-v0.4.0-windows-x86_64.exe",
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
      "url": "https://github.com/<owner>/<repo>/releases/download/v0.4.0/nextpage-android-v0.4.0.apk",
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

- Android APK/AAB: `nextpage-android-v{version}.apk` / `nextpage-android-v{version}.aab`
  (example: `nextpage-android-v0.4.0.apk`).
- Desktop bundles: `nextpage-desktop-v{version}-{target}.{ext}`
  (examples: `nextpage-desktop-v0.4.0-windows-x86_64.exe`,
  `nextpage-desktop-v0.4.0-macos-universal.dmg`,
  `nextpage-desktop-v0.4.0-linux-x86_64.AppImage`).

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

## 6. Signing and publishing prerequisites (blocked-until-decided)

Production cutover is BLOCKED until the signing prerequisites are decided. Until
then clients MUST run against the mock/staging feed. No keys, keystores, or CI
secret values are created by this slice.

Reference (read-only): `.github/workflows/release-builds.yml` — current state is
unsigned desktop builds (no `TAURI_SIGNING_PRIVATE_KEY`) and debug-keystore
signed Android release builds (`signingConfig = signingConfigs.getByName("debug")`
in `android/app/build.gradle.kts`).

Blocked work package:

1. Android release-key continuity/rotation decision. No production Android feed
   until decided; rotating signing keys later requires keeping the first key,
   so this is not rushed.
2. Tauri updater keypair generation, `TAURI_SIGNING_PRIVATE_KEY` CI secret
   provisioning, and published public key for the updater config. The desktop
   updater path is nonfunctional in production until present.
3. CI publishes one `latest-<platform>.json` per platform alongside the signed
   assets after signing, matching the contracted shapes in sections 2-3.

Production cutover rule: given no Android release keystore decision or no Tauri
updater keypair/CI secret, a production feed publication is treated as blocked
and clients remain on the mock/staging feed.

## 7. Mock/staging fixtures

Static per-platform fixtures, schema-valid against the production shapes, usable
by tests and manual verification without network access to GitHub Releases:

- `mocks/update-feed/android-latest.json` — Android shape (section 3).
- `mocks/update-feed/desktop-latest.json` — desktop shape (section 2).

Mock fixture validation passes for every required field of the contracted shape.
Mock `signature` values are clearly labeled placeholders, not real signatures.
