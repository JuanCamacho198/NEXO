# G2 writer/reader audit — reading progress single source of truth (WU2a/WU2b)

Change: `storage-layout-and-sync` (FR-10). Slices: PR3 / WU2a (single-writer gate)
and PR4 / WU2b (column drop). Gate: G2.

`reading_progress.percentage` is the sole canonical writer target. `books.progress_percentage`
was write-dead under WU2a and is dropped by the WU2b Room migration (29→30); this audit now
guards against reintroducing a writer of that retired column.

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
5. `progress_percentage` inside an `INSERT` **target column list** whose list spans several lines.
   The line-based scan cannot see those, so a portable awk pass collapses each file to one logical
   statement per line (append lines, split at `;`) and checks only the parenthesized list that
   follows `INTO <table>`. Anything after `SELECT` is a read, not a write: the WU2b DROP migration
   legitimately reads the column it is about to drop (backfill + verification), and flagging that
   read would be a false positive. This runs identically with or without ripgrep.

Mappers that copy the field from itself or from the canonical progress source
(`progressPercentage = progressPercentage`, `progressPercentage = canonical.percentage`) introduce
no new position and are excluded from the camelCase failure set.

## Intentional writers (post-WU2b) — expected: none

- `android/app/src/main/java/com/nexo/data/local/dao/BookDao.kt`
  - `updateReadingProgress`: reads `:progress` only to derive `reading_state` / `completed_at`;
    still bumps `progress_updated_at` for reading-list ordering. It never sets the dropped column.
  - `completeReading`: sets `reading_state='completed'` / `completed_at`, not a percentage.
- All previous cache writers routed through `BookDao.updateReadingProgress`
  (`UpdateReadingProgressUseCase` -> `ReaderRepositoryImpl.updateBookReadingState`,
  `LibraryRepositoryImpl.updateReadingProgress`, `SupabaseBookCatalogSync` seed) are therefore
  write-dead with no call-site change.

`ProgressReconciler` and `ProgressBackfillRunner` were deleted in WU2b: their reason to exist — the
cache column — is gone, and the reconcile/backfill now lives inside the 29→30 DROP migration.

### In-migration backfill (WU2b, version-skip safe)

A version-skip upgrade (a device jumping from a pre-WU2a build straight to the WU2b APK) opens the
database and runs migrations during open, BEFORE `MainActivity.onCreate` could schedule any
app-start runner. The backfill therefore runs INSIDE `MIGRATION_29_30`: seed cache-only positions,
let the cache win where it is newer (or where a NULL timestamp carries real divergent progress —
the WU2a verify-W4 semantics), verify zero divergence, and only then recreate `books` without the
column. A failed verification throws, so Room rolls the transaction back and the column survives.

### Known non-position writer (documented, not a violation)

`BookDao.upsert` / `upsertAll` persist a whole `BookEntity`; the retired column no longer exists on
the entity, so import/sync cannot rewrite it. Positions are written only through `reading_progress`.

## Reader inventory

Android (production), post-WU2b:

| Location | Read | Classification |
|---|---|---|
| `data/local/dao/BookDao.kt` `observeReadingBooks` | `reading_progress.percentage < 100` (join) | reading-list SQL filter on canonical. |
| `domain/usecase/GetBookProgressUseCase.kt` | `reading_progress.percentage` | canonical-only path (`null` -> `0f`). |
| `data/repository/LibraryRepositoryImpl.kt` `toDomainWithCanonical()` / `observeBookById()` | `canonical.percentage` | canonical-derived. |
| `data/repository/HomeRepositoryImpl.kt` `toBookWithCanonical()` | `canonical.percentage` | same. |
| `presentation/feature/bookdetail/ReadingProgressSection.kt` | `progress?.percentage ?: book.progressPercentage` | UI reads the domain `Book`, canonical-derived. |
| `presentation/feature/bookdetail/BookDetailHero.kt` | `book.progressPercentage > 0f` | same. |
| `data/local/AppDatabaseMigrations.kt` | `MIGRATION_29_30` reads the column for the backfill/verify | the only remaining reader; this is a read and is not flagged. |
| `domain/model/Book.kt` | `progressPercentage` domain field | canonical value carried into the UI. |

Desktop: **no `books.progress_percentage` column exists** (`migrations/0001_init.sql` books table has
no progress column; `0002_books.sql` adds `current_page`/`total_pages`). Desktop progress is already
canonical-only:

- `repository/library.rs` reads `COALESCE(rp.percentage, 0.0) AS progress_percentage` (computed alias
  from `reading_progress`, not a column).
- `models/mod.rs` `LibraryBookDto.progress_percentage` is that DTO field; `avg_progress_percentage`
  is a separate reading-stats aggregate.

Consequence: no desktop column drop exists to perform, and no desktop work is needed for WU2b.

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
| `multiline-insert` | target column list spanning lines (caught by the normalized pass) |
| `setter-style` | `holder.setProgressPercentage(...)` |

Run locally: `bash scripts/ci/progress-single-writer-audit-selftest.sh`.

## G2 backfill invariant (=== 0 required)

Zero positions exist ONLY in the retired column when all hold after backfill:

1. No `cache > 0` row lacks a canonical row.
2. No cache row with a **known** timestamp is strictly newer than its canonical row.
3. A NULL cache timestamp is unknown provenance. When it carries real progress it must have won
   the reconcile and been backfilled, so the cache row equals its canonical row.

`MIGRATION_29_30` runs exactly this check before dropping the column and throws if any count is
non-zero, so a lossy migration can never commit.

Asserted by `AppDatabaseMigrationTest.migration 29 to 30 backfills every divergent position before
dropping the cache column` (fixtures: canonical-newer, cache-newer, cache-only, canonical-only,
zero, NULL-timestamp divergent) plus the version-skip and no-op cases in the same test class.

## Rollback

`git revert` the WU2b commit: the column, the reconciler, the runner and the cache readers are all
restored together. The migration is one-way, but the backfill is lossless, so a forward re-drop is
safe.
