-- 20261001000002_add_user_books_metadata_columns.sql
-- Schema-drift repair: `public.user_books` carries six metadata columns that
-- exist in the live database and are read and written by the Android client
-- (SupabaseBookCatalogDataSource.UserBookRow + upsertBook), but that no
-- migration in this repository created. The `user_books` create migration and
-- the Android data class both admit, in comments, that these columns were
-- applied out of band through the Supabase MCP during the SDD apply phase, so a
-- clean rebuild from migrations was missing them.
--
-- This migration exists so a clean rebuild reproduces production. It is
-- additive and idempotent: every column already exists in production, so each
-- `add column if not exists` is a no-op there. Nothing is dropped, renamed,
-- rewritten, or backfilled, and no default or NOT NULL constraint is added.
--
-- Live shape (read-only inspection of the production database), per column:
--   file_size      bigint null, no default
--   genre          text   null, no default
--   language       text   null, no default
--   publisher      text   null, no default
--   tags           text   null, no default
--   published_date text   null, no default
--
-- No index, constraint, RLS policy, or realtime publication entry references
-- any of these six columns, so this migration leaves all of those untouched.
-- The desktop client (SupabaseBookCatalogSync) neither reads nor writes them;
-- Android is the sole writer.

alter table public.user_books
  add column if not exists file_size      bigint,
  add column if not exists genre          text,
  add column if not exists language       text,
  add column if not exists publisher      text,
  add column if not exists tags           text,
  add column if not exists published_date text;
