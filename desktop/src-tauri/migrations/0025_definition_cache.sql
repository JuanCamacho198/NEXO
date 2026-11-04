-- 0025_definition_cache: per-account cache of "Definir" results.
--
-- Why a NEW table and not `translation_cache` (0024). The translation cache key
-- is `sha256(owner|source|target|text)`, and that shape assumes a LANGUAGE PAIR:
-- every row carries `source_language` and `target_language` because MyMemory
-- needs both to answer. A definition has no pair. It has a WORD and the language
-- the definition is written in, and the source of the gloss is decided by the
-- cascade, not by the caller. Folding definitions into `translation_cache` would
-- force a fabricated `target_language` (or a fabricated `source_language`) into
-- every row just to satisfy two `NOT NULL` columns — and the history view, which
-- reads that table, would then show definition rows as translations with a pair
-- the user never chose. One fact, one table: definitions get their own.
--
-- Why persist at all. `commands/translate.rs` documents the measured Android bug
-- this project already paid for: a cache that was GLOBAL leaked one account's
-- reading history to another. The answer is not "no cache", it is "a cache that
-- cannot be read across accounts". Definitions are public (Wiktionary), so the
-- privacy stakes are lower than the translation cache, but the rule is
-- unconditional here because it is the rule the repository already enforces:
-- scoping by `user_id` is what makes a persistent cache safe, and a second
-- ownership mechanism would be a second thing to get wrong.
--
-- The cache also earns its keep beyond privacy: a definition costs TWO
-- Wiktionary round-trips plus a MyMemory call in the worst case (see
-- `run_cascade`), so a hit is the difference between three network requests and
-- zero — and the app stays usable while offline for any word already looked up.
--
-- Ownership contract (option A, product decision). Rows are written and read
-- ONLY while an account is active. `user_id` is `TEXT NOT NULL DEFAULT ''` for
-- uniformity with `0015_dictionary_sync` and `0024`, but the empty string is
-- never written: `repository/definition.rs::cache_owner` short-circuits before
-- touching SQL. That check lives in the repository and not in the command
-- because it is a STORAGE rule — every write path inherits it and no caller can
-- opt out of it. See `commands/define.rs`, which deliberately does NOT branch on
-- anonymity.
--
-- Not synced, like the translation cache: it is a local accelerator. The same
-- account on another device re-populates it from Wiktionary. That is also why
-- there is no `deleted_at` / `synced_at` pair.

-- One row per (account, word, definition language, reader language).
--
-- `word` and `word_language` are stored VERBATIM as the caller supplied them,
-- at the same boundary `0024` draws for its language pair: the cascade matches
-- `{{lengua|XX}}` blocks case-sensitively, so a normalization rule in Rust could
-- silently split one logical entry into two rows, or — worse — merge two
-- different words into one. Identity is decided by `cache_key`; these columns
-- are the payload.
--
-- `definitions` is the parsed result as JSON, not one row per definition sense.
-- DECISION, and the trade-off it pays: a definition result is only ever read or
-- written WHOLE — the reader shows the list, or none of it — so a per-sense
-- table would add a second writer, a second read path and an ordering column for
-- a fact no caller queries by. The cost is asymmetry with the lookup: the row's
-- identity is `(user, word, languages)`, so a per-sense table would index by the
-- same key anyway and gain nothing. The cost of JSON is that a short word whose
-- senses change upstream keeps its cached senses until it is overwritten, which
-- is exactly the staleness every cache trades for, and the reason each write
-- refreshes the whole row.
--
-- `source` is `native` or `translated` and it is PERSISTED, not recomputed: the
-- UI warns the reader when a gloss is machine-translated, so a hit that returned
-- the definitions without the source would show a machine translation as if a
-- human had written it. That is the one field a cache must not lose.
--
-- `attribution` is stored rather than re-derived from a constant, because the
-- CC BY-SA license requires it to accompany the text. A future source (a
-- different wiki, a different license) changes the string for NEW rows without
-- invalidating the old ones.
CREATE TABLE IF NOT EXISTS definition_cache (
  user_id TEXT NOT NULL DEFAULT '',
  cache_key TEXT NOT NULL,
  word TEXT NOT NULL DEFAULT '',
  word_language TEXT NOT NULL DEFAULT '',
  reader_language TEXT NOT NULL DEFAULT '',
  definitions TEXT NOT NULL DEFAULT '[]',
  source TEXT NOT NULL DEFAULT '',
  attribution TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- The one key the cache ever looks up, and the upsert target. Mirrors
-- `idx_dict_user_norm` (0015) and `idx_translation_cache_user_key` (0024): the
-- natural key is `(user_id, ...)`, and without this index both the "read this
-- account's entry" path and the upsert become order-dependent scans.
CREATE UNIQUE INDEX IF NOT EXISTS idx_definition_cache_user_key
  ON definition_cache(user_id, cache_key);

-- History read, if and when a UI wants one: newest first, per account.
-- `cache_key` is the tiebreaker so two rows written in the same millisecond keep
-- a stable order across reads (same reasoning as
-- `idx_translation_cache_user_created`). It costs one index and makes the
-- ordering deterministic instead of accidental; there is no prune (see below),
-- so nothing else consumes it today.
CREATE INDEX IF NOT EXISTS idx_definition_cache_user_created
  ON definition_cache(user_id, created_at DESC, cache_key DESC);

-- No prune, and no TTL. Both are deliberate; the ODD asks for neither.
--
-- NO TTL. The ODD's own observation is that Wiktionary wikitext changes slowly,
-- and that is the honest reading: a definition cached today is very likely still
-- correct in a month. A TTL would therefore cost a schema column, a comparison
-- on every read, and a class of "the cache expired so we paid three network
-- calls again" behavior, while buying near-nothing: the value it protects
-- against is a rare upstream correction, and the cost of being stale is a
-- slightly dated gloss on one word, not a wrong answer. Refresh is instead
-- edge-triggered by the reader's own behavior — a re-definition of the same word
-- overwrites the row (see the `ON CONFLICT` upsert in
-- `repository/definition.rs::cache_definition`).
--
-- NO prune. The translation cache is pruned because it is unbounded by
-- construction: it stores arbitrary user selections, up to 2000 characters each,
-- and 0024 caps it at 500 rows per account so the file cannot grow for the life
-- of the install. This table's growth is bounded by something else entirely —
-- VOCABULARY. A row exists only for a word the reader actually looked up, and a
-- reader's distinct looked-up words are naturally in the hundreds to low
-- thousands, not unbounded. A prune would need a cap chosen with no data behind
-- it (500? 5000?), and that cap would START DELETING ROWS THE READER STILL WANTS
-- while saving a few hundred KB. The honest position: measure first. If a real
-- install ever shows this table growing without bound, the fix is the same
-- `LIMIT -1 OFFSET n` sweep 0024 already uses, and `created_at` plus
-- `idx_definition_cache_user_created` are in place for it.
