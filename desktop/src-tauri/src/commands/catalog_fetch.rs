//! Catalog feed fetch — Tauri command behind the Standard Ebooks rail.
//!
//! The webview must never fetch the Atom feed itself: standardebooks.org sends
//! no `Access-Control-Allow-Origin`, so a frontend `fetch` always dies on CORS
//! before a single byte arrives. This command performs the download in Rust
//! (no CORS) and returns the raw body text; the frontend parses it, so the
//! existing Atom mapper and its status→code mapping are unchanged.
//!
//! Transport only: HTTPS pre-check, Nexo UA, bounded redirects, timeouts and
//! no cookie store (same policy as `addon_fetch` / `update_feed` / `download`).
//! The Accept header is the Atom/XML media type this endpoint actually serves
//! — the update-feed precedent asks for `application/json`, which a catalog
//! feed would not answer. A non-2xx status surfaces
//! `CATALOG_FETCH_HTTP_{status}` and never echoes the body.

pub const CATALOG_FETCH_USER_AGENT: &str = "Nexo/Desktop (contact: TBD)";
pub const CATALOG_FETCH_MAX_REDIRECTS: usize = 5;
pub const CATALOG_FETCH_CONNECT_TIMEOUT_S: u64 = 15;
pub const CATALOG_FETCH_READ_TIMEOUT_S: u64 = 30;
/// Atom/XML first, permissive fallback so a feed served as `text/xml` still
/// answers instead of 406-ing.
pub const CATALOG_FETCH_ACCEPT: &str = "application/atom+xml, application/xml;q=0.9, */*;q=0.8";

// Module-private on purpose: `commands/mod.rs` re-exports every command module
// with a glob, and `addon_fetch` already exports `ERR_HTTPS_REQUIRED`,
// `ERR_NETWORK` and `check_https` with those exact names. Two globs exporting
// one name make it ambiguous (and `cargo clippy -D warnings` rejects the
// warning), so the catalog-local items stay unexported — same precedent as
// `update_feed.rs` / `download.rs`.
const ERR_HTTPS_REQUIRED: &str = "CATALOG_FETCH_HTTPS_REQUIRED";
const ERR_HTTP_PREFIX: &str = "CATALOG_FETCH_HTTP_";
const ERR_NETWORK: &str = "CATALOG_FETCH_NETWORK";

#[derive(Debug, Clone, PartialEq, Eq)]
struct CatalogFetchError {
    code: String,
    detail: String,
}

impl std::fmt::Display for CatalogFetchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.detail)
    }
}

impl std::error::Error for CatalogFetchError {}

fn error(code: &str, detail: impl Into<String>) -> CatalogFetchError {
    CatalogFetchError { code: code.to_string(), detail: detail.into() }
}

fn check_https(url: &str) -> Result<(), CatalogFetchError> {
    let parsed = url::Url::parse(url)
        .map_err(|_| error(ERR_HTTPS_REQUIRED, format!("catalog URL must be https: {url}")))?;
    if parsed.scheme() != "https" {
        return Err(error(ERR_HTTPS_REQUIRED, format!("catalog URL must be https: {url}")));
    }
    Ok(())
}

fn catalog_fetch_client() -> reqwest::Client {
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    CLIENT
        .get_or_init(|| {
            reqwest::Client::builder()
                .user_agent(CATALOG_FETCH_USER_AGENT)
                .redirect(reqwest::redirect::Policy::limited(CATALOG_FETCH_MAX_REDIRECTS))
                .connect_timeout(std::time::Duration::from_secs(CATALOG_FETCH_CONNECT_TIMEOUT_S))
                .timeout(std::time::Duration::from_secs(CATALOG_FETCH_READ_TIMEOUT_S))
                .build()
                .expect("catalog fetch http client build")
        })
        .clone()
}

async fn fetch_feed_body(client: &reqwest::Client, url: &str) -> Result<String, CatalogFetchError> {
    check_https(url)?;
    let response = client
        .get(url)
        .header(reqwest::header::USER_AGENT, CATALOG_FETCH_USER_AGENT)
        .header(reqwest::header::ACCEPT, CATALOG_FETCH_ACCEPT)
        .send()
        .await
        .map_err(|e| error(ERR_NETWORK, format!("catalog request failed: {e}")))?;
    let status = response.status();
    if !status.is_success() {
        return Err(error(
            &format!("{ERR_HTTP_PREFIX}{}", status.as_u16()),
            format!("catalog feed responded {status}"),
        ));
    }
    response.text().await.map_err(|e| error(ERR_NETWORK, format!("catalog read failed: {e}")))
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn fetchCatalogFeed(url: String) -> Result<String, String> {
    fetch_feed_body(&catalog_fetch_client(), &url).await.map_err(|e| e.to_string())
}

#[cfg(test)]
mod catalog_fetch_tests {
    use super::*;

    #[test]
    fn rejects_http_scheme_before_any_io() {
        let err = check_https("http://standardebooks.org/feeds/atom/new-releases").unwrap_err();
        assert_eq!(err.code, ERR_HTTPS_REQUIRED);
    }

    #[test]
    fn rejects_non_http_schemes_before_any_io() {
        for url in [
            "ftp://standardebooks.org/feeds/atom/new-releases",
            "file:///C:/feeds/new-releases.xml",
            "data:application/atom+xml,<feed/>",
        ] {
            let err = check_https(url).unwrap_err();
            assert_eq!(err.code, ERR_HTTPS_REQUIRED, "{url}");
        }
    }

    #[test]
    fn accepts_https_url() {
        check_https("https://standardebooks.org/feeds/atom/new-releases").unwrap();
    }

    #[test]
    fn rejects_malformed_url_as_https_required() {
        let err = check_https("not a url").unwrap_err();
        assert_eq!(err.code, ERR_HTTPS_REQUIRED);
    }

    /// The feed is Atom/XML; asking for JSON (the update-feed header) is the
    /// exact mistake this command exists to avoid.
    #[test]
    fn accept_header_prefers_atom_xml() {
        assert!(CATALOG_FETCH_ACCEPT.starts_with("application/atom+xml"));
        assert!(!CATALOG_FETCH_ACCEPT.contains("application/json"));
    }
}
