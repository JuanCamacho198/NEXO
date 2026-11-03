-- 0024_translation_preferences_and_cache: per-account translation settings
-- (T3) and the per-account translation cache/history (T4).
--
-- Why a table for the preferences instead of two `app_settings` keys: the
-- preference is a PAIR (source + target) whose sides are meaningful together,
-- and `validate_setting` refuses the nested JSON object that would be needed to
-- keep that pair atomic in `app_settings`. Two loose scalar keys can
-- desynchronise; a row cannot.
--
-- Why the cache lives here as a table and not as an in-memory map: a
-- translation cache IS reading history — on Android the cache was global and
-- leaked between accounts. Scoping every row by `user_id` is what makes it
-- safe, and the same `user_id` also scopes the history, so there is exactly one
-- table for one fact (`translation_cache` IS the history; a second history
-- table would be a second writer for the same event).
--
-- Ownership contract (option A, product decision): the cache is written and
-- read ONLY while an account is active. `user_id` is therefore
-- `TEXT NOT NULL DEFAULT ''` for uniformity with `0015_dictionary_sync`, but
-- the empty string is never written — the cache layer short-circuits before
-- touching SQL when `require_user_id()` is empty. See
-- `repository/translation.rs::read_cached_translation` and
-- `cache_translation` for the enforcement point.
--
-- The cache is NOT synced: it is a local accelerator plus a local history, and
-- the same user on another device simply re-populates it from MyMemory. That is
-- also why there is no `deleted_at` / `synced_at` pair here.

-- ── T3: per-account language preferences ───────────────────────────────────
-- One row per account. NULL on a side means "infer": source from the book's
-- language, target from the app locale (see the frontend
-- `translationLanguageResolution.ts`, which is the single place that resolves
-- those NULLs).
CREATE TABLE IF NOT EXISTS translation_preferences (
  user_id TEXT NOT NULL DEFAULT '',
  source_language TEXT,
  target_language TEXT,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- One preference row per account. Mirrors `idx_dict_user_norm` in 0015: the
-- natural key is `(user_id, ...)`, and without this the "read the row for this
-- account" path and the upsert target both become order-dependent scans.
CREATE UNIQUE INDEX IF NOT EXISTS idx_translation_preferences_user
  ON translation_preferences(user_id);

-- ── T4: per-account translation cache (and history) ────────────────────────
-- `cache_key` is SHA-256(owner|source|target|text) as lowercase hex, computed
-- in Rust by `translation_cache_key`. A hash rather than the raw text keeps the
-- UNIQUE index bounded no matter how long a selection is: MyMemory fails any
-- request past `TRANSLATE_MAX_LEN` (2000 chars), so a key column holding the
-- text itself would index up to 2 KB per row. The full `original_text` is still
-- stored beside the key, so the hash is emphatically NOT the storage — it only
-- decides identity.
--
-- The owner is part of the key even though `user_id` is also a column, because
-- the key is what the lookup consults; leaving the identity to be reassembled
-- from two columns is exactly the class of bug the composite UNIQUE prevents.
--
-- `original_text`/`translated_text` carry `DEFAULT ''` for the same reason as
-- `user_id`: uniformity with the 0015 columns, not a value ever written — the
-- layer above refuses an empty text before it reaches SQL.
--
-- `source_language`/`target_language` are persisted verbatim from the request
-- and are NOT normalized here. The backend cannot normalize them consistently
-- with the client that built the key (the pair arrives already resolved by
-- `translationLanguageResolution.ts`), and a second normalization rule in Rust
-- would silently split one logical entry into two rows.
CREATE TABLE IF NOT EXISTS translation_cache (
  user_id TEXT NOT NULL DEFAULT '',
  cache_key TEXT NOT NULL,
  source_language TEXT NOT NULL,
  target_language TEXT NOT NULL,
  original_text TEXT NOT NULL DEFAULT '',
  translated_text TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- The one key the cache ever looks up, and the upsert target.
CREATE UNIQUE INDEX IF NOT EXISTS idx_translation_cache_user_key
  ON translation_cache(user_id, cache_key);

-- History read: newest first, per account. `cache_key` is the tiebreaker so
-- two rows written in the same millisecond keep a stable order across reads
-- (same reasoning as `idx_notifications_created_at`).
CREATE INDEX IF NOT EXISTS idx_translation_cache_user_created
  ON translation_cache(user_id, created_at DESC, cache_key DESC);
