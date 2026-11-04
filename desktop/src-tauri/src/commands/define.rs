//! The "Definir" cascade — turning a selected word into a definition written
//! in the reader's language.
//!
//! Three sources, tried in order, and the order is the whole design:
//!
//! 1. `{reader_lang}.wiktionary` for the word's own language. When it hits, the
//!    gloss is already in the reader's language AND is native (a human wrote
//!    `casa` for `house`), so nothing is machine-translated. This is the best
//!    outcome and it is preferred on purpose.
//! 2. `en.wiktionary` for the same word. This is the COVERAGE fallback: many
//!    English words simply are not on es-wiktionary. Measured case:
//!    `juxtaposition` has no es.wiktionary entry but a full en.wiktionary one.
//! 3. MyMemory, translating the step-2 gloss from English into the reader's
//!    language. It is the only step that can invent a wrong answer, so its
//!    output is marked `translated` and the UI must say so.
//!
//! Steps 2+3 exist to make the answer NEVER empty. Step 1 alone would leave
//! every English word the es wiki lacks with no definition at all.
//!
//! A per-account cache (migration 0025) brackets all of this: consulted before
//! the cascade, written after it, and scoped to the active account so one
//! reader's lookups are never visible to another. It is skipped entirely when no
//! account is active — see [`define_with_cache`] and
//! `repository::definition::cache_owner`, which is where that decision is
//! enforced.
//!
//! The split that makes this testable: the sequence of decisions lives in
//! `run_cascade`, which takes the three data sources as function arguments. The
//! Tauri command wires the real HTTP fetchers into it; the tests wire fakes in.
//! The only requests a test makes are to a refused loopback port (see
//! `define_network_tests`), never to the real wikis.
//!
//! The sources are `FnMut` closures that RETURN A FUTURE, and the cascade
//! awaits each one. That shape is not decoration: an earlier version handed the
//! cascade synchronous closures and had the command resolve the HTTP inside
//! `tauri::async_runtime::block_on`, which is tokio — calling `block_on` from
//! inside a task of that same runtime blocks the worker the nested future needs
//! and the command NEVER RETURNS. "Definir" hung forever in the shipped app
//! while every test stayed green, because the tests drove the cascade with
//! synchronous fakes and never ran the production path. Both the async closures
//! and `define_network_tests` exist so that cannot happen again.

use super::translate::request_translation;
use super::wiktionary::{self, Definition};
use serde::Serialize;

/// Wikimedia requires an identifiable User-Agent with a real contact; a generic
/// or missing agent is blocked. This replaces the `TBD` placeholder the
/// translate client carries — the cascade hits Wikimedia, not MyMemory.
pub const WIKTIONARY_USER_AGENT: &str =
    "Nexo/Desktop (https://github.com/nexo-reader/nexo; contact: nexo-reader@proton.me)";
pub const WIKTIONARY_MAX_REDIRECTS: usize = 5;
pub const WIKTIONARY_CONNECT_TIMEOUT_S: u64 = 15;
pub const WIKTIONARY_READ_TIMEOUT_S: u32 = 30;

/// Attribution required by Wiktionary's license (CC BY-SA 4.0). It is shown
/// with every definition, not stored in a footer, because the license requires
/// it accompany the reused text.
pub const WIKTIONARY_ATTRIBUTION: &str = "Wiktionary, CC BY-SA 4.0 (https://www.wiktionary.org/)";

const ERR_HTTPS_REQUIRED: &str = "DEFINE_HTTPS_REQUIRED";
const ERR_NETWORK: &str = "DEFINE_NETWORK";

/// Where a definition came from, which decides how much the reader may trust it.
///
/// `Native` is a gloss a human wrote in the reader's language. `Translated` is a
/// machine translation of an English gloss: correct most of the time, wrong in
/// ways the reader cannot see, so the UI surfaces the distinction.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum DefinitionSource {
    Native,
    Translated,
}

/// One definition line as the reader shows it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DefinitionItem {
    pub text: String,
    pub part_of_speech: Option<String>,
}

/// The whole result of a "Definir" call.
///
/// `definitions` is empty ONLY when every source missed — the honest "no
/// definition" the reader renders as such. `source` is `None` in that case;
/// otherwise it names step 1 (`native`) or step 3 (`translated`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DefinitionResult {
    pub word: String,
    pub definitions: Vec<DefinitionItem>,
    pub source: Option<DefinitionSource>,
    /// CC BY-SA attribution, always carried so the UI cannot forget the license.
    pub attribution: String,
}

impl DefinitionResult {
    fn empty(word: &str) -> Self {
        Self {
            word: word.to_string(),
            definitions: Vec::new(),
            source: None,
            attribution: WIKTIONARY_ATTRIBUTION.to_string(),
        }
    }
}

/// Language of the selected word, as a Wiktionary language code.
///
/// DECISION (the task asks for this to be documented): the frontend supplies
/// it, from `Book.language`, and it is threaded through unchanged. There is NO
/// detection step — off-device detection is unreliable on a single word and
/// adds a network round-trip before the first lookup.
///
/// When the language is UNKNOWN (`None`, or an empty/whitespace string), the
/// cascade cannot pick a `{{lengua|XX}}` block safely, so it SKIPS step 1 and
/// goes to en.wiktionary / translation with `en` as the assumed source
/// language. `en` is the measured worst case (an English book on a Spanish UI)
/// and the fallback that keeps the answer non-empty. A wrong guess still
/// produces a real gloss; an empty one produces nothing at all.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SourceLanguage<'a> {
    pub word_lang: &'a str,
    pub known: bool,
}

impl<'a> SourceLanguage<'a> {
    pub fn known(word_lang: &'a str) -> Self {
        Self { word_lang, known: true }
    }

    pub fn unknown() -> Self {
        Self { word_lang: "en", known: false }
    }

    /// Normalise an optional frontend-supplied code.
    pub fn from_optional(code: &'a str) -> Self {
        if code.trim().is_empty() {
            Self::unknown()
        } else {
            Self::known(code.trim())
        }
    }
}

// ── Transport ─────────────────────────────────────────────────────────────
//
// Same posture as `translate.rs` and `catalog_fetch.rs`: an HTTPS pre-check,
// bounded redirects, explicit timeouts, typed error codes returned as `String`.
// The endpoint is a parameter so tests can point at loopback; production
// always passes the hardcoded `wiktionary_endpoint`.

