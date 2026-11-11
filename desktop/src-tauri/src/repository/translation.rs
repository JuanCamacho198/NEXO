use chrono::Utc;
use rusqlite::{params, OptionalExtension};
use sha2::{Digest, Sha256};

use crate::error::{AppError, AppResult};
use crate::models::{
    BookTranslationSourceDto, TranslationCacheEntryDto, TranslationPreferencesDto,
};

use super::LibraryRepository;

/// Longest selection the reader will hand to the translator. Mirrors
/// `commands::translate::TRANSLATE_MAX_LEN`; kept as its own constant so the
/// repository does not depend on the command module just for a bound.
const MAX_TRANSLATION_TEXT_LEN: usize = 2000;

/// Bound on a language code. The longest ISO 639-1 tag is two letters, so 16 is
/// generous room for a region-shaped tag (`en-US`) without accepting a payload
/// that would be stored verbatim.
const MAX_LANGUAGE_TAG_LEN: usize = 16;

/// How many history rows `list_translation_history` returns at most. The
/// history is a list in the UI, not an export, so this is a display bound.
const TRANSLATION_HISTORY_LIMIT: i64 = 200;

/// Rows of translation cache one account may keep. Android uses 500, and this
/// mirrors it deliberately rather than inventing a second number: the two
/// platforms share the reader, so a user who translates on both should see the
/// same history depth, not two.
///
/// 500 is also the right *shape* for this table. The history view shows at most
/// `TRANSLATION_HISTORY_LIMIT` (200) rows, so the cap stays comfortably above
/// what the UI can even display while still bounding an otherwise unbounded
/// growth: at the 2000-char reader maximum a full cache is a few hundred KB,
/// not a file that grows for the life of the install.
pub const TRANSLATION_CACHE_MAX_ROWS: i64 = 500;

/// The canonical pre-image of the cache key: `owner|source|target|text`, pipe
/// separated, no quoting, in that exact order.
///
/// The owner is inside the hash for the same reason it is a column: two
/// accounts translating the same sentence must not collide. `|` is safe as a
/// separator because the first three fields cannot contain one — a language tag
/// is `[A-Za-z-]` after validation and the owner is an opaque id — so the split
/// between the three fixed fields and the free text is unambiguous. The text is
/// deliberately LAST and unescaped: it is the only field that may contain a
/// pipe, and nothing after it is parsed.
fn translation_key_preimage(
    user_id: &str,
    source_language: &str,
    target_language: &str,
    text: &str,
) -> String {
    format!("{}|{}|{}|{}", user_id, source_language, target_language, text)
}

/// SHA-256 of the pre-image as lowercase hex.
///
/// A hash rather than the raw text keeps the UNIQUE index bounded: the text can
/// be up to `MAX_TRANSLATION_TEXT_LEN` characters, the key is always 64. The
/// repository still stores `original_text`, so the hash decides identity only —
/// it is not the storage. SHA-256 (already a dependency, already used for the
/// reading-session id in `progress.rs`) is chosen for cross-platform parity: the
/// same function, from the same input, yields the same key on every platform
/// that could ever read this cache.
pub fn translation_cache_key(
    user_id: &str,
    source_language: &str,
    target_language: &str,
    text: &str,
) -> String {
    let digest = Sha256::digest(
        translation_key_preimage(user_id, source_language, target_language, text).as_bytes(),
    );
    format!("{:x}", digest)
}

/// `None` when the cache must not be touched at all.
///
/// This is the enforcement point for the option-A product decision: with no
/// active account there is no cache. `require_user_id()` returns `""` when
/// anonymous, and `""` is precisely what would be written to `user_id` — which
/// would turn every anonymous translation into a row shared by every future
/// anonymous session. Translating still works without an account; only
/// persistence stops. This lives here, in the repository, because it is a
/// storage rule, not a caller's courtesy: every write path inherits it.
fn cache_owner(user_id: &str) -> Option<&str> {
    let trimmed = user_id.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed)
    }
}

fn validate_text(label: &str, text: &str) -> AppResult<()> {
    if text.trim().is_empty() {
        return Err(AppError::InvalidInput(format!("Translation {label} is required")));
    }
    if text.chars().count() > MAX_TRANSLATION_TEXT_LEN {
        return Err(AppError::InvalidInput(format!(
            "Translation {label} exceeds {MAX_TRANSLATION_TEXT_LEN} characters"
        )));
    }
    Ok(())
}

fn validate_language_tag(label: &str, tag: &str) -> AppResult<()> {
    if tag.trim().is_empty() {
        return Err(AppError::InvalidInput(format!("Translation {label} is required")));
    }
    if tag.len() > MAX_LANGUAGE_TAG_LEN {
        return Err(AppError::InvalidInput(format!(
            "Translation {label} exceeds {MAX_LANGUAGE_TAG_LEN} characters"
        )));
    }
    if !tag.chars().all(|c| c.is_ascii_alphabetic() || c == '-') {
        return Err(AppError::InvalidInput(format!(
            "Translation {label} must be an ISO 639-1 tag: {tag}"
        )));
    }
    Ok(())
}

// ── T3: per-account language preferences ─────────────────────────────────────

/// The stored pair for one account. A missing row is `Ok(None)`, which the
/// caller reads as "both sides infer" — the anonymous case and a fresh account
/// are the same answer, so no default row is ever created.
pub fn get_translation_preferences(
    repo: &LibraryRepository,
    user_id: &str,
) -> AppResult<Option<TranslationPreferencesDto>> {
    let Some(owner) = cache_owner(user_id) else {
        return Ok(None);
    };

    let stored = repo
        .connection
        .prepare(
            "SELECT source_language, target_language, definition_language, updated_at
             FROM translation_preferences
             WHERE user_id = ?1
             LIMIT 1",
        )?
        .query_row(params![owner], |row| {
            Ok(TranslationPreferencesDto {
                source_language: row.get(0)?,
                target_language: row.get(1)?,
                definition_language: row.get(2)?,
                updated_at: row.get(3)?,
            })
        })
        .optional()?;

    Ok(stored)
}

