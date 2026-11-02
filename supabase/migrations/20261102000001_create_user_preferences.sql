-- 20261102000001_create_user_preferences.sql
-- Cross-device account preferences. Today the only column is the daily reading
-- goal, which until now lived exclusively in device-local SharedPreferences
-- (`nexo_reading_goal__{userId}.xml`), so a second signed-in device was asked to
-- choose it again and — because `OnboardingGate` infers "onboarding completed"
-- from "the account has a goal" — was shown the onboarding step despite the
-- account having already completed it elsewhere.
--
-- Why a dedicated table (option A in the ODD):
--   - B (goal on `user_books`) is wrong: the goal is not per book.
--   - C (Drive cold backup) only covers the restore path, not "second device,
--     signed in, online".
--   - A is the only shape that answers "second device with a session" directly
--     and stays open for future account preferences (theme, language, ...).
--
-- Shape note: the DDL matches the live/sibling convention — column names in
-- snake_case, `user_id` as PK + FK to `auth.users(id) on delete cascade`, and a
-- NOT NULL `updated_at` defaulting to now(). No `id` surrogate key is added:
-- preferences are one row per account, so `user_id` IS the natural key, which
-- also makes the upsert conflict target obvious (`onConflict = "user_id"`).
--
-- `updated_at` is the last-write-wins clock the client carries (same convention
-- as `reading_progress` / `reading_sessions`). Clients always send the value
-- they want to win, so the server default is only a fallback for a write that
-- omits it.
--
-- RLS: enabled with the four `auth.uid() = user_id` policies, mirroring
-- `user_dictionary_words` / `user_books` / `reading_sessions`. There is no
-- `or supabase_user_id is null` escape hatch — that belongs only to `devices`.
-- Reads and writes are scoped to `authenticated`; the anonymous/local session
-- (decision D5) owns nothing and never reaches this table.

create table if not exists public.user_preferences (
  user_id            uuid primary key references auth.users(id) on delete cascade,
  daily_goal_minutes int,
  updated_at         timestamptz not null default now()
);

-- Idempotent backfill for a pre-existing out-of-band table (partial shape). On
-- production and on a clean rebuild each statement is a no-op. The PK and the FK
-- are owned by the `create table if not exists` above.
alter table public.user_preferences
  add column if not exists daily_goal_minutes int,
  add column if not exists updated_at         timestamptz not null default now();

alter table public.user_preferences enable row level security;

-- Ownership policies mirror the sibling tables exactly: `for <cmd> to
-- authenticated` keyed on `auth.uid() = user_id`, with WITH CHECK on the write
-- policies so a row can never be re-parented onto another user.
drop policy if exists "user_preferences_select" on public.user_preferences;
create policy "user_preferences_select" on public.user_preferences
  for select to authenticated
  using ((select auth.uid()) = user_id);

drop policy if exists "user_preferences_insert" on public.user_preferences;
create policy "user_preferences_insert" on public.user_preferences
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

drop policy if exists "user_preferences_update" on public.user_preferences;
create policy "user_preferences_update" on public.user_preferences
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "user_preferences_delete" on public.user_preferences;
create policy "user_preferences_delete" on public.user_preferences
  for delete to authenticated
  using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.user_preferences to authenticated;

-- Verify (run separately):
--   select tablename, policyname, cmd, qual, with_check
--   from pg_policies where schemaname = 'public' and tablename = 'user_preferences'
--   order by policyname;
-- Expect: 4 rows, every `qual` / `with_check` containing `auth.uid()`, and both
--         write policies declaring with_check.
