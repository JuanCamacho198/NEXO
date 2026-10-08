//! Update feed fetch — Tauri command behind the in-app update check.
//!
//! The webview must never fetch `latest.json` itself: GitHub sends no
//! `Access-Control-Allow-Origin`, so a frontend `fetch` always dies on CORS
//! in the installed build. This command performs the download in Rust
//! (no CORS) and returns the raw body text; the frontend parses it so the
//! existing UNREACHABLE / MALFORMED mapping in `updateChecker` is unchanged.
//!
//! Transport only: HTTPS pre-check, Nexo UA, bounded redirects, timeouts and
//! no cookie store (same policy as `addon_fetch` / `download`). A non-2xx
//! status surfaces `UPDATE_FEED_HTTP_{status}` and never echoes the body.

pub const UPDATE_FEED_USER_AGENT: &str = "Nexo/Desktop (contact: TBD)";
pub const UPDATE_FEED_MAX_REDIRECTS: usize = 5;
pub const UPDATE_FEED_CONNECT_TIMEOUT_S: u64 = 15;
pub const UPDATE_FEED_READ_TIMEOUT_S: u64 = 30;

// Module-private on purpose: `commands/mod.rs` re-exports every command
// module with a glob, and `addon_fetch` already exports `ERR_HTTPS_REQUIRED`,
// `ERR_NETWORK` and `check_https` with those exact names. Two globs exporting
// one name make it ambiguous (and `cargo clippy -D warnings` rejects the
// warning), so the update-feed-local items stay unexported — same precedent
// as `download.rs`. The `UPDATE_FEED_*` code values are unchanged.
const ERR_HTTPS_REQUIRED: &str = "UPDATE_FEED_HTTPS_REQUIRED";
const ERR_HTTP_PREFIX: &str = "UPDATE_FEED_HTTP_";
const ERR_NETWORK: &str = "UPDATE_FEED_NETWORK";

#[derive(Debug, Clone, PartialEq, Eq)]
struct UpdateFeedError {
    code: String,
    detail: String,
}

impl std::fmt::Display for UpdateFeedError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.detail)
    }
}

impl std::error::Error for UpdateFeedError {}

fn error(code: &str, detail: impl Into<String>) -> UpdateFeedError {
    UpdateFeedError { code: code.to_string(), detail: detail.into() }
}

fn check_https(url: &str) -> Result<(), UpdateFeedError> {
    let parsed = url::Url::parse(url)
        .map_err(|_| error(ERR_HTTPS_REQUIRED, format!("update feed URL must be https: {url}")))?;
    if parsed.scheme() != "https" {
        return Err(error(ERR_HTTPS_REQUIRED, format!("update feed URL must be https: {url}")));
    }
    Ok(())
}

fn update_feed_client() -> reqwest::Client {
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    CLIENT
        .get_or_init(|| {
            reqwest::Client::builder()
                .user_agent(UPDATE_FEED_USER_AGENT)
                .redirect(reqwest::redirect::Policy::limited(UPDATE_FEED_MAX_REDIRECTS))
                .connect_timeout(std::time::Duration::from_secs(UPDATE_FEED_CONNECT_TIMEOUT_S))
                .timeout(std::time::Duration::from_secs(UPDATE_FEED_READ_TIMEOUT_S))
                .build()
                .expect("update feed http client build")
        })
        .clone()
}

async fn fetch_feed_body(client: &reqwest::Client, url: &str) -> Result<String, UpdateFeedError> {
    check_https(url)?;
    let response = client
        .get(url)
        .header(reqwest::header::USER_AGENT, UPDATE_FEED_USER_AGENT)
        .header(reqwest::header::ACCEPT, "application/json")
        .send()
        .await
        .map_err(|e| error(ERR_NETWORK, format!("update feed request failed: {e}")))?;
    let status = response.status();
    if !status.is_success() {
        return Err(error(
            &format!("{ERR_HTTP_PREFIX}{}", status.as_u16()),
            format!("update feed responded {status}"),
        ));
    }
    response.text().await.map_err(|e| error(ERR_NETWORK, format!("update feed read failed: {e}")))
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn fetchUpdateFeed(url: String) -> Result<String, String> {
    fetch_feed_body(&update_feed_client(), &url).await.map_err(|e| e.to_string())
}

#[cfg(test)]
mod update_feed_tests {
    use super::*;

    #[test]
    fn rejects_http_scheme_before_any_io() {
        let err = check_https("http://example.com/latest.json").unwrap_err();
        assert_eq!(err.code, ERR_HTTPS_REQUIRED);
    }

    #[test]
    fn rejects_non_http_schemes_before_any_io() {
        for url in ["ftp://example.com/latest.json", "file:///etc/latest.json"] {
            let err = check_https(url).unwrap_err();
            assert_eq!(err.code, ERR_HTTPS_REQUIRED, "{url}");
        }
    }

    #[test]
    fn accepts_https_url() {
        check_https("https://example.com/latest.json").unwrap();
    }

    #[test]
    fn rejects_malformed_url_as_https_required() {
        let err = check_https("not a url").unwrap_err();
        assert_eq!(err.code, ERR_HTTPS_REQUIRED);
    }
}
