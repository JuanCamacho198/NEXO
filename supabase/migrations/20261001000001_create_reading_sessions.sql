-- 20261001000001_create_reading_sessions.sql
-- Schema-drift repair: `public.reading_sessions` is read and written by both
-- clients (Android SupabaseProgressDataSource / SupabaseProgressSync, desktop
-- SupabaseProgressSync) and is subscribed to over the `sessions:uid` realtime
-- channel, but no migration in this repository created it. The DDL below was
-- derived from the live database (read-only inspection) and cross-checked
-- against the client usage. It is additive and idempotent, so it is a no-op on
-- production, where the table already exists.
--
-- Live shape: 10 columns, PK `id`, FK `user_id -> auth.users(id) on delete
-- cascade`, RLS enabled with the four `auth.uid() = user_id` policies below,
-- and default replica identity. The live database is missing only membership
-- in the `supabase_realtime` publication, which this migration adds.
--
-- Conflict model (both clients): deterministic `id` primary key, upserted with
-- `onConflict = 'id'`; `updated_at` is the client-carried LWW clock; `user_id`
-- is the realtime filter and ownership key. There is no tombstone column.

create table if not exists public.reading_sessions (
  id               text primary key,
  user_id          uuid not null references auth.users(id) on delete cascade,
  book_id          text not null,
  started_at       timestamptz not null,
  duration_minutes int not null,
  date             timestamptz not null,
  device           text not null default 'android',
  updated_at       timestamptz not null default now(),
  start_percentage real,
  end_percentage   real
);

-- Idempotent backfill for an out-of-band table with a partial shape. On
-- production and on a clean rebuild every column already exists, so each
-- statement is a no-op. The PK (`id`) and the FK are owned by the
-- `create table if not exists` above.
alter table public.reading_sessions
  add column if not exists user_id          uuid,
  add column if not exists book_id          text,
  add column if not exists started_at       timestamptz,
  add column if not exists duration_minutes int,
  add column if not exists date             timestamptz,
  add column if not exists device           text not null default 'android',
  add column if not exists updated_at       timestamptz not null default now(),
  add column if not exists start_percentage real,
  add column if not exists end_percentage   real;

-- Clients read every session for a user, and the live database already carries
-- the (user_id, date) index. The book-scoped index covers per-book lookups.
create index if not exists reading_sessions_user_date_idx
  on public.reading_sessions(user_id, date);
create index if not exists reading_sessions_user_book_idx
  on public.reading_sessions(user_id, book_id);

alter table public.reading_sessions enable row level security;

-- Ownership policies mirror the sibling user_dictionary_words / user_books
-- pattern exactly: `for <cmd> to authenticated` keyed on `auth.uid() = user_id`.
-- The `or supabase_user_id is null` escape hatch used by `devices` is
-- deliberately NOT copied. The live policies carry these exact names, so this
-- block recreates them identically.
drop policy if exists "reading_sessions_select" on public.reading_sessions;
create policy "reading_sessions_select" on public.reading_sessions
  for select to authenticated
  using ((select auth.uid()) = user_id);

drop policy if exists "reading_sessions_insert" on public.reading_sessions;
create policy "reading_sessions_insert" on public.reading_sessions
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

drop policy if exists "reading_sessions_update" on public.reading_sessions;
create policy "reading_sessions_update" on public.reading_sessions
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "reading_sessions_delete" on public.reading_sessions;
create policy "reading_sessions_delete" on public.reading_sessions
  for delete to authenticated
  using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.reading_sessions to authenticated;

-- Realtime: both clients subscribe to the `sessions:uid` channel, so the table
-- must be a member of `supabase_realtime`. It is NOT a member in the live
-- database today, which is why the subscription never fires. Guarded so the
-- migration stays idempotent; siblings add the table with the same ALTER.
do $$
begin
  if not exists (
    select 1
    from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'reading_sessions'
  ) then
    alter publication supabase_realtime add table public.reading_sessions;
  end if;
end
$$;
