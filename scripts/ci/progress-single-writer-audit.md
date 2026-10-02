# G2 writer/reader audit — reading progress single source of truth (WU2a)

Change: `storage-layout-and-sync` (FR-10). Slice: PR3 / WU2a. Gate: G2 (blocks WU2b).

`reading_progress.percentage` is the sole canonical writer target. `books.progress_percentage`
is write-dead (read-only fallback) and stays present until WU2b.

## Scanned scope

Every root where a writer could hide:

- `android/app/src/main` — Room DAOs/entities, repositories, `AppDatabaseMigrations.kt`.
- `desktop/src-tauri/src` — Rust repositories/models.
- `desktop/src-tauri/migrations` — desktop SQLite migration tree.
- `supabase` — SQL migrations and policies.

`AUDIT_ROOT` overrides the root. The committed self-test
(`progress-single-writer-audit-selftest.sh`) points it at each fixture tree in
`fixtures/progress-audit/`; CI runs the self-test right after the audit.

## Writer detection

A writer is any of:

1. `progress_percentage` on the left side of a snake_case assignment (SQL `SET`, Kotlin/TS
   assignment), matched with a word boundary so the `avg_progress_percentage` read alias is not
   mistaken for the cache column.
2. `progressPercentage` on the left side of a camelCase assignment.
3. Any `INSERT`/`upsert` statement on the same line that carries the column.
4. A setter-style mutation: `setProgressPercentage(...)`, `SetProgressPercentage(...)`, or
   `set_progress_percentage(...)`.
5. An `INSERT ... INTO ... progress_percentage` whose column list spans several lines. The scan
   cannot see those line-by-line, so a portable awk pass collapses each file to one logical
   statement per line (append lines, split at `;`) and applies the SQL test there. This runs
   identically with or without ripgrep.

Mappers that copy the field from itself or from the canonical progress source
(`progressPercentage = progressPercentage`, `progressPercentage = canonical.percentage`) introduce
no new position and are excluded from the camelCase failure set.

## Intentional writers (post-change) — expected: none

Central retirement point:

- `android/app/src/main/java/com/nexo/data/local/dao/BookDao.kt`
  - `updateReadingProgress`: no longer sets `progress_percentage` (reads `:progress` only to
    derive `reading_state` / `completed_at`; still bumps `progress_updated_at` for reading-list
    ordering until WU2b joins `reading_progress`).
  - `completeReading`: no longer sets `progress_percentage = 100`.
- `android/app/src/main/java/com/nexo/data/sync/ProgressReconciler.kt`
  - canonical-wins branch no longer writes the cache column (log only).
  - cache-only / cache-newer positions are backfilled INTO `reading_progress` (the backfill).
  - `reconcileAll` now walks every book, not only canonical-backed books, so cache-only rows are
    actually reached.

All previous cache writers routed through `BookDao.updateReadingProgress`
(`UpdateReadingProgressUseCase` -> `ReaderRepositoryImpl.updateBookReadingState`,
`LibraryRepositoryImpl.updateReadingProgress`, `SupabaseBookCatalogSync` seed) are therefore
write-dead with no call-site change.

### Auth-independent backfill path (verify W3)

`ProgressReconciler.reconcileAll()` is invoked from two places:

- `GoogleDriveSyncService.bootstrap` (session-gated, kept — gives authenticated devices a prompt
  backfill).
- `ProgressBackfillRunner` (new), scheduled from `MainActivity.onCreate` on the container's IO
  scope. This path has no `SessionManager`, Drive login, or session gate anywhere in the call
  chain, so a legacy device that never signs in still backfills before WU2b drops the column.

Chosen over a WorkManager one-shot because the reconcile is a bounded local Room scan that must not
block startup but also must not depend on the background scheduler eventually running it; the
launch is non-blocking and idempotent (`reconcileAll` is safe to run repeatedly).

### Known non-position writer (documented, not a violation)

`BookDao.upsert` / `upsertAll` persist a whole `BookEntity`, so the column is physically rewritten
with the entity's default/stale value (typically `0f`) on import/sync. It carries no reading
position; positions are written only through `reading_progress`.

## Reader inventory

Android (production):

