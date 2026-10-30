-- Bookmarks carried only a page/position/title triple, so the CFI location that
-- the reader actually needs was never stored locally. The frontend has been
-- sending `cfiLocation` and `locatorJson` all along (bookmarksState.svelte.ts:85),
-- but it routed them to Supabase through the outbox; the local row kept nothing
-- that could resolve back to a place in the book.
--
-- `updated_at` is added for the same reason: docs/sync.md defines the conflict
-- rule as `remote.updatedAt > local`, and a bookmark row had no clock to compare.
--
-- Nullable on purpose: existing rows have no CFI and cannot invent one, and
-- `page` stays as the display fallback (docs/sync.md:29 — page is derived, never
-- canonical). Backfill `updated_at` from `created_at` so every row has a clock.
ALTER TABLE bookmarks ADD COLUMN cfi_location TEXT;
ALTER TABLE bookmarks ADD COLUMN locator_json TEXT;
ALTER TABLE bookmarks ADD COLUMN updated_at TEXT;

UPDATE bookmarks SET updated_at = created_at WHERE updated_at IS NULL OR updated_at = '';

CREATE INDEX IF NOT EXISTS idx_bookmarks_updated_at ON bookmarks(updated_at);
