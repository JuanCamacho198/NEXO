-- 0027_book_translation_source: the per-BOOK source language preference.
--
-- Why a table and not a column on `translation_preferences`. That table is keyed
-- by `user_id` alone — its UNIQUE index is `idx_translation_preferences_user`
-- and the natural key is the ACCOUNT — so a per-book value has no place to live
-- there: one row per account can hold exactly one source preference, and a
-- second row for the same account would violate that index. The alternative, a
-- `book_translation_preferences` JSON blob inside the existing row, would make
-- the account's preference and every book's preference one indivisible write,
-- so setting a language for the book being read would rewrite the general
-- preference of the account as a side effect. Different facts, different
-- lifetimes, different keys: one fact per table.
--
-- Why the model needs two levels at all. Until now a language picked from the
-- in-reader language bar was written to the account (`translation_preferences`)
-- and therefore LEAKED into every other book: choosing Spanish for a French
-- novel silently re-labelled the source of an English one. The resolution is
-- now ordered, and the BOOK wins:
--   1. this table's `source_language` for the book being read, when set;
--   2. `translation_preferences.source_language` for the account;
--   3. inferred — the book's metadata language, then local detection;
--   4. refusal (`unknown-source`), which the selection panel turns into a
--      question.
-- Steps 1 and 2 are the same KIND of value at two different scopes, which is
-- why they are two tables rather than one: the scope is the key.
--
-- Why SOURCE ONLY, with no target column. The source is a property of the TEXT
-- being read — a French novel is French for every reader, so "what language is
-- this book" is a question about the book and belongs next to it. The target is
-- a property of the READER — "which language do I want translations in" — and
-- is the same answer in every book they open. A per-book target would be a
-- setting that silently changes when the reader changes books, which is the
-- opposite of what a preference is for; and it would duplicate a decision the
-- account already holds. The destination stays in `translation_preferences`,
-- and this table deliberately has no column for it.
--
-- What NULL means, and how it differs from "automatic". A NULL `source_language`
-- is NOT the value "automatic" or "detect". It is the ABSENCE of a choice for
-- this book — it is what makes the resolution FALL THROUGH to the account's
-- general preference, and from there to inference. Storing the string
-- `'automatic'` instead would be a different fact: it would be a decision the
-- reader made FOR THIS BOOK, pinned in this table, and it would stop the cascade
-- at step 1 — the account preference would never be consulted again, for this
-- book or any other, and the general setting would appear broken. The two are
-- therefore not interchangeable, and no sentinel string is used. This is also
-- why there is no default row: an absent row and a row with a NULL source mean
-- the same thing ("no choice for this book"), so nothing is written until the
-- reader actually picks one, and `NULL` is a storable value so the reader can
-- undo a choice without deleting the row.
--
-- Ownership contract (option A, product decision), identical to 0024/0025:
-- rows are written and read ONLY while an account is active. `user_id` is
-- therefore `TEXT NOT NULL DEFAULT ''` for uniformity with `0015_dictionary_sync`
-- and `0024`, but the empty string is never written — the repository
-- short-circuits before touching SQL (`repository/translation.rs`). The check
-- lives in the repository, not in the command, because it is a STORAGE rule:
-- every write path inherits it and no caller can opt out of it.
--
-- The FK to `books` is `ON DELETE CASCADE`, matching `0003`/`0004`/`0012`: a
-- preference for a book that no longer exists is not a fact worth keeping, and
-- `PRAGMA foreign_keys = ON` is set on every connection (`db.rs`). It is scoped
-- by `user_id` as well as `book_id` because the same physical book, deleted and
-- re-imported by another account on a shared machine, must not inherit the first
-- reader's choice.
--
-- Not synced, like the other per-account tables here: it is a local display
-- preference. A second device re-establishes its own, and there is deliberately
-- no `deleted_at` / `synced_at` pair.

-- One row per (account, book).
CREATE TABLE IF NOT EXISTS book_translation_source (
  user_id TEXT NOT NULL DEFAULT '',
  book_id TEXT NOT NULL,
  -- ISO 639-1 code, or NULL meaning "the reader has made NO choice for this
  -- book" — see the header. Never a sentinel string.
  source_language TEXT,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
);

-- The natural key, and the upsert target. Mirrors
-- `idx_translation_preferences_user` (0024): without it, both "read this book's
-- source for this account" and the `ON CONFLICT(user_id, book_id)` upsert become
-- order-dependent scans, and a duplicate row per book could be inserted.
CREATE UNIQUE INDEX IF NOT EXISTS idx_book_translation_source_user_book
  ON book_translation_source(user_id, book_id);