| Location | Read | Classification |
|---|---|---|
| `data/local/dao/BookDao.kt` `observeReadingBooks` | `progress_percentage < 100` | reading-list SQL filter; `reading_state='reading'` is the primary gate. WU2b moves ordering to a `reading_progress` join. |
| `domain/usecase/GetBookProgressUseCase.kt` | `observeBookById(...).progressPercentage` | canonical path fallback (`canonical ?: cache ?: 0`). |
| `data/sync/ProgressReconciler.kt` | `book.progressPercentage` | backfill source (intended). |
| `data/repository/LibraryRepositoryImpl.kt` `toDomain()` | `progressPercentage = progressPercentage` | fallback only; `observeLibrary()` prefers `toDomainWithCanonical` when a canonical row exists. |
| `data/repository/HomeRepositoryImpl.kt` `toBook()` | `progressPercentage = progressPercentage` | same fallback pattern. |
| `presentation/feature/bookdetail/ReadingProgressSection.kt` | `progress?.percentage ?: book.progressPercentage` | UI reads the domain `Book`, canonical-derived when canonical exists. |
| `presentation/feature/bookdetail/BookDetailHero.kt` | `book.progressPercentage > 0f` | same. |
| `data/local/entity/BookEntity.kt` | field + `@Deprecated` | schema declaration. |
| `data/local/AppDatabaseMigrations.kt` | `ALTER TABLE ... ADD COLUMN progress_percentage` | historical migration, retained. |

Desktop: **no `books.progress_percentage` column exists** (`migrations/0001_init.sql` books table has
no progress column; `0002_books.sql` adds `current_page`/`total_pages`). Desktop progress is already
canonical-only:

- `repository/library.rs` reads `COALESCE(rp.percentage, 0.0) AS progress_percentage` (computed alias
  from `reading_progress`, not a column).
- `models/mod.rs` `LibraryBookDto.progress_percentage` is that DTO field; `avg_progress_percentage`
  is a separate reading-stats aggregate.

Consequence: the desktop double-write and the prescribed desktop backfill migration do not exist to
remove. Creating an empty migration would be dishonest, so none was added. WU2a's backfill is
Android-only.

## Self-test (proves the gate can fail)

`scripts/ci/progress-single-writer-audit-selftest.sh` runs the audit against every fixture tree in
`scripts/ci/fixtures/progress-audit/` and asserts each is caught (audit exits 1), then runs it
against the repository and asserts it passes (exit 0). CI runs it immediately after the audit step,
so a future change that blinds the pattern fails CI instead of passing silently.

| Fixture | Writer shape |
|---|---|
| `snake-case-assignment` | `SET progress_percentage = ...` |
| `camel-case-assignment` | `book.progressPercentage = ...` |
| `same-line-insert` | `INSERT ... progress_percentage ...` on one line |
| `multiline-insert` | column list spanning lines (caught by the normalized pass) |
| `setter-style` | `holder.setProgressPercentage(...)` |

Run locally: `bash scripts/ci/progress-single-writer-audit-selftest.sh`.

## G2 backfill invariant (=== 0 required)

Zero positions exist ONLY in the retired column when all hold after backfill:

1. No `cache > 0` row lacks a canonical row.
2. No cache row with a **known** timestamp is strictly newer than its canonical row.
3. A NULL cache timestamp is unknown provenance. When it carries real progress it must have won
   the reconcile and been backfilled, so the cache row equals its canonical row.

The design's literal equality query
(`... id NOT IN (SELECT book_id FROM reading_progress WHERE percentage = books.progress_percentage)`)
is stricter than the invariant and would report false positives on every divergent row where
canonical is newer (the common case). The timestamp-based invariant above is the correct no-loss
check and is what the acceptance test asserts.

Asserted by `ProgressReconcilerTest.backfill_divergent_cacheOnly_canonicalOnly_preservesEveryPosition`
(fixtures: canonical-newer, cache-newer, cache-only, canonical-only, equal, NULL-timestamp divergent)
and by `backfill_runsOnAuthIndependentStartupPath_withNoAuthenticatedSession` (the auth-independent
startup path).

## Rollback

Re-enable cache writers in `BookDao` (column still present) and revert `ProgressReconciler`; no
schema change, no migration to undo.