/// Upsert the pair for one account. `None` on a side is a real value: it
/// CLEARS that side back to "infer", which is why neither side is optional at
/// the API level.
pub fn save_translation_preferences(
    repo: &LibraryRepository,
    user_id: &str,
    source_language: Option<&str>,
    target_language: Option<&str>,
) -> AppResult<TranslationPreferencesDto> {
    let Some(owner) = cache_owner(user_id) else {
        return Err(AppError::InvalidInput(
            "Translation preferences require an active account".to_string(),
        ));
    };

    let source = normalize_preference(source_language, "sourceLanguage")?;
    let target = normalize_preference(target_language, "targetLanguage")?;
    let now = Utc::now().to_rfc3339();

    repo.connection.execute(
        "INSERT INTO translation_preferences (user_id, source_language, target_language, updated_at)
         VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT(user_id) DO UPDATE SET
           source_language = excluded.source_language,
           target_language = excluded.target_language,
           updated_at = excluded.updated_at",
        params![owner, source, target, now],
    )?;

    // The definition language is read back, not assumed: this update does not
    // touch it (see `save_definition_language` for why the writes are split),
    // so an account that already chose one must get it back here or the
    // settings form would drop it on every save of the pair.
    let definition: Option<String> = repo
        .connection
        .query_row(
            "SELECT definition_language FROM translation_preferences WHERE user_id = ?1 LIMIT 1",
            params![owner],
            |row| row.get(0),
        )
        .optional()?
        .flatten();

    Ok(TranslationPreferencesDto {
        source_language: source,
        target_language: target,
        definition_language: definition,
        updated_at: now,
    })
}

/// Persist the language a definition is written in, for one account (D6).
///
/// Split from `save_translation_preferences` rather than folded into it because
/// the two are written from different controls by different gestures: the pair
/// has one "Guardar" button for both sides, while the definition language is a
/// single selector that saves the moment it changes. Folded together, changing
/// one would rewrite the other with whatever the UI happened to hold — a stale
/// value silently overwriting a fresh one.
///
/// It upserts, so it works on an account that has never saved a pair: the row
/// is created with the pair NULL (infer), which is exactly the default the
/// reader already had. `None` clears back to "the app locale" — a real value,
/// not a missing one.
pub fn save_definition_language(
    repo: &LibraryRepository,
    user_id: &str,
    definition_language: Option<&str>,
) -> AppResult<TranslationPreferencesDto> {
    let Some(owner) = cache_owner(user_id) else {
        return Err(AppError::InvalidInput(
            "Definition language requires an active account".to_string(),
        ));
    };

    let definition = normalize_preference(definition_language, "definitionLanguage")?;
    let now = Utc::now().to_rfc3339();

    repo.connection.execute(
        "INSERT INTO translation_preferences
           (user_id, source_language, target_language, definition_language, updated_at)
         VALUES (?1, NULL, NULL, ?2, ?3)
         ON CONFLICT(user_id) DO UPDATE SET
           definition_language = excluded.definition_language,
           updated_at = excluded.updated_at",
        params![owner, definition, now],
    )?;

    // The pair is read back rather than assumed: the upsert above leaves an
    // existing row's pair untouched, and answering with `NULL` for a pair the
    // account had set would be a lie the settings UI would then render.
    let (source_language, target_language): (Option<String>, Option<String>) =
        repo.connection.query_row(
            "SELECT source_language, target_language
             FROM translation_preferences
             WHERE user_id = ?1
             LIMIT 1",
            params![owner],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;

    Ok(TranslationPreferencesDto {
        source_language,
        target_language,
        definition_language: definition,
        updated_at: now,
    })
}

/// A preference side: absent, blank or padded all mean "infer" (`None`); a
/// present value must be a plausible language code. A blank string is NOT a
/// code, so it collapses to `None` rather than being written as `""` and
/// re-read as a set-but-unusable preference — that distinction is what the
/// frontend's `invalid-source` / `invalid-target` reasons exist to report.
fn normalize_preference(value: Option<&str>, label: &str) -> AppResult<Option<String>> {
    let Some(raw) = value else {
        return Ok(None);
    };
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }
    validate_language_tag(label, trimmed)?;
    Ok(Some(trimmed.to_ascii_lowercase()))
}

// ── T12/T13: per-book source preference ─────────────────────────────────────

/// The source language stored for one `(account, book)`, or `None` when the
/// reader has made no choice for this book.
///
/// `None` covers three cases that are deliberately one answer: no active
/// account (option A — see [`cache_owner`]), no row for this book, and a row
/// whose `source_language` is NULL. All three mean "fall through to the
/// account's general preference", so the caller does not have to tell them
/// apart and no default row is ever created.
///
/// A malformed `book_id` is NOT rejected here: this is a read, and an id that
/// matches no row simply has no preference. The write path is the one that
/// refuses a blank book, where a blank would mean storing a value under a key
/// that can never be addressed again.
pub fn get_book_translation_source(
    repo: &LibraryRepository,
    user_id: &str,
    book_id: &str,
) -> AppResult<Option<BookTranslationSourceDto>> {
    let Some(owner) = cache_owner(user_id) else {
        return Ok(None);
    };
    if book_id.trim().is_empty() {
        return Ok(None);
    }

    let stored = repo
        .connection
        .prepare(
            "SELECT source_language, updated_at
             FROM book_translation_source
             WHERE user_id = ?1 AND book_id = ?2
             LIMIT 1",
        )?
        .query_row(params![owner, book_id], |row| {
            Ok(BookTranslationSourceDto { source_language: row.get(0)?, updated_at: row.get(1)? })
        })
        .optional()?;

    Ok(stored)
}

/// The EFFECTIVE source for one book: the book's own choice when it has one,
/// otherwise the account's general preference.
///
/// This is the resolution order the feature is built around, collapsed into the
/// one call the frontend makes before a translation:
///   1. `book_translation_source.source_language` for this `(account, book)`;
///   2. `translation_preferences.source_language` for the account;
///   3. `None`, which the frontend reads as "infer" — its own cascade then uses
///      the book's metadata language, then local detection over the text, and
///      finally refuses.
///
/// It deliberately does NOT merge the two levels into a substituted row: the
/// result says which source to use, never "this is the book's choice". Whether a
/// language was chosen for the BOOK or for the ACCOUNT is provenance the caller
/// must not lose — the UI has to know if it may offer to clear a choice that was
/// never made for this book — so that question is answered by
/// [`get_book_translation_source`], not by inferring it from this value.
///
/// No separate command duplicates the general preference: this reads the same
/// `translation_preferences` row the general commands write, so the two can
/// never disagree.
pub fn resolve_book_translation_source(
    repo: &LibraryRepository,
    user_id: &str,
    book_id: &str,
) -> AppResult<Option<String>> {
    if let Some(book) = get_book_translation_source(repo, user_id, book_id)? {
        if let Some(source) = book.source_language {
            return Ok(Some(source));
        }
    }

    // The account side reuses the same read the general commands use rather than
    // a second query, so a change to how the pair is stored cannot desynchronise
    // the two paths.
    let general = get_translation_preferences(repo, user_id)?;
    Ok(general.and_then(|preferences| preferences.source_language))
}