fn wiktionary_client() -> reqwest::Client {
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    CLIENT
        .get_or_init(|| {
            reqwest::Client::builder()
                .user_agent(WIKTIONARY_USER_AGENT)
                .redirect(reqwest::redirect::Policy::limited(WIKTIONARY_MAX_REDIRECTS))
                .connect_timeout(std::time::Duration::from_secs(WIKTIONARY_CONNECT_TIMEOUT_S))
                .timeout(std::time::Duration::from_secs(u64::from(WIKTIONARY_READ_TIMEOUT_S)))
                .build()
                .expect("wiktionary http client build")
        })
        .clone()
}

fn wiktionary_endpoint(wiki_lang: &str, word: &str) -> String {
    format!(
        "https://{wiki_lang}.wiktionary.org/w/api.php?action=parse&page={word}&prop=wikitext&format=json"
    )
}

/// The wikitext of `word` on `{wiki_lang}.wiktionary`, or `None`.
///
/// A missing page is a 200 with an `error` body on the MediaWiki API, and
/// `extract_wikitext` already turns that into `None`. A transport failure is an
/// `Err`; the cascade treats both as "this source missed", so one dead wiki
/// never aborts the whole lookup.
async fn fetch_wikitext(
    client: &reqwest::Client,
    endpoint: &str,
    word: &str,
) -> Result<Option<String>, String> {
    let url = url::Url::parse(endpoint)
        .map_err(|e| format!("{ERR_HTTPS_REQUIRED}: wiktionary endpoint invalid: {e}"))?;
    if url.scheme() != "https" {
        return Err(format!("{ERR_HTTPS_REQUIRED}: wiktionary endpoint must be https"));
    }
    let response = client
        .get(url)
        .query(&[("word", word)])
        .send()
        .await
        .map_err(|e| format!("{ERR_NETWORK}: wiktionary request failed: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("{ERR_NETWORK}: wiktionary responded {}", response.status()));
    }
    let body =
        response.text().await.map_err(|e| format!("{ERR_NETWORK}: wiktionary read failed: {e}"))?;
    Ok(wiktionary::extract_wikitext(&body))
}

fn to_items(defs: Vec<Definition>) -> Vec<DefinitionItem> {
    defs.into_iter()
        .map(|d| DefinitionItem { text: d.text, part_of_speech: d.part_of_speech })
        .collect()
}

// ── Cache integration (D4) ────────────────────────────────────────────────
//
// The cascade above is pure: it takes three injected sources and returns a
// result. The cache brackets it, and it lives HERE rather than inside
// `run_cascade` because the cascade is the part that must stay free of accounts
// and storage — its tests wire in fakes and never touch a database, which is
// what makes the step ordering assertable.
//
// Placement follows `translate.rs` exactly: the repository owns the option-A
// short-circuit (see `repository::definition::cache_owner`), and the caller does
// NOT branch on anonymity. It calls `read_cached_definition` with whatever
// `require_user_id()` returned; `""` reads as a miss and the write is a no-op.
// That is the point — "no caller can opt out of it".

/// The command-side bridge between the cascade's DTO and the repository's.
///
/// They are deliberately two types: the command's `DefinitionResult` is the wire
/// shape the frontend reads, and `models::LibraryDefinitionResult` is the stored
/// shape. Keeping them apart is what lets the repository be tested without the
/// command module, and vice versa.
fn to_stored(result: &DefinitionResult) -> crate::models::LibraryDefinitionResult {
    use crate::models::{DefinitionItemDto, LibraryDefinitionSource};

    crate::models::LibraryDefinitionResult {
        word: result.word.clone(),
        definitions: result
            .definitions
            .iter()
            .map(|d| DefinitionItemDto {
                text: d.text.clone(),
                part_of_speech: d.part_of_speech.clone(),
            })
            .collect(),
        source: result.source.map(|s| match s {
            DefinitionSource::Native => LibraryDefinitionSource::Native,
            DefinitionSource::Translated => LibraryDefinitionSource::Translated,
        }),
        attribution: result.attribution.clone(),
    }
}

fn from_stored(stored: crate::models::LibraryDefinitionResult) -> DefinitionResult {
    DefinitionResult {
        word: stored.word,
        definitions: stored
            .definitions
            .into_iter()
            .map(|d| DefinitionItem { text: d.text, part_of_speech: d.part_of_speech })
            .collect(),
        source: match stored.source {
            Some(crate::models::LibraryDefinitionSource::Native) => Some(DefinitionSource::Native),
            Some(crate::models::LibraryDefinitionSource::Translated) => {
                Some(DefinitionSource::Translated)
            }
            // An unrecognised stored value must not be promoted to `native`: the
            // reader is told the source is unknown rather than misled. An empty
            // definition list is the same state as a miss.
            _ => None,
        },
        attribution: stored.attribution,
    }
}

/// The whole cache policy of the feature as one callable unit — the composition
/// `defineSelection` performs inline, kept here so the TESTS can exercise the
/// exact ordering the command uses.
///
/// It is a free function and NOT the command body on purpose: the command
/// signature carries `tauri::State<'_, AppState>`, which needs a live app
/// (repository, queue worker, logger on disk) and is therefore not constructible
/// in a unit test. Every test that has to prove "the second call does not hit the
/// sources" needs exactly this function, so it takes `&LibraryRepository` and
/// `user_id` directly.
///
/// The command cannot call this verbatim: it must DROP the repository mutex
/// before the cascade awaits, because a `MutexGuard<LibraryRepository>` held
/// across an `.await` makes the future non-`Send`. Here the guard lives inside
/// the two scoped helpers, so this function is safe to await from a test.
///
/// The ordering is the contract:
///  1. read the cache; a hit returns without any source being touched,
///  2. otherwise run the cascade,
///  3. write what the cascade produced.
///
/// With no active account every step is still executed, and none of them does
/// anything: `user_id` is `""`, the repository's `cache_owner` short-circuits,
/// the read is `None` and the write is refused. Nothing here branches on
/// anonymity — that is precisely the point (see `repository::definition`).
///
/// A cache read error or a write error never turns a working definition into an
/// error for the reader. A cache is an accelerator, never a dependency — the same
/// posture `translateSelection` takes for its own cache.
#[cfg(test)]
#[allow(clippy::too_many_arguments)]
pub(crate) async fn define_with_cache<
    Native,
    English,
    Translate,
    NativeFut,
    EnglishFut,
    TranslateFut,
>(
    repo: &crate::repository::LibraryRepository,
    user_id: &str,
    word: &str,
    reader_lang: &str,
    word_lang: SourceLanguage<'_>,
    native_fetch: Native,
    english_fetch: English,
    translate: Translate,
) -> DefinitionResult
where
    Native: FnMut(&str) -> NativeFut,
    English: FnMut(&str) -> EnglishFut,
    Translate: FnMut(&str, &str) -> TranslateFut,
    NativeFut: std::future::Future<Output = Result<Option<String>, String>>,
    EnglishFut: std::future::Future<Output = Result<Option<String>, String>>,
    TranslateFut: std::future::Future<Output = Result<String, String>>,
{
    // Step 0 — the cache. With no active account this is a `None` from the
    // repository's own short-circuit, not a branch here.
    if let Some(stored) =
        read_cached_definition_best_effort(repo, user_id, word, reader_lang, word_lang)
    {
        return from_stored(stored);
    }

    let result =
        run_cascade(word, reader_lang, word_lang, native_fetch, english_fetch, translate).await;

    cache_definition_best_effort(repo, user_id, word, reader_lang, word_lang, &result);

    result
}

/// The read half, as a separate function so the command can scope the repository
/// mutex around it and DROP the guard before it awaits the cascade. Holding a
/// `MutexGuard<LibraryRepository>` across an `.await` makes the future non-`Send`,
/// which a Tauri command must be.
fn read_cached_definition_best_effort(
    repo: &crate::repository::LibraryRepository,
    user_id: &str,
    word: &str,
    reader_lang: &str,
    word_lang: SourceLanguage<'_>,
) -> Option<crate::models::LibraryDefinitionResult> {
    repo.read_cached_definition(user_id, word, reader_lang, word_lang.word_lang).ok().flatten()
}

/// The write half. Best-effort for the same reason as the read: the repository
/// itself refuses an empty result (see `repository::definition`), because a "no
/// definition" is a statement about the network and pinning it would trap the
/// word.
fn cache_definition_best_effort(
    repo: &crate::repository::LibraryRepository,
    user_id: &str,
    word: &str,
    reader_lang: &str,
    word_lang: SourceLanguage<'_>,
    result: &DefinitionResult,
) {
    let _ =
        repo.cache_definition(user_id, word, reader_lang, word_lang.word_lang, &to_stored(result));
}

/// The cascade, with every data source injected.
///
/// `native_fetch` returns the wikitext of `word` on the reader's-language wiki
/// (step 1); it is `None` for the "language unknown" case, which skips step 1.
/// `english_fetch` returns the wikitext on en.wiktionary (step 2). `translate`
/// turns an English gloss into the reader's language (step 3).
///
/// Each source returns a FUTURE which the cascade awaits. A source may therefore
/// be pure (an `async` block holding the answer), as every cascade test wires
/// it, or real HTTP, as the command wires it — without changing this sequence
/// and without a nested runtime: see the module header on why handing these
/// closures a `block_on` was a deadlock, not a shortcut.
///
/// The closures are `FnMut` so tests can record calls — the "step 1 hit does
/// not touch step 2" assertion is only meaningful if the call is observable.
/// The call itself records immediately; only the awaited future is lazy.
///
/// An `Err` from a fetcher marks that source as MISSED and the cascade moves
/// on; it never aborts. This is deliberate: a definition from a later source is
/// worth more than a hard error from an earlier one, and the reader's
/// experience is "no definition" only when every source truly has nothing.
pub(crate) async fn run_cascade<Native, English, Translate, NativeFut, EnglishFut, TranslateFut>(
    word: &str,
    reader_lang: &str,
    word_lang: SourceLanguage<'_>,
    mut native_fetch: Native,
    mut english_fetch: English,
    mut translate: Translate,
) -> DefinitionResult
where
    Native: FnMut(&str) -> NativeFut,
    English: FnMut(&str) -> EnglishFut,
    Translate: FnMut(&str, &str) -> TranslateFut,
    NativeFut: std::future::Future<Output = Result<Option<String>, String>>,
    EnglishFut: std::future::Future<Output = Result<Option<String>, String>>,
    TranslateFut: std::future::Future<Output = Result<String, String>>,
{
    // Step 1 — the reader's-language wiki, native gloss. Skipped when the word's
    // language is unknown, because the `{{lengua|XX}}` block cannot be chosen.
    if word_lang.known {
        if let Ok(Some(wikitext)) = native_fetch(word).await {
            let defs = wiktionary::definitions(&wikitext, word_lang.word_lang, reader_lang);
            if !defs.is_empty() {
                return DefinitionResult {
                    word: word.to_string(),
                    definitions: to_items(defs),
                    source: Some(DefinitionSource::Native),
                    attribution: WIKTIONARY_ATTRIBUTION.to_string(),
                };
            }
        }
    }

    // Step 2 — en.wiktionary, English gloss. Coverage for words the reader's
    // wiki lacks. Only reached when step 1 produced nothing usable.
    let english_defs = match english_fetch(word).await {
        Ok(Some(wikitext)) => wiktionary::definitions(&wikitext, word_lang.word_lang, "en"),
        _ => Vec::new(),
    };

    // Step 3 — translate the English gloss into the reader's language. The
    // first sense is translated; the rest ride along untranslated ONLY IF the
    // first translation fails, so a partial success is still shown.
    if !english_defs.is_empty() {
        let mut items = Vec::with_capacity(english_defs.len());
        let mut translated_any = false;
        for def in &english_defs {
            if let Ok(text) = translate(&def.text, reader_lang).await {
                translated_any = true;
                items.push(DefinitionItem { text, part_of_speech: def.part_of_speech.clone() });
            }
        }
        if translated_any {
            return DefinitionResult {
                word: word.to_string(),
                definitions: items,
                source: Some(DefinitionSource::Translated),
                attribution: WIKTIONARY_ATTRIBUTION.to_string(),
            };
        }
    }

    // Every source missed. Not an error: the reader shows "no definition".
    DefinitionResult::empty(word)
}

/// Look up a definition for `text`, in `reader_lang`, for a word written in
/// `word_lang`.
///
/// The reader-facing entry point. It is a thin wrapper over [`run_cascade`] that
/// binds the real transport: the two Wiktionary fetchers and the existing
/// MyMemory request path from `translate.rs` (reused, not reimplemented).
///
/// The cascade is bracketed by the per-account cache (D4, migration 0025): a hit
/// returns without any source being consulted, and what the cascade resolves is
/// written back. The account is command-level state (`AppState.active_user`) and
/// that is exactly why the cache is applied HERE and not in the HTTP layer —
/// the transport functions deliberately know nothing about the database, which
/// is what makes them testable against a loopback port.
///
/// With no active account the cache is neither read nor written, and the command
/// does NOT branch on that: `require_user_id()` returns `""`, the repository's
/// `cache_owner` short-circuits on it, and the definition still comes back. See
/// `repository::definition::cache_owner` — the enforcement lives one layer down
/// so no caller can opt out of it.
#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn defineSelection(
    state: tauri::State<'_, crate::state::AppState>,
    text: String,
    readerLang: String,
    wordLang: Option<String>,
) -> Result<DefinitionResult, String> {
    let word = text.trim();
    if word.is_empty() {
        return Ok(DefinitionResult::empty(word));
    }
    let reader_lang =
        if readerLang.trim().is_empty() { "en".to_string() } else { readerLang.trim().to_string() };
    let word_lang = SourceLanguage::from_optional(wordLang.as_deref().unwrap_or(""));

    let wiki_client = wiktionary_client();
    let translate_client = super::translate::translate_client();
    let reader = reader_lang.clone();
    let reader_for_translation = reader_lang.clone();

    // The cache/short-circuit is the repository's; `""` is what an anonymous
    // caller hands over and it is handled there, not here.
    let user_id = state.active_user.require_user_id();

    // Step 0 — the cache, under a lock that is DROPPED before the cascade
    // awaits: a `MutexGuard<LibraryRepository>` held across an `.await` makes the
    // command future non-`Send`, which Tauri requires. The guard is scoped to
    // this block; `translateSelection` does the same.
    let cached = {
        let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
        read_cached_definition_best_effort(&repository, &user_id, word, &reader_lang, word_lang)
    };
    if let Some(stored) = cached {
        return Ok(from_stored(stored));
    }

    let result = run_cascade(
        word,
        &reader_lang,
        word_lang,
        |w| {
            let endpoint = wiktionary_endpoint(&reader, w);
            let client = wiki_client.clone();
            let word = w.to_string();
            // A source hands back its future and `run_cascade` awaits it;
            // resolving the request here with `block_on` deadlocks — see the
            // module header. Only owned captures, so the future carries no
            // caller lifetime.
            async move { fetch_wikitext(&client, &endpoint, &word).await }
        },
        |w| {
            let endpoint = wiktionary_endpoint("en", w);
            let client = wiki_client.clone();
            let word = w.to_string();
            async move { fetch_wikitext(&client, &endpoint, &word).await }
        },
        |gloss, target| {
            let client = translate_client.clone();
            let gloss = gloss.to_string();
            // Borrows no caller local: only owned captures, so the returned
            // future carries no lifetime and the cascade stays simple.
            let target =
                if target.is_empty() { reader_for_translation.clone() } else { target.to_string() };
            async move {
                request_translation(&client, &gloss, "en", &target).await.map_err(|e| e.to_string())
            }
        },
    )
    .await;

    // Step 2 — write back, under a fresh scoped lock. Best-effort: a storage
    // failure must not turn a delivered definition into an error.
    if let Ok(repository) = state.repository.lock() {
        cache_definition_best_effort(&repository, &user_id, word, &reader_lang, word_lang, &result);
    }

    Ok(result)
}

