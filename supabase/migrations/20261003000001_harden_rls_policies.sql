-- Migration: Harden RLS policies (3 authorization holes)
--
-- Closes:
--   1. public.devices  -- null-owner bypass on select/update/delete.
--   2. public.highlight_tags -- "for all" policy had no WITH CHECK, so writes
--      were not validated against the parent highlight's owner.
--   3. public.reading_progress -- update policy had no WITH CHECK, so a row
--      could be re-parented onto another user.
--
-- BEHAVIOUR CHANGE (intended, do not undo):
--   After this migration, every row in public.devices whose supabase_user_id
--   IS NULL becomes invisible and immutable to every authenticated client.
--   Those rows are no longer selectable, updatable or deletable by anyone.
--   There are no confirmed production users on such rows and no mechanism to
--   claim them, so no backfill is performed. The INSERT policy is intentionally
--   left untouched so anonymous device registration keeps working.

-- ============================================================================
-- Hole 1: public.devices -- null-owner bypass
-- ============================================================================

drop policy if exists "users_select_own_devices" on public.devices;
create policy "users_select_own_devices" on public.devices
  for select
  using (auth.uid()::text = supabase_user_id::text);

-- "users_insert_own_devices" is intentionally NOT touched: anonymous device
-- registration (supabase_user_id is null and auth.uid() is null) stays valid.

drop policy if exists "users_update_own_devices" on public.devices;
create policy "users_update_own_devices" on public.devices
  for update
  using (auth.uid()::text = supabase_user_id::text)
  with check (auth.uid()::text = supabase_user_id::text);

drop policy if exists "users_delete_own_devices" on public.devices;
create policy "users_delete_own_devices" on public.devices
  for delete
  using (auth.uid()::text = supabase_user_id::text);

-- ============================================================================
-- Hole 2: public.highlight_tags -- missing WITH CHECK
-- ============================================================================

drop policy if exists "Users own highlight_tags" on public.highlight_tags;
create policy "Users own highlight_tags"
  on public.highlight_tags for all
  using (
    exists (select 1 from public.highlights where id = highlight_id and user_id = auth.uid())
  )
  with check (
    exists (select 1 from public.highlights where id = highlight_id and user_id = auth.uid())
  );

-- ============================================================================
-- Hole 3: public.reading_progress -- update policy missing WITH CHECK
-- ============================================================================

drop policy if exists "Users can update own reading progress" on public.reading_progress;
create policy "Users can update own reading progress"
  on public.reading_progress for update
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- Verify (run separately):
--   select tablename, policyname, cmd, qual, with_check
--   from pg_policies where schemaname = 'public' order by tablename, policyname;
-- Expect: no `qual` or `with_check` containing `is null` for devices
--         select/update/delete, and every `for all` / `for update` policy
--         declares with_check.
