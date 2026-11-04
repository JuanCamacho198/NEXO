//! Per-account cache of "Definir" results (migration 0025).
//!
//! The publishing entry point is [`crate::commands::define::defineSelection`],
//! which reads this cache before it runs the cascade and writes it afterwards.
//! Everything about the CACHE lives here; the cascade itself lives in
//! `commands/define.rs` and knows nothing about accounts or storage.
//!
//! The one rule this module enforces on every path is option A, the product
//! decision stated in `commands/translate.rs`: with no active account, nothing
//! is cached. It is enforced in [`cache_owner`] — a repository-level check, not
//! a caller's courtesy — so no future caller can opt out of it.
//!
//! Why a cache at all, given the same reasoning was already applied to
//! translation: a definition costs a real Wiktionary round-trip (and often two,
//! plus a MyMemory call — see `run_cascade`), so a hit is three network requests
//! saved. The security half of the Android lesson applies too: the read and the
//! write are both scoped to `user_id`, so one account can never see another's
//! lookups.

use rusqlite::{params, OptionalExtension};
use sha2::{Digest, Sha256};

use crate::error::{AppError, AppResult};
use crate::models::{
    DefinitionCacheEntryDto, DefinitionItemDto, LibraryDefinitionResult, LibraryDefinitionSource,
};

use super::LibraryRepository;

/// Longest word the cache will store. Mirrors the shape of
/// `MAX_TRANSLATION_TEXT_LEN` in `repository/translation.rs` rather than the
/// reader's selection cap: a dictionary headword is its own bound, and 200
/// characters is far past any real one (`antidisestablishmentarianism` is 28)
/// while still refusing a whole paragraph handed in as a "word".
const MAX_DEFINITION_WORD_LEN: usize = 200;

/// Bound on a language code. The longest Wiktionary code this feature uses is
/// two letters (`en`, `es`); 16 leaves room for a region-shaped tag (`pt-BR`)
/// without accepting a payload that would be stored verbatim. Same number, same
/// reasoning as `MAX_LANGUAGE_TAG_LEN` in `repository/translation.rs`.
const MAX_LANGUAGE_TAG_LEN: usize = 16;

/// Bound on the serialised definitions blob, so one pathological entry cannot
/// put an unbounded payload in the row. A definition list is a handful of short
/// sentences; 64 KB is orders of magnitude of headroom.
const MAX_DEFINITIONS_JSON_LEN: usize = 64 * 1024;

/// The canonical pre-image of the cache key:
/// `owner|reader|wordLanguage|word`, pipe separated, no quoting.
///
/// DECISION — why this is hashed at all, when the key is a short, already-bounded
/// word. The translation cache hashes because its text can be 2000 characters
/// and a raw-text key would index up to 2 KB per row; that argument does NOT
/// apply here. What does apply is uniformity: this table's key is a single
/// opaque identity column exactly like `translation_cache.cache_key`, so the two
/// caches are read, written and reasoned about the same way, and a future
/// normalization of the pre-image cannot change the on-disk width of the index.
/// SHA-256 is already a dependency (used by `translation_cache_key` and by the
/// reading-session id in `progress.rs`).
///
/// DECISION — what is IN the key, and why the READER language is part of it.
/// A definition is `(word, definition language)` — there is no pair, which is
/// the stated reason this is not `translation_cache`. But the cascade RESOLVES
/// differently per reader language: step 1 looks up a DIFFERENT wiki, and step 3
/// translates INTO a different language. `casa` on an `es` UI and `casa` on an
/// `en` UI are two different results produced by two different code paths, so
/// the reader language is identity here, not payload. The word and both
/// languages are ALSO stored as columns — the hash decides identity, it is
/// emphatically not the storage.
///
/// The owner is inside the hash for the same reason it is a column: two
/// accounts defining the same word must not collide. `|` is safe as a separator
/// because none of the first three fields can contain one — a language tag is
/// `[A-Za-z-]` after validation and the owner is an opaque id — so the split
/// between the three fixed fields and the trailing word is unambiguous. The word
/// is deliberately LAST and unescaped: it is the only field that may contain a
/// pipe, and nothing after it is parsed.
fn definition_key_preimage(
    user_id: &str,
    reader_language: &str,
    word_language: &str,
    word: &str,
) -> String {
    format!("{}|{}|{}|{}", user_id, reader_language, word_language, word)
}