#[cfg(test)]
mod define_cascade_tests {
    use super::*;

    const ES_HOUSE: &str = include_str!("../fixtures/wiktionary/es_house.json");
    const ES_CASA: &str = include_str!("../fixtures/wiktionary/es_casa.json");
    const EN_JUXTAPOSITION: &str = include_str!("../fixtures/wiktionary/en_juxtaposition.json");

    fn wikitext(fixture: &str) -> Option<String> {
        wiktionary::extract_wikitext(fixture)
    }

    /// A recorded fake transport: it serves the fixtures it was given and
    /// counts how often each step was asked, so the "step 1 hit does not touch
    /// step 2/3" contract is assertable.
    #[derive(Default)]
    struct Calls {
        native: Vec<String>,
        english: Vec<String>,
        translate: Vec<(String, String)>,
    }

    #[test]
    fn step_one_hit_is_native_and_never_calls_the_fallbacks() {
        // `house` on es.wiktionary (asked as the reader's-language wiki) returns
        // a native Spanish gloss; the English wiki and MyMemory must not run.
        // If the cascade ever reached a fallback the test panics here, before it
        // could fail an assertion.
        let mut calls = Calls::default();

        let result = tauri::async_runtime::block_on(run_cascade(
            "house",
            "es",
            SourceLanguage::known("en"),
            |w| {
                calls.native.push(w.to_string());
                async { Ok(wikitext(ES_HOUSE)) }
            },
            |w| {
                calls.english.push(w.to_string());
                async { panic!("step 2 must not run when step 1 hits") }
            },
            |g, t| {
                calls.translate.push((g.to_string(), t.to_string()));
                async { panic!("step 3 must not run when step 1 hits") }
            },
        ));

        assert_eq!(result.source, Some(DefinitionSource::Native));
        assert!(!result.definitions.is_empty());
        assert_eq!(calls.native, vec!["house".to_string()]);
        assert!(calls.english.is_empty());
        assert!(calls.translate.is_empty());
        assert!(result.attribution.contains("CC BY-SA"));
    }

