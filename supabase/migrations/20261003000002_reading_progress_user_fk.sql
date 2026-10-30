-- Migration: add the missing FK from public.reading_progress.user_id to auth.users(id)
--
-- Gap closed:
--   public.reading_progress (20260709000005_create_reading_progress.sql) declared
--   `user_id uuid not null` with NO foreign key, unlike every sibling table
--   (bookmarks, highlights, highlight_tags, user_books, user_dictionary_words,
--   reading_sessions), all of which declare `references auth.users(id) on delete
--   cascade`. The consequence was that deleting an auth user orphaned their
--   reading_progress rows.
--
-- Approach for pre-existing rows (chosen, not assumed):
--   The constraint is added NOT VALID and then validated as a separate, explicit
--   step. This was chosen over `delete from ... where user_id not in (...)`
--   because the target database was not reachable from the authoring environment
--   (no psql, no supabase CLI, only anon keys in .env), so the table contents
--   could not be inspected. NOT VALID makes the ALTER TABLE unable to fail on
--   existing orphan rows and makes no destructive assumption: no data is deleted.
--   The VALIDATE CONSTRAINT that follows is the visible step that reports, at
--   apply time, whether orphan rows actually exist. If it errors, the fix is a
--   deliberate data decision (backfill or delete), not a silent side effect of
--   this migration.
--
-- Note: NOT VALID only skips the check of EXISTING rows; the FK is enforced for
-- every INSERT and UPDATE immediately after the ALTER TABLE, even before VALIDATE.

-- Idempotency: drop any prior constraint of the same explicit name before adding,
-- so a re-run does not error on duplicate_object.
alter table public.reading_progress
  drop constraint if exists reading_progress_user_id_fkey;

alter table public.reading_progress
  add constraint reading_progress_user_id_fkey
    foreign key (user_id) references auth.users(id) on delete cascade
    not valid;

-- Separate, visible validation step (see the approach note above).
alter table public.reading_progress
  validate constraint reading_progress_user_id_fkey;