/// SHA-256 of the pre-image as lowercase hex. See
/// [`definition_key_preimage`] for the DECISION behind hashing this table's key.
pub fn definition_cache_key(
    user_id: &str,
    reader_language: &str,
    word_language: &str,
    word: &str,
) -> String {
    let digest = Sha256::digest(
        definition_key_preimage(user_id, reader_language, word_language, word).as_bytes(),
    );
    format!("{:x}", digest)
}

/// `None` when the cache must not be touched at all.
///
/// This is the enforcement point for the option-A product decision, copied
/// verbatim in spirit from `repository::translation::cache_owner`: with no
/// active account there is no cache. `require_user_id()` returns `""` when
/// anonymous, and `""` is precisely what would be written to `user_id` — which
/// would turn every anonymous lookup into a row shared by every future anonymous
/// session. Defining a word still works without an account; only persistence
/// stops. It lives here, in the repository, because it is a storage rule:
/// every write path inherits it.
fn cache_owner(user_id: &str) -> Option<&str> {
    let trimmed = user_id.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed)
    }
}

fn validate_word(word: &str) -> AppResult<()> {
    if word.trim().is_empty() {
        return Err(AppError::InvalidInput("Definition word is required".to_string()));
    }
    if word.chars().count() > MAX_DEFINITION_WORD_LEN {
        return Err(AppError::InvalidInput(format!(
            "Definition word exceeds {MAX_DEFINITION_WORD_LEN} characters"
        )));
    }
    Ok(())
}

/// A language tag is optional for the word (the cascade has a documented
/// "unknown" path and assumes `en`), so `""` is a legitimate stored value. A
/// present one must still be a plausible code — the same rule, the same
/// reasoning, as `validate_language_tag` in `repository/translation.rs`.
fn validate_language_tag(label: &str, tag: &str) -> AppResult<()> {
    if tag.is_empty() {
        return Ok(());
    }
    if tag.len() > MAX_LANGUAGE_TAG_LEN {
        return Err(AppError::InvalidInput(format!(
            "Definition {label} exceeds {MAX_LANGUAGE_TAG_LEN} characters"
        )));
    }
    if !tag.chars().all(|c| c.is_ascii_alphabetic() || c == '-') {
        return Err(AppError::InvalidInput(format!(
            "Definition {label} must be an ISO 639-1 tag: {tag}"
        )));
    }
    Ok(())
}

/// Read the cached definition for an exact `(account, reader, wordLanguage,
/// word)`.
///
/// Two `None` answers are deliberately the same value, for the same reason
/// `read_cached_translation` collapses them: no active account means the cache
/// is not consultable at all (option A), while an active account with nothing
/// cached for this key simply missed. Neither is an error; both mean "run the
/// cascade".
///
/// The lookup is by `(user_id, cache_key)`, never by the word alone, so a hit is
/// exact by construction: a case-insensitive or normalized match would return
/// the definition of a DIFFERENT word as if it were this one — and Wiktionary
/// headwords are case-sensitive (`Apple` the company vs `apple` the fruit).
pub fn read_cached_definition(
    repo: &LibraryRepository,
    user_id: &str,
    word: &str,
    reader_language: &str,
    word_language: &str,
) -> AppResult<Option<LibraryDefinitionResult>> {
    let Some(owner) = cache_owner(user_id) else {
        return Ok(None);
    };
    if word.trim().is_empty() {
        return Ok(None);
    }

    let key = definition_cache_key(owner, reader_language, word_language, word);
    let hit: Option<(String, String, String, String)> = repo
        .connection
        .prepare(
            "SELECT definitions, source, attribution, word
             FROM definition_cache
             WHERE user_id = ?1 AND cache_key = ?2
             LIMIT 1",
        )?
        .query_row(params![owner, key], |row| {
            Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?))
        })
        .optional()?;

    let Some((definitions_json, source, attribution, stored_word)) = hit else {
        return Ok(None);
    };

    // A row whose JSON does not parse is treated as a MISS, not an error: the
    // caller then runs the cascade and overwrites the row. Failing the read
    // would turn one corrupt row into a permanently broken word.
    let definitions: Vec<DefinitionItemDto> = match serde_json::from_str(&definitions_json) {
        Ok(parsed) => parsed,
        Err(_) => return Ok(None),
    };

    Ok(Some(LibraryDefinitionResult {
        word: stored_word,
        definitions,
        source: Some(LibraryDefinitionSource::from_stored(&source)),
        attribution,
    }))
}

