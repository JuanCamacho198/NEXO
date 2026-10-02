# G2 writer/reader audit — reading progress single source of truth (WU2a)

Change: `storage-layout-and-sync` (FR-10). Slice: PR3 / WU2a. Gate: G2 (blocks WU2b).

`reading_progress.percentage` is the sole canonical writer target. `books.progress_percentage`
is write-dead (read-only fallback) and stays present until WU2b.

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

## G2 backfill invariant (=== 0 required)

Zero positions exist ONLY in the retired column when both hold after backfill:

1. No `cache > 0` row lacks a canonical row.
2. No cache row is strictly newer than its canonical row.

The design's literal equality query
(`... id NOT IN (SELECT book_id FROM reading_progress WHERE percentage = books.progress_percentage)`)
is stricter than the invariant and would report false positives on every divergent row where
canonical is newer (the common case). The timestamp-based invariant above is the correct no-loss
check and is what the acceptance test asserts.

Asserted by `ProgressReconcilerTest.backfill_divergent_cacheOnly_canonicalOnly_preservesEveryPosition`
(fixtures: canonical-newer, cache-newer, cache-only, canonical-only, equal).

## Rollback

Re-enable cache writers in `BookDao` (column still present) and revert `ProgressReconciler`; no
schema change, no migration to undo.
