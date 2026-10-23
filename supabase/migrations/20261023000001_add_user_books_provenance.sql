-- 20261023000001_add_user_books_provenance.sql
-- Addon provenance on the cloud catalog: where an imported book came from when
-- a v3 addon delivered it.
--
--   source_addon_id text null, no default -- manifest id, e.g. org.example.gutendex
--   source_item_id  text null, no default -- namespaced item id, e.g. gutenberg:1342
--   isbn13          text null, no default -- edition key shared across catalogs
--
-- Additive and idempotent: `add column if not exists` is a no-op on any
-- environment that already carries the columns. Nothing is dropped, renamed,
-- rewritten, or backfilled — rows synced before this migration keep NULL,
-- because their provenance is unknowable and a guess would be wrong.
--
-- Deliberately NOT NULL and NOT unique: rows synced by older clients omit the
-- columns entirely, and duplicate books already exist in production data.
-- Dedupe is enforced by the importer before download, not by a constraint here.
--
-- No index, RLS policy, or realtime publication entry is added: the columns are
-- read with the rest of the row and never filtered server-side.

alter table public.user_books
  add column if not exists source_addon_id text,
  add column if not exists source_item_id  text,
  add column if not exists isbn13          text;
