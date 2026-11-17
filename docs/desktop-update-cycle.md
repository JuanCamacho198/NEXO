# Proving the desktop update cycle

Audit reference: `odd/tasks/desktop-security-checklist-audit.md` item #17 ("Updater path not proven end to end"). This document is the procedure. **It has not been executed.** No 0.7.0 → 0.7.1 cycle has ever been run, and the macOS half is blocked on certificates (see "The macOS trap").

Read `docs/release-update-feed.md` first for the feed contract. This file is the operator procedure, not the contract.

## The feed, in one paragraph

The updater endpoint is baked into the binary **at build time** from `VITE_UPDATE_FEED_URL` (`release-builds.yml`, build step) and duplicated in `plugins.updater.endpoints`. Both point at the versionless location:

```
https://github.com/JuanCamacho198/NEXO/releases/download/desktop-latest/latest.json
```

`desktop-latest` is a dedicated **prerelease** whose only asset is `latest.json`, re-uploaded with `--clobber` by the `desktop-checksums` fan-in job after every `desktop-v*` release, with its `platforms.*.url` values rewritten from API asset URLs to browser download URLs. The per-version `latest.json` attached to `desktop-v0.7.1` is a historical record and is **never** an endpoint.

That is the whole reason the versionless URL exists: an installed build's endpoint is frozen, so a per-version URL can only ever report its own version — the app would answer "you are up to date" forever.

## What the app actually does

Worth knowing before testing, because it shapes what you should see:

1. **Startup check.** `App.svelte` schedules `runStartupUpdateCheck` after first paint (`scheduleAfterFirstPaint`), once per launch, guarded by a module-level flag. On `status === 'available'` it pushes an **info toast**: `update.availableToast` with the feed version. It does not prompt.
2. **Manual check** lives in Settings → About (`SettingsAboutTab.svelte`): "Check", then an "Update available" panel with version, notes, and **Update now** / **Later**.
3. **Update now** calls `performPluginUpdateNow` → `plugin-updater.check()` again (not the earlier result), then `downloadAndInstall` with progress events (`Started` gives `contentLength`, `Progress` accumulates `chunkLength`), then `relaunchApp()` → `plugin-process.relaunch()`.
4. **Later** records a suppression for `REMIND_LATER_INTERVAL_MS` = **7 days** (`updateSuppression.ts`) in localStorage. If today's test says "no toast", check the suppression record before blaming the feed — clicking "Later" during a previous test silences the startup toast for a week.
5. Empty or missing `VITE_UPDATE_FEED_URL` means the check is skipped entirely (`feedUrl.trim().length === 0` → no fetch, no dialog, version display only). A local dev build therefore proves nothing about the release path.

## Prerequisites

- **Version numbers are not yours to change for the test.** The audit's frozen constraint holds: do not bump `desktop/package.json`, `desktop/src-tauri/tauri.conf.json`, or `tauri.conf.json`'s `version`. The cycle is 0.7.0 → 0.7.1 and it happens through release-please on `main`, exactly like a real release.
- The installed baseline must be built **the same way** releases are — tagged, via CI. A locally built `.dmg`/`.msi` has an empty or dev feed URL and will never see an update. This is the single most common false negative in this procedure.
- A machine you can wipe, or at least uninstall from, since the baseline must be a clean install of 0.7.0.
- Shell access to watch logs (Windows: `Get-Content`; macOS: `log stream`), see "How to watch the updater".

## Windows procedure (0.7.0 → 0.7.1)

### 1. Install the baseline

1. On a clean Windows VM, download `Nexo.Desktop_0.7.0_x64-setup.exe` from the **`desktop-v0.7.0` release page** — not from a local build.
2. Install, launch, and open Settings → About. It must read **0.7.0**. This is the control: if the installed baseline is not 0.7.0, every later step measures nothing.

### 2. Confirm the baseline's endpoint is the versionless feed

```powershell
# The baked-in string is greppable inside the shipped JS/asar payload.
# From the install dir; adjust if you installed per-user.
$root = "$env:LOCALAPPDATA\Nexo Desktop"
Select-String -Path "$root\**\*.js" -Pattern 'desktop-latest/latest.json' -SimpleMatch
```

