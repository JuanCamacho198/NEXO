//! Selection translation — MyMemory request behind the reader's selection
//! toolbar, plus the three pure guards that keep its output honest.
//!
//! The guards exist because the service misbehaves in measured, specific ways:
//! it fabricates output for punctuation (`"."` → `"idiomas"`), answers an
//! unbounded number of questions badly rather than refusing, echoes the input
//! with a high `match` when it has nothing to say, and returns HTML entities.
//! Every test below pins a case that was MEASURED against the live API.
//!
//! There is deliberately NO cache: the desktop has no active-account
//! mechanism, and a translation cache is reading history — on Android that
//! cache was global and leaked between accounts. Shipping without one repeats
//! none of that risk. See `TRANSLATE_ENDPOINT`.

/// Longest selection the toolbar will translate. A selection past this is
/// almost always a whole-page drag, not a lookup.
pub const TRANSLATE_MAX_LEN: usize = 2000;

/// MyMemory is a public endpoint and needs no key. There is deliberately NO
/// cache here: the desktop has no active-account mechanism, and on Android the
/// translation cache WAS reading history — global, shared across accounts, and
/// a real leak. Shipping without a cache repeats none of that risk (MyMemory is
/// free and unauthenticated). Adding one would replicate the Android bug.
pub const TRANSLATE_ENDPOINT: &str = "https://api.mymemory.translated.net/get";
pub const TRANSLATE_USER_AGENT: &str = "Nexo/Desktop (contact: TBD)";
pub const TRANSLATE_MAX_REDIRECTS: usize = 5;
pub const TRANSLATE_CONNECT_TIMEOUT_S: u64 = 15;
pub const TRANSLATE_READ_TIMEOUT_S: u64 = 30;

/// True when the string is too short, has no letter, or is a URL/email — the
/// shapes MyMemory answers with fabricated or markup output.
///
/// The minimum is 2 characters AFTER trim, on purpose: a one-character
/// selection in the reader is almost always an accidental drag of the handle,
/// and the API happily invents output for it ("." → "bility"). `"y"` would
/// translate well, but it is rejected by the same rule and the test fixes that
/// decision (see `rejects_single_letter_even_when_it_would_translate_well`).
pub fn is_translatable_input(text: &str) -> bool {
    let trimmed = text.trim();
    if trimmed.chars().count() < 2 {
        return false;
    }
    if trimmed.chars().count() > TRANSLATE_MAX_LEN {
        return false;
    }
    if !trimmed.chars().any(char::is_alphabetic) {
        return false;
    }
    if looks_like_url_or_email(trimmed) {
        return false;
    }
    true
}

/// A URL or an email is a reference, not prose; translating it is noise at
/// best and a fabricated answer at worst.
fn looks_like_url_or_email(text: &str) -> bool {
    let lower = text.to_lowercase();
    if lower.starts_with("http://") || lower.starts_with("https://") || lower.starts_with("www.") {
        return true;
    }
    // A single token with an `@` and a dotted domain after it is an email.
    if !text.contains(char::is_whitespace) {
        if let Some((_, domain)) = text.split_once('@') {
            if domain.contains('.') && !domain.starts_with('.') && !domain.ends_with('.') {
                return true;
            }
        }
    }
    false
}

/// True when `translated` is a usable sentence: non-empty, not an HTML entity,
/// and not merely the original echoed back.
///
/// The echo check exists because `match` is not trustworthy: a French→French
/// request answered with the original text AND `match: 0.99`. Only an exact,
/// case-insensitive, trimmed comparison catches a no-op translation.
pub fn is_usable_output(original: &str, translated: &str) -> bool {
    let out = translated.trim();
    if out.is_empty() {
        return false;
    }
    if out.eq_ignore_ascii_case(original.trim()) {
        return false;
    }
    if is_html_entity(out) {
        return false;
    }
    true
}

/// True only for a COMPLETE HTML entity (`&laquo;`, `&#171;`, `&#xAB;`).
///
/// The pattern demands the full `&...;` construct, so a bare ampersand inside
/// real text (`AT&T`) is never rejected — rejecting it would be a regression.
fn is_html_entity(text: &str) -> bool {
    static ENTITY: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    let re = ENTITY.get_or_init(|| {
        regex::Regex::new(r"^&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);$")
            .expect("html entity regex")
    });
    re.is_match(text)
}

// ── Transport ─────────────────────────────────────────────────────────────
//
// Same posture as `catalog_fetch.rs`: HTTPS pre-check, bounded redirects,
// explicit timeouts, no cookie store, typed error codes returned as `String`.
// Nothing is persisted — see the NO-CACHE note on `TRANSLATE_ENDPOINT`.