/// Write one resolved definition result for one account.
///
/// Returns `false` — not an error — when there is no active account: option A
/// says anonymous lookups are simply not persisted, and turning that into an
/// error would make a public feature fail on a missing account. Mirrors
/// `cache_translation`.
///
/// DECISION — only a NON-EMPTY result is ever written, and the guard is here,
/// not at the call site. An empty result means every source missed; that is a
/// real answer, but it is a statement about the NETWORK that day, not about the
/// word. Persisting it would pin a transient outage (Wikimedia down, no route,
/// a rate limit) to that word for as long as the row lives, and the reader
/// could never recover by retrying — the same trap `commands/translate.rs`
/// documents for "no translation". So the empty case re-runs the cascade every
/// time, which is the honest behavior.
///
/// A row is overwritten whole on conflict rather than merged: the definition
/// list is one fact that was resolved in one cascade run, and a merge would
/// invent a result no source ever produced.
pub fn cache_definition(
    repo: &LibraryRepository,
    user_id: &str,
    word: &str,
    reader_language: &str,
    word_language: &str,
    result: &LibraryDefinitionResult,
) -> AppResult<bool> {
    let Some(owner) = cache_owner(user_id) else {
        return Ok(false);
    };
    if result.definitions.is_empty() {
        return Ok(false);
    }

    validate_word(word)?;
    validate_language_tag("readerLanguage", reader_language)?;
    validate_language_tag("wordLanguage", word_language)?;

    let definitions_json = serde_json::to_string(&result.definitions).map_err(|e| {
        AppError::InvalidInput(format!("Definition payload is not serialisable: {e}"))
    })?;
    if definitions_json.len() > MAX_DEFINITIONS_JSON_LEN {
        return Err(AppError::InvalidInput(format!(
            "Definition payload exceeds {MAX_DEFINITIONS_JSON_LEN} bytes"
        )));
    }

    let key = definition_cache_key(owner, reader_language, word_language, word);
    let now = chrono::Utc::now().to_rfc3339();

    repo.connection.execute(
        "INSERT INTO definition_cache
           (user_id, cache_key, word, word_language, reader_language,
            definitions, source, attribution, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
         ON CONFLICT(user_id, cache_key) DO UPDATE SET
           definitions = excluded.definitions,
           source = excluded.source,
           attribution = excluded.attribution,
           created_at = excluded.created_at",
        params![
            owner,
            key,
            word,
            word_language,
            reader_language,
            definitions_json,
            result.source.map(|s| s.as_str()).unwrap_or(""),
            result.attribution,
            now
        ],
    )?;

    Ok(true)
}

/// One account's definition history, newest first — the cache table itself,
/// exactly as `list_translation_history` reads `translation_cache`.
///
/// `[]` when there is no active account, for the same option-A reason. This is
/// not consumed by the UI yet (that is D5/D6/D7); it exists because the index
/// `idx_definition_cache_user_created` was created for it and an index with no
/// reader is an index nobody can validate. It is exercised by the tests.
pub fn list_definition_history(
    repo: &LibraryRepository,
    user_id: &str,
    limit: i64,
) -> AppResult<Vec<DefinitionCacheEntryDto>> {
    let Some(owner) = cache_owner(user_id) else {
        return Ok(Vec::new());
    };
    if limit <= 0 {
        return Err(AppError::InvalidInput(
            "Definition history limit must be positive".to_string(),
        ));
    }

    let mut statement = repo.connection.prepare(
        "SELECT cache_key, word, word_language, reader_language, source, created_at
         FROM definition_cache
         WHERE user_id = ?1
         ORDER BY created_at DESC, cache_key DESC
         LIMIT ?2",
    )?;

    let rows = statement.query_map(params![owner, limit], |row| {
        Ok(DefinitionCacheEntryDto {
            cache_key: row.get(0)?,
            word: row.get(1)?,
            word_language: row.get(2)?,
            reader_language: row.get(3)?,
            source: row.get(4)?,
            created_at: row.get(5)?,
        })
    })?;

    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::DefinitionItemDto;
    use crate::repository::tests::new_repository;

    const OWNER: &str = "user-1";

    fn native_result(word: &str, text: &str) -> LibraryDefinitionResult {
        LibraryDefinitionResult {
            word: word.to_string(),
            definitions: vec![DefinitionItemDto {
                text: text.to_string(),
                part_of_speech: Some("sustantivo".to_string()),
            }],
            source: Some(LibraryDefinitionSource::Native),
            attribution: "Wiktionary, CC BY-SA 4.0 (https://www.wiktionary.org/)".to_string(),
        }
    }

    /// Requirement 1: a stored definition comes back with BOTH the definition
    /// list AND the `source`. The `source` half is the one worth pinning: it is
    /// what the UI reads to warn that a gloss was machine-translated, so losing
    /// it would show a translation as if a human had written it.
    #[test]
    fn a_stored_definition_round_trips_with_its_source() {
        let repo = new_repository();
        let stored = native_result("house", "casa");

        assert!(cache_definition(&repo, OWNER, "house", "es", "en", &stored).unwrap());

        let read = read_cached_definition(&repo, OWNER, "house", "es", "en").unwrap().unwrap();
        assert_eq!(read.word, "house");
        assert_eq!(read.definitions.len(), 1);
        assert_eq!(read.definitions[0].text, "casa");
        assert_eq!(read.definitions[0].part_of_speech.as_deref(), Some("sustantivo"));
        assert_eq!(read.source, Some(LibraryDefinitionSource::Native));
        assert!(read.attribution.contains("CC BY-SA"));
    }

    #[test]
    fn a_translated_source_survives_the_round_trip_distinctly() {
        let repo = new_repository();
        let mut stored = native_result("juxtaposition", "yuxtaposición");
        stored.source = Some(LibraryDefinitionSource::Translated);

        cache_definition(&repo, OWNER, "juxtaposition", "es", "en", &stored).unwrap();

        let read =
            read_cached_definition(&repo, OWNER, "juxtaposition", "es", "en").unwrap().unwrap();
        assert_eq!(read.source, Some(LibraryDefinitionSource::Translated));
        assert_ne!(read.source, Some(LibraryDefinitionSource::Native));
    }

    #[test]
    fn a_miss_is_none_not_an_error() {
        let repo = new_repository();
        assert_eq!(read_cached_definition(&repo, OWNER, "house", "es", "en").unwrap(), None);

        cache_definition(&repo, OWNER, "house", "es", "en", &native_result("house", "casa"))
            .unwrap();

        // A different reader language is a different cascade, hence a different
        // entry: no hit.
        assert_eq!(read_cached_definition(&repo, OWNER, "house", "en", "en").unwrap(), None);
        // A different word language likewise.
        assert_eq!(read_cached_definition(&repo, OWNER, "house", "es", "es").unwrap(), None);
        // A different word, obviously.
        assert_eq!(read_cached_definition(&repo, OWNER, "casa", "es", "en").unwrap(), None);
    }

    /// The word is matched EXACTLY: case is identity, because Wiktionary
    /// headwords are case-sensitive.
    #[test]
    fn the_word_match_is_exact_and_case_sensitive() {
        let repo = new_repository();
        cache_definition(&repo, OWNER, "Apple", "es", "en", &native_result("Apple", "empresa"))
            .unwrap();

        assert_eq!(read_cached_definition(&repo, OWNER, "apple", "es", "en").unwrap(), None);
        assert!(read_cached_definition(&repo, OWNER, "Apple", "es", "en").unwrap().is_some());
    }

    /// Requirement 2, first half: with no active account NOTHING is written.
    /// The absence of a row is asserted directly against the table, because a
    /// no-op write that happened to be harmless for the caller would still make
    /// the anonymous word visible to every later account sharing the empty owner.
    #[test]
    fn anonymous_definitions_are_never_cached() {
        let repo = new_repository();
        let stored = native_result("house", "casa");

        assert!(!cache_definition(&repo, "", "house", "es", "en", &stored).unwrap());
        assert!(!cache_definition(&repo, "   ", "house", "es", "en", &stored).unwrap());

        let count: i64 = repo
            .connection
            .query_row("SELECT COUNT(*) FROM definition_cache", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 0);
    }

    /// Requirement 2, second half: with no active account the READ returns
    /// empty, and it cannot see a row a real account owns.
    #[test]
    fn an_anonymous_read_returns_nothing_and_cannot_see_an_accounts_entry() {
        let repo = new_repository();
        cache_definition(&repo, OWNER, "house", "es", "en", &native_result("house", "casa"))
            .unwrap();

        assert_eq!(read_cached_definition(&repo, "", "house", "es", "en").unwrap(), None);
        assert_eq!(read_cached_definition(&repo, "   ", "house", "es", "en").unwrap(), None);
        assert!(list_definition_history(&repo, "", 50).unwrap().is_empty());
    }

    /// Requirement 3: the isolation contract, asserted on BOTH accounts' counts.
    /// The same word defined by two accounts produces two rows, each readable
    /// only by its owner.
    #[test]
    fn two_accounts_defining_the_same_word_see_their_own_entry() {
        let repo = new_repository();
        cache_definition(&repo, "user-1", "house", "es", "en", &native_result("house", "casa"))
            .unwrap();
        cache_definition(&repo, "user-2", "house", "es", "en", &native_result("house", "hogar"))
            .unwrap();

        let first = read_cached_definition(&repo, "user-1", "house", "es", "en").unwrap().unwrap();
        let second = read_cached_definition(&repo, "user-2", "house", "es", "en").unwrap().unwrap();

        assert_eq!(first.definitions[0].text, "casa");
        assert_eq!(second.definitions[0].text, "hogar");

        // Both counts, stated directly: one row each, not one shared row and not
        // two rows for one account.
        let count_for = |owner: &str| -> i64 {
            repo.connection
                .query_row(
                    "SELECT COUNT(*) FROM definition_cache WHERE user_id = ?1",
                    params![owner],
                    |row| row.get(0),
                )
                .unwrap()
        };
        assert_eq!(count_for("user-1"), 1);
        assert_eq!(count_for("user-2"), 1);
    }

    /// Re-caching the same key updates in place. Without this, a reader who
    /// re-defines a word would grow the table and the history would show the
    /// same word twice — and the oldest row would keep being served.
    #[test]
    fn recaching_the_same_key_updates_instead_of_duplicating() {
        let repo = new_repository();
        cache_definition(&repo, OWNER, "house", "es", "en", &native_result("house", "casa"))
            .unwrap();
        cache_definition(&repo, OWNER, "house", "es", "en", &native_result("house", "morada"))
            .unwrap();

        let count: i64 = repo
            .connection
            .query_row("SELECT COUNT(*) FROM definition_cache", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 1);

        let read = read_cached_definition(&repo, OWNER, "house", "es", "en").unwrap().unwrap();
        assert_eq!(read.definitions[0].text, "morada");
    }

    /// DECISION under test: an EMPTY result is never cached. It is a statement
    /// about the network that day, so pinning it would make the word permanently
    /// undefined until something overwrote the row.
    #[test]
    fn an_empty_result_is_never_cached() {
        let repo = new_repository();
        let empty = LibraryDefinitionResult {
            word: "qqqqzzzz".to_string(),
            definitions: Vec::new(),
            source: None,
            attribution: "Wiktionary, CC BY-SA 4.0".to_string(),
        };

        assert!(!cache_definition(&repo, OWNER, "qqqqzzzz", "es", "en", &empty).unwrap());

        let count: i64 = repo
            .connection
            .query_row("SELECT COUNT(*) FROM definition_cache", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 0);
        // Which means the next call runs the cascade again, as it must.
        assert_eq!(read_cached_definition(&repo, OWNER, "qqqqzzzz", "es", "en").unwrap(), None);
    }

    #[test]
    fn cache_rejects_an_empty_or_overlong_word() {
        let repo = new_repository();
        let stored = native_result("house", "casa");

        assert!(matches!(
            cache_definition(&repo, OWNER, "   ", "es", "en", &stored),
            Err(AppError::InvalidInput(_))
        ));
        let too_long = "a".repeat(MAX_DEFINITION_WORD_LEN + 1);
        assert!(matches!(
            cache_definition(&repo, OWNER, &too_long, "es", "en", &stored),
            Err(AppError::InvalidInput(_))
        ));
        let at_cap = "a".repeat(MAX_DEFINITION_WORD_LEN);
        assert!(cache_definition(&repo, OWNER, &at_cap, "es", "en", &stored).unwrap());
    }

    #[test]
    fn cache_rejects_a_malformed_language_tag() {
        let repo = new_repository();
        let stored = native_result("house", "casa");
        assert!(matches!(
            cache_definition(&repo, OWNER, "house", "es1", "en", &stored),
            Err(AppError::InvalidInput(_))
        ));
    }

    /// The word language is genuinely OPTIONAL: the cascade documents an
    /// "unknown" path that assumes `en`, and the empty string records that
    /// faithfully instead of being rejected.
    #[test]
    fn an_empty_word_language_is_a_legitimate_stored_value() {
        let repo = new_repository();
        let mut stored = native_result("house", "casa");
        stored.source = Some(LibraryDefinitionSource::Translated);

        assert!(cache_definition(&repo, OWNER, "house", "es", "", &stored).unwrap());
        let read = read_cached_definition(&repo, OWNER, "house", "es", "").unwrap().unwrap();
        assert_eq!(read.source, Some(LibraryDefinitionSource::Translated));
    }

    /// A corrupt JSON payload can only be produced by hand, so it is written by
    /// hand here. The contract: a MISS, not an error, so the next call repairs
    /// the row by running the cascade.
    #[test]
    fn a_corrupt_payload_reads_as_a_miss_rather_than_an_error() {
        let repo = new_repository();
        let key = definition_cache_key(OWNER, "es", "en", "house");
        repo.connection
            .execute(
                "INSERT INTO definition_cache
                   (user_id, cache_key, word, word_language, reader_language,
                    definitions, source, attribution)
                 VALUES (?1, ?2, 'house', 'en', 'es', 'not json', 'native', 'x')",
                params![OWNER, key],
            )
            .unwrap();

        assert_eq!(read_cached_definition(&repo, OWNER, "house", "es", "en").unwrap(), None);

        // ...and the cascade result repairs it.
        cache_definition(&repo, OWNER, "house", "es", "en", &native_result("house", "casa"))
            .unwrap();
        assert!(read_cached_definition(&repo, OWNER, "house", "es", "en").unwrap().is_some());
    }

    #[test]
    fn the_key_is_stable_and_sensitive_to_all_four_fields() {
        let a = definition_cache_key("user-1", "es", "en", "house");
        let b = definition_cache_key("user-1", "es", "en", "house");
        assert_eq!(a, b);
        assert_ne!(a, definition_cache_key("user-2", "es", "en", "house"));
        assert_ne!(a, definition_cache_key("user-1", "en", "en", "house"));
        assert_ne!(a, definition_cache_key("user-1", "es", "es", "house"));
        assert_ne!(a, definition_cache_key("user-1", "es", "en", "casa"));
        assert_eq!(a.len(), 64);
    }

    #[test]
    fn history_returns_newest_first_for_one_account_only() {
        let repo = new_repository();
        cache_definition(&repo, "user-1", "uno", "es", "en", &native_result("uno", "one")).unwrap();
        cache_definition(&repo, "user-1", "dos", "es", "en", &native_result("dos", "two")).unwrap();
        cache_definition(&repo, "user-2", "tres", "es", "en", &native_result("tres", "three"))
            .unwrap();

        let history = list_definition_history(&repo, "user-1", 50).unwrap();
        assert_eq!(history.len(), 2);
        assert_eq!(history[0].word, "dos");
        assert_eq!(history[1].word, "uno");
        assert_eq!(history[0].source, "native");
        assert_eq!(history[0].cache_key.len(), 64);
        assert!(!history[0].created_at.is_empty());

        assert_eq!(list_definition_history(&repo, "user-2", 50).unwrap().len(), 1);
    }

    /// `''` must never reach the `user_id` column. The column defaults to it for
    /// uniformity with `0015_dictionary_sync` and `0024`, but no row may carry
    /// it, because such a row would be visible to every later anonymous session.
    #[test]
    fn no_row_ever_carries_an_empty_owner() {
        let repo = new_repository();
        cache_definition(&repo, OWNER, "house", "es", "en", &native_result("house", "casa"))
            .unwrap();
        let _ = cache_definition(&repo, "", "house", "es", "en", &native_result("house", "casa"));

        let empty: i64 = repo
            .connection
            .query_row("SELECT COUNT(*) FROM definition_cache WHERE user_id = ''", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(empty, 0);
    }

    /// THE MIGRATION TEST. Requirement 5.
    ///
    /// `apply_test_migrations` in `repository/mod.rs` is a HAND-MAINTAINED
    /// duplicate of the production `MIGRATIONS` array in `db.rs`: registering
    /// 0025 in one and not the other makes the table vanish for every test while
    /// the app keeps working. This test builds a real repository through
    /// `new_repository()` and writes AND reads the table through it, so a
    /// missing registration fails here with "no such table" and names its own
    /// cause.
    #[test]
    fn migration_0025_table_exists_and_is_usable_for_repository_tests() {
        let repo = new_repository();

        let found: Option<String> = repo
            .connection
            .query_row(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?1",
                params!["definition_cache"],
                |row| row.get(0),
            )
            .optional()
            .unwrap();
        assert_eq!(
            found.as_deref(),
            Some("definition_cache"),
            "definition_cache is missing from apply_test_migrations"
        );

        // Not just "the name is present": the columns the code reads must exist,
        // and a full write/read round trip must work on the migrated schema.
        assert!(cache_definition(
            &repo,
            OWNER,
            "house",
            "es",
            "en",
            &native_result("house", "casa")
        )
        .unwrap());
        let read = read_cached_definition(&repo, OWNER, "house", "es", "en").unwrap().unwrap();
        assert_eq!(read.definitions[0].text, "casa");

        for column in [
            "user_id",
            "cache_key",
            "word",
            "word_language",
            "reader_language",
            "definitions",
            "source",
            "attribution",
            "created_at",
        ] {
            let present: Option<String> = repo
                .connection
                .query_row(
                    "SELECT name FROM pragma_table_info('definition_cache') WHERE name = ?1",
                    params![column],
                    |row| row.get(0),
                )
                .optional()
                .unwrap();
            assert_eq!(present.as_deref(), Some(column), "definition_cache.{column} is missing");
        }
    }
}
