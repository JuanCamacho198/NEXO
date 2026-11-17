# Desktop offline capabilities — what was measured, what was not

The audit marked offline behaviour PARTIAL because degradation was inferred,
never measured. This document records what each network surface does with the
network off, and how each verdict was reached.

Honesty boundary: this host cannot cut the network, so nothing below was
observed at runtime with the interface down. What is provable statically is a
named guard in the code — an explicit `navigator.onLine` check, an explicit
timeout, or an explicit offline UI branch. Each row states which one applies.
The "needs airplane-mode" section lists what static reading cannot settle.

## Method

For each surface, read the call path and answer three questions:

1. Does it check `navigator.onLine === false` before doing work?
2. If not, does the request have a timeout, or does it wait for an OS-level
   network failure?
3. What does the UI render — an offline state, a generic error, or a spinner
   that has no exit?

## Capability table

| Feature | Works offline | Degrades how | Verified how |
| --- | --- | --- | --- |
| Library (local books, shelf, counts) | **Yes** | Nothing — every read is a SQLite command over IPC | Static: `SafeCover.svelte` is the only remote touchpoint and its `<img>` has an `onerror` → initial-letter fallback; covers already on disk come through the asset protocol |
| Reading a downloaded book (EPUB render, fonts) | **Yes** | Nothing — chapters and fonts are local asset URLs | Static: `useEpubRender.svelte.ts` `probeMissingFontUrls` HEAD-probes `asset:` URLs only; a failure just drops the `@font-face` rule |
| Highlights, bookmarks, notes | **Yes** (local write) | Writes land in SQLite; sync is deferred | Static: `SyncOutboxDao.ts` is the local outbox; `SyncService.flushWithGate` returns early when offline |
| Sync (progress, bookmarks, highlights, dictionary, catalog) | **No** — degrades cleanly | Rows queue in the outbox and flush on the next online interval; nothing is lost | Static: `SyncService.ts:437` (online event) and `:470` (`flushWithGate`) both early-return on `navigator.onLine === false`, and the 30 s interval calls the same gate |
| Device presence heartbeat | **No** — degrades cleanly (fixed in this task) | Tick is skipped while offline, resumes on the next tick | Test: `useDeviceHeartbeat.offline.test.ts` proves no client read and no call while offline, and a resumed call once `onLine` flips (RED observed before the guard) |
| Discover rails (Gutendex, Open Library, Google Books) | **No** — explicit offline state | Rail renders the connectivity copy with a retry, not a spinner | Static: `DiscoverRailsDomainState.svelte.ts` sets `status = 'offline'` from the catalog error code; `DiscoverScreen.svelte:324` renders `DiscoverOfflineState` behind its `showOffline` flag; `discoverErrorCopy.ts` maps offline → `discover.offline` |
| Discover detail sheet | **No** — explicit offline state | Shows connectivity copy plus retry; with a seeded card payload it keeps the payload instead of blanking | Static: `DiscoverDomainState.svelte.ts:234` sets `detailStatus = 'offline'`; `DiscoverDetail.svelte:142` has the branch |
| Standard Ebooks feed | **No** — degrades like the other rails | Rust `fetchCatalogFeed` returns a typed error; the rail maps it to the offline state | Static: transport is `catalogFeedFetch` → IPC; the Rust command has a bounded timeout, and the caller races its own deadline |
| Translation (selection toolbar) | **No** — explicit `unavailable` state | Toolbar shows "service unavailable", kept distinct from "no result" | Static: `TranslationPort.ts` documents `unavailable` as transport failure; `SelectionToolbar.svelte:1277` renders it |
| Dictionary / definitions | **No** — error, no hang | Rust `defineSelection` returns a typed `CommandError`; the definition panel renders the failure | Static: the command is HTTPS-only with an explicit client timeout; no webview fetch is involved |
| Addon install / registry | **No** — explicit offline branch | Install button reports "you are offline" and does no registry I/O | Static: `useSettingsAddons.svelte.ts:70` checks `navigator.onLine === false` before calling; a `NETWORK` error also maps to the offline outcome |
| Remote covers (not yet on disk) | Partially | The image fails to load and the initial-letter fallback renders | Static: `SafeCover.svelte` `onerror` path |
| Update check | **No** — explicit offline state | Reports `update.errorOffline` instead of a generic failure | Static: `updateChecker.ts` `defaultIsOnline` is the first gate and returns an offline result |
| Google Drive connect | **No** — explicit offline failure (fixed in this task) | Returns `DRIVE_OFFLINE` before starting the loopback server, so the section shows the offline copy plus retry instead of "Connecting…" forever | Test: `DriveConnectService.offline.test.ts` proves the failure code, `isConnecting === false`, and no loopback/browser work; `DriveSection.svelte` maps the code to `settings.sync.drive.offline` (RED observed before the guard) |
| Google Drive upload/download, cold backup | **No** — error | `fetch` to `www.googleapis.com` rejects; the caller surfaces a sync error | Static: no `onLine` guard; the failure is handled but not pre-empted |
| Supabase auth (sign-in, session refresh) | **No** — error | Sign-in fails with a network error; an existing session keeps working from the stored grant | Static: no `onLine` guard; supabase-js owns the failure |
| Sentry feedback (crash dialog) | Deferred | Queues the report and drains it when online again | Static: `FeedbackDialog.svelte:112,159` and `AppModals.svelte` gate on `navigator.onLine` and queue |