// Module-private on purpose: `commands/mod.rs` globs every command module and
// `catalog_fetch` already exports `ERR_HTTPS_REQUIRED` / `ERR_NETWORK` with
// those exact names, so translate-local items stay unexported (same precedent
// as `update_feed.rs` / `download.rs`).
const ERR_HTTPS_REQUIRED: &str = "TRANSLATE_HTTPS_REQUIRED";
const ERR_HTTP_PREFIX: &str = "TRANSLATE_HTTP_";
const ERR_NETWORK: &str = "TRANSLATE_NETWORK";
const ERR_UNTRANSLATABLE_INPUT: &str = "TRANSLATE_UNTRANSLATABLE_INPUT";
const ERR_QUOTA_EXCEEDED: &str = "TRANSLATE_QUOTA_EXCEEDED";
const ERR_NO_TRANSLATION: &str = "TRANSLATE_NO_TRANSLATION";
const ERR_BAD_RESPONSE: &str = "TRANSLATE_BAD_RESPONSE";

#[derive(Debug, Clone, PartialEq, Eq)]
struct TranslateError {
    code: String,
    detail: String,
}

impl std::fmt::Display for TranslateError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.detail)
    }
}

impl std::error::Error for TranslateError {}

fn error(code: &str, detail: impl Into<String>) -> TranslateError {
    TranslateError { code: code.to_string(), detail: detail.into() }
}

/// The subset of the MyMemory payload this feature reads. Only `translatedText`
/// and `match` are needed; `match` is kept for observability even though the
/// echo guard (not `match`) is what rejects a no-op.
#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct MyMemoryResponse {
    response_data: MyMemoryData,
    response_status: serde_json::Value,
}

#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct MyMemoryData {
    translated_text: String,
    /// Kept for observability only. It is NOT used as a validity signal: a
    /// French→French echo arrives with `match: 0.99`, so the echo guard
    /// (`is_usable_output`), not this value, decides a no-op.
    #[serde(default, rename = "match")]
    #[allow(dead_code)]
    match_confidence: Option<f64>,
}

fn translate_client() -> reqwest::Client {
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    CLIENT
        .get_or_init(|| {
            reqwest::Client::builder()
                .user_agent(TRANSLATE_USER_AGENT)
                .redirect(reqwest::redirect::Policy::limited(TRANSLATE_MAX_REDIRECTS))
                .connect_timeout(std::time::Duration::from_secs(TRANSLATE_CONNECT_TIMEOUT_S))
                .timeout(std::time::Duration::from_secs(TRANSLATE_READ_TIMEOUT_S))
                .build()
                .expect("translate http client build")
        })
        .clone()
}

/// MyMemory reports some failures inside a 200 body via `responseStatus`
/// (e.g. quota exhausted). A non-200 in the body is a failure even when the
/// HTTP transport was fine.
///
/// Measured live: a same-language pair (e.g. an English book on an English UI)
/// answers `responseStatus: "403"` with `"PLEASE SELECT TWO DISTINCT
/// LANGUAGES"`. That is not an outage — there is simply nothing to translate —
/// so it maps to `ERR_NO_TRANSLATION` (the honest "no result"), while every
/// other non-200 stays a failure.
fn check_response_status(status: &serde_json::Value) -> Result<(), TranslateError> {
    let as_number = status.as_i64().or_else(|| status.as_str().and_then(|s| s.parse().ok()));
    match as_number {
        Some(200) => Ok(()),
        Some(403) => {
            Err(error(ERR_NO_TRANSLATION, "translation service requires two distinct languages"))
        }
        Some(429) => {
            Err(error(ERR_QUOTA_EXCEEDED, format!("translation quota exceeded ({status})")))
        }
        Some(code) => Err(error(ERR_BAD_RESPONSE, format!("translation service responded {code}"))),
        None => Err(error(ERR_BAD_RESPONSE, "translation service returned no status".to_string())),
    }
}

/// Parse a MyMemory body into a usable translation, applying both output
/// guards. `Err(ERR_NO_TRANSLATION)` is the honest answer for an echo, an
/// empty string, or an HTML entity — the caller shows "no translation", never
/// the original dressed up as one.
fn parse_translation(original: &str, body: &str) -> Result<String, TranslateError> {
    let parsed: MyMemoryResponse = serde_json::from_str(body)
        .map_err(|e| error(ERR_BAD_RESPONSE, format!("translation body was not JSON: {e}")))?;
    check_response_status(&parsed.response_status)?;
    let translated = parsed.response_data.translated_text;
    if !is_usable_output(original, &translated) {
        return Err(error(ERR_NO_TRANSLATION, "no usable translation was returned".to_string()));
    }
    Ok(translated.trim().to_string())
}

