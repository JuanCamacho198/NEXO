-- 0022_book_provenance: where an imported book came from, for books delivered
-- by a v3 addon. `source_item_id` holds the addon-namespaced id (for example
-- `gutenberg:1342`); the prefixes are frozen after protocol 3.0.0, so this
-- column outlives any single addon. `isbn13` is the edition key addons use for
-- a book that exists on several upstream catalogs.
--
-- All three are nullable and NOT backfilled: books imported before the v3
-- protocol have no knowable provenance, and guessing one would be wrong. No
-- unique constraint either — duplicate books already exist in production data,
-- and enforcing uniqueness is the importer's job (dedupe happens before the
-- download, not at the storage layer).
ALTER TABLE books ADD COLUMN source_addon_id TEXT;
ALTER TABLE books ADD COLUMN source_item_id TEXT;
ALTER TABLE books ADD COLUMN isbn13 TEXT;
