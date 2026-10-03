# Storage Layout & Sync (WU6)

This document is the WU6 (tasks 5.3) documentation: per-store retention policy, the
Drive `manifest.json` contract, the in-migration backfill note, and the deferred
follow-ups.

## Version markers

| Marker | Location | Meaning |
|--------|----------|---------|
| `layout.json` | Android `filesDir/`, desktop app-data root | Local tree layout version. **Missing == version 0 (legacy tree), never a clean install.** |
| `migration.json` | Same directory as `layout.json` | Idempotent migration journal (`steps[]`); a re-run resumes from it. |
| `Nexo/manifest.json` | Google Drive `Nexo/` | Drives the cross-client gates: `layoutVersion`, `filenameVersion`, `guardVersion`, `legacyParked`, `coldBackupCutover`, per-object `{name, version, checksum}`. |

A version marker is bumped only after the verification pass succeeds: move files
→ rewrite stored paths (one transaction) → verify → bump last. A failed
verification leaves the marker unbumped and the app on the legacy tree. A missing
or unparseable remote manifest is treated as **version 0** for every gate.

## Retention policy (same threshold per store: 30 days, weekly)

Retention prunes only soft-deleted rows: the predicate always includes
`deleted_at IS NOT NULL`, so live rows are never touched. Android prunes books /
highlights / bookmarks; desktop prunes the same tables; Supabase exposes
`public.prune_soft_deleted(interval)` for the server tables. VACUUM runs after a
local prune. Pruning is scheduled weekly per store; it never runs inside a
user-facing save path.

| Store | Executor | Schedule | Implementation |
|-------|----------|----------|----------------|
| Android Room | `RetentionPruner` (`RetentionDao`) | WorkManager periodic worker (`WorkManagerRetentionPruneScheduler`), unique, `ExistingPeriodicWorkPolicy.KEEP` | `RetentionPruneWorker`, asserted at app start |
| Desktop SQLite | `retention::run_retention` | startup-idle on its own connection | `desktop/src-tauri/src/main.rs` (`build_state`) |
| Supabase | `prune_soft_deleted(interval)` (SECURITY DEFINER) | Not scheduled — app-driven | `supabase/migrations/20261002000001_storage_layout_retention.sql` |

`pg_cron` is not enabled in this project (no `cron.schedule` usage in the repo),
so the server function is invoked by the app executors above rather than by a
database schedule. Execute on the function is revoked from `public` / `anon` /
`authenticated` and granted to `service_role`.

## Drive `manifest.json` contract

```jsonc
{
  "layoutVersion": 1,       // WU6 target tree (books/ + backups/ + legacy/)
  "filenameVersion": 1,     // WU4 canonical algorithm shipped
  "guardVersion": 1,        // WU5 write guard active
  "legacyParked": false,    // Phase B parking complete (always false this change)
  "coldBackupCutover": { "dualWrite": true, "newPathPrimary": true },
  "updatedAt": "2026-10-02T00:00:00Z",
  "objects": [{ "name": "gutendex2701.epub", "version": 3, "checksum": "ab" }]
}
```

- **`filenameVersion >= 1` is the layout-migration gate.** The Drive book folder
  is not migrated while a legacy tree exists and the remote manifest does not yet
  prove the WU4 canonical filenames shipped — otherwise the retired sanitizers
  could still create new Drive collisions.
- The gate only has meaning when there is something to migrate. A fresh install
  has no legacy folder and always creates the canonical one; it never consults
  the manifest gate.
- A new client advertises `filenameVersion: 1` when it writes the manifest. On an
  existing install the legacy folder therefore stays live on the first launch
  after this change and migrates in place on the next one.
- Cold backup is dual-written: the legacy `Nexo/Books/{userId}/nexo_cold_backup.json`
  path keeps being written and read as a fallback, while `Nexo/backups/{userId}/`
  becomes primary.

### Drive book folder: adopt-or-rename (`Nexo/books/`)

The legacy book folder is the capitalized `Nexo/Books/`; the target is
`Nexo/books/`. Desktop (`GDriveProvider` + `driveLayoutMigration.ts`) and Android
(`GoogleDriveStorageRemoteDataSource` + `DriveBooksFolder`) follow the same plan:

1. **Canonical `books/` present → adopt it.** If Drive's case-sensitive name
   lookup surfaces BOTH `books/` and `Books/` (Drive allows case-different
   siblings), canonical wins and the legacy twin is left untouched — never
   merged, never deleted.
2. **Legacy `Books/` only, `filenameVersion < 1` → blocked.** The legacy folder
   stays live; nothing is created, renamed, or deleted.
3. **Legacy `Books/` only, `filenameVersion >= 1` → rename in place** via
   `files.update` on the SAME folder id. This is a true rename, not a duplicate
   create: every child is preserved, no bytes are moved out of the folder. No
   book is lost.
4. **Neither present → create `books/`** (FR-01 fresh install).

Because the Drive `name = '...'` query is case-sensitive, a pre-WU4 client that
searches the literal `Books` name will not match the renamed folder. The resolver
probes both names in a single request and adopts the canonical folder; a
re-created legacy `Books` is detected as the case-sensitive duplicate and left
untouched for Phase B parking. The gate is what prevents this rename from running
before the canonical filenames have shipped.

### Local on-disk layout migration is deliberately not manifest-gated

The local `layout.json` migration (Rust `layout.rs`, Android `LayoutMigration`)
runs on every start and is idempotent. It is **not** gated on the remote
`filenameVersion` because:

- the remote manifest is not available at cold start (the Drive client runs in
  the webview/UI layer, after `migrate_layout` has already executed), and
  fabricating a local cache of a remote gate would add a distributed-consistency
  channel for no safety gain;
- it moves only id-derived local files, which cannot participate in the Drive
  filename-collision class the gate protects against.

The manifest gate is therefore enforced where the manifest actually exists — the
Drive layout migration — and both required paths hold: a fresh install migrates
untouched (FR-01), and a legacy Drive tree with `filenameVersion < 1` does not
migrate.

## In-migration backfill note

WU2b dropped `books.progress_percentage`. The cache → canonical backfill runs
**inside the DROP migration itself** — the Room migration step
(`AppDatabaseMigrations.kt`) and the desktop SQLite drop migration — not in the
app-start runner (`ProgressBackfillRunner` / `MainActivity.onCreate`). A
version-skip upgrade opens the database and runs migrations before
`MainActivity.onCreate`, so an app-start backfill would run after the column is
already gone and lose every cache-only position. The backfill is a no-op when
there is nothing to move and is safe to re-run. Keep the backfill script in-repo
until WU6 is fully shipped.

## Deferred follow-ups

- **Phase B legacy parking (`Nexo/legacy/`).** Gemini-named legacy objects are
  copied to their canonical names in Phase A and left in place. Moving
  superseded copies into `Nexo/legacy/` is gated on the `legacyParked` cutover
  flag and requires evidence that every active client reports
  `filenameVersion >= 1`. Never delete; parking moves, it does not remove.
- **Android reconciler scheduling hook.** `DriveReconciler` is implemented and
  unit-tested but no production path schedules it: `GoogleDriveSyncService` does
  not yet call it after pull. Desktop wires `driveReconciler` into
  `SyncService.syncBooks`. Android must hook the reconciler into its pull/sync
  cycle (reusing the listing it already performs) to close FR-07 symmetrically.
- **Old cold-backup path removal.** `Nexo/Books/{userId}/nexo_cold_backup.json`
  stays dual-written until the cutover flags prove no client generation needs it.
- **`Nexo/Books` legacy folder removal.** The legacy folder is left untouched by
  the rename path and by the duplicate-case path; its cleanup belongs to Phase B
  parking.

## Verification

- Desktop: `cargo fmt --check`, `cargo check`, `cargo clippy -- -D warnings`,
  `cargo test` from `desktop/src-tauri`; `bun run --cwd desktop test`.
- Android: from `android/`, `cmd /c "gradlew.bat --no-daemon :app:spotlessApply"`
  then the full unit/lint/verify task set.
- `scripts/ci/progress-single-writer-audit.sh` (G2 writer audit).