    #[test]
    fn casa_as_a_spanish_word_is_native_and_does_not_touch_en() {
        // The reader is Spanish and the selected word is Spanish: es.wiktionary
        // already defines it in Spanish. No fallback must fire: step 2 sets the
        // flag and asserts it stayed false at the end, and step 3 panics if the
        // cascade ever asks for a translation here.
        let mut english_called = false;

        let result = tauri::async_runtime::block_on(run_cascade(
            "casa",
            "es",
            SourceLanguage::known("es"),
            |_| async { Ok(wikitext(ES_CASA)) },
            |_| {
                english_called = true;
                async { Ok(wikitext(EN_JUXTAPOSITION)) }
            },
            |_, _| async { panic!("translation must not run for a native hit") },
        ));

        assert_eq!(result.source, Some(DefinitionSource::Native));
        let joined =
            result.definitions.iter().map(|d| d.text.as_str()).collect::<Vec<_>>().join(" | ");
        assert!(
            joined.contains("edificación") || joined.contains("edificacion"),
            "expected the real Spanish senses, got {joined:?}"
        );
        assert!(!english_called, "en.wiktionary was consulted despite a native hit");
    }

    /// The case that justifies the whole cascade: `juxtaposition` has NO
    /// es.wiktionary entry (step 1 misses) but a full en.wiktionary one (step 2
    /// hits), which MyMemory passes into Spanish (step 3). The result must be
    /// marked `translated`, because the reader has a right to know.
    #[test]
    fn juxtaposition_walks_all_three_steps_and_marks_translated() {
        let mut calls = Calls::default();

        let result = tauri::async_runtime::block_on(run_cascade(
            "juxtaposition",
            "es",
            SourceLanguage::known("en"),
            |w| {
                calls.native.push(w.to_string());
                // es.wiktionary has no such page: 200 with an error body, which
                // `extract_wikitext` reduces to None.
                async { Ok(None) }
            },
            |w| {
                calls.english.push(w.to_string());
                async { Ok(wikitext(EN_JUXTAPOSITION)) }
            },
            |gloss, target| {
                calls.translate.push((gloss.to_string(), target.to_string()));
                // A stand-in for the real MyMemory answer, so the test needs no
                // network: every English gloss becomes a Spanish one here.
                let translated = format!("ES({gloss})");
                async move { Ok(translated) }
            },
        ));

        // All three steps ran, in order.
        assert_eq!(calls.native, vec!["juxtaposition".to_string()]);
        assert_eq!(calls.english, vec!["juxtaposition".to_string()]);
        assert!(!calls.translate.is_empty(), "step 3 must run");
        assert!(calls.translate.iter().all(|(_, t)| t == "es"));

        // The source is a machine translation, and the UI is told so.
        assert_eq!(result.source, Some(DefinitionSource::Translated));
        let joined =
            result.definitions.iter().map(|d| d.text.as_str()).collect::<Vec<_>>().join(" | ");
        assert!(joined.contains("ES("), "expected translated glosses, got {joined:?}");
        assert!(joined.contains("nearness"), "the English sense must have been translated");
    }