Expected: at least one hit. If it is absent, this build was not produced with `VITE_UPDATE_FEED_URL` set and the whole test is invalid — stop and rebuild through CI.

### 3. Confirm the live feed reports 0.7.0 *before* 0.7.1 exists

```powershell
$feed = Invoke-RestMethod 'https://github.com/JuanCamacho198/NEXO/releases/download/desktop-latest/latest.json'
$feed.version
$feed.platforms.'windows-x86_64'.url
```

Expected: `0.7.0`, and a URL shaped `releases/download/desktop-v0.7.0/Nexo.Desktop_0.7.0_x64-setup.exe`. If the URL is an `api.github.com` asset URL instead, the fan-in rewrite did not run and the updater will receive JSON metadata where it expects a binary — that is a feed bug, not an updater bug.

### 4. Launch 0.7.0 and confirm the "up to date" behaviour

Launch, wait ~10 s, open Settings → About, click **Check**.

Expected: **"You are up to date"** (`update.upToDate`). No toast on startup either — the feed says 0.7.0 and 0.7.0 is installed.

This is the step that proves the *absence* of an update is detected correctly. A client that always claims an update is available is as broken as one that never does, and step 6 depends on this baseline being trustworthy.

### 5. Release 0.7.1

Through the normal pipeline, never by hand: merge release-please's `desktop-v0.7.1` PR to `main`. That creates the tag; `release-builds.yml` builds three legs; the `desktop-checksums` fan-in publishes `SHA256SUMS.txt` and re-uploads `latest.json` to `desktop-latest`.

Do not proceed until the fan-in job is green and:

```powershell
$feed = Invoke-RestMethod 'https://github.com/JuanCamacho198/NEXO/releases/download/desktop-latest/latest.json'
$feed.version                                    # must now be 0.7.1
$feed.platforms.PSObject.Properties.Name         # windows-x86_64, darwin-*, linux-*
```

All three platform families must be present. A feed with only `windows-x86_64` means the matrix legs clobbered each other instead of merging — that is the open post-0.3.2 verification item in the task file, and it fails here, loudly, before you touch the client.

### 6. Take the update on the already-running 0.7.0 client

Leave 0.7.0 installed. Relaunch it (a **fresh launch**, because the startup check runs once per launch and a suppression may be active — see step 7 if no toast appears).

Check, in order:

1. **Toast appears.** Within the scheduler's post-first-paint delay, an info toast reads the version from `update.availableToast`. If the feed says 0.7.1 and no toast appears → check the suppression record (step 7).
2. **Manual check agrees.** Settings → About → **Check** shows the "Update available" panel with 0.7.1 and the release notes.
3. **Download completes.** Click **Update now**. The panel shows download progress; the button disables. Expected: progress advances to completion.
4. **Signature verifies.** The updater validates the `.sig` against `plugins.updater.pubkey` **before** installing. Wrong key or tampered payload = a spurious-looking `FeedUnreachableError` / install error, not a silent install. If it fails here, the payload and the key came from different update keypairs.
5. **Relaunch prompt.** After install the panel asks `update.relaunchConfirm` ("The update is installed. Relaunch now?").
6. **Relaunch lands on 0.7.1.** Click **Yes**. The app relaunches; Settings → About must read **0.7.1**, and the startup check on that fresh launch must report up to date.

That is the pass condition: **toast, download, signature, relaunch, and a new version number in the About tab.** Anything less is not a proven cycle.

### 7. Diagnosing "no toast"

In this order:

- **Was it suppressed?** `localStorage['<key from updateSuppression.ts>']` — a `remindLater` within 7 days silences the startup toast. Use Settings → About → Check to bypass it, or clear the entry.
- **Was the feed URL baked?** Step 2. Local builds always fail here and it is not a bug.
- **Was the feed fetchable?** An unreachable feed is surfaced as `update.errorUnreachable`, offline as `update.errorOffline`, malformed JSON as `update.errorMalformed`. Each names a different layer: offline = network, unreachable = 404/CORS/URL, malformed = the feed's shape.
- **Does the feed advertise a *greater* semver?** Equal or older reports no update by design. Confirm 0.7.1 is really there (step 5).

