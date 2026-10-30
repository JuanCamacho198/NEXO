# Three-store audit — security, inconsistencies, integrity

Date: 2026-10-28
Branch audited: `feat/account-isolation-and-topic-rails`
Method: read-only. `git grep` (tracked files only), `jq` against `33.json`, no files written.

Scope: Room v33 (Android local), Rust SQLite 23 migrations (desktop), Supabase 16 migrations (remote).

Severity roll-up: **1 CRITICAL · 7 HIGH · 9 MEDIUM · 3 LOW**

---

## Executive summary

1. **CRITICAL** — Supabase `devices` RLS null-owner bypass: any signed-in user can
   read, reassign and delete every legacy device row.
2. **HIGH** — `highlight_tags` and `reading_progress` UPDATE policies have `USING`
   but no `WITH CHECK`, so a row can be repointed at another user's id.
3. **HIGH** — Rust store has no `user_id` on `books`, `reading_progress`,
   `highlights`, `bookmarks`: the isolation this branch adds elsewhere is absent
   on desktop.
4. **HIGH** — Rust `recompute_reading_stats_from_sessions` filters by no user at
   all: two desktop accounts share one stat total.
5. **HIGH** — Room `reading_stats` PK is `book_id` alone while every query filters
   `userId`: a second account reading the same book overwrites the first.
6. **HIGH** — Room `dictionary_words` has no unique index on
   `(user_id, word_normalized)`; Supabase and Rust both enforce it.
7. **HIGH** — Session duration units differ: Rust seconds, Android/Supabase
   minutes; conversions truncate.
8. **MEDIUM** — Room `userId` vs `user_id` naming drift confirmed.

Resolved since the previous audit: bookmark LWW clock (`0022`), outbox ownership
(`0023`), `local.properties`/`.env` correctly ignored, Room chain contiguous 1→33.

---

## 1. Credentials and secrets — CLEAN

All six files matching a JWT/`service_role` pattern are **test fixtures**. Every
`eyJ…` token resolves to the public jwt.io demo credential (`sub: 123`), used to
assert redaction behaviour.

| File | Verdict |
|---|---|
| `docs/storage-layout-and-sync.md` | prose mentioning the `service_role` role |
| `supabase/migrations/20261002000001_storage_layout_retention.sql` | role name in a comment and a `grant execute` |
| `desktop/src/test/unit/services/GDriveProvider.test.ts` | redaction-test input |
| `desktop/src/test/unit/recovery/crossDeviceAcceptance.test.ts` | asserts `[JWT_REDACTED]` |
| `desktop/src/test/unit/logger/sentryPiiScrubber.test.ts` | same demo token |
| `android/.../DriveCatalogContractTest.kt` | same demo token |

**No real secret in tracked files.** No `BEGIN … PRIVATE KEY`, no `GOCSPX-`, no
service-role JWT.

`android/local.properties` and `desktop/.env` are **ignored and untracked**
(`android/.gitignore:6`, `desktop/.gitignore:27`), confirming `android/AGENTS.md`.
All workflow references use `${{ secrets.* }}`.

Storage: bucket `book-covers` is `public = false` with owner-scoped policies keyed
on `(storage.foldername(name))[1] = auth.uid()::text`. **No `USING (true)` anywhere
in `supabase/`.**

One live Supabase project ref appears in `desktop/src-tauri/tauri.conf.json:24`
inside a CSP `connect-src` allowlist. A URL, not a credential. Expected.

---

## 2. RLS and authorization

| Table | RLS | Policies | Grant |
|---|---|---|---|
| `devices` | yes | select/update/delete carry `or supabase_user_id is null`; update has no `WITH CHECK` | **none** |
| `reading_progress` | yes | update has `USING` only, no `WITH CHECK` | **none** |
| `bookmarks` | yes | `for all` + `WITH CHECK` — correct | **none** |
| `highlights` | yes | `for all` + `WITH CHECK` — correct | **none** |
| `tags` | yes | `for all` + `WITH CHECK` — correct | **none** |
| `highlight_tags` | yes | `for all` `USING` only, no `WITH CHECK` | **none** |
| `user_books` | yes | 4 policies, all `auth.uid() = user_id` + `WITH CHECK` | **none** |
| `user_dictionary_words` | yes | all four with `WITH CHECK` | yes |
| `reading_sessions` | yes | all four with `WITH CHECK` | yes |

