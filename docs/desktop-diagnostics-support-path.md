# Deserialising a diagnostics bundle: the support path

Audit item #18. This is the path a user or a support engineer takes to get
evidence out of a running NEXO desktop install **without a developer and without
turning on the hidden debug panel**.

## What exists

| Surface | Where | Reachable by a user? |
| --- | --- | --- |
| `diagnose` | `src-tauri/src/commands/diagnostics.rs:212` | Indirectly, inside the bundle |
| `getLogs` | `src-tauri/src/commands/diagnostics.rs:218` | Indirectly, inside the bundle |
| `collectDiagnosticsBundle` | `src-tauri/src/commands/diagnostics.rs:228` | **Yes** |

Before this task the only shipped callers were `DebugPanel` (gated behind
`debugState.enabled`, off in release) and `FeedbackDialog`'s opt-in checkbox —
and that dialog only opens on a crash (`ErrorFallback` dispatches
`np:open-feedback`) or on the next launch after one. A user whose app is slow,
stuck offline, or refusing to sync had no on-demand path at all.

## The user path

1. Open **Settings → About**.
2. Scroll to **Support** (the panel with GitHub / Report Issue links).
3. Press **Copy diagnostics**.
4. Paste into the issue, an email, or a chat.

The button calls `collectDiagnosticsBundle` and copies the JSON to the
clipboard. It reports one of three visible states in a `role="status"` region:
copied, failed, or in-flight. A thrown command or an empty payload reports
failure — it never copies a placeholder and never claims success.

Source: `src/lib/features/settings/components/SettingsAboutTab.svelte`
(`exportDiagnostics`), strings under `settings.about.diagnostics*`.

## What the bundle contains

Assembled in Rust, `commands/diagnostics.rs:228`:

- `generatedAt`, app version, platform
- `logs` — the capped tail of the rotated JSONL log
- `diagnose` — health probe details (log path, book counts, missing-file count)
- a scrubbed settings projection

It does **not** contain book files, highlight text, or the Supabase session.

## What is redacted

Redaction happens twice, and the second one is authoritative:

- **Frontend**, `src/lib/shared/logger/redaction.ts` — every retained
  `LogRecord` and every event broadcast to a sink passes `redactRecord` before
  it is stored or transferred. Covers `key=value` / `key: value` secrets,
  `Bearer …`, DSNs, absolute Windows/Unix/`~` paths, and — added in this task —
  bare JWTs and vendor key prefixes (`sk-live-…`) that appear in prose with no
  key in front of them.
- **Rust**, `src-tauri/src/logger.rs` — the same key-set applied at the IPC
  boundary before the bundle is serialised.

### Proof, not assumption

`src/test/unit/logger/loggerRedactionEndToEnd.test.ts` feeds a production-shaped
line (`token exchanged for eyJ... eyJ... api_key=sk-live-… authorization: Bearer
… C:\Users\…\private.epub`) through `logger.error()` and asserts on what a
registered sink and the retained ring actually receive. It is not a
`redactRecord` fixture: it exercises the real call path.

That test found a real hole when it was written: a bare JWT in prose survived
every rule, because all existing patterns required a `key=` anchor. Fixed in
`redaction.ts` (`JWT_RE`, `VENDOR_KEY_RE`). The Rust boundary still uses
anchor-only patterns, so a bare token in prose is redacted by the frontend rule
and **not** by Rust — this is defence in depth, not a single gate.

## Verifying the path by hand

1. `bun run tauri:dev` (or install a release build).
2. Settings → About → Copy diagnostics.
3. Paste into a text editor. Look for `[REDACTED]` and `[REDACTED_PATH]`.
4. Search the pasted text for your home directory name and for `eyJ`. Neither
   should appear.

If `[REDACTED]` does not appear anywhere, the bundle is empty or the log ring
was cleared — check the status text before filing a bug against redaction.