## Fixed in this task

Two surfaces had a real hang or a hidden cost, both cheap and both clearly
wrong. Everything else was already either offline-aware or fails as a visible
error.

1. **Drive connect hung forever.** `runDriveConnect` started the loopback
   server, opened the browser, then awaited a callback that cannot arrive
   offline — with no timeout anywhere in the file. `driveState.isConnecting`
   stayed `true`, so `DriveSection.svelte` rendered "Connecting…" with the
   buttons disabled, permanently. Now an `navigator.onLine === false` check
   returns `DRIVE_OFFLINE` before any loopback or browser work, and the section
   renders explicit offline copy with a retry.

2. **Heartbeat burned a timeout every 120 s while offline.** The tick attempted
   the request and swallowed every error silently, so offline the app waited out
   the network failure on a timer with no user-visible reason. Now the tick
   returns before reading the client.

## Listed, not fixed

- **Drive upload/download and cold backup** have no `onLine` guard. The failure
  surfaces as a sync error rather than an offline-labelled one, but nothing
  hangs. Mapping `DRIVE_NOT_CONNECTED`-style codes to connectivity copy is a
  product decision, not a defect.
- **Supabase auth** relies on supabase-js. A pre-flight `onLine` check would
  only change the message, not the outcome.
- **No `wss:` source for Supabase Realtime** in the CSP, so realtime channels
  are already blocked regardless of connectivity. Tracked in
  `docs/desktop-csp-origins.md`; unrelated to offline behaviour but it makes any
  "realtime works offline/online" claim untestable until fixed.

## Needs a manual airplane-mode run

Static reading cannot confirm:

1. **No permanent spinner anywhere.** The two hangs above are fixed and
   unit-tested, but a session-wide sweep for a third one requires running the
   app with the interface down: library → reader → discover → detail →
   translation → definition → addons → settings → Drive.
2. **The offline branches actually render.** The guards are unit-tested; the
   copy on screen is not. Look for `discover.offline`, `addon.install.offline`,
   `settings.sync.drive.offline`, `update.errorOffline` and the toolbar's
   "unavailable" state.
3. **Recovery without a reload.** Every row claims the surface resumes when the
   network returns. Only the heartbeat test exercises that transitions; the UI
   paths (retry buttons, outbox flush interval) need a live flip of the network.
4. **No unhandled rejection reaches the console.** An unhandled rejection is
   logged to Sentry, and some are silently swallowed in `updateHeartbeat`'s
   caller by design, so a clean console during the sweep is part of the check.

Procedure: run the packaged build or `npm run tauri dev`, disable the network
adapter (do not use the browser's devtools offline toggle — it does not change
`navigator.onLine` for the Tauri webview the same way), walk the list above,
watch the WebView console, then re-enable the network without restarting and
confirm each surface recovers.