### Holes

**CRITICAL — `devices` null-owner bypass.**
`20260709000003_enable_rls_devices.sql:13,32,41`. Any authenticated user matches
every row where `supabase_user_id is null`. Consequence: read, reassign or delete
other users' legacy device records. The INSERT branch (`:23`) is correctly
`and auth.uid() is null`.

**HIGH — `highlight_tags` missing `WITH CHECK`.**
`20260709000008_create_tags_highlight_tags.sql:27-31`. Postgres applies `WITH CHECK`
to the *new* row on INSERT/UPDATE; `USING` only validates the result set for
UPDATE/DELETE. With no `WITH CHECK`, a user can insert a `(highlight_id, tag_id)`
pair referencing another user's highlight. Compare the correct `for all` policies
on `tags`, `highlights`, `bookmarks`.

**HIGH — `reading_progress` UPDATE without `WITH CHECK`.**
`20260709000005_create_reading_progress.sql:25-27`. A user can
`UPDATE … SET user_id = '<other uuid>'`, re-parenting their row to someone else.

**MEDIUM — 6 tables with no explicit `GRANT`** (`devices`, `reading_progress`,
`bookmarks`, `highlights`, `tags`, `highlight_tags`). Supabase defaults likely
cover them, but this **cannot be determined from the repo**. See Unverified.

**No defect — `prune_soft_deleted`.** The only `SECURITY DEFINER` function
(`20261002000001:20-26`). Search path pinned, EXECUTE revoked from
`public`/`anon`/`authenticated` and granted to `service_role` (`:64-67`). Correct.

**No triggers** exist in `supabase/migrations/`. N/A.

---

## 3. Cross-store inconsistencies

### 3.1 Session duration units differ — HIGH
Rust stores `duration_seconds` (`0004_desktop_feature_parity.sql:32`). Android and
Supabase store `duration_minutes` (`ReadingSessionEntity.kt:28-29`,
`20261001000001_create_reading_sessions.sql:24`). Rust→remote truncates
`/60` (`repository/progress.rs:260`); remote→Rust multiplies `*60` (`:806`).
A desktop session loses up to 59 seconds per round trip, and the local recomputed
stat stops matching the persisted value.

### 3.2 Reading statistics computed two ways — HIGH
Rust derives everything live from `reading_sessions`
(`repository/reading_stats.rs:45-85`, rounding at `:80`). Android maintains a
denormalized `reading_stats` table upserted separately
(`ReadingStatsRepositoryImpl.kt:115-143`). The same account reports different
totals per platform on identical session data.

### 3.3 `date` is a different type per store — MEDIUM
Rust writes an RFC3339 string (`reading_day.rs:34-36`) into a TEXT column
(`0014:5`). Android stores a `Long` (`ReadingSessionEntity.kt:30`) written from
`ReadingDay.readingDayStartMillis` (`ReadingStatsRepositoryImpl.kt:119`). Both sync
through Supabase `date timestamptz`, so one value is parsed by two code paths.

### 3.4 Tombstone types differ, and sessions have none — MEDIUM
Supabase `timestamptz`; Rust `TEXT` RFC3339; Room `INTEGER` epoch millis.
`reading_sessions` carries **no tombstone in any store**
(`20261001000001:17` states it outright), so session deletes cannot propagate.

### 3.5 Rust ownership missing — HIGH
`books`, `reading_progress`, `highlights`, `bookmarks` have no owner column. Only
`reading_sessions` (`0014:4`), `dictionary_words` (`0015:3`) and now `sync_outbox`
(`0023:9`). The single largest contradiction with this branch's goal.