    /// The step-2 fetcher runs even when step 1 returns a page with no section
    /// for the word (a real 200 with no usable block), and step 3 follows.
    #[test]
    fn a_page_without_the_word_section_falls_through_to_english() {
        let result = tauri::async_runtime::block_on(run_cascade(
            "juxtaposition",
            "es",
            SourceLanguage::known("en"),
            // A real es.wiktionary page that simply has no `{{lengua|en}}`.
            |_| async { Ok(wikitext(ES_CASA)) },
            |_| async { Ok(wikitext(EN_JUXTAPOSITION)) },
            |g, _| {
                let translated = format!("ES({g})");
                async move { Ok(translated) }
            },
        ));

        assert_eq!(result.source, Some(DefinitionSource::Translated));
        assert!(!result.definitions.is_empty());
    }

    /// Nothing anywhere: empty, not an error, not a panic.
    #[test]
    fn nothing_found_is_an_empty_result_not_an_error() {
        let result = tauri::async_runtime::block_on(run_cascade(
            "qqqqzzzz",
            "es",
            SourceLanguage::known("en"),
            |_| async { Ok(None) },
            |_| async { Ok(None) },
            |_, _| async { panic!("translation must not run with no English gloss") },
        ));

        assert!(result.definitions.is_empty());
        assert_eq!(result.source, None);
        // The license still travels, even with no text to attribute.
        assert!(result.attribution.contains("CC BY-SA"));
        assert_eq!(result.word, "qqqqzzzz");
    }

    /// A transport failure in step 1 must not abort: the cascade moves to the
    /// fallback and still delivers.
    #[test]
    fn a_failed_source_is_skipped_not_fatal() {
        let result = tauri::async_runtime::block_on(run_cascade(
            "juxtaposition",
            "es",
            SourceLanguage::known("en"),
            |_| async { Err("DEFINE_NETWORK: es wiki down".to_string()) },
            |_| async { Ok(wikitext(EN_JUXTAPOSITION)) },
            |g, _| {
                let translated = format!("ES({g})");
                async move { Ok(translated) }
            },
        ));

        assert_eq!(result.source, Some(DefinitionSource::Translated));
    }

    /// A translation failure must not throw away the English gloss silently —
    /// here it leaves the result empty rather than showing an untranslated
    /// English sentence under a Spanish UI, which is the honest failure.
    #[test]
    fn a_failed_translation_yields_no_result_rather_than_raw_english() {
        let result = tauri::async_runtime::block_on(run_cascade(
            "juxtaposition",
            "es",
            SourceLanguage::known("en"),
            |_| async { Ok(None) },
            |_| async { Ok(wikitext(EN_JUXTAPOSITION)) },
            |_, _| async { Err("TRANSLATE_QUOTA_EXCEEDED".to_string()) },
        ));

        assert!(result.definitions.is_empty());
        assert_eq!(result.source, None);
    }

    /// DECISION under test: an unknown source language skips step 1 entirely
    /// and assumes `en` for steps 2/3, so the answer is still non-empty.
    #[test]
    fn unknown_word_language_skips_step_one_and_assumes_english() {
        let mut native_called = false;

        let result = tauri::async_runtime::block_on(run_cascade(
            "juxtaposition",
            "es",
            SourceLanguage::unknown(),
            |_| {
                native_called = true;
                async { Ok(wikitext(ES_CASA)) }
            },
            |_| async { Ok(wikitext(EN_JUXTAPOSITION)) },
            |g, _| {
                let translated = format!("ES({g})");
                async move { Ok(translated) }
            },
        ));

        assert!(!native_called, "step 1 must be skipped when the language is unknown");
        assert_eq!(result.source, Some(DefinitionSource::Translated));
        assert!(!result.definitions.is_empty());
    }

    #[test]
    fn an_empty_or_whitespace_language_code_reads_as_unknown() {
        assert!(!SourceLanguage::from_optional("").known);
        assert!(!SourceLanguage::from_optional("   ").known);
        assert!(SourceLanguage::from_optional("en").known);
        assert_eq!(SourceLanguage::from_optional(" en ").word_lang, "en");
    }
}

