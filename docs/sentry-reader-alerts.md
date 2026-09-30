# Sentry Reader Error Alerts

Setup guide for reader failure alerts across the NEXO Sentry projects.
D.1/D.2 (saved searches, alert rule) are manual steps in the sentry.io UI;
this document records the exact queries, thresholds, and rollback procedure
so any maintainer can reproduce them.

## Projects

| Project | Platform | Purpose |
|---------|----------|---------|
| `nexo-desktop` | Tauri (desktop renderer) | Reader errors routed via `handleError(err, 'reader')` with `source: reader` tag and context extras (PR1) |
| `nexo-android` | Android | Typed reader events via `captureTypedEvent` with `source=reader` + `event` tags and structured extras (PR2) |

Organization: `nexo-app`. Create the saved searches and alert rule in
**both** projects unless noted otherwise.

## Tag contract

Both platforms set the same tag keys so one query shape works everywhere:

- `source` = `reader` (set via `setTag` in PR1/PR2)
- `event` = typed event name (set via `setTag` in PR1/PR2)

If a query returns no results, verify in the Sentry event payload
(Tags section) that `source`/`event` are indexed tags rather than extras.
Adjust the field prefix (`tags[source,...]` vs `source:...`) to match what
Sentry actually indexes for the project SDK.

## Saved searches

Create each under **Issues > Search > Save Search** (one per project).
Names and queries must match exactly:

### 1. Reader — skipped highlights

Name: `Reader — skipped highlights`

```text
source:reader event:highlight_skipped
```

Covers highlight skips (e.g. out-of-viewport, unmapped CFI).

### 2. Reader — sync failures

Name: `Reader — sync failures`

```text
source:reader event:sync_outbox_failed
```

Covers highlight/bookmark sync outbox failures.

### 3. Reader — highlight load failures

Name: `Reader — highlight load failures`

```text
source:reader message:"Failed to load highlights"
```

Covers desktop highlight-load failures surfaced by message text
(where no dedicated `event` tag exists).

## Alert rule

Name: `Reader failure rate above threshold`

- Go to **Projects > [project] > Alerts > Create Alert > Issues**.
- Condition: issue count matching **any** of the 3 saved searches
  grows by **more than 10 events in 1 hour**.
- Environment: production (add staging only if noise is acceptable).
- Action: notify the default reader-alerts channel/recipients.
- Repeat for the second project (same name, same threshold).

Threshold rationale: reader failures are bursty (a bad release can skip
hundreds of highlights); 10/hour separates real regressions from isolated
user-local corruption.

## Structured extras reference

Use these keys when triaging alert events in Sentry
(Context/Additional Data section):

PR1 (desktop, via `handleError` context):

- `bookId`, `highlightId`, `cfi`, `format`

PR2 (Android, via `captureTypedEvent` extras):

- `highlightId`, `cfi`, `reason` (highlight skips)
- `entityType`, `entityId` (sync outbox failures)

PII note: user-typed content (note text, tag names, highlight text) is
scrubbed to `[Redacted]` before egress; only IDs, locators, and lengths
are stored.

## Rollback

- Alert/search changes: edit or delete the saved search or alert rule in
  the sentry.io UI (**Projects > Alerts** or **Issues > Saved Searches**).
  No code deploy required.
- This document: revert via `git revert <commit-sha>`.

## Performance metric vocabulary (cross-platform-telemetry-v1)

The P0 performance metrics share one vocabulary across `nexo-desktop` and
`nexo-android` (defined in TS in
`desktop/src/lib/shared/logger/metricTypes.ts`, mirrored in
`android/app/src/main/java/com/nexo/debug/MetricVocabulary.kt`; a lockstep
test fails the build on drift):

| Metric | Type | Producers |
|---|---|---|
| `app_cold_start` | distribution (ms, bucketed) | desktop + android |
| `reader_open` | distribution (ms, bucketed) | desktop + android |
| `reader_ttfp_web` | distribution (ms, bucketed) | desktop only |
| `reader_ttfp_native` | distribution (ms, bucketed) | android only |
| `sync_flush` | distribution (ms, bucketed) | desktop + android |
| `outbox_depth` | gauge (bucketed count) | desktop + android |
| `book_import` | distribution (ms, bucketed) | android (desktop e2e import = `ipc_call` with `feature=importBook`) |
| `ipc_call` | distribution (ms, bucketed) | desktop only |

### Tag contract

- `source` = `reader` | `app_shell` | `sync` | `import`
- `event` = metric name (metrics); typed event name (errors)
- `feature` = Tauri command name (`ipc_call` only)
- `format` = `epub` | `pdf`
- `platform` = `desktop` | `android`
- `engine` = `epubjs` | `readium` | `pdfjs` (reader metrics only)

No tag ever carries a book id, title, path, ISBN, CFI, or user identity; the
redaction layers denylist these keys even on call-site error.

### Bucketing rules (numerics are never exact)

- Durations (ms): log-scale buckets `[100, 250, 500, 1000, 2000, 4000, 8000, 16000, 32000, 64000, +Inf]` — emitted as the bucket's upper bound.
- Outbox depth: `0, 1-4, 5-19, 20-99, 100+` — emitted as the bucket's upper bound, only on bucket change.
- File/library sizes: `0, 1-5MB, 5-20MB, 20-100MB, 100MB+`.

### Platform-specific TTFP definitions — NEVER average across them

- `reader_ttfp_web` (desktop, epubjs): `display(cfi)` called → first `rendered`
  callback for that opening. A webview-paint timestamp.
- `reader_ttfp_native` (android, Readium): `loadBook` invoked →
  `readiumPublication` set with `isLoading=false`. A native-publication-ready
  timestamp.

These are structurally different events; a dashboard query MUST group by
`platform` (and `engine` where present) and MUST NOT average or directly
compare `reader_ttfp_web` with `reader_ttfp_native`.

### Alert rules for performance series

- Group by `platform`; use `engine` to split reader series.
- Cold start: alert when the `app_cold_start` p95 (per release) crosses its
  bucket by two edges vs the previous release, per platform separately.
- Sync: alert on `sync_flush` failure rate (from `<name>_error` increments) and
  on `outbox_depth` sustaining the `100+` bucket for 30 minutes.