async fn request_translation(
    client: &reqwest::Client,
    text: &str,
    source: &str,
    target: &str,
) -> Result<String, TranslateError> {
    if !is_translatable_input(text) {
        return Err(error(ERR_UNTRANSLATABLE_INPUT, "selection is not translatable".to_string()));
    }
    let url = url::Url::parse(TRANSLATE_ENDPOINT)
        .map_err(|e| error(ERR_HTTPS_REQUIRED, format!("translate endpoint invalid: {e}")))?;
    if url.scheme() != "https" {
        return Err(error(ERR_HTTPS_REQUIRED, "translate endpoint must be https".to_string()));
    }
    let langpair = format!("{source}|{target}");
    let response = client
        .get(url)
        .query(&[("q", text), ("langpair", langpair.as_str())])
        .send()
        .await
        .map_err(|e| error(ERR_NETWORK, format!("translation request failed: {e}")))?;
    let status = response.status();
    if !status.is_success() {
        return Err(error(
            &format!("{ERR_HTTP_PREFIX}{}", status.as_u16()),
            format!("translation service responded {status}"),
        ));
    }
    let body = response
        .text()
        .await
        .map_err(|e| error(ERR_NETWORK, format!("translation read failed: {e}")))?;
    parse_translation(text, &body)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn translateSelection(
    text: String,
    sourceLang: String,
    targetLang: String,
) -> Result<String, String> {
    request_translation(&translate_client(), &text, &sourceLang, &targetLang)
        .await
        .map_err(|e| e.to_string())
}

#[cfg(test)]
mod translate_guards_tests {
    use super::*;

    // ── Guard 2: input that is not worth sending ──────────────────────────
    // Measured against the live API: "." came back as "bility" (fabricated)
    // and "«" came back as "&laquo;" (markup). Both must be rejected BEFORE
    // any request is made.

    #[test]
    fn rejects_single_punctuation_that_the_api_fabricates() {
        // Live: "." → "bility".
        assert!(!is_translatable_input("."));
        // Live: "«" → "&laquo;".
        assert!(!is_translatable_input("«"));
    }

    #[test]
    fn rejects_whitespace_only_and_short_input() {
        assert!(!is_translatable_input(""));
        assert!(!is_translatable_input("   "));
        assert!(!is_translatable_input(" "));
        assert!(!is_translatable_input("a"));
        assert!(!is_translatable_input("7"));
    }

    #[test]
    fn rejects_input_with_no_letter_at_all() {
        assert!(!is_translatable_input("123"));
        assert!(!is_translatable_input("..."));
        assert!(!is_translatable_input("—"));
        assert!(!is_translatable_input("()"));
    }

    /// The documented decision: "y" IS translated well by the API ("and"),
    /// but one-character selections are almost always an accidental handle
    /// drag in the reader, so the minimum length rejects it on purpose. This
    /// test fixes that choice so it cannot be "fixed" by accident.
    #[test]
    fn rejects_single_letter_even_when_it_would_translate_well() {
        // Live: "y" → "and". Rejected anyway: minimum length wins.
        assert!(!is_translatable_input("y"));
    }

    #[test]
    fn rejects_urls_and_emails() {
        assert!(!is_translatable_input("https://example.com/a/b"));
        assert!(!is_translatable_input("http://example.com"));
        assert!(!is_translatable_input("www.example.com"));
        assert!(!is_translatable_input("reader@example.com"));
    }

    #[test]
    fn accepts_a_short_real_word_and_a_sentence() {
        assert!(is_translatable_input("hello"));
        assert!(is_translatable_input("ephemeral"));
        assert!(is_translatable_input("The quick brown fox."));
        // Two characters that are letters clear the minimum.
        assert!(is_translatable_input("to"));
    }

    #[test]
    fn rejects_input_longer_than_the_cap() {
        let too_long = "a".repeat(TRANSLATE_MAX_LEN + 1);
        assert!(!is_translatable_input(&too_long));
        let at_cap = "a".repeat(TRANSLATE_MAX_LEN);
        assert!(is_translatable_input(&at_cap));
    }

    // ── Guard 1: the echo ─────────────────────────────────────────────────
    // Measured: a French→French request answered with the original text AND
    // match: 0.99, so `match` cannot be trusted to detect a no-op. Only an
    // exact (case-insensitive, trimmed) comparison catches it.

    #[test]
    fn treats_an_identical_response_as_no_translation() {
        assert!(!is_usable_output("bonjour", "bonjour"));
    }

    #[test]
    fn treats_a_case_only_difference_as_an_echo() {
        assert!(!is_usable_output("Hello", "hello"));
        assert!(!is_usable_output("BONJOUR", "bonjour"));
    }

    #[test]
    fn treats_a_whitespace_only_difference_as_an_echo() {
        assert!(!is_usable_output("bonjour", "  bonjour  "));
        assert!(!is_usable_output("  hello world ", "hello world"));
    }

    #[test]
    fn accepts_a_real_translation() {
        assert!(is_usable_output("hola", "hello"));
        assert!(is_usable_output("The quick brown fox", "El rápido zorro marrón"));
    }

    // ── Guard 3: output that is not text ──────────────────────────────────

    #[test]
    fn rejects_html_entity_output() {
        // Named, decimal and hex entities — all measured shapes of the "«" bug.
        assert!(!is_usable_output("x", "&laquo;"));
        assert!(!is_usable_output("x", "&#171;"));
        assert!(!is_usable_output("x", "&#xAB;"));
        assert!(!is_usable_output("x", "&amp;"));
    }

    #[test]
    fn rejects_empty_translated_output() {
        assert!(!is_usable_output("hello", ""));
        assert!(!is_usable_output("hello", "   "));
    }

    /// The trap: `AT&T` contains a bare `&`, not an entity. Rejecting it would
    /// be a regression, so the entity pattern must demand the full construct.
    #[test]
    fn keeps_a_translation_containing_a_bare_ampersand() {
        assert!(is_usable_output("AT&T", "AT&T Corporation"));
        assert!(is_usable_output("Tom & Jerry", "Tom y Jerry"));
    }
}

#[cfg(test)]
mod translate_parser_tests {
    use super::*;

    /// The real success shape, captured from the live API:
    /// `{"responseData":{"translatedText":"...","match":0.99},"responseStatus":200}`.
    const OK_BODY: &str =
        r#"{"responseData":{"translatedText":"and","match":0.99},"responseStatus":200}"#;

    /// The live echo bug: French in, French out, still `match: 0.99`.
    const ECHO_BODY: &str =
        r#"{"responseData":{"translatedText":"bonjour","match":0.99},"responseStatus":200}"#;

    /// The live markup bug: `«` came back as the HTML entity.
    const ENTITY_BODY: &str =
        r#"{"responseData":{"translatedText":"&laquo;","match":1.0},"responseStatus":200}"#;

    #[test]
    fn parses_a_real_translation_from_the_documented_payload() {
        assert_eq!(parse_translation("y", OK_BODY).unwrap(), "and");
    }

    #[test]
    fn rejects_the_echo_even_though_match_is_high() {
        let err = parse_translation("bonjour", ECHO_BODY).unwrap_err();
        assert_eq!(err.code, ERR_NO_TRANSLATION);
    }

    #[test]
    fn rejects_markup_output() {
        let err = parse_translation("«", ENTITY_BODY).unwrap_err();
        assert_eq!(err.code, ERR_NO_TRANSLATION);
    }

    #[test]
    fn surfaces_a_quota_error_from_the_response_status() {
        let body = r#"{"responseData":{"translatedText":"","match":0},"responseStatus":429}"#;
        let err = parse_translation("hello", body).unwrap_err();
        assert_eq!(err.code, ERR_QUOTA_EXCEEDED);
    }

    #[test]
    fn surfaces_a_non_200_response_status_as_bad_response() {
        let body = r#"{"responseData":{"translatedText":"x","match":1},"responseStatus":500}"#;
        let err = parse_translation("hello", body).unwrap_err();
        assert_eq!(err.code, ERR_BAD_RESPONSE);
    }

    /// Measured live: a same-language pair answers `responseStatus: "403"` with
    /// `"PLEASE SELECT TWO DISTINCT LANGUAGES"`. There is nothing to translate,
    /// so it is a "no result", never an outage.
    #[test]
    fn treats_the_same_language_pair_rejection_as_no_translation() {
        let body = r#"{"responseData":{"translatedText":"PLEASE SELECT TWO DISTINCT LANGUAGES","match":0},"responseStatus":"403"}"#;
        let err = parse_translation("hello", body).unwrap_err();
        assert_eq!(err.code, ERR_NO_TRANSLATION);
    }

    /// MyMemory reports the status as a string in some payloads.
    #[test]
    fn accepts_a_string_response_status_of_200() {
        let body =
            r#"{"responseData":{"translatedText":"hello","match":1},"responseStatus":"200"}"#;
        assert_eq!(parse_translation("hola", body).unwrap(), "hello");
    }

    #[test]
    fn rejects_a_body_that_is_not_json() {
        let err = parse_translation("hello", "<html>error</html>").unwrap_err();
        assert_eq!(err.code, ERR_BAD_RESPONSE);
    }
}