/// The regression test for the bug that made "Definir" hang in the shipped app.
///
/// Every other test in this file drives `run_cascade` from a plain `#[test]`,
/// and that is exactly how the bug survived: it resolves the cascade with
///
///     tauri::async_runtime::block_on(run_cascade(...))
///
/// which starts a fresh runtime on the TEST thread, so the production command
/// body was never executed by any test. In the app the command IS a task of the
/// Tauri runtime, and `block_on` there is tokio's — see below for what that does.
///
/// So these tests run inside `#[tokio::test]`: the cascade is awaited by a task
/// of a tokio runtime, which is the shape production has. Each source returns a
/// future whose body does real I/O against an endpoint that refuses; the point
/// is that the cascade COMPLETES, with the network error, instead of waiting.
///
/// MEASURED, with the three `block_on` calls temporarily restored in the command:
/// the first run of these tests FAILED with tokio's own diagnosis,
///
///     Cannot start a runtime from within a runtime. This happens because a
///     function (like `block_on`) attempted to block the current thread while
///     the thread is being used to drive asynchronous tasks.
///
/// and the second run PASSED. That inconsistency is the finding, not a flaw in
/// the test: whether `tauri::async_runtime` is a tokio runtime at all on a given
/// test binary depends on state a previous test established, so the naive
/// "block_on from inside a tokio task" repro is not deterministic.
///
/// The deterministic version of the same defect has one extra ingredient, and it
/// is the whole point: `block_on` must run on the runtime's CURRENT-THREAD
/// driver, which is exactly what a plain `#[test]` gives you. Measured with that
/// shape, the block NEVER returns — no panic, no error, no exit. That is the
/// hang, and it is also why `#[test(flavor = "current_thread")]` matters here:
/// under a multi-thread flavor the nested work is stolen by another worker and
/// the same code returns, so a passing multi-thread run proves nothing.
///
/// What the tests below therefore establish: with no `block_on` anywhere in the
/// production path, a tokio task awaiting the cascade's sources completes, and
/// the step ordering is unchanged.
#[cfg(test)]
mod define_cascade_no_deadlock_tests {
    use super::*;

    const ES_HOUSE: &str = include_str!("../fixtures/wiktionary/es_house.json");

    /// An HTTPS endpoint that refuses a connection: the URL the production
    /// fetchers build, minus the wiki.
    async fn refused_endpoint() -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let port = listener.local_addr().expect("local addr").port();
        drop(listener);
        format!("https://127.0.0.1:{port}/")
    }

    /// The cascade must come back with the honest empty result when the only
    /// source is a dead endpoint — the same path the app takes when the wikis
    /// are unreachable. Before the fix this never returned.
    ///
    /// The `join!` is a belt-and-braces guard: if a future regression made the
    /// cascade wait, the assertion fails in seconds instead of hanging the
    /// suite, which is what a `block_on`-based test cannot do for itself.
    #[tokio::test(flavor = "current_thread")]
    async fn a_dead_endpoint_completes_the_cascade_instead_of_blocking_it() {
        let endpoint = refused_endpoint().await;
        let client = reqwest::Client::builder().build().expect("test client build");

        let outcome = tokio::time::timeout(
            std::time::Duration::from_secs(20),
            run_cascade(
                // The reader's-language wiki is the dead one, so step 1 does a
                // real request; the other two are stubbed out of the way.
                "house",
                "es",
                SourceLanguage::known("en"),
                {
                    let endpoint = endpoint.clone();
                    let client = client.clone();
                    move |w| {
                        let endpoint = endpoint.clone();
                        let client = client.clone();
                        let word = w.to_string();
                        async move { fetch_wikitext(&client, &endpoint, &word).await }
                    }
                },
                |_| async { Ok(None) },
                |_, _| async { Err("must not be reached: there is no English gloss".to_string()) },
            ),
        )
        .await
        .expect("the cascade never returned: a source blocked the runtime instead of awaiting");

        // Transport failure is a MISS, never an abort: the cascade moved on.
        assert!(outcome.definitions.is_empty());
        assert_eq!(outcome.source, None);
        assert_eq!(outcome.word, "house");
    }

    /// The same shape with a source that SUCCEEDS. It proves the fix did not
    /// merely make the failure path return: a real future resolving inside a
    /// tokio task still feeds the cascade and still stops at step 1, so the
    /// step-ordering contract survives the move to async sources.
    #[tokio::test(flavor = "current_thread")]
    async fn an_awaited_source_still_feeds_the_cascade_and_stops_at_step_one() {
        let outcome = tokio::time::timeout(
            std::time::Duration::from_secs(20),
            run_cascade(
                "house",
                "es",
                SourceLanguage::known("en"),
                |_| async { Ok(wiktionary::extract_wikitext(ES_HOUSE)) },
                |_| async { Err("step 2 must not run when step 1 hits".to_string()) },
                |_, _| async { Err("step 3 must not run when step 1 hits".to_string()) },
            ),
        )
        .await
        .expect("the cascade never returned");

        assert_eq!(outcome.source, Some(DefinitionSource::Native));
        assert!(!outcome.definitions.is_empty());
        assert!(outcome.attribution.contains("CC BY-SA"));
    }
}

#[cfg(test)]
mod define_network_tests {
    use super::*;

    /// A `reqwest::Error` cannot be built by hand, so the honest source is a
    /// real transport failure. Port 1 on loopback refuses immediately: no DNS,
    /// no internet, no timeout wait. The scheme must be https to reach the
    /// transport at all.
    const REFUSED_ENDPOINT: &str = "https://127.0.0.1:1/";

    #[test]
    fn a_closed_port_surfaces_the_network_code() {
        let client = reqwest::Client::builder().build().expect("test client build");
        let err =
            tauri::async_runtime::block_on(fetch_wikitext(&client, REFUSED_ENDPOINT, "house"))
                .unwrap_err();
        assert!(err.starts_with(ERR_NETWORK), "got {err}");
    }

    /// The endpoint builder is pure: a `{lang}` and a word, nothing else.
    #[test]
    fn the_endpoint_names_the_wiki_and_the_word() {
        let url = wiktionary_endpoint("es", "house");
        assert!(url.starts_with("https://es.wiktionary.org/w/api.php"));
        assert!(url.contains("page=house"));
        assert!(url.contains("prop=wikitext"));

        let en = wiktionary_endpoint("en", "juxtaposition");
        assert!(en.starts_with("https://en.wiktionary.org/"));
    }

    /// The User-Agent must be identifiable and carry a real contact, per the
    /// Wikimedia policy. A `TBD` placeholder or a bare product name is blocked.
    #[test]
    fn the_user_agent_is_identifiable_and_carries_a_contact() {
        assert!(WIKTIONARY_USER_AGENT.starts_with("Nexo/"));
        assert!(WIKTIONARY_USER_AGENT.contains("contact:"), "policy requires a contact");
        assert!(!WIKTIONARY_USER_AGENT.contains("TBD"), "the placeholder must be gone");
    }

