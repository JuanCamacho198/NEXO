# PR1 open-item verdicts (`storage-layout-and-sync`)

## 0.2 Android prune scheduler: WorkManager, scheduler-only

Retention prune on Android runs as a WorkManager periodic prune worker that
follows `data/remote/work/WorkManagerOutboxDrainScheduler.kt` (unique work,
`ExistingWorkPolicy.KEEP`, lazy `WorkManager` resolution, wired via a `by
lazy` slot in `di/AppContainer.kt`). No new scheduler framework, no coupling
to the sync tick. The prune hook itself ships in WU3.

## 0.3 Supabase retention executor: app-driven (no `pg_cron`)

Repo grep for `pg_cron` / `cron.schedule` is clean, so the default is
app-driven prune (desktop startup-idle check + Android WorkManager from 0.2).
The WU3 retention migration creates the SQL prune FUNCTION with scheduling
documented, but does NOT call `cron.schedule`. If `pg_cron` is later proven
enabled, scheduling moves into the migration explicitly.

## 0.4 `reading_state` audit: local-only, no server migration

`supabase/migrations/20260709000009_create_user_books.sql`,
`20260806000001_cross_device_library_recovery.sql`, and
`20261001000002_add_user_books_metadata_columns.sql` contain NO
`reading_state` column (migration-wide grep is clean), and neither does
`20261001000001_create_reading_sessions.sql`. The column exists only in
Android Room (`books.reading_state`, `AppDatabaseMigrations.kt`) and in
desktop SQLite scope. Verdict: FR-12 indexes are local-only (Room + desktop
SQLite). No Supabase `reading_state` migration in this change.