## macOS procedure (0.7.0 → 0.7.1)

Identical shape — install clean 0.7.0 `.dmg`, confirm `desktop-latest/latest.json` reports 0.7.0, release 0.7.1, then check toast → download → signature → relaunch → About reads 0.7.1. Platform differences:

- The updater installs `Nexo.Desktop_0.7.1_universal.app.tar.gz`, not the `.dmg`. The `.dmg` is for humans; the `.tar.gz` is for `plugin-updater`.
- Progress and log checks:

  ```bash
  log stream --predicate 'process == "Nexo Desktop"' --level debug
  ```

- Confirm the baked endpoint the same way:

  ```bash
  grep -r 'desktop-latest/latest.json' "/Applications/Nexo Desktop.app/Contents/Resources/"
  ```

### The macOS trap — read this before scheduling the macOS half

**The macOS updater cannot install an unsigned bundle.** `plugin-updater` hands the downloaded `.app` to the system, and macOS refuses to replace a running application with one that fails signature validation. The failure is not a nice error dialog; it typically surfaces as a generic install failure or the app relaunching on the *old* version with no visible explanation.

Consequences:

- **The macOS side of this document is blocked until `docs/desktop-code-signing.md` is done.** Not "degraded" — blocked. There is no unsigned workaround: an ad-hoc signature (`codesign -s -`) satisfies Gatekeeper locally but is not stable across builds, so the updater's validation of a *replacement* bundle fails.
- **Windows has no equivalent hard block** — the updater installs fine, it is only SmartScreen that complains about the download. So the Windows cycle can be proven **today**, and should be proven first: it validates the feed, the merge, the signature chain of the *updater manifest*, and `relaunch()` — everything except the OS code signature.
- Expect a second trap after signing starts: the updater validates that the new bundle's **Team ID and signing identity match the installed one**. Signing 0.7.0 with nothing and 0.7.1 with a Developer ID produces two bundles the OS considers unrelated, and the update is refused. **The first signed release is a new baseline** — updates can only be proven from a signed 0.7.x to a signed 0.7.y.

## Worked example: what a failure tells you

| Symptom | What it means | Where to look |
| --- | --- | --- |
| No toast, About says up to date, but the feed reports a newer version | The installed binary's endpoint is not the versionless feed (built without `VITE_UPDATE_FEED_URL`, or an old per-version endpoint) | Step 2 grep; the build step's env block |
| `update.errorMalformed` | Feed JSON parses but is not the expected shape | `latest.json` fields — `version`, `platforms`, each `url` + `signature` |
| `update.errorUnreachable` | 404, or URLs are API asset URLs instead of browser download URLs | The fan-in rewrite (`scripts/release/publish-desktop-updater-feed.sh`); a plain GET on an API asset URL returns JSON, not a binary |
| `update.errorOffline` | Network, proxy, or the machine really is offline | Nothing app-side |
| Progress starts then fails on install | Signature validation failed: payload and `plugins.updater.pubkey` disagree | The update keypair used by `TAURI_SIGNING_PRIVATE_KEY`; `.sig` asset present on the release |
| Installed, prompt shown, **relaunch returns to the old version** (macOS) | Bundle not signed, or signed with a different identity than the installed one | `docs/desktop-code-signing.md`; `codesign -dv --verbose=4` on both bundles |
| Toast appears, immediate manual check says up to date | The feed changed between the two calls (fan-in republished mid-test) | Re-run against a settled feed |
| Works on Windows, macOS never sees the update | The feed's `darwin-*` keys are missing — matrix legs clobbered instead of merged | `latest.json` `platforms` key list (step 5) |

## Status

| Half | Can it be run today? | Why |
| --- | --- | --- |
| Windows 0.7.0 → 0.7.1 | **Yes**, once a real 0.7.1 release exists | Windows installs unsigned updates; only SmartScreen complains |
| macOS 0.7.0 → 0.7.1 | **No** | Needs a Developer ID Application certificate and notarisation — `docs/desktop-code-signing.md` operator actions 1–3. And the baseline must itself be a *signed* build |

Neither half has been run. Do not mark audit #17 closed on the strength of this document; it closes when a human has walked one of these procedures clean and recorded the result.