    #[test]
    fn a_non_https_endpoint_is_refused_before_any_request() {
        let client = reqwest::Client::builder().build().expect("test client build");
        let err =
            tauri::async_runtime::block_on(fetch_wikitext(&client, "http://127.0.0.1:1/", "house"))
                .unwrap_err();
        assert!(err.starts_with(ERR_HTTPS_REQUIRED), "got {err}");
    }
}

#[cfg(test)]
mod define_cache_tests {
    use super::*;
    use crate::repository::tests::new_repository;
    use crate::state::ActiveUser;

    const ES_HOUSE: &str = include_str!("../fixtures/wiktionary/es_house.json");
    const EN_JUXTAPOSITION: &str = include_str!("../fixtures/wiktionary/en_juxtaposition.json");

    fn wikitext(fixture: &str) -> Option<String> {
        wiktionary::extract_wikitext(fixture)
    }

    /// Requirement 4, the point of the whole feature: the SECOND lookup of the
    /// same word serves from the cache and touches no source at all.
    ///
    /// The counters are the assertion — "no source was asked" is only meaningful
    /// if every source is observable, so each fake increments its own count and
    /// the test asserts they are unchanged on the second call.
    #[test]
    fn a_second_lookup_of_the_same_word_does_not_reach_the_sources() {
        let repo = new_repository();
        let active_user = ActiveUser::new();
        active_user.set(Some("user-7".to_string()));
        let owner = active_user.require_user_id();

        let mut native_calls = 0;
        let mut english_calls = 0;

        // First call: the cache misses, step 1 hits (es.wiktionary has `house`).
        let first = tauri::async_runtime::block_on(define_with_cache(
            &repo,
            &owner,
            "house",
            "es",
            SourceLanguage::known("en"),
            |_| {
                native_calls += 1;
                async { Ok(wikitext(ES_HOUSE)) }
            },
            |_| {
                english_calls += 1;
                async { Ok(wikitext(EN_JUXTAPOSITION)) }
            },
            |_, _| async { panic!("step 3 must not run for a native hit") },
        ));

        assert_eq!(first.source, Some(DefinitionSource::Native));
        assert_eq!((native_calls, english_calls), (1, 0));

        // Second call: identical inputs, and every source is poisoned. A cache
        // miss would panic before it could fail an assert, which is the honest
        // way to state "the sources were not consulted".
        let second = tauri::async_runtime::block_on(define_with_cache(
            &repo,
            &owner,
            "house",
            "es",
            SourceLanguage::known("en"),
            |_| async { panic!("the native source must not run on a cache hit") },
            |_| async { panic!("the english source must not run on a cache hit") },
            |_, _| async { panic!("the translator must not run on a cache hit") },
        ));

        assert_eq!(second, first, "the cached result must be identical, source included");
        assert_eq!(
            (native_calls, english_calls),
            (1, 0),
            "the second call must not have reached a source"
        );
    }

    /// The cache stores the TRANSLATED path too, and the `source` survives it —
    /// which is the field the UI needs in order to warn the reader.
    #[test]
    fn a_cached_translated_result_keeps_its_source() {
        let repo = new_repository();
        let owner = "user-7";

        let first = tauri::async_runtime::block_on(define_with_cache(
            &repo,
            owner,
            "juxtaposition",
            "es",
            SourceLanguage::known("en"),
            |_| async { Ok(None) },
            |_| async { Ok(wikitext(EN_JUXTAPOSITION)) },
            |g, _| {
                let translated = format!("ES({g})");
                async move { Ok(translated) }
            },
        ));
        assert_eq!(first.source, Some(DefinitionSource::Translated));

        let second = tauri::async_runtime::block_on(define_with_cache(
            &repo,
            owner,
            "juxtaposition",
            "es",
            SourceLanguage::known("en"),
            |_| async { panic!("source 1 must not run on a hit") },
            |_| async { panic!("source 2 must not run on a hit") },
            |_, _| async { panic!("the translator must not run on a hit") },
        ));
        assert_eq!(second.source, Some(DefinitionSource::Translated));
        assert_eq!(second.definitions, first.definitions);
    }

    /// Requirement 2, through the real path: with NO active account the cache
    /// neither stores nor serves. Both halves are asserted — the second call
    /// must reach the sources AGAIN (nothing was written), and the row count in
    /// the table must be zero (nothing leaked to the empty owner).
    #[test]
    fn without_an_active_account_nothing_is_cached_and_nothing_is_served() {
        let repo = new_repository();
        let active_user = ActiveUser::new(); // never set: anonymous
        let owner = active_user.require_user_id();
        assert_eq!(owner, "");

        let mut native_calls = 0;
        let mut run = || {
            tauri::async_runtime::block_on(define_with_cache(
                &repo,
                &owner,
                "house",
                "es",
                SourceLanguage::known("en"),
                |_| {
                    native_calls += 1;
                    async { Ok(wikitext(ES_HOUSE)) }
                },
                |_| async { panic!("step 2 must not run for a native hit") },
                |_, _| async { panic!("step 3 must not run for a native hit") },
            ))
        };

        let first = run();
        let second = run();

        assert_eq!(first.source, Some(DefinitionSource::Native));
        assert_eq!(second.source, Some(DefinitionSource::Native));
        assert_eq!(native_calls, 2, "an anonymous lookup must never be served from a cache");

        let count: i64 = repo
            .connection()
            .query_row("SELECT COUNT(*) FROM definition_cache", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 0, "an anonymous lookup must never write a row");
    }