### 3.6 Room `userId` vs `user_id` drift — MEDIUM
`reading_sessions.userId` (`ReadingSessionEntity.kt:31`) and
`reading_stats.userId` (`ReadingStatsEntity.kt:28`) are camelCase. The other seven
tables are `user_id`. Every predicate in `ReadingSessionDao.kt` (12 sites) and
`ReadingStatsDao.kt` (4 sites) uses `userId`. Origin:
`AppDatabaseMigrations.kt:320`. Not a runtime bug today; a trap for the next
query author.

### 3.7 Docs contradict schema — MEDIUM
`docs/SUPABASE-ARCHITECTURE.md:56-83` documents `devices` as
`user_id uuid not null references auth.users(id)` with three strict
`auth.uid() = user_id` policies. The repo implements `user_id text`
(`20260709000001:3`) plus a separate `supabase_user_id uuid`
(`20260709000002:6-7`) and the `is null` bypass. Anyone implementing from the doc
re-creates the weaker model without knowing the bypass exists.

### 3.8 `reading_sessions` cannot round-trip losslessly — MEDIUM
`docs/sync.md:3` names Supabase the sole hot truth, but Rust keeps
`duration_seconds` and `ended_at` columns Supabase does not have.

---

## 4. Data integrity

### 4.1 `reading_progress` has no FK to `auth.users` — MEDIUM
`20260709000005_create_reading_progress.sql:1-13`. Every sibling has
`references auth.users(id) on delete cascade`. Deleting a user orphans their
progress rows.

### 4.2 `reading_stats` PK collision — HIGH
`@PrimaryKey @ColumnInfo(name = "book_id")` (`ReadingStatsEntity.kt:22-24`) with
`OnConflictStrategy.REPLACE` (`ReadingStatsDao.kt:21-22`). Queries filter `user_id`
(`:12,18,24,27`) but the PK does not include it, and the only index is on
`book_id`. **Two accounts reading the same book overwrite each other's row.**
The v33 migration did not touch this table.

### 4.3 Android dictionary uniqueness missing — HIGH
`dictionary_words` indices `index_dictionary_words_user_id` and
`index_dictionary_words_word_normalized` are **both `"unique": false`** in
`33.json`. Supabase declares `unique (user_id, normalized_word)`
(`20260825000001:15`); Rust creates `CREATE UNIQUE INDEX idx_dict_user_norm`
(`0015:34`). Android can persist duplicates, then meets the remote constraint on
upsert (`SupabaseDictionaryDataSource.kt:24`).

### 4.4 Normalization is equivalent, but unenforced server-side — LOW
Android `DictionaryNormalizer.kt:19-22` and Rust `repository/dictionary.rs:20-22`
agree (trim → lowercase → NFD → strip combining marks). Kotlin `lowercase()` is
locale-invariant. **Postgres has no normalization function** — `normalized_word` is
trusted as sent, so uniqueness depends entirely on writers.

### 4.5 Room has no CHECK constraints — LOW
Supabase enforces CHECKs on `devices.type`, `user_books.lifecycle/catalog_version/recovery_protocol`,
`udw.srs_stage`, `udw.word` length; Rust on `sync_outbox.operation` and
`srs_stage`. Room's `books.reading_state` is a bare `TEXT NOT NULL` with no
closed-set enforcement.

### 4.6 Empty-string owner is a third account bucket — MEDIUM
`reading_sessions.user_id` defaults to `''` in both Room and Rust, and Rust
explicitly counts `OR user_id = ''` locally (`0014:2-3`).

### 4.7 Cache pruning is consistent — no defect
Android `deleteExpired` / `deleteLegacyNamespaces` (`DiscoverCacheDao.kt:36,41`);
Rust bulk sweep (`db.rs:349`) plus lazy eviction (`db.rs:305-308`). See Unverified
for the Android caller question.

---

