//! Addon resource fetch — Tauri command + transport guards.
//!
//! Transport guards only: HTTPS pre-check, 64KB streaming cap, Nexo UA,
//! no cookie store. Manifest JSON validation lives in TS/Kotlin
//! (`validateManifest` / `ManifestValidator`) so both platforms share it.

use serde::Serialize;

pub const ADDON_MAX_BYTES: u64 = 64 * 1024;
pub const ADDON_USER_AGENT: &str = "Nexo/Desktop (contact: TBD)";

pub const ERR_HTTPS_REQUIRED: &str = "ADDON_FETCH_HTTPS_REQUIRED";
pub const ERR_TOO_LARGE: &str = "ADDON_FETCH_TOO_LARGE";
pub const ERR_BAD_CONTENT_TYPE: &str = "ADDON_FETCH_BAD_CONTENT_TYPE";
pub const ERR_INVALID_MANIFEST: &str = "ADDON_FETCH_INVALID_MANIFEST";
pub const ERR_NETWORK: &str = "ADDON_FETCH_NETWORK";

#[derive(Debug)]
pub struct AddonFetchError {
    pub code: String,
    pub detail: String,
}

impl std::fmt::Display for AddonFetchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.detail)
    }
}

impl std::error::Error for AddonFetchError {}

fn error(code: &str, detail: impl Into<String>) -> AddonFetchError {
    AddonFetchError { code: code.to_string(), detail: detail.into() }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AddonResource {
    pub status: u16,
    pub content_type: Option<String>,
    pub body: Vec<u8>,
}

pub fn check_https(url: &str) -> Result<(), AddonFetchError> {
    let parsed = url::Url::parse(url)
        .map_err(|_| error(ERR_HTTPS_REQUIRED, format!("install URL must be https: {url}")))?;
    if parsed.scheme() != "https" {
        return Err(error(ERR_HTTPS_REQUIRED, format!("install URL must be https: {url}")));
    }
    Ok(())
}

/// Enforce the byte cap while streaming: abort with TOO_LARGE as soon as
/// accumulated bytes exceed `max` — never buffers the whole payload.
pub fn push_chunk(buf: &mut Vec<u8>, max: u64, chunk: &[u8]) -> Result<(), AddonFetchError> {
    let projected = buf.len() as u64 + chunk.len() as u64;
    if projected > max {
        return Err(error(ERR_TOO_LARGE, format!("resource exceeds {max} bytes")));
    }
    buf.extend_from_slice(chunk);
    Ok(())
}

pub async fn fetch_resource(
    client: &reqwest::Client,
    url: &str,
    max_bytes: u64,
) -> Result<AddonResource, AddonFetchError> {
    check_https(url)?;
    let response = client
        .get(url)
        .header(reqwest::header::USER_AGENT, ADDON_USER_AGENT)
        .header(reqwest::header::ACCEPT, "application/json")
        .send()
        .await
        .map_err(|e| error(ERR_NETWORK, format!("addon request failed: {e}")))?;
    let status = response.status().as_u16();
    let content_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());
    let mut body: Vec<u8> = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = futures_util::StreamExt::next(&mut stream).await {
        let chunk = chunk.map_err(|e| error(ERR_NETWORK, format!("addon stream failed: {e}")))?;
        push_chunk(&mut body, max_bytes, &chunk)?;
    }
    Ok(AddonResource { status, content_type, body })
}

fn addon_client() -> reqwest::Client {
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    CLIENT
        .get_or_init(|| {
            // Deliberately NO cookie store: addon origins never receive or replay cookies.
            reqwest::Client::builder()
                .user_agent(ADDON_USER_AGENT)
                .redirect(reqwest::redirect::Policy::limited(5))
                .build()
                .expect("addon http client build")
        })
        .clone()
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn fetchAddonResource(
    url: String,
    maxBytes: Option<u64>,
) -> Result<AddonResource, String> {
    let max_bytes = maxBytes.unwrap_or(ADDON_MAX_BYTES).min(ADDON_MAX_BYTES);
    fetch_resource(&addon_client(), &url, max_bytes).await.map_err(|e| e.to_string())
}

#[cfg(test)]
mod addon_fetch_tests {
    use super::*;

    #[test]
    fn rejects_http_scheme_before_any_io() {
        let err = check_https("http://example.com/manifest.json").unwrap_err();
        assert_eq!(err.code, ERR_HTTPS_REQUIRED);
    }

    #[test]
    fn rejects_non_http_schemes_before_any_io() {
        for url in ["ftp://example.com/m.json", "file:///etc/manifest.json"] {
            let err = check_https(url).unwrap_err();
            assert_eq!(err.code, ERR_HTTPS_REQUIRED, "{url}");
        }
    }

    #[test]
    fn accepts_https_url() {
        check_https("https://example.com/manifest.json").unwrap();
    }

    #[test]
    fn rejects_malformed_url_as_https_required() {
        let err = check_https("not a url").unwrap_err();
        assert_eq!(err.code, ERR_HTTPS_REQUIRED);
    }

    #[test]
    fn accumulates_chunks_under_cap() {
        let mut buf = Vec::new();
        push_chunk(&mut buf, 1024, &[0u8; 512]).unwrap();
        push_chunk(&mut buf, 1024, &[0u8; 512]).unwrap();
        assert_eq!(buf.len(), 1024);
    }

    #[test]
    fn aborts_stream_when_cap_exceeded() {
        let mut buf = Vec::new();
        push_chunk(&mut buf, 64 * 1024, &[0u8; 60 * 1024]).unwrap();
        let err = push_chunk(&mut buf, 64 * 1024, &[0u8; 8 * 1024]).unwrap_err();
        assert_eq!(err.code, ERR_TOO_LARGE);
    }

    #[test]
    fn exact_cap_is_accepted() {
        let mut buf = Vec::new();
        push_chunk(&mut buf, 64 * 1024, &[0u8; 64 * 1024]).unwrap();
        assert_eq!(buf.len(), 64 * 1024);
    }
}