/// Upsert the source language for one `(account, book)`.
///
/// `None` is a REAL value, not a missing one: it CLEARS this book's choice back
/// to NULL, which is what makes the resolution fall through to the account's
/// general preference again. That is the only way back to the default the reader
/// started on, so the parameter is `Option<&str>` rather than a value that has
/// to be deleted out of band.
///
/// It upserts, so the reader can choose a language for a book that never had a
/// row without a create step, and a second choice updates the same row instead
/// of conflicting on `idx_book_translation_source_user_book`.
///
/// A blank `book_id` is refused rather than stored: the row would be keyed under
/// an id no caller can address again, so the choice would be silently lost while
/// appearing to have been saved.
pub fn save_book_translation_source(
    repo: &LibraryRepository,
    user_id: &str,
    book_id: &str,
    source_language: Option<&str>,
) -> AppResult<BookTranslationSourceDto> {
    let Some(owner) = cache_owner(user_id) else {
        return Err(AppError::InvalidInput(
            "A per-book translation source requires an active account".to_string(),
        ));
    };
    if book_id.trim().is_empty() {
        return Err(AppError::InvalidInput(
            "A per-book translation source requires a book id".to_string(),
        ));
    }

    // Same normalisation as the general preference, and for the same reason: a
    // blank value collapses to NULL ("no choice for this book") rather than
    // being written as `''`, which would read back as a set-but-unusable
    // preference and stop the cascade at step 1 with no language.
    let source = normalize_preference(source_language, "sourceLanguage")?;
    let now = Utc::now().to_rfc3339();

    repo.connection.execute(
        "INSERT INTO book_translation_source (user_id, book_id, source_language, updated_at)
         VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT(user_id, book_id) DO UPDATE SET
           source_language = excluded.source_language,
           updated_at = excluded.updated_at",
        params![owner, book_id, source, now],
    )?;

    Ok(BookTranslationSourceDto { source_language: source, updated_at: now })
}

// ── T4: per-account translation cache (and history) ──────────────────────────

/// Read the cached translation for an exact `(account, source, target, text)`.
///
/// Two `None` answers are deliberately the same value, but for different
/// reasons and the code above does not have to tell them apart: no active
/// account means the cache is not consultable at all (option A), while an
/// active account with nothing cached for this exact key simply missed.
/// Neither is an error; both mean "ask MyMemory".
///
/// The lookup is by `(user_id, cache_key)`, never by text, so a hit is exact by
/// construction: a normalized or case-insensitive match would return a
/// translation of a DIFFERENT sentence as if it were this one.
pub fn read_cached_translation(
    repo: &LibraryRepository,
    user_id: &str,
    source_language: &str,
    target_language: &str,
    original_text: &str,
) -> AppResult<Option<String>> {
    let Some(owner) = cache_owner(user_id) else {
        return Ok(None);
    };
    if original_text.trim().is_empty() {
        return Ok(None);
    }

    let key = translation_cache_key(owner, source_language, target_language, original_text);
    let hit: Option<String> = repo
        .connection
        .prepare(
            "SELECT translated_text
             FROM translation_cache
             WHERE user_id = ?1 AND cache_key = ?2
             LIMIT 1",
        )?
        .query_row(params![owner, key], |row| row.get(0))
        .optional()?;

    Ok(hit.filter(|text| !text.is_empty()))
}