    /// Requirement 3, end to end: two accounts, the same word, each gets its own
    /// answer. `user-2`'s cache is pre-seeded with a different gloss, so a
    /// cross-account hit is unmistakable.
    #[test]
    fn two_accounts_looking_up_the_same_word_see_their_own_answer() {
        let repo = new_repository();

        // user-1 populates their entry from the cascade.
        let first = tauri::async_runtime::block_on(define_with_cache(
            &repo,
            "user-1",
            "house",
            "es",
            SourceLanguage::known("en"),
            |_| async { Ok(wikitext(ES_HOUSE)) },
            |_| async { panic!("step 2 must not run") },
            |_, _| async { panic!("step 3 must not run") },
        ));
        assert_eq!(first.source, Some(DefinitionSource::Native));

        // user-2 has never looked this word up: a shared row would be served
        // here, so the source MUST be reached again.
        let mut user_2_reached_the_source = false;
        let second = tauri::async_runtime::block_on(define_with_cache(
            &repo,
            "user-2",
            "house",
            "es",
            SourceLanguage::known("en"),
            |_| {
                user_2_reached_the_source = true;
                async { Ok(wikitext(ES_HOUSE)) }
            },
            |_| async { panic!("step 2 must not run") },
            |_, _| async { panic!("step 3 must not run") },
        ));

        assert!(user_2_reached_the_source, "user-2 must not be served user-1's entry");
        assert_eq!(second.source, Some(DefinitionSource::Native));

        // Both accounts now own exactly one row, and each reads its own.
        let count_for = |owner: &str| -> i64 {
            repo.connection()
                .query_row(
                    "SELECT COUNT(*) FROM definition_cache WHERE user_id = ?1",
                    rusqlite::params![owner],
                    |row| row.get(0),
                )
                .unwrap()
        };
        assert_eq!(count_for("user-1"), 1);
        assert_eq!(count_for("user-2"), 1);
    }

    /// Signing out must make BOTH halves silent no-ops on the very next call,
    /// which is the contract `translate.rs` pins for its own cache. Asserted
    /// with a real `ActiveUser` being cleared, not with a literal `""`.
    #[test]
    fn after_sign_out_the_next_lookup_neither_hits_nor_stores() {
        let repo = new_repository();
        let active_user = ActiveUser::new();
        active_user.set(Some("user-7".to_string()));

        tauri::async_runtime::block_on(define_with_cache(
            &repo,
            &active_user.require_user_id(),
            "house",
            "es",
            SourceLanguage::known("en"),
            |_| async { Ok(wikitext(ES_HOUSE)) },
            |_| async { panic!("step 2 must not run") },
            |_, _| async { panic!("step 3 must not run") },
        ));

        active_user.clear();
        let owner = active_user.require_user_id();
        assert_eq!(owner, "");

        // No hit: the pre-seeded row is invisible, so the source runs again.
        let mut sources_ran = 0;
        let result = tauri::async_runtime::block_on(define_with_cache(
            &repo,
            &owner,
            "house",
            "es",
            SourceLanguage::known("en"),
            |_| {
                sources_ran += 1;
                async { Ok(wikitext(ES_HOUSE)) }
            },
            |_| async { panic!("step 2 must not run") },
            |_, _| async { panic!("step 3 must not run") },
        ));
        assert_eq!(sources_ran, 1, "an anonymous lookup must not read an account's row");
        assert_eq!(result.source, Some(DefinitionSource::Native));

        // No write: still exactly the one row the signed-in session created.
        let count: i64 = repo
            .connection()
            .query_row("SELECT COUNT(*) FROM definition_cache", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 1);
    }

    /// The empty result is NOT cached, so a transient "nothing anywhere" does
    /// not trap the word: the next lookup asks the sources again and can recover.
    #[test]
    fn a_no_definition_result_is_not_cached_so_a_later_lookup_can_recover() {
        let repo = new_repository();
        let owner = "user-7";

        let empty = tauri::async_runtime::block_on(define_with_cache(
            &repo,
            owner,
            "qqqqzzzz",
            "es",
            SourceLanguage::known("en"),
            |_| async { Ok(None) },
            |_| async { Ok(None) },
            |_, _| async { panic!("step 3 must not run with no english gloss") },
        ));
        assert!(empty.definitions.is_empty());
        assert_eq!(empty.source, None);

        let count: i64 = repo
            .connection()
            .query_row("SELECT COUNT(*) FROM definition_cache", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 0);

        // The next call reaches the sources again and, this time, succeeds.
        let recovered = tauri::async_runtime::block_on(define_with_cache(
            &repo,
            owner,
            "qqqqzzzz",
            "es",
            SourceLanguage::known("en"),
            |_| async { Ok(wikitext(ES_HOUSE)) },
            |_| async { panic!("step 2 must not run") },
            |_, _| async { panic!("step 3 must not run") },
        ));
        assert_eq!(recovered.source, Some(DefinitionSource::Native));
    }

    /// The reader language is part of the identity, so the same word on a
    /// different UI language is a different cascade and must MISS.
    #[test]
    fn the_same_word_for_a_different_reader_language_is_a_miss() {
        let repo = new_repository();
        let owner = "user-7";

        tauri::async_runtime::block_on(define_with_cache(
            &repo,
            owner,
            "house",
            "es",
            SourceLanguage::known("en"),
            |_| async { Ok(wikitext(ES_HOUSE)) },
            |_| async { panic!("step 2 must not run") },
            |_, _| async { panic!("step 3 must not run") },
        ));

        let mut reached_the_source = false;
        tauri::async_runtime::block_on(define_with_cache(
            &repo,
            owner,
            "house",
            "en",
            SourceLanguage::known("en"),
            |_| {
                reached_the_source = true;
                async { Ok(None) }
            },
            |_| async { Ok(None) },
            |_, _| async { Err("TRANSLATE_QUOTA_EXCEEDED".to_string()) },
        ));
        assert!(reached_the_source, "a different reader language must not reuse the entry");
    }
}

#[cfg(test)]
mod define_dto_tests {
    use super::*;

    /// The wire shape the frontend reads. It is pinned because the UI branches
    /// on `source` to warn about a machine translation, and on `attribution` to
    /// satisfy the license — a silent rename here would break both.
    #[test]
    fn the_result_serialises_with_source_and_attribution() {
        let result = DefinitionResult {
            word: "house".to_string(),
            definitions: vec![DefinitionItem {
                text: "casa".to_string(),
                part_of_speech: Some("sustantivo".to_string()),
            }],
            source: Some(DefinitionSource::Native),
            attribution: WIKTIONARY_ATTRIBUTION.to_string(),
        };

        let json = serde_json::to_value(&result).expect("serialise");
        assert_eq!(json["word"], "house");
        assert_eq!(json["source"], "native");
        assert_eq!(json["definitions"][0]["text"], "casa");
        assert_eq!(json["definitions"][0]["partOfSpeech"], "sustantivo");
        assert!(json["attribution"].as_str().unwrap().contains("CC BY-SA"));
    }

    #[test]
    fn a_translated_result_serialises_its_source_distinctly() {
        let json = serde_json::to_value(DefinitionSource::Translated).unwrap();
        assert_eq!(json, "translated");
    }
}