## 5. Migration-chain defects

### Room — contiguous 1→33, verified
`ALL` (`AppDatabaseMigrations.kt:900-934`) lists every step, `version = 33`,
`33.json` reports 33 with 12 entities. Match verified.

- **MEDIUM, destructive by design.** `MIGRATION_32_33` (`:721-898`) drops and
  recreates six tables with **no data migration**. Justified at `:709-719`. Any
  pre-v33 install loses its library on upgrade — intentional and user-visible.
- **LOW, positional copy.** `INSERT INTO sync_file_mappings_new SELECT * FROM …`
  (`:92`) and the same for `sync_outbox` (`:128`). Guarded by a row-count assertion
  and safe today, but any future column addition breaks them silently.

### Rust — contiguous 1→23, verified
`MIGRATIONS: [(&str, &str); 23]` (`db.rs:11`) includes `0022` (`:42`) and `0023`
(`:43`). An earlier truncated grep suggested `0023` was missing; **it is not.**

- **MEDIUM, replay-unsafe.** `has_column` guards exist for `0002, 0005, 0007, 0010,
  0011, 0015, 0022, 0023` (`db.rs:79-90`), but **`0014`, `0019`, `0020` have no
  guard** and are bare `ALTER TABLE ADD COLUMN` (`0014:4-6`, `0019:7-16`,
  `0020:10`). A partial apply leaves the tracker without the row and the replay
  fails with "duplicate column".
- **LOW, destructive but safe.** `0015:31` drops `dictionary_words` after
  `INSERT OR IGNORE` into the replacement (`:29-30`). Data-preserving.

### Supabase — idempotent where it matters
`create table if not exists`, `add column if not exists`, `drop policy if exists`
before every `create policy`, and a `do $$ … $$` guard for the realtime
publication. No gaps in filenames.

- **CRITICAL, unresolvable from repo.** `20261001000001:4-8` and
  `20261001000002:2-8` state their objects were created via the Supabase MCP and
  that a clean rebuild did not reproduce production. The repo cannot prove no
  *other* out-of-band column exists.
- **MEDIUM, destructive.** `20260920000001:10` drops `is_favorite`, mirrored in
  Rust `0020`. Intentional.

---

## 6. Resolved since the previous audit

- **Bookmark LWW clock fixed.** `0022` adds `cfi_location`, `locator_json`,
  `updated_at` and backfills the clock from `created_at`; registered at `db.rs:42`.
- **Outbox ownership implemented.** `0023` adds `user_id`; the drain filters by it
  (`commands/outbox.rs:183`); ownerless rows are reported, not drained
  (`:173-177`).
- **`local.properties` / `.env` genuinely ignored**, confirming `android/AGENTS.md`.
- **Room version and schema agree** — `version = 33` matches `33.json`.
- **`prune_soft_deleted` privilege model sound** — EXECUTE revoked from all client
  roles.

---

## 7. Unverified

- **Supabase GRANTs for 6 tables.** Likely covered by project defaults, but not
  determinable from the repo. Verify against the live
  `information_schema.role_table_grants`.
- **Live-production drift.** Two migrations are self-described out-of-band repairs,
  so divergence happened at least twice. Other drifted objects cannot be enumerated
  without read-only inspection of the live database.
- **`ANDROID_LOCAL_PROPERTIES` secret contents.** Consumed by
  `.github/workflows/release-builds.yml:509`; outside the repo.
- **Whether `DiscoverCacheDao.deleteExpired` / `deleteLegacyNamespaces` are ever
  called.** The methods exist but no caller was found in tracked Android source.
- **Postgres `normalized_word` correctness** for Turkish `İ`, German `ß` or
  decomposed input, given no DB-side normalization.
- **`reading_stats` real-world impact.** The PK collision is proven from the schema;
  how often two accounts share a `book_id` was not measured.
- **Rust replay safety for `0014`/`0019`/`0020`.** The missing guards are measured;
  whether any real database is half-applied is not.