/// Write one successful translation for one account.
///
/// Returns `false` — not an error — when there is no active account: option A
/// says anonymous translations are simply not persisted, and turning that into
/// an error would make an offline-free feature fail on a missing account.
///
/// Only a real translation is ever written. This function is reachable only
/// after the output guards in `commands/translate.rs` accepted the response, so
/// an echo, an HTML entity or an empty string cannot become a row. A
/// "no translation" outcome is deliberately NOT cached (see the module docs in
/// `commands/translate.rs`): caching a refusal would pin a transient service
/// condition for as long as the row lives, and the user could never recover by
/// retrying.
pub fn cache_translation(
    repo: &LibraryRepository,
    user_id: &str,
    source_language: &str,
    target_language: &str,
    original_text: &str,
    translated_text: &str,
) -> AppResult<bool> {
    let Some(owner) = cache_owner(user_id) else {
        return Ok(false);
    };

    validate_text("text", original_text)?;
    validate_text("translation", translated_text)?;
    validate_language_tag("sourceLanguage", source_language)?;
    validate_language_tag("targetLanguage", target_language)?;

    let key = translation_cache_key(owner, source_language, target_language, original_text);
    let source = source_language.trim().to_ascii_lowercase();
    let target = target_language.trim().to_ascii_lowercase();
    let now = Utc::now().to_rfc3339();

    repo.connection.execute(
        "INSERT INTO translation_cache
           (user_id, cache_key, source_language, target_language, original_text, translated_text, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
         ON CONFLICT(user_id, cache_key) DO UPDATE SET
           translated_text = excluded.translated_text,
           created_at = excluded.created_at",
        params![owner, key, source, target, original_text, translated_text, now],
    )?;

    // Self-maintaining cap: every successful write sweeps this account back to
    // the bound, so the cache cannot grow without limit and no caller has to
    // remember a separate maintenance call. It runs on the same connection
    // inside the same call as the insert, so a translation and the pruning that
    // follows it are never observably out of order. A prune failure is not
    // surfaced as a write failure: the translation IS cached either way, and
    // turning a housekeeping error into a failed translation would be worse
    // than a cache that is briefly one row over its cap.
    let _ = prune_translation_cache(repo, owner, TRANSLATION_CACHE_MAX_ROWS);

    Ok(true)
}

/// Keep at most `max_rows` entries for one account, dropping the oldest.
///
/// Returns how many rows were removed. `0` is the ordinary answer: below the
/// cap there is nothing to do, and with no active owner there is no account
/// whose rows could be swept (option A — see [`cache_owner`]).
///
/// This is a storage rule and lives in the repository next to the writes, so
/// every write path inherits it; a caller cannot forget to call it. The order
/// is `created_at DESC, cache_key DESC` — exactly the shape of
/// `idx_translation_cache_user_created` — so the sweep is an index walk with no
/// sort, and two rows written in the same millisecond still order stably.
///
/// The `user_id = ?1` predicate is not an optimisation: it is the isolation
/// contract. Pruning one account must never remove a row of another, and the
/// owner is the first column of the index, so the sweep is confined to one
/// account's slice by construction.
pub fn prune_translation_cache(
    repo: &LibraryRepository,
    user_id: &str,
    max_rows: i64,
) -> AppResult<i64> {
    let Some(owner) = cache_owner(user_id) else {
        return Ok(0);
    };
    if max_rows <= 0 {
        return Err(AppError::InvalidInput(
            "Translation cache max rows must be positive".to_string(),
        ));
    }

    // `LIMIT -1 OFFSET n` is SQLite's "everything past the first n": the
    // subquery keeps the newest `max_rows` keys and the DELETE removes the rest
    // by their composite key, so only this owner's rows are ever candidates.
    let removed = repo.connection.execute(
        "DELETE FROM translation_cache
         WHERE user_id = ?1
           AND cache_key IN (
             SELECT cache_key FROM translation_cache
             WHERE user_id = ?1
             ORDER BY created_at DESC, cache_key DESC
             LIMIT -1 OFFSET ?2
           )",
        params![owner, max_rows],
    )?;

    Ok(removed as i64)
}

/// The account's translation history, newest first — the cache table itself.
/// `() ` when there is no active account, for the same option-A reason.
pub fn list_translation_history(
    repo: &LibraryRepository,
    user_id: &str,
) -> AppResult<Vec<TranslationCacheEntryDto>> {
    let Some(owner) = cache_owner(user_id) else {
        return Ok(Vec::new());
    };

    let mut statement = repo.connection.prepare(
        "SELECT cache_key, source_language, target_language, original_text, translated_text, created_at
         FROM translation_cache
         WHERE user_id = ?1
         ORDER BY created_at DESC, cache_key DESC
         LIMIT ?2",
    )?;

    let rows = statement.query_map(params![owner, TRANSLATION_HISTORY_LIMIT], |row| {
        Ok(TranslationCacheEntryDto {
            cache_key: row.get(0)?,
            source_language: row.get(1)?,
            target_language: row.get(2)?,
            original_text: row.get(3)?,
            translated_text: row.get(4)?,
            created_at: row.get(5)?,
        })
    })?;

    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::repository::tests::{insert_book, new_repository};

    const OWNER: &str = "user-1";

    #[test]
    fn preferences_are_absent_until_saved() {
        let repo = new_repository();
        assert_eq!(get_translation_preferences(&repo, OWNER).unwrap(), None);
    }

    #[test]
    fn preferences_round_trip_for_one_account() {
        let repo = new_repository();

        let saved = save_translation_preferences(&repo, OWNER, Some("ES"), Some("en")).unwrap();
        assert_eq!(saved.source_language.as_deref(), Some("es"));
        assert_eq!(saved.target_language.as_deref(), Some("en"));
        assert!(!saved.updated_at.is_empty());

        let read = get_translation_preferences(&repo, OWNER).unwrap().unwrap();
        assert_eq!(read.source_language.as_deref(), Some("es"));
        assert_eq!(read.target_language.as_deref(), Some("en"));
    }

    /// D6: a fresh account, or an account that saved a pair before the column
    /// existed, reads `None` — which the frontend treats as "the app locale",
    /// exactly the behaviour that predates the column. No backfill is needed
    /// precisely because `None` is a working value.
    #[test]
    fn definition_language_is_absent_until_an_account_picks_one() {
        let repo = new_repository();
        save_translation_preferences(&repo, OWNER, Some("es"), Some("en")).unwrap();

        let read = get_translation_preferences(&repo, OWNER).unwrap().unwrap();
        assert_eq!(read.definition_language, None);
    }

    /// D6 round trip, including the normalisation the pair already gets: the
    /// stored code is lowercased, because the cascade builds a wiki hostname
    /// out of it (`{lang}.wiktionary.org`) and `ES` would be a different host.
    #[test]
    fn definition_language_round_trips_and_is_normalised() {
        let repo = new_repository();

        let saved = save_definition_language(&repo, OWNER, Some("ES")).unwrap();
        assert_eq!(saved.definition_language.as_deref(), Some("es"));

        let read = get_translation_preferences(&repo, OWNER).unwrap().unwrap();
        assert_eq!(read.definition_language.as_deref(), Some("es"));
    }

    /// DECISION under test: writing the definition language must not disturb the
    /// pair, and writing the pair must not disturb the definition language. Both
    /// directions are asserted, because either regression would silently reset a
    /// preference the reader had set from the other control.
    #[test]
    fn the_definition_language_and_the_pair_do_not_overwrite_each_other() {
        let repo = new_repository();

        // Definition first, on an account with no row at all: the row is
        // created with the pair NULL (infer), which is the documented default.
        let created = save_definition_language(&repo, OWNER, Some("fr")).unwrap();
        assert_eq!(created.source_language, None);
        assert_eq!(created.target_language, None);
        assert_eq!(created.definition_language.as_deref(), Some("fr"));

        // The pair lands on that same row and keeps the definition language.
        let pair = save_translation_preferences(&repo, OWNER, Some("es"), Some("en")).unwrap();
        assert_eq!(pair.definition_language.as_deref(), Some("fr"));

        // ...and the other way round.
        let changed = save_definition_language(&repo, OWNER, Some("de")).unwrap();
        assert_eq!(changed.source_language.as_deref(), Some("es"));
        assert_eq!(changed.target_language.as_deref(), Some("en"));
        assert_eq!(changed.definition_language.as_deref(), Some("de"));

        // Still one row for the account, not three.
        let count: i64 = repo
            .connection
            .query_row("SELECT COUNT(*) FROM translation_preferences", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 1);
    }

    /// `None` is the "use the app locale" value, and it must be storable AFTER
    /// a language was chosen — otherwise the reader can never go back to the
    /// default they started on.
    #[test]
    fn the_definition_language_can_be_cleared_back_to_the_app_locale() {
        let repo = new_repository();

        save_definition_language(&repo, OWNER, Some("fr")).unwrap();
        let cleared = save_definition_language(&repo, OWNER, None).unwrap();
        assert_eq!(cleared.definition_language, None);

        let read = get_translation_preferences(&repo, OWNER).unwrap().unwrap();
        assert_eq!(read.definition_language, None);
    }

    /// A blank or padded value collapses to `None` rather than being written as
    /// `""`, which would read back as a set-but-unusable preference.
    #[test]
    fn a_blank_definition_language_reads_as_infer() {
        let repo = new_repository();

        let saved = save_definition_language(&repo, OWNER, Some("   ")).unwrap();
        assert_eq!(saved.definition_language, None);
    }

    /// The account scoping applies to the new write exactly as it does to the
    /// pair: without an account the write is refused, not silently dropped.
    #[test]
    fn saving_a_definition_language_without_an_account_is_refused() {
        let repo = new_repository();
        assert!(save_definition_language(&repo, "", Some("es")).is_err());
    }

    /// And it is `user_id` that scopes it, not the process: two accounts pick
    /// different languages and neither sees the other's.
    #[test]
    fn the_definition_language_is_scoped_per_account() {
        let repo = new_repository();
        save_definition_language(&repo, "user-1", Some("es")).unwrap();
        save_definition_language(&repo, "user-2", Some("de")).unwrap();

        assert_eq!(
            get_translation_preferences(&repo, "user-1").unwrap().unwrap().definition_language,
            Some("es".to_string())
        );
        assert_eq!(
            get_translation_preferences(&repo, "user-2").unwrap().unwrap().definition_language,
            Some("de".to_string())
        );
    }

    #[test]
    fn preferences_are_scoped_per_account() {
        let repo = new_repository();
        save_translation_preferences(&repo, "user-1", Some("es"), Some("en")).unwrap();
        save_translation_preferences(&repo, "user-2", Some("fr"), None).unwrap();

        let first = get_translation_preferences(&repo, "user-1").unwrap().unwrap();
        let second = get_translation_preferences(&repo, "user-2").unwrap().unwrap();

        assert_eq!(first.source_language.as_deref(), Some("es"));
        assert_eq!(first.target_language.as_deref(), Some("en"));
        assert_eq!(second.source_language.as_deref(), Some("fr"));
        assert_eq!(second.target_language, None);
    }

    #[test]
    fn saving_twice_updates_the_single_row_instead_of_inserting() {
        let repo = new_repository();
        save_translation_preferences(&repo, OWNER, Some("es"), Some("en")).unwrap();
        save_translation_preferences(&repo, OWNER, Some("de"), Some("fr")).unwrap();

        let count: i64 = repo
            .connection
            .query_row("SELECT COUNT(*) FROM translation_preferences", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 1);

        let read = get_translation_preferences(&repo, OWNER).unwrap().unwrap();
        assert_eq!(read.source_language.as_deref(), Some("de"));
        assert_eq!(read.target_language.as_deref(), Some("fr"));
    }

    #[test]
    fn clearing_a_side_stores_null_meaning_infer() {
        let repo = new_repository();
        save_translation_preferences(&repo, OWNER, Some("es"), Some("en")).unwrap();
        save_translation_preferences(&repo, OWNER, None, None).unwrap();

        let read = get_translation_preferences(&repo, OWNER).unwrap().unwrap();
        assert_eq!(read.source_language, None);
        assert_eq!(read.target_language, None);
    }

    #[test]
    fn a_blank_side_collapses_to_infer_instead_of_being_stored() {
        let repo = new_repository();
        save_translation_preferences(&repo, OWNER, Some("   "), Some("")).unwrap();

        let read = get_translation_preferences(&repo, OWNER).unwrap().unwrap();
        assert_eq!(read.source_language, None);
        assert_eq!(read.target_language, None);
    }

    #[test]
    fn preferences_reject_a_malformed_language_tag() {
        let repo = new_repository();
        let result = save_translation_preferences(&repo, OWNER, Some("1"), Some("en"));
        assert!(matches!(result, Err(AppError::InvalidInput(_))));
    }

    #[test]
    fn preferences_require_an_active_account() {
        let repo = new_repository();
        assert!(matches!(
            save_translation_preferences(&repo, "", Some("es"), Some("en")),
            Err(AppError::InvalidInput(_))
        ));
        assert_eq!(get_translation_preferences(&repo, "").unwrap(), None);
    }

    #[test]
    fn cache_key_is_stable_and_owner_sensitive() {
        let a = translation_cache_key("user-1", "es", "en", "hola");
        let b = translation_cache_key("user-1", "es", "en", "hola");
        let other_owner = translation_cache_key("user-2", "es", "en", "hola");
        let other_pair = translation_cache_key("user-1", "es", "fr", "hola");
        let other_text = translation_cache_key("user-1", "es", "en", "hola!");

        assert_eq!(a, b);
        assert_ne!(a, other_owner);
        assert_ne!(a, other_pair);
        assert_ne!(a, other_text);
        assert_eq!(a.len(), 64);
    }

    /// Known-good vector, computed independently:
    /// `shasum -a 256 <<< "user-1|es|en|hola"` (no trailing newline).
    #[test]
    fn cache_key_matches_the_documented_pre_image() {
        let expected = {
            let digest = Sha256::digest(b"user-1|es|en|hola");
            format!("{:x}", digest)
        };
        assert_eq!(translation_cache_key("user-1", "es", "en", "hola"), expected);
    }

    #[test]
    fn cache_round_trips_with_an_active_account() {
        let repo = new_repository();

        assert!(cache_translation(&repo, OWNER, "es", "en", "hola", "hello").unwrap());
        let hit = read_cached_translation(&repo, OWNER, "es", "en", "hola").unwrap();
        assert_eq!(hit.as_deref(), Some("hello"));
    }

    #[test]
    fn cache_miss_is_a_none_not_an_error() {
        let repo = new_repository();
        assert_eq!(read_cached_translation(&repo, OWNER, "es", "en", "hola").unwrap(), None);
        // Same text, different pair: a different key, so still a miss.
        cache_translation(&repo, OWNER, "es", "en", "hola", "hello").unwrap();
        assert_eq!(read_cached_translation(&repo, OWNER, "es", "fr", "hola").unwrap(), None);
        // A different case is a different sentence, not a hit.
        assert_eq!(read_cached_translation(&repo, OWNER, "es", "en", "Hola").unwrap(), None);
    }

    #[test]
    fn cache_is_scoped_per_account() {
        let repo = new_repository();
        cache_translation(&repo, "user-1", "es", "en", "hola", "hello").unwrap();

        assert_eq!(read_cached_translation(&repo, "user-2", "es", "en", "hola").unwrap(), None);
        assert_eq!(
            read_cached_translation(&repo, "user-1", "es", "en", "hola").unwrap().as_deref(),
            Some("hello")
        );
    }

    /// Option A, the product decision, as an executable contract: without an
    /// active account NOTHING is written and NOTHING is read. The absence of a
    /// row is asserted directly against the table, because a no-op write that
    /// happened to be harmless for the caller would still be a leak of the
    /// anonymous text to every later account that shares the empty owner.
    #[test]
    fn anonymous_translations_are_never_cached() {
        let repo = new_repository();

        assert!(!cache_translation(&repo, "", "es", "en", "hola", "hello").unwrap());
        assert!(!cache_translation(&repo, "   ", "es", "en", "hola", "hello").unwrap());

        let count: i64 = repo
            .connection
            .query_row("SELECT COUNT(*) FROM translation_cache", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 0);

        assert_eq!(read_cached_translation(&repo, "", "es", "en", "hola").unwrap(), None);
        assert!(list_translation_history(&repo, "").unwrap().is_empty());
    }

    /// The other half of option A: an anonymous translation must not become a
    /// hit for a later signed-in account either. Writing the row through a real
    /// owner is the only way a value can exist at all.
    #[test]
    fn an_anonymous_read_cannot_see_an_accounts_cached_entry() {
        let repo = new_repository();
        cache_translation(&repo, OWNER, "es", "en", "hola", "hello").unwrap();

        assert_eq!(read_cached_translation(&repo, "", "es", "en", "hola").unwrap(), None);
    }

    #[test]
    fn cache_rejects_an_empty_text_or_translation() {
        let repo = new_repository();
        assert!(matches!(
            cache_translation(&repo, OWNER, "es", "en", "", "hello"),
            Err(AppError::InvalidInput(_))
        ));
        assert!(matches!(
            cache_translation(&repo, OWNER, "es", "en", "hola", "   "),
            Err(AppError::InvalidInput(_))
        ));
    }

    #[test]
    fn cache_rejects_a_malformed_language_tag() {
        let repo = new_repository();
        assert!(matches!(
            cache_translation(&repo, OWNER, "es1", "en", "hola", "hello"),
            Err(AppError::InvalidInput(_))
        ));
    }

    #[test]
    fn cache_rejects_text_past_the_reader_cap() {
        let repo = new_repository();
        let too_long = "a".repeat(MAX_TRANSLATION_TEXT_LEN + 1);
        assert!(matches!(
            cache_translation(&repo, OWNER, "es", "en", &too_long, "hello"),
            Err(AppError::InvalidInput(_))
        ));
        let at_cap = "a".repeat(MAX_TRANSLATION_TEXT_LEN);
        assert!(cache_translation(&repo, OWNER, "es", "en", &at_cap, "hello").unwrap());
    }

    #[test]
    fn recaching_the_same_key_updates_instead_of_duplicating() {
        let repo = new_repository();
        cache_translation(&repo, OWNER, "es", "en", "hola", "hello").unwrap();
        cache_translation(&repo, OWNER, "es", "en", "hola", "hi").unwrap();

        let count: i64 = repo
            .connection
            .query_row("SELECT COUNT(*) FROM translation_cache", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 1);
        assert_eq!(
            read_cached_translation(&repo, OWNER, "es", "en", "hola").unwrap().as_deref(),
            Some("hi")
        );
    }

    #[test]
    fn history_returns_newest_first_for_one_account_only() {
        let repo = new_repository();
        cache_translation(&repo, "user-1", "es", "en", "uno", "one").unwrap();
        cache_translation(&repo, "user-1", "es", "en", "dos", "two").unwrap();
        cache_translation(&repo, "user-2", "es", "en", "tres", "three").unwrap();

        let history = list_translation_history(&repo, "user-1").unwrap();
        assert_eq!(history.len(), 2);
        assert_eq!(history[0].original_text, "dos");
        assert_eq!(history[0].translated_text, "two");
        assert_eq!(history[1].original_text, "uno");

        let other = list_translation_history(&repo, "user-2").unwrap();
        assert_eq!(other.len(), 1);
        assert_eq!(other[0].original_text, "tres");
    }

    #[test]
    fn history_entries_carry_the_full_language_pair() {
        let repo = new_repository();
        cache_translation(&repo, OWNER, "PT-BR", "EN", "olá", "hello").unwrap();

        let history = list_translation_history(&repo, OWNER).unwrap();
        assert_eq!(history.len(), 1);
        assert_eq!(history[0].source_language, "pt-br");
        assert_eq!(history[0].target_language, "en");
        assert_eq!(history[0].cache_key.len(), 64);
        assert!(!history[0].created_at.is_empty());
    }

    /// The migration really reached the database `new_repository()` builds.
    ///
    /// `apply_test_migrations` is a hand-maintained duplicate of the production
    /// `MIGRATIONS` array: forgetting 0024 there makes these two tables vanish
    /// for every test while the app keeps working. Every other test in this
    /// module fails with "no such table" in that case; this one states the
    /// requirement directly so the failure names its own cause.
    #[test]
    fn migration_0024_tables_exist_for_repository_tests() {
        let repo = new_repository();
        for table in ["translation_preferences", "translation_cache"] {
            let found: Option<String> = repo
                .connection
                .query_row(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?1",
                    params![table],
                    |row| row.get(0),
                )
                .optional()
                .unwrap();
            assert_eq!(
                found.as_deref(),
                Some(table),
                "{table} is missing from apply_test_migrations"
            );
        }
    }

    // ── T12/T13: per-book source preference ──────────────────────────────────

    /// No row is ever created for a book the reader has not chosen for: an
    /// absent row and a NULL source both mean "fall through to the account".
    #[test]
    fn a_book_source_is_absent_until_it_is_chosen() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");

        assert_eq!(get_book_translation_source(&repo, OWNER, "book-1").unwrap(), None);
        // And with no account at all.
        assert_eq!(get_book_translation_source(&repo, "", "book-1").unwrap(), None);
    }

    /// The upsert, both halves: a first choice inserts, a second updates the
    /// same row (the UNIQUE on `(user_id, book_id)` is the target), and the code
    /// is normalised exactly like the general preference.
    #[test]
    fn a_book_source_upserts_and_is_normalised() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");

        let saved = save_book_translation_source(&repo, OWNER, "book-1", Some("FR")).unwrap();
        assert_eq!(saved.source_language.as_deref(), Some("fr"));
        assert!(!saved.updated_at.is_empty());

        let changed = save_book_translation_source(&repo, OWNER, "book-1", Some("de")).unwrap();
        assert_eq!(changed.source_language.as_deref(), Some("de"));

        let count: i64 = repo
            .connection
            .query_row("SELECT COUNT(*) FROM book_translation_source", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 1, "a second choice must update, not insert");

        let read = get_book_translation_source(&repo, OWNER, "book-1").unwrap().unwrap();
        assert_eq!(read.source_language.as_deref(), Some("de"));
    }

    /// `None` is the "no choice for this book" value, and it must be storable
    /// AFTER a language was chosen — otherwise the reader can never go back to
    /// the account's general preference.
    #[test]
    fn a_book_source_can_be_cleared_back_to_the_general_preference() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");
        save_book_translation_source(&repo, OWNER, "book-1", Some("fr")).unwrap();

        let cleared = save_book_translation_source(&repo, OWNER, "book-1", None).unwrap();
        assert_eq!(cleared.source_language, None);

        let read = get_book_translation_source(&repo, OWNER, "book-1").unwrap().unwrap();
        assert_eq!(read.source_language, None);
    }

    /// A blank value collapses to NULL rather than being written as `''`, which
    /// would stop the cascade at the book level with no usable language.
    #[test]
    fn a_blank_book_source_reads_as_no_choice() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");

        let saved = save_book_translation_source(&repo, OWNER, "book-1", Some("   ")).unwrap();
        assert_eq!(saved.source_language, None);
    }

    /// DECISION under test, the whole point of the second level: the BOOK wins
    /// over the ACCOUNT, and clearing the book falls back to the account.
    #[test]
    fn the_book_source_wins_over_the_account_preference() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");
        save_translation_preferences(&repo, OWNER, Some("es"), Some("en")).unwrap();

        // No book-level choice yet: the account's preference is the answer.
        assert_eq!(
            resolve_book_translation_source(&repo, OWNER, "book-1").unwrap().as_deref(),
            Some("es")
        );

        // A book-level choice overrides it...
        save_book_translation_source(&repo, OWNER, "book-1", Some("fr")).unwrap();
        assert_eq!(
            resolve_book_translation_source(&repo, OWNER, "book-1").unwrap().as_deref(),
            Some("fr")
        );

        // ...and clearing the book falls back to the account, not to "nothing".
        save_book_translation_source(&repo, OWNER, "book-1", None).unwrap();
        assert_eq!(
            resolve_book_translation_source(&repo, OWNER, "book-1").unwrap().as_deref(),
            Some("es")
        );
    }

    /// With neither level set the answer is `None` — "infer", which the frontend
    /// resolves from the book's metadata, then local detection, then refuses.
    #[test]
    fn the_resolved_book_source_is_none_when_neither_level_is_set() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");

        assert_eq!(resolve_book_translation_source(&repo, OWNER, "book-1").unwrap(), None);
    }

    /// And it is `(user_id, book_id)` that scopes it: two accounts on the same
    /// physical book pick different languages and neither sees the other's.
    #[test]
    fn the_book_source_is_scoped_per_account() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");
        save_book_translation_source(&repo, "user-1", "book-1", Some("es")).unwrap();
        save_book_translation_source(&repo, "user-2", "book-1", Some("de")).unwrap();

        assert_eq!(
            get_book_translation_source(&repo, "user-1", "book-1")
                .unwrap()
                .unwrap()
                .source_language
                .as_deref(),
            Some("es")
        );
        assert_eq!(
            get_book_translation_source(&repo, "user-2", "book-1")
                .unwrap()
                .unwrap()
                .source_language
                .as_deref(),
            Some("de")
        );
        assert_eq!(get_book_translation_source(&repo, "user-3", "book-1").unwrap(), None);
    }

    /// A choice for one book must not leak into another — the bug the second
    /// level exists to fix.
    #[test]
    fn a_book_source_does_not_leak_into_another_book() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");
        insert_book(&repo, "book-2", "/books/two.epub");

        save_book_translation_source(&repo, OWNER, "book-1", Some("fr")).unwrap();

        assert_eq!(get_book_translation_source(&repo, OWNER, "book-2").unwrap(), None);
        assert_eq!(resolve_book_translation_source(&repo, OWNER, "book-2").unwrap(), None);
    }

    /// Without an account the write is REFUSED, not silently dropped — a lost
    /// preference is a silent data loss, the same rule as the general pair.
    #[test]
    fn saving_a_book_source_without_an_account_is_refused() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");

        assert!(matches!(
            save_book_translation_source(&repo, "", "book-1", Some("es")),
            Err(AppError::InvalidInput(_))
        ));
        assert!(matches!(
            save_book_translation_source(&repo, "   ", "book-1", Some("es")),
            Err(AppError::InvalidInput(_))
        ));
    }

    /// A blank book id is refused: the row would be keyed under an id no caller
    /// can address again, so the choice would be lost while appearing saved.
    #[test]
    fn saving_a_book_source_without_a_book_is_refused() {
        let repo = new_repository();
        assert!(matches!(
            save_book_translation_source(&repo, OWNER, "  ", Some("es")),
            Err(AppError::InvalidInput(_))
        ));
    }

    #[test]
    fn a_book_source_rejects_a_malformed_language_tag() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");
        assert!(matches!(
            save_book_translation_source(&repo, OWNER, "book-1", Some("1")),
            Err(AppError::InvalidInput(_))
        ));
    }

    /// A blank book id reads as "no choice" rather than erroring: this is a read,
    /// and an unaddressable id simply has no row.
    #[test]
    fn reading_a_blank_book_source_is_none_not_an_error() {
        let repo = new_repository();
        assert_eq!(get_book_translation_source(&repo, OWNER, "").unwrap(), None);
        assert_eq!(resolve_book_translation_source(&repo, OWNER, "   ").unwrap(), None);
    }

    /// `''` must never reach `user_id` here either — such a row would be the
    /// book preference of every later anonymous session.
    #[test]
    fn no_book_source_row_carries_an_empty_owner() {
        let repo = new_repository();
        insert_book(&repo, "book-1", "/books/one.epub");
        save_book_translation_source(&repo, OWNER, "book-1", Some("es")).unwrap();
        let _ = save_book_translation_source(&repo, "", "book-1", Some("es"));

        let empty: i64 = repo
            .connection
            .query_row(
                "SELECT COUNT(*) FROM book_translation_source WHERE user_id = ''",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(empty, 0);
    }

    /// The migration really reached the database `new_repository()` builds, for
    /// the same reason as `migration_0024_tables_exist_for_repository_tests`:
    /// `apply_test_migrations` is a hand-maintained duplicate of `MIGRATIONS`.
    #[test]
    fn migration_0027_table_exists_for_repository_tests() {
        let repo = new_repository();
        let found: Option<String> = repo
            .connection
            .query_row(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?1",
                params!["book_translation_source"],
                |row| row.get(0),
            )
            .optional()
            .unwrap();
        assert_eq!(found.as_deref(), Some("book_translation_source"));
    }

    // ── T4: cache pruning (owner-scoped, count-bounded) ──────────────────────

    /// Seed `count` distinct cache rows for one owner, oldest first, so
    /// `created_at` ordering is deterministic and every row is distinguishable.
    ///
    /// Writes straight to SQL rather than through `cache_translation`: that
    /// function now prunes after every insert, so seeding through it could
    /// never produce the over-cap state these tests are about.
    fn seed_cache(repo: &LibraryRepository, user_id: &str, count: usize) {
        for index in 0..count {
            let text = format!("text-{index}");
            let key = translation_cache_key(user_id, "es", "en", &text);
            // A distinct, monotonic timestamp per row keeps the DESC order
            // unambiguous even inside one millisecond.
            let created_at = format!("2024-01-01T00:00:{:02}.{:03}Z", index / 1000, index % 1000);
            repo.connection
                .execute(
                    "INSERT INTO translation_cache
                       (user_id, cache_key, source_language, target_language,
                        original_text, translated_text, created_at)
                     VALUES (?1, ?2, 'es', 'en', ?3, ?4, ?5)",
                    params![user_id, key, text, format!("tr-{index}"), created_at],
                )
                .unwrap();
        }
    }

    fn cache_rows(repo: &LibraryRepository, user_id: &str) -> i64 {
        repo.connection
            .query_row(
                "SELECT COUNT(*) FROM translation_cache WHERE user_id = ?1",
                params![user_id],
                |row| row.get(0),
            )
            .unwrap()
    }

    /// Below the cap the sweep is a no-op — the common case must not churn.
    #[test]
    fn pruning_below_the_cap_deletes_nothing() {
        let repo = new_repository();
        seed_cache(&repo, OWNER, 10);

        let removed = prune_translation_cache(&repo, OWNER, TRANSLATION_CACHE_MAX_ROWS).unwrap();

        assert_eq!(removed, 0);
        assert_eq!(cache_rows(&repo, OWNER), 10);
    }

    /// At the cap nothing is removed either: the bound is inclusive.
    #[test]
    fn pruning_at_exactly_the_cap_deletes_nothing() {
        let repo = new_repository();
        seed_cache(&repo, OWNER, 5);

        let removed = prune_translation_cache(&repo, OWNER, 5).unwrap();

        assert_eq!(removed, 0);
        assert_eq!(cache_rows(&repo, OWNER), 5);
    }

    /// Over the cap, the OLDEST rows go and the newest survive — the newest are
    /// the ones a reader is most likely to ask for again.
    #[test]
    fn pruning_over_the_cap_removes_the_oldest_rows() {
        let repo = new_repository();
        seed_cache(&repo, OWNER, 10);

        let removed = prune_translation_cache(&repo, OWNER, 4).unwrap();

        assert_eq!(removed, 6);
        assert_eq!(cache_rows(&repo, OWNER), 4);

        // The four newest survive...
        for index in 6..10 {
            assert_eq!(
                read_cached_translation(&repo, OWNER, "es", "en", &format!("text-{index}"))
                    .unwrap()
                    .as_deref(),
                Some(format!("tr-{index}").as_str()),
                "text-{index} must survive the prune"
            );
        }
        // ...and the six oldest are gone.
        for index in 0..6 {
            assert_eq!(
                read_cached_translation(&repo, OWNER, "es", "en", &format!("text-{index}"))
                    .unwrap(),
                None,
                "text-{index} must have been pruned"
            );
        }
    }

    /// The isolation contract, as an executable assertion on BOTH counts:
    /// pruning one account must not touch a single row of another. Both owners
    /// are over the cap, so an owner-blind `DELETE` would empty the second one.
    #[test]
    fn pruning_one_account_leaves_another_untouched() {
        let repo = new_repository();
        seed_cache(&repo, "user-1", 600);
        seed_cache(&repo, "user-2", 600);

        let removed = prune_translation_cache(&repo, "user-1", TRANSLATION_CACHE_MAX_ROWS).unwrap();

        assert_eq!(removed, 100);
        assert_eq!(cache_rows(&repo, "user-1"), TRANSLATION_CACHE_MAX_ROWS);
        assert_eq!(cache_rows(&repo, "user-2"), 600, "user-2 must be untouched");

        // A row of user-2 that user-1 would have pruned is still readable.
        assert_eq!(
            read_cached_translation(&repo, "user-2", "es", "en", "text-0").unwrap().as_deref(),
            Some("tr-0")
        );
    }

    /// No active account ⇒ no prune, for the same option-A reason the writes
    /// short-circuit: there is no owner whose rows could be swept.
    #[test]
    fn pruning_without_an_account_deletes_nothing() {
        let repo = new_repository();
        seed_cache(&repo, OWNER, 10);

        let blanks = ["", "   "];
        for blank in blanks {
            let removed = prune_translation_cache(&repo, blank, 1).unwrap();
            assert_eq!(removed, 0, "an anonymous prune must be a no-op");
        }
        assert_eq!(cache_rows(&repo, OWNER), 10);
    }

    /// The self-maintaining contract: the cap is enforced by the WRITE path,
    /// not only by an explicit sweep. Writing past the cap must leave the cache
    /// at the cap, with the oldest entries gone — a caller that never calls
    /// `prune_translation_cache` still cannot grow the table without limit.
    #[test]
    fn writing_past_the_cap_keeps_the_account_at_the_cap() {
        let repo = new_repository();
        // One past the cap, written through the real write path.
        for index in 0..=TRANSLATION_CACHE_MAX_ROWS as usize {
            cache_translation(
                &repo,
                OWNER,
                "es",
                "en",
                &format!("w-{index}"),
                &format!("wt-{index}"),
            )
            .unwrap();
        }

        assert_eq!(cache_rows(&repo, OWNER), TRANSLATION_CACHE_MAX_ROWS);
        // The very first write is the one that did not survive.
        assert_eq!(read_cached_translation(&repo, OWNER, "es", "en", "w-0").unwrap(), None);
        // The newest write did.
        let last = format!("w-{}", TRANSLATION_CACHE_MAX_ROWS);
        assert_eq!(
            read_cached_translation(&repo, OWNER, "es", "en", &last).unwrap().as_deref(),
            Some(format!("wt-{}", TRANSLATION_CACHE_MAX_ROWS).as_str())
        );
    }

    /// Recaching an existing key must NOT count as a new row, so a user who
    /// repeatedly translates the same sentence never evicts anything.
    #[test]
    fn recaching_an_existing_key_does_not_evict() {
        let repo = new_repository();
        seed_cache(&repo, OWNER, 10);

        for _ in 0..20 {
            cache_translation(&repo, OWNER, "es", "en", "text-5", "again").unwrap();
        }

        assert_eq!(cache_rows(&repo, OWNER), 10);
        assert_eq!(
            read_cached_translation(&repo, OWNER, "es", "en", "text-0").unwrap().as_deref(),
            Some("tr-0"),
            "an update is not a new entry, so the oldest row must survive"
        );
    }

    /// `''` must never reach the `user_id` column. The column defaults to it for
    /// uniformity with `0015_dictionary_sync`, but no row may actually carry it,
    /// because such a row would be visible to every later anonymous session.
    #[test]
    fn no_row_ever_carries_an_empty_owner() {
        let repo = new_repository();
        cache_translation(&repo, OWNER, "es", "en", "hola", "hello").unwrap();
        save_translation_preferences(&repo, OWNER, None, None).unwrap();
        // Best-effort anonymous writes, which must all be refused.
        let _ = cache_translation(&repo, "", "es", "en", "x", "y");

        for table in ["translation_cache", "translation_preferences"] {
            let empty: i64 = repo
                .connection
                .query_row(&format!("SELECT COUNT(*) FROM {table} WHERE user_id = ''"), [], |row| {
                    row.get(0)
                })
                .unwrap();
            assert_eq!(empty, 0, "{table} contains a row owned by the empty user id");
        }
    }
}
