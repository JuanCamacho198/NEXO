use chrono::Utc;
use rusqlite::{params, OptionalExtension};
use sha2::{Digest, Sha256};

use crate::error::{AppError, AppResult};
use crate::models::{TranslationCacheEntryDto, TranslationPreferencesDto};

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

    let stored: Option<(Option<String>, Option<String>, String)> = repo
        .connection
        .prepare(
            "SELECT source_language, target_language, updated_at
             FROM translation_preferences
             WHERE user_id = ?1
             LIMIT 1",
        )?
        .query_row(params![owner], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))
        .optional()?;

    Ok(stored.map(|(source_language, target_language, updated_at)| TranslationPreferencesDto {
        source_language,
        target_language,
        updated_at,
    }))
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

    Ok(TranslationPreferencesDto {
        source_language: source,
        target_language: target,
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
    use crate::repository::tests::new_repository;

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
