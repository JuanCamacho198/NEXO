-- 20261002000001_storage_layout_retention.sql
-- FR-14 retention for Supabase. Soft-deleted rows older than 30 days are
-- pruned by this function; live rows (`deleted_at IS NULL`) are never touched.
--
-- Executor (decision 0.3, packages/drive-filename-fixtures/DECISIONS.md):
-- this repository has NO pg_cron (grep for `cron.schedule` is clean), so the
-- function is deliberately NOT scheduled here. It is an operator/service-role
-- maintenance entry point; scheduling is external (Supabase scheduled
-- functions / dashboard cron / an operator job). The app-driven half of the
-- policy runs on-device: Android WorkManager (RetentionPruneWorker) prunes
-- Room, and desktop SQLite prunes its own store at startup-idle
-- (desktop/src-tauri/src/retention.rs). Postgres storage reclamation is left
-- to autovacuum, which runs on its own schedule.
--
-- The function runs SECURITY DEFINER because a global tombstone sweep must
-- span every user's rows and therefore cannot be subject to per-user RLS.
-- EXECUTE is revoked from PUBLIC/anon/authenticated so a client session can
-- never call it; only service_role (and operators) may.

create or replace function public.prune_soft_deleted(
  p_older_than interval default interval '30 days'
)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_cutoff  timestamptz := now() - p_older_than;
  v_removed integer := 0;
  v_count   integer;
begin
  -- bookmarks.user_id / highlights.user_id / user_books.user_id /
  -- user_dictionary_words.user_id are the ownership keys; the trust anchor is
  -- the caller's server-side role, not a client-supplied id.
  delete from public.bookmarks
   where deleted_at is not null and deleted_at < v_cutoff;
  get diagnostics v_count = row_count;
  v_removed := v_removed + v_count;

  delete from public.highlights
   where deleted_at is not null and deleted_at < v_cutoff;
  get diagnostics v_count = row_count;
  v_removed := v_removed + v_count;

  delete from public.user_books
   where deleted_at is not null and deleted_at < v_cutoff;
  get diagnostics v_count = row_count;
  v_removed := v_removed + v_count;

  delete from public.user_dictionary_words
   where deleted_at is not null and deleted_at < v_cutoff;
  get diagnostics v_count = row_count;
  v_removed := v_removed + v_count;

  return v_removed;
end;
$$;

comment on function public.prune_soft_deleted(interval) is
  'FR-14 retention: deletes soft-deleted rows older than p_older_than across bookmarks, highlights, user_books, user_dictionary_words. Not scheduled in-repo (no pg_cron); invoke from an operator/service-role job. Live rows are never touched.';

-- Server-side only: strip the default PUBLIC EXECUTE and grant to service_role.
revoke all on function public.prune_soft_deleted(interval) from public;
revoke all on function public.prune_soft_deleted(interval) from anon;
revoke all on function public.prune_soft_deleted(interval) from authenticated;
grant execute on function public.prune_soft_deleted(interval) to service_role;
