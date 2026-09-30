# Sync — Supabase SoT Hot + Drive Cold Backup (PR4)

> **Invariant**: Supabase is the sole hot source of truth for `user_books`, `reading_progress`, `highlights`, `bookmarks`, `reading_sessions`.  
> Google Drive is **cold backup only** — Settings → Export / Import on demand, plus one-shot FK-ordered backfill. No hot `pushState` / `pullState` / `schedulePull` for state on save/open.

## Hot Path (Supabase)

- **Gating**: every PostgREST upsert and Realtime subscribe is gated by `hasLiveSession()` (`sessionManager.getCurrentSession()` / `authState.userId` continuous + one `recheckLiveSession()` for silent drops DA-1). Gated calls no-op — no request, no throw, no `markFailed`.
- **Upserts** (`onConflict`):
  - `user_books(user_id, id)` — hash dedup, covers via `book-covers` bucket, `SupabaseBookCatalogSync`
  - `reading_progress(user_id, book_id)` — CFI + percentage + `locator_json`, `version+1` (Rust `upsert_progress` active `book_id` where `deleted_at IS NULL`)
  - `highlights(id)` — `cfi_range` non-null for EPUB, tombstone `deleted_at`, `type`/`locator_json`
  - `bookmarks(user_id, book_id, cfi_location)` — unique, `cfi_location` canonical, `locator_json`
  - `reading_sessions(id)` — per-id, never coalesced, streak via Realtime
- **LWW**: `LastWriteWinsConflictResolver` unified for 5 tables — `remote.updatedAt > local → remote`; `< → local`; `== → recordId` lexicographic no-op; tombstone compares `deletedAt`. Clock skew mitigated by server `now()` + `version`.
- **Realtime**: single supervisor per platform owning 4 channels `progress:uid` / `highlights:uid` / `bookmarks:uid` / `sessions:uid`, `<2s`, teardown on logout (desktop `destroy/subscribeAll`, Android `realtimeJob` + `unsubscribeAll`). Stale instances (`userId` mismatch) are gated no-ops.
- **Outbox** (offline-first, valid JSON):
  - Android `SyncOutboxEntity` — `indices [entity_id], [entity_type, entity_id]`, FK removed (PR4 migration 24→25) so rows survive book delete (payload carries `bookId`).
  - `payloadJson` MUST be non-empty valid JSON object (never `"{}"` or malformed) — enforced via `ensureValidJson` / `JSONObject`.
  - `READING_PROGRESS` **coalesced by `bookId` keep latest** — one row per `(type, bookId)` (desktop `addCoalescedSyncOutboxItem`, Android `getByTypeAndEntityId` + `updatePayload`); flood of location events collapses to single row.
  - `HIGHLIGHT` / `BOOKMARK` / `READING_SESSION` **per `id`** — atomic enqueue, never coalesced across ids (each highlight/bookmark/session is its own row). `entityId == id`, payload contains `id`.
  - Processor gated by `hasLiveSession` — `SupabaseProgressSync.processOutbox` (Android) / `SyncOutboxService.flush` (Desktop) with circuit-breaker on 400/401 (60→120→240→300s cap).

## CFI-First Derived Page (LWW+Version, PR1+PR4)

- Canonical state: `cfiLocation` / `cfiRange` + `percentage` + `locatorJson` (Readium `{"href","type":"application/xhtml+xml","locations":{"fragment":"epubcfi(...)","progression":0.42}}`).
- **Page is derived**, never synced as source: `page = LocatorCodec.fromCfi(cfiRange ?? cfiLocation) ?? 1`.
- `LocatorCodec.ts` (desktop) ↔ `LocatorCodec.kt` (Android) parity: `parseSpineIndex(cfi)` extracts `epubcfi(/6/N)` where `N ≥ 1`; `fromCfi` returns `1` for any valid `epubcfi`, `null` otherwise; callers use `?: 1`.
- `current_page` / `pageNumber` are **deprecated** as sync sources — display-only and must not be written as canonical state. `current_page` column remains nullable for back-compat but is ignored on sync.
- Highlights: `cfi_range` non-null for EPUB (validated in Rust `highlights.rs`).

## Drive Cold Backup (PR3+PR4)

- **Only Settings touches Drive**: `DriveColdBackupService.exportColdBackup(userId)` / `importColdBackup(userId)` — Settings UI (`SettingsDataStorageScreen.kt`, `SettingsDataTab.svelte` / `SettingsPanel.svelte`) gated by `driveAuthorized && userId != null`, `hasLiveSession` for import.
- **File**: `books/{userId}/nexo_cold_backup.json` (physical `nexo_cold_backup.json` inside `Nexo/Books` protocol folder, `drive.file` scope). Reuses `GDriveProvider` / `GoogleDriveStorageRemoteDataSource` primitives: **POST with `parents` on create, PATCH without `parents` on update** — 403 fix retained.
- **Export shape** (`ColdBackupJson` / `ColdExport`): `{ version:1, exportedAt, books[], progress[], highlights[], bookmarks[], sessions[] }` + `bins` implicit (book files already in `Nexo/Books`).
- **Import**: FK-ordered `books → progress → highlights → bookmarks → sessions`, **chunk 100** idempotent (`onConflict` per table), gated `hasLiveSession`. Second run importing same 250-row backup produces `totalImported == first` and zero FK errors.
- **Backfill**: one-shot `Drive → Supabase` on first login, reuses cold path; legacy fallback scans `*_state.json` files (`BookStateJson` schema) and aggregates into same FK-order chunk 100 path.
- **Hot retired**: `GoogleDriveSyncService.pushState / pullState / schedulePull(state)` deleted in PR4; `GoogleDriveJsonStateSync` stripped to stub (compile compat, schema `BookStateJson` for backfill parsing only); `GoogleDriveSyncService.schedulePush` now handles **only BOOK file upload** (`pushBook`); `schedulePull` only downloads book binaries for existing local books/mappings (catalog owns new imports).

## Single Supervisor + Session Gate

| Platform | Supervisor | Channels | Gate | Teardown |
|----------|-----------|----------|------|----------|
| Android | `SupabaseProgressSync.subscribeToRealtimeChanges` | `progress:uid` `highlights:uid` `bookmarks:uid` `sessions:uid` via `SupabaseProgressDataSource` | `hasLiveSession()` + `userId == session.userId` | `stop()` `realtimeJob.cancel` `unsubscribeAll` |
| Desktop | `SupabaseProgressSync.ts` + `SupabaseBookCatalogSync` | `progress:uid` `highlights:uid` `bookmarks:uid` `sessions:uid` via `supabase.channel` | `hasLiveSession() && userId == authState.userId` continuous + `recheckLiveSession` | `destroy()` `unsubscribeRealtime` |

## Data Flow (Post-PR4)

```
Save (Reader/Highlights/Bookmarks) → Room/Rust SQLite → Outbox (valid JSON, coalesced)
               │                               │
               └──────── LWW ─── SupabaseProgressSync → PostgREST onConflict (gated)
                                    ↕ Realtime (single supervisor, 4 channels, <2s)
                              Remote row → LWW resolver → local upsert (active book_id, version+1)

Settings Export → DriveColdBackupService → GDriveProvider.upload (multipart, parents only on create)
Settings Import / One-shot backfill → chunk100 FK-order upserts → Supabase (idempotent)
Page read → LocatorCodec(cfi) → derived page (never persisted as source)
Book binary → GoogleDriveSyncService.pushBook / schedulePull (file only, catalog sync handles metadata)
```

## Verification (PR4)

- `cargo test --lib -- repository` — `highlights::tests` (cfi_range non-null), `progress::tests` (active row `version+1`, `deleted_at IS NULL`)
- `pnpm vitest run src/test/unit/sync src/test/unit/services/DriveColdBackupService.test.ts src/test/unit/outbox` — LWW, LocatorCodec, cold FK-order idempotent, gated hasLiveSession
- `pnpm vitest run src/test/unit/outbox/OutboxParity.test.ts` — progress coalesced, highlight/bookmark/session per-id, valid JSON (not `"{}"`)
- `./gradlew :app:testDebugUnitTest` — `LocatorCodecTest`, `SupabaseProgressSyncTest`, `ColdBackupE2ETest` (export≠Drive on save, import×2 idempotent, offline queue flush)
- `./gradlew :app:compileDebugKotlin` — no 403 `parents` regression (storage data source omits `parents` on update)

## Rollback

Feature branch `feature/supabase-source-of-truth-drive-cold-backup` (feature-branch-chain PR1→PR2→PR3→PR4). Revert PR4 then PR3/PR2/PR1 individually — each PR has autonomous rollback boundary (no cross-PR file overlap beyond wiring). Flag `syncMode='supabase'` → `dual` restores `schedulePull/pushState` in one commit if needed. Data untouched; cold exports readable.
