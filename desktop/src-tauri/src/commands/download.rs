//! Remote book download — backend streaming transfer with hard caps.
//!
//! The webview never fetches a book binary (the CSP forbids it): this module
//! performs the transfer in Rust, streams it to a `.part` file inside
//! `{app_data}/tmp/downloads`, emits throttled byte progress on the
//! `discover-download-progress` event, supports per-transfer cancellation and
//! atomically renames `.part` -> final on success. The frontend then feeds the
//! final file into the EXISTING file import path (`getFileBytes` +
//! `importRecoveredBook`), so no new persistence model is introduced.
//!
//! Policy (mirrored from `addon_fetch`, deliberately NOT reusing
//! `fetchAddonResource`, which stays the 64 KiB JSON path):
//! - `check_https` runs BEFORE any I/O;
//! - the client is built once (`download_client`) with a bounded redirect
//!   policy, the Nexo User-Agent, connect/read timeouts and no cookie
//!   persistence (`reqwest` is built without its cookie feature, so cookies
//!   can neither be stored nor replayed);
//! - `MAX_DOWNLOAD_BYTES` is enforced twice: pre-stream from `content-length`
//!   and per chunk while streaming;
//! - non-2xx responses surface `BOOK_DOWNLOAD_HTTP_{status}` and never echo an
//!   upstream body.
//!
//! Terminal events: exactly one is emitted per registered transfer. The
//! transfer writes to `{app_data}/tmp/downloads/{sanitizedId}.{sanitizedExt}`
//! and never uses a user-controlled path segment. `BOOK_DOWNLOAD_BAD_PATH`
//! covers local path problems (preparing, writing, finalizing, hashing) as
//! well as a refused `discardRemoteDownload` target.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::Manager;

use crate::filename::{canonical_extension, normalize_segment};

pub const DOWNLOAD_USER_AGENT: &str = "Nexo/Desktop (contact: TBD)";
pub const MAX_DOWNLOAD_BYTES: u64 = 67_108_864;
pub const MAX_REDIRECTS: usize = 5;
pub const CONNECT_TIMEOUT_S: u64 = 15;
pub const READ_TIMEOUT_S: u64 = 30;
pub const PROGRESS_THROTTLE_MS: u128 = 250;
pub const PROGRESS_MIN_BYTES: u64 = 262_144;
pub const DOWNLOAD_EVENT: &str = "discover-download-progress";

pub const ERR_HTTP_PREFIX: &str = "BOOK_DOWNLOAD_HTTP_";
pub const ERR_CANCELLED: &str = "BOOK_DOWNLOAD_CANCELLED";
pub const ERR_BAD_PATH: &str = "BOOK_DOWNLOAD_BAD_PATH";

// Module-private on purpose: `commands/mod.rs` re-exports every command module
// with a glob, and `addon_fetch` already exports `ERR_HTTPS_REQUIRED`,
// `ERR_TOO_LARGE`, `ERR_NETWORK` and `check_https` with those exact names. Two
// globs exporting one name make it ambiguous (and `cargo clippy -D warnings`
// rejects the warning), so the download-local items stay unexported. The
// `BOOK_DOWNLOAD_*` code values are unchanged.
const ERR_HTTPS_REQUIRED: &str = "BOOK_DOWNLOAD_HTTPS_REQUIRED";
const ERR_TOO_LARGE: &str = "BOOK_DOWNLOAD_TOO_LARGE";
const ERR_NETWORK: &str = "BOOK_DOWNLOAD_NETWORK";

pub(crate) const PHASE_PROGRESS: &str = "progress";
pub(crate) const PHASE_COMPLETED: &str = "completed";
pub(crate) const PHASE_FAILED: &str = "failed";
pub(crate) const PHASE_CANCELLED: &str = "cancelled";

const DOWNLOADS_DIR: &str = "downloads";
const MAX_ID_CHARS: usize = 64;
const HASH_BUFFER_BYTES: usize = 64 * 1024;
const FALLBACK_ID: &str = "download";

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct DownloadError {
    pub code: String,
    pub detail: String,
    /// Bytes durably written when the transfer failed; 0 for a pre-stream
    /// policy failure. Carried so the single terminal event reports the count
    /// actually reached instead of guessing.
    pub downloaded: u64,
}

impl DownloadError {
    fn new(code: &str, detail: impl Into<String>) -> Self {
        Self { code: code.to_string(), detail: detail.into(), downloaded: 0 }
    }

    fn at(mut self, downloaded: u64) -> Self {
        self.downloaded = downloaded;
        self
    }

    fn http(status: u16) -> Self {
        Self::new(
            &format!("{ERR_HTTP_PREFIX}{status}"),
            format!("download failed with status {status}"),
        )
    }

    fn too_large(cap: u64) -> Self {
        Self::new(ERR_TOO_LARGE, format!("download exceeds the {cap} byte cap"))
    }

    fn network(detail: impl Into<String>) -> Self {
        Self::new(ERR_NETWORK, detail)
    }

    fn cancelled() -> Self {
        Self::new(ERR_CANCELLED, "download cancelled")
    }

    fn bad_path(detail: impl Into<String>) -> Self {
        Self::new(ERR_BAD_PATH, detail)
    }
}

impl std::fmt::Display for DownloadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.detail)
    }
}

impl std::error::Error for DownloadError {}

/// `context: {error}` plus every source in the chain, so a transport failure is
/// diagnosable without ever echoing an upstream response body.
fn describe_transport_error(context: &str, err: &(dyn std::error::Error + 'static)) -> String {
    let mut message = format!("{context}: {err}");
    let mut source = err.source();
    while let Some(cause) = source {
        message.push_str(" <- ");
        message.push_str(&cause.to_string());
        source = cause.source();
    }
    message
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadRemoteBookInput {
    pub transfer_id: String,
    pub url: String,
    pub format: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadRemoteBookResult {
    pub file_path: String,
    pub bytes: u64,
    pub sha256: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadProgressPayload {
    pub transfer_id: String,
    pub downloaded: u64,
    pub total: Option<u64>,
    pub phase: &'static str,
}

/// Terminal outcome of a streamed transfer, with the byte count reached.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum StreamOutcome {
    Completed { downloaded: u64 },
    Cancelled { downloaded: u64 },
}

impl StreamOutcome {
    pub(crate) fn downloaded(&self) -> u64 {
        match self {
            StreamOutcome::Completed { downloaded } | StreamOutcome::Cancelled { downloaded } => {
                *downloaded
            }
        }
    }
}

pub(crate) trait ProgressSink {
    fn emit(&mut self, downloaded: u64, total: Option<u64>, phase: &'static str);
}

pub(crate) struct TauriProgressSink {
    app: tauri::AppHandle,
    transfer_id: String,
}

impl TauriProgressSink {
    pub(crate) fn new(app: tauri::AppHandle, transfer_id: impl Into<String>) -> Self {
        Self { app, transfer_id: transfer_id.into() }
    }
}

impl ProgressSink for TauriProgressSink {
    fn emit(&mut self, downloaded: u64, total: Option<u64>, phase: &'static str) {
        use tauri::Emitter;
        let payload = DownloadProgressPayload {
            transfer_id: self.transfer_id.clone(),
            downloaded,
            total,
            phase,
        };
        let _ = self.app.emit(DOWNLOAD_EVENT, payload);
    }
}

/// Mirrors `addon_fetch::check_https`: a non-https scheme or a malformed URL is
/// rejected before any I/O. Module-private for the same glob-clash reason as the
/// code constants above.
fn check_https(url: &str) -> Result<(), DownloadError> {
    let parsed = url::Url::parse(url).map_err(|_| {
        DownloadError::new(ERR_HTTPS_REQUIRED, format!("download URL must be https: {url}"))
    })?;
    if parsed.scheme() != "https" {
        return Err(DownloadError::new(
            ERR_HTTPS_REQUIRED,
            format!("download URL must be https: {url}"),
        ));
    }
    Ok(())
}

/// Pre-stream guard: non-2xx becomes a deterministic `BOOK_DOWNLOAD_HTTP_{status}`
/// (never an upstream body), and a declared `content-length` above the cap is
/// rejected before a single byte reaches the disk.
pub(crate) fn guard_response(response: &reqwest::Response) -> Result<Option<u64>, DownloadError> {
    let status = response.status();
    if !status.is_success() {
        return Err(DownloadError::http(status.as_u16()));
    }
    match response.content_length() {
        Some(declared) if declared > MAX_DOWNLOAD_BYTES => {
            Err(DownloadError::too_large(MAX_DOWNLOAD_BYTES))
        }
        other => Ok(other),
    }
}

/// Throttle decision: emit when the window has elapsed OR the byte burst is
/// large enough — whichever happens first (one event per chunk would flood the
/// webview on a fast transfer).
pub(crate) fn should_emit(elapsed_ms: u128, delta_bytes: u64) -> bool {
    elapsed_ms >= PROGRESS_THROTTLE_MS || delta_bytes >= PROGRESS_MIN_BYTES
}

fn downloads_dir(app_data_dir: &Path) -> PathBuf {
    app_data_dir.join("tmp").join(DOWNLOADS_DIR)
}

pub(crate) fn download_paths(
    app_data_dir: &Path,
    transfer_id: &str,
    format: Option<&str>,
) -> (PathBuf, PathBuf) {
    let id = normalize_segment(transfer_id, MAX_ID_CHARS, FALLBACK_ID);
    let ext = canonical_extension(format);
    let dir = downloads_dir(app_data_dir);
    let final_path = dir.join(format!("{id}.{ext}"));
    let part_path = dir.join(format!("{id}.{ext}.part"));
    (part_path, final_path)
}

/// Lexical `..`/`.` resolution; safe for paths that do not exist yet.
fn normalize_lexically(path: &Path) -> PathBuf {
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            Component::ParentDir => {
                normalized.pop();
            }
            Component::CurDir => {}
            other => normalized.push(other.as_os_str()),
        }
    }
    normalized
}

/// Containment check for discard: the candidate must resolve inside
/// `{app_data}/tmp/downloads`. Lexically first (so a `..` traversal is refused
/// even when nothing exists yet), then by real filesystem identity.
pub(crate) fn is_inside_downloads_dir(app_data_dir: &Path, candidate: &Path) -> bool {
    let base = normalize_lexically(&downloads_dir(app_data_dir));
    let absolute =
        if candidate.is_absolute() { candidate.to_path_buf() } else { base.join(candidate) };
    if normalize_lexically(&absolute).starts_with(&base) {
        return true;
    }
    match (std::fs::canonicalize(&base), std::fs::canonicalize(&absolute)) {
        (Ok(base_real), Ok(candidate_real)) => candidate_real.starts_with(base_real),
        _ => false,
    }
}

pub(crate) fn discard_part(path: &Path) {
    if path.exists() {
        let _ = std::fs::remove_file(path);
    }
}

fn sha256_file(path: &Path) -> Result<String, DownloadError> {
    let mut file = std::fs::File::open(path)
        .map_err(|err| DownloadError::bad_path(format!("failed to read the download: {err}")))?;
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; HASH_BUFFER_BYTES];
    loop {
        let read = file.read(&mut buffer).map_err(|err| {
            DownloadError::bad_path(format!("failed to hash the download: {err}"))
        })?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

fn finalize_download(part_path: &Path, final_path: &Path) -> Result<String, DownloadError> {
    std::fs::rename(part_path, final_path).map_err(|err| {
        DownloadError::bad_path(format!("failed to finalize the download: {err}"))
    })?;
    sha256_file(final_path)
}

/// Per-transfer cancellation flags. Registered before the request is sent and
/// removed when the transfer ends, so cancelling a finished id is a no-op.
static TRANSFERS: OnceLock<Mutex<HashMap<String, Arc<AtomicBool>>>> = OnceLock::new();

fn transfers() -> &'static Mutex<HashMap<String, Arc<AtomicBool>>> {
    TRANSFERS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(crate) fn register_transfer(transfer_id: &str) -> Arc<AtomicBool> {
    let flag = Arc::new(AtomicBool::new(false));
    if let Ok(mut map) = transfers().lock() {
        map.insert(transfer_id.to_string(), flag.clone());
    }
    flag
}

pub(crate) fn cancel_transfer(transfer_id: &str) -> bool {
    match transfers().lock() {
        Ok(map) => match map.get(transfer_id) {
            Some(flag) => {
                flag.store(true, Ordering::SeqCst);
                true
            }
            None => false,
        },
        Err(_) => false,
    }
}

pub(crate) fn finish_transfer(transfer_id: &str) {
    if let Ok(mut map) = transfers().lock() {
        map.remove(transfer_id);
    }
}

/// Unregisters the transfer however it ends, so cancelling a finished transfer
/// stays a harmless no-op.
struct TransferGuard<'a> {
    transfer_id: &'a str,
}

impl Drop for TransferGuard<'_> {
    fn drop(&mut self) {
        finish_transfer(self.transfer_id);
    }
}

pub(crate) async fn stream_to_file<S, T, E>(
    body: S,
    sink: &mut impl Write,
    cap: u64,
    total: Option<u64>,
    cancel: &AtomicBool,
    progress: &mut impl ProgressSink,
) -> Result<StreamOutcome, DownloadError>
where
    S: futures_util::Stream<Item = Result<T, E>> + Unpin,
    T: AsRef<[u8]>,
    E: std::fmt::Display,
{
    let mut body = body;
    let mut downloaded: u64 = 0;
    let mut last_emit = Instant::now();
    let mut last_emitted_bytes: u64 = 0;

    while let Some(chunk) = body.next().await {
        if cancel.load(Ordering::SeqCst) {
            return Ok(StreamOutcome::Cancelled { downloaded });
        }
        let chunk = match chunk {
            Ok(chunk) => chunk,
            Err(err) => {
                return Err(
                    DownloadError::network(format!("download stream failed: {err}")).at(downloaded)
                );
            }
        };
        let bytes: &[u8] = chunk.as_ref();
        let projected = downloaded.saturating_add(bytes.len() as u64);
        if projected > cap {
            // Abort at the boundary: the offending chunk is never written.
            return Err(DownloadError::too_large(cap).at(downloaded));
        }
        if let Err(err) = sink.write_all(bytes) {
            return Err(
                DownloadError::bad_path(format!("failed to write download: {err}")).at(downloaded)
            );
        }
        downloaded = projected;

        if should_emit(last_emit.elapsed().as_millis(), downloaded - last_emitted_bytes) {
            last_emit = Instant::now();
            last_emitted_bytes = downloaded;
            progress.emit(downloaded, total, PHASE_PROGRESS);
        }
    }

    if let Err(err) = sink.flush() {
        return Err(
            DownloadError::bad_path(format!("failed to flush download: {err}")).at(downloaded)
        );
    }
    Ok(StreamOutcome::Completed { downloaded })
}

/// Entry point: the https policy gate runs BEFORE anything else, then the
/// transfer runs. A non-https URL therefore cannot register a transfer, emit an
/// event, or touch the disk.
pub(crate) async fn perform_download(
    client: &reqwest::Client,
    app_data_dir: &Path,
    input: &DownloadRemoteBookInput,
    progress: &mut impl ProgressSink,
) -> Result<DownloadRemoteBookResult, DownloadError> {
    check_https(&input.url)?;
    perform_transfer(client, app_data_dir, input, progress).await
}

/// The transfer itself, AFTER the https policy gate: register, fetch, stream
/// to `.part`, finalize, clean up. Split out from `perform_download` so the
/// transport tests can drive it against a loopback server (plain http, which
/// the gate above must reject) without weakening the gate.
pub(crate) async fn perform_transfer(
    client: &reqwest::Client,
    app_data_dir: &Path,
    input: &DownloadRemoteBookInput,
    progress: &mut impl ProgressSink,
) -> Result<DownloadRemoteBookResult, DownloadError> {
    let (part_path, final_path) =
        download_paths(app_data_dir, &input.transfer_id, input.format.as_deref());
    let cancel_flag = register_transfer(&input.transfer_id);
    let _guard = TransferGuard { transfer_id: &input.transfer_id };

    if let Err(err) = std::fs::create_dir_all(downloads_dir(app_data_dir)) {
        progress.emit(0, None, PHASE_FAILED);
        return Err(DownloadError::bad_path(format!("failed to prepare the downloads dir: {err}")));
    }
    // Retry safety: a leftover final file for the same transfer id would make
    // the rename below non-atomic, so it is removed before streaming.
    let _ = std::fs::remove_file(&final_path);

    let response = match client.get(&input.url).send().await {
        Ok(response) => response,
        Err(err) => {
            progress.emit(0, None, PHASE_FAILED);
            return Err(DownloadError::network(describe_transport_error(
                "download request failed",
                &err,
            )));
        }
    };

    let declared_total = response.content_length();
    let total = match guard_response(&response) {
        Ok(total) => total,
        Err(err) => {
            progress.emit(0, declared_total, PHASE_FAILED);
            return Err(err);
        }
    };

    let mut file = match std::fs::File::create(&part_path) {
        Ok(file) => file,
        Err(err) => {
            progress.emit(0, total, PHASE_FAILED);
            return Err(DownloadError::bad_path(format!(
                "failed to create the partial download: {err}"
            )));
        }
    };

    let outcome = stream_to_file(
        response.bytes_stream(),
        &mut file,
        MAX_DOWNLOAD_BYTES,
        total,
        &cancel_flag,
        progress,
    )
    .await;
    drop(file); // Windows: the handle must be closed before the rename

    let downloaded = match &outcome {
        Ok(streamed) => streamed.downloaded(),
        Err(err) => err.downloaded,
    };
    let result = match outcome {
        Ok(StreamOutcome::Completed { .. }) => match finalize_download(&part_path, &final_path) {
            Ok(sha256) => Ok(DownloadRemoteBookResult {
                file_path: final_path.to_string_lossy().to_string(),
                bytes: downloaded,
                sha256,
            }),
            Err(err) => Err(err.at(downloaded)),
        },
        Ok(StreamOutcome::Cancelled { .. }) => Err(DownloadError::cancelled().at(downloaded)),
        Err(err) => Err(err),
    };

    // Exactly ONE terminal event per registered transfer, whichever the outcome.
    match result {
        Ok(result) => {
            progress.emit(downloaded, total, PHASE_COMPLETED);
            Ok(result)
        }
        Err(err) => {
            // A failure or cancel leaves neither the `.part` nor a final file.
            discard_part(&part_path);
            discard_part(&final_path);
            let phase = if err.code == ERR_CANCELLED { PHASE_CANCELLED } else { PHASE_FAILED };
            progress.emit(err.downloaded, total, phase);
            Err(err)
        }
    }
}

pub(crate) fn discard_remote_download(
    app_data_dir: &Path,
    file_path: &str,
) -> Result<bool, DownloadError> {
    let candidate = PathBuf::from(file_path);
    if !is_inside_downloads_dir(app_data_dir, &candidate) {
        return Err(DownloadError::bad_path(format!(
            "refusing to delete a path outside the downloads dir: {file_path}"
        )));
    }
    if !candidate.exists() {
        return Ok(false);
    }
    std::fs::remove_file(&candidate)
        .map_err(|err| DownloadError::bad_path(format!("failed to delete the download: {err}")))?;
    Ok(true)
}

pub(crate) fn build_download_client() -> reqwest::ClientBuilder {
    // Deliberately NO cookie persistence: reqwest is compiled with
    // `default-features = false, features = ["rustls-tls", "stream"]`, so its
    // cookie feature is absent and no cookie store is even available.
    reqwest::Client::builder()
        .user_agent(DOWNLOAD_USER_AGENT)
        .redirect(reqwest::redirect::Policy::limited(MAX_REDIRECTS))
        .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_S))
        .read_timeout(Duration::from_secs(READ_TIMEOUT_S))
}

pub(crate) fn download_client() -> reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT
        .get_or_init(|| build_download_client().build().expect("download http client build"))
        .clone()
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn downloadRemoteBook(
    app: tauri::AppHandle,
    payload: DownloadRemoteBookInput,
) -> Result<DownloadRemoteBookResult, String> {
    let app_data_dir = app.path().app_data_dir().map_err(|err| format!("{}", err))?;
    let mut progress = TauriProgressSink::new(app, payload.transfer_id.clone());
    perform_download(&download_client(), &app_data_dir, &payload, &mut progress)
        .await
        .map_err(|err| err.to_string())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn cancelRemoteDownload(transfer_id: String) -> Result<bool, String> {
    Ok(cancel_transfer(&transfer_id))
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn discardRemoteDownload(app: tauri::AppHandle, file_path: String) -> Result<bool, String> {
    let app_data_dir = app.path().app_data_dir().map_err(|err| format!("{}", err))?;
    discard_remote_download(&app_data_dir, &file_path).map_err(|err| err.to_string())
}

#[cfg(test)]
mod download_tests {
    use super::*;
    use futures_util::stream;
    use std::net::TcpListener;

    #[derive(Debug, Clone, PartialEq, Eq)]
    struct RecordedEvent {
        downloaded: u64,
        total: Option<u64>,
        phase: &'static str,
    }

    #[derive(Debug, Default)]
    struct RecordingSink {
        events: Vec<RecordedEvent>,
    }

    impl RecordingSink {
        fn progress_count(&self) -> usize {
            self.events.iter().filter(|event| event.phase == PHASE_PROGRESS).count()
        }

        fn terminal_count(&self) -> usize {
            self.events.iter().filter(|event| event.phase != PHASE_PROGRESS).count()
        }

        fn last(&self) -> &RecordedEvent {
            self.events.last().expect("at least one event")
        }
    }

    impl ProgressSink for RecordingSink {
        fn emit(&mut self, downloaded: u64, total: Option<u64>, phase: &'static str) {
            self.events.push(RecordedEvent { downloaded, total, phase });
        }
    }

    /// Records the size of every `write` call, proving the transfer writes each
    /// chunk as it arrives instead of buffering the whole body.
    #[derive(Debug, Default)]
    struct CountingSink {
        writes: Vec<usize>,
    }

    impl Write for CountingSink {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.writes.push(buf.len());
            Ok(buf.len())
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    /// Progress recorder shared with the test thread, so a test can wait for the
    /// transfer to reach a known byte count before acting on it.
    #[derive(Debug, Default, Clone)]
    struct SharedSink {
        inner: Arc<Mutex<RecordingSink>>,
    }

    impl SharedSink {
        fn recorded(&self) -> std::sync::MutexGuard<'_, RecordingSink> {
            self.inner.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
        }

        fn progress_count(&self) -> usize {
            self.recorded().progress_count()
        }
    }

    impl ProgressSink for SharedSink {
        fn emit(&mut self, downloaded: u64, total: Option<u64>, phase: &'static str) {
            self.recorded().emit(downloaded, total, phase);
        }
    }

    /// One step of a canned loopback HTTP response.
    enum ServerStep {
        Write(String),
        Sleep(Duration),
    }

    /// Read timeout for one `drain_request_head` read and the overall bound on
    /// how long the harness waits for a request head to arrive.
    const REQUEST_READ_TIMEOUT: Duration = Duration::from_millis(500);
    const REQUEST_DRAIN_BUDGET: Duration = Duration::from_secs(5);
    const MAX_REQUEST_HEAD_BYTES: usize = 8 * 1024;

    /// Reads until the request head (`\r\n\r\n`) is consumed, so the request is
    /// fully drained before the harness writes its response.
    ///
    /// This is not cosmetic. On Windows a socket that is closed while inbound
    /// data is still unread sends an RST instead of a FIN, and the peer then
    /// fails the exchange with `WSAECONNRESET` (os error 10054) even though the
    /// server answered. Reading the head first makes the close graceful.
    ///
    /// Bounded: a client that connects and sends nothing is given
    /// `REQUEST_DRAIN_BUDGET` before the harness answers anyway.
    fn drain_request_head(stream: &mut std::net::TcpStream) {
        let mut buffer = [0u8; 1024];
        let mut head: Vec<u8> = Vec::new();
        let deadline = Instant::now() + REQUEST_DRAIN_BUDGET;
        while Instant::now() < deadline {
            match stream.read(&mut buffer) {
                Ok(0) => return,
                Ok(read) => {
                    head.extend_from_slice(&buffer[..read]);
                    let head_complete = head.windows(4).any(|window| window == b"\r\n\r\n");
                    if head_complete || head.len() >= MAX_REQUEST_HEAD_BYTES {
                        return;
                    }
                }
                Err(err)
                    if err.kind() == std::io::ErrorKind::WouldBlock
                        || err.kind() == std::io::ErrorKind::TimedOut => {}
                Err(_) => return,
            }
        }
    }

    /// Every test that drives the shared async runtime (`tauri::async_runtime`)
    /// holds this lock, so the transfer tests run one at a time against a single
    /// shared executor. Pure tests (path sanitizing, throttle math, registries)
    /// still run in parallel.
    fn runtime_guard() -> std::sync::MutexGuard<'static, ()> {
        static RUNTIME_LOCK: Mutex<()> = Mutex::new(());
        RUNTIME_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Minimal loopback HTTP/1.1 server: one script per accepted connection,
    /// served in order. Loopback only — no external host is ever contacted.
    struct TestServer {
        port: u16,
        served: Arc<std::sync::atomic::AtomicUsize>,
        stop: Arc<AtomicBool>,
        handle: Option<std::thread::JoinHandle<()>>,
    }

    impl TestServer {
        fn url(&self, path: &str) -> String {
            format!("http://127.0.0.1:{}{path}", self.port)
        }

        /// Connections actually accepted — 0 proves a request was never issued.
        fn served(&self) -> usize {
            self.served.load(Ordering::SeqCst)
        }

        /// Lets the accept loop exit immediately when no more requests are
        /// expected (a test that proves a request was never made).
        fn stop(&self) {
            self.stop.store(true, Ordering::SeqCst);
        }
    }

    impl Drop for TestServer {
        fn drop(&mut self) {
            // Teardown must never block on the accept loop's idle limit: once
            // the owner is gone no further connection is expected, so the loop
            // is signalled to exit and the join returns promptly even when a
            // failed assertion left part of the script unserved.
            self.stop();
            if let Some(handle) = self.handle.take() {
                let _ = handle.join();
            }
        }
    }

    fn write(text: impl Into<String>) -> Vec<ServerStep> {
        vec![ServerStep::Write(text.into())]
    }

    /// Binds a loopback listener with one script per accepted connection.
    ///
    /// Only the LISTENER is non-blocking (so the accept loop can honour the stop
    /// flag and its idle limit). Every accepted stream is switched back to
    /// blocking before it is read, because Windows `accept()` inherits the
    /// listener's non-blocking mode: a non-blocking accepted stream makes the
    /// request read return `WSAEWOULDBLOCK` (os error 10035) instead of waiting
    /// for the request, which leaves the request unconsumed and turns the
    /// subsequent close into an RST the client reports as `WSAECONNRESET` (os
    /// error 10054). That single race was what failed one transfer test per
    /// affected run before the harness drained its requests.
    fn spawn_server(connections: Vec<Vec<ServerStep>>) -> TestServer {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let port = listener.local_addr().expect("local addr").port();
        listener.set_nonblocking(true).expect("non blocking accept");
        let expected = connections.len();
        let served_count = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let served_by_thread = served_count.clone();
        let stop = Arc::new(AtomicBool::new(false));
        let stop_by_thread = stop.clone();
        let handle = std::thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(30);
            let idle_limit = Duration::from_secs(5);
            let mut served = 0usize;
            let mut last_activity = Instant::now();
            while served < expected && Instant::now() < deadline {
                if stop_by_thread.load(Ordering::SeqCst) {
                    break;
                }
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        served += 1;
                        served_by_thread.fetch_add(1, Ordering::SeqCst);
                        last_activity = Instant::now();
                        // Undo the non-blocking flag the accepted stream
                        // inherited from the listener, so the read below waits
                        // for the request instead of failing with WouldBlock.
                        let _ = stream.set_nonblocking(false);
                        let _ = stream.set_read_timeout(Some(REQUEST_READ_TIMEOUT));
                        drain_request_head(&mut stream);
                        for step in &connections[served - 1] {
                            match step {
                                ServerStep::Write(text) => {
                                    if stream.write_all(text.as_bytes()).is_err() {
                                        break;
                                    }
                                    let _ = stream.flush();
                                }
                                ServerStep::Sleep(duration) => std::thread::sleep(*duration),
                            }
                        }
                        // Explicit half-close: every canned response carries its
                        // Content-Length, so the client sees a clean FIN instead
                        // of relying on the socket drop to close the exchange.
                        let _ = stream.shutdown(std::net::Shutdown::Write);
                    }
                    Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                        if Instant::now().duration_since(last_activity) > idle_limit {
                            break;
                        }
                        std::thread::sleep(Duration::from_millis(5));
                    }
                    Err(_) => break,
                }
            }
        });
        TestServer { port, served: served_count, stop, handle: Some(handle) }
    }

    fn ok_response(body: &str) -> String {
        format!(
            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            body.len(),
            body
        )
    }

    fn status_response(status: u16, body: &str) -> String {
        format!(
            "HTTP/1.1 {status} Test\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        )
    }

    fn declared_length_response(declared: u64) -> String {
        format!("HTTP/1.1 200 OK\r\nContent-Length: {declared}\r\nConnection: close\r\n\r\n")
    }

    fn redirect_response(to: &str) -> String {
        format!(
            "HTTP/1.1 302 Found\r\nLocation: {to}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        )
    }

    /// `hops` redirects followed by a 200 — pins the redirect policy exactly.
    fn chain_server(hops: usize) -> TestServer {
        let mut connections: Vec<Vec<ServerStep>> =
            (0..hops).map(|hop| write(redirect_response(&format!("/{}", hop + 1)))).collect();
        connections.push(write(ok_response("done")));
        spawn_server(connections)
    }

    /// `hops` redirects and nothing else: a client that follows one redirect too
    /// many ends up knocking on a closed port instead of a 200.
    fn redirects_only_server(hops: usize) -> TestServer {
        spawn_server(
            (0..hops).map(|hop| write(redirect_response(&format!("/{}", hop + 1)))).collect(),
        )
    }

    fn test_client() -> reqwest::Client {
        // The production builder minus proxy resolution: these assertions speak
        // to a loopback socket and must not depend on proxy environment vars.
        build_download_client().no_proxy().build().expect("test client build")
    }

    fn input_for(transfer_id: &str, url: &str) -> DownloadRemoteBookInput {
        DownloadRemoteBookInput {
            transfer_id: transfer_id.to_string(),
            url: url.to_string(),
            format: Some("epub".to_string()),
        }
    }

    /// Drives the transfer AFTER the policy gate — the loopback harness is plain
    /// http, which `perform_download` must reject, so transport tests call the
    /// post-gate seam while the gate itself is pinned separately.
    ///
    /// Deliberately a single attempt with no transport retry. The harness drains
    /// and answers every request, so a transport failure here is a real defect
    /// and must fail the test instead of being retried away.
    fn run_download(
        client: &reqwest::Client,
        app_data_dir: &Path,
        input: &DownloadRemoteBookInput,
        sink: &mut impl ProgressSink,
    ) -> Result<DownloadRemoteBookResult, DownloadError> {
        tauri::async_runtime::block_on(perform_transfer(client, app_data_dir, input, sink))
    }

    /// Drives the full entry point, policy gate included.
    fn run_entrypoint(
        client: &reqwest::Client,
        app_data_dir: &Path,
        input: &DownloadRemoteBookInput,
        sink: &mut RecordingSink,
    ) -> Result<DownloadRemoteBookResult, DownloadError> {
        tauri::async_runtime::block_on(perform_download(client, app_data_dir, input, sink))
    }

    /// Sends one request through a fresh loopback server: the response, or the
    /// error the client raised (`is_redirect` for an over-long chain). No retry —
    /// the harness answers deterministically, so a transport error must surface.
    fn send_through_fresh_server<F>(make: F) -> Result<reqwest::Response, reqwest::Error>
    where
        F: FnOnce() -> (TestServer, String),
    {
        let (server, url) = make();
        let client = test_client();
        let result = tauri::async_runtime::block_on(async { client.get(&url).send().await });
        drop(server);
        result
    }

    fn run_stream<T, E, S>(
        body: S,
        sink: &mut impl Write,
        cap: u64,
        total: Option<u64>,
        cancel: &AtomicBool,
        recorder: &mut impl ProgressSink,
    ) -> Result<StreamOutcome, DownloadError>
    where
        S: futures_util::Stream<Item = Result<T, E>> + Unpin,
        T: AsRef<[u8]>,
        E: std::fmt::Display,
    {
        tauri::async_runtime::block_on(stream_to_file(body, sink, cap, total, cancel, recorder))
    }

    fn download_entries(app_data_dir: &Path) -> Vec<String> {
        let dir = downloads_dir(app_data_dir);
        let mut entries: Vec<String> = match std::fs::read_dir(&dir) {
            Ok(read_dir) => read_dir
                .filter_map(|entry| entry.ok())
                .map(|entry| entry.file_name().to_string_lossy().to_string())
                .collect(),
            Err(_) => Vec::new(),
        };
        entries.sort();
        entries
    }

    #[test]
    fn mandated_constants_are_pinned() {
        assert_eq!(DOWNLOAD_USER_AGENT, "Nexo/Desktop (contact: TBD)");
        assert_eq!(MAX_DOWNLOAD_BYTES, 67_108_864);
        assert_eq!(MAX_REDIRECTS, 5);
        assert_eq!(CONNECT_TIMEOUT_S, 15);
        assert_eq!(READ_TIMEOUT_S, 30);
        assert_eq!(PROGRESS_THROTTLE_MS, 250);
        assert_eq!(PROGRESS_MIN_BYTES, 262_144);
        assert_eq!(DOWNLOAD_EVENT, "discover-download-progress");
        assert_eq!(ERR_HTTPS_REQUIRED, "BOOK_DOWNLOAD_HTTPS_REQUIRED");
        assert_eq!(ERR_TOO_LARGE, "BOOK_DOWNLOAD_TOO_LARGE");
        assert_eq!(ERR_HTTP_PREFIX, "BOOK_DOWNLOAD_HTTP_");
        assert_eq!(ERR_NETWORK, "BOOK_DOWNLOAD_NETWORK");
        assert_eq!(ERR_CANCELLED, "BOOK_DOWNLOAD_CANCELLED");
        assert_eq!(ERR_BAD_PATH, "BOOK_DOWNLOAD_BAD_PATH");
    }

    #[test]
    fn check_https_accepts_https_and_rejects_everything_else() {
        check_https("https://example.com/book.epub").expect("https is accepted");
        for bad in [
            "http://example.com/book.epub",
            "ftp://example.com/book.epub",
            "file:///etc/book.epub",
            "not a url",
            "",
        ] {
            assert_eq!(check_https(bad).unwrap_err().code, ERR_HTTPS_REQUIRED, "{bad}");
        }
    }

    /// Threat matrix: `http://`, `file://`, `ftp://` and malformed URLs are
    /// rejected BEFORE any I/O. The `http://` case points at a live loopback
    /// server, so "no I/O" is proved by the server never being contacted.
    #[test]
    fn rejects_non_https_scheme_before_any_io() {
        let _serial = runtime_guard();
        let dir = tempfile::tempdir().expect("tempdir");
        let client = test_client();
        let mut sink = RecordingSink::default();
        let server = spawn_server(vec![write(ok_response("epub-body"))]);

        let mut urls = vec![server.url("/book.epub")];
        urls.extend([
            "ftp://example.com/book.epub".to_string(),
            "file:///etc/book.epub".to_string(),
            "not a url".to_string(),
        ]);

        for url in &urls {
            let input = input_for("never-io", url);
            let err = run_entrypoint(&client, dir.path(), &input, &mut sink).unwrap_err();
            assert_eq!(err.code, ERR_HTTPS_REQUIRED, "{url}");
            assert_eq!(err.downloaded, 0, "{url}");
        }

        // Nothing was requested, registered, emitted, or written.
        assert_eq!(server.served(), 0, "the policy gate must not issue a request");
        server.stop();
        assert!(sink.events.is_empty(), "{:?}", sink.events);
        assert!(download_entries(dir.path()).is_empty());
        assert!(!downloads_dir(dir.path()).exists());
    }

    /// Threat matrix: the redirect chain stops at the configured limit.
    #[test]
    fn redirect_policy_is_limited_to_five() {
        let _serial = runtime_guard();

        let response = send_through_fresh_server(|| {
            let server = chain_server(MAX_REDIRECTS);
            let url = server.url("/0");
            (server, url)
        })
        .expect("a chain of five redirects is followed");
        assert_eq!(response.status().as_u16(), 200);

        let error = send_through_fresh_server(|| {
            let server = redirects_only_server(MAX_REDIRECTS + 1);
            let url = server.url("/0");
            (server, url)
        })
        .expect_err("a chain of six redirects is rejected");
        assert!(error.is_redirect(), "{error}");
    }

    /// Threat matrix: a declared `content-length` above the cap is rejected
    /// pre-stream, with no file and one failed terminal event.
    #[test]
    fn rejects_oversize_content_length_before_writing() {
        let _serial = runtime_guard();
        let dir = tempfile::tempdir().expect("tempdir");
        let client = test_client();
        let server = spawn_server(vec![write(declared_length_response(MAX_DOWNLOAD_BYTES + 1))]);
        let mut sink = RecordingSink::default();
        let input = input_for("oversize-transfer", &server.url("/big.epub"));

        let err = run_download(&client, dir.path(), &input, &mut sink).unwrap_err();

        assert_eq!(err.code, ERR_TOO_LARGE);
        assert_eq!(err.downloaded, 0);
        assert_eq!(sink.terminal_count(), 1);
        assert_eq!(sink.last().phase, PHASE_FAILED);
        assert!(download_entries(dir.path()).is_empty());
    }

    /// Threat matrix: a body that grows past the cap mid-stream aborts at the
    /// boundary; the `.part` is removable and no final file exists.
    #[test]
    fn aborts_stream_at_the_cap_and_removes_part() {
        let _serial = runtime_guard();
        let dir = tempfile::tempdir().expect("tempdir");
        let (part_path, final_path) = download_paths(dir.path(), "cap-transfer", Some("epub"));
        std::fs::create_dir_all(part_path.parent().expect("part parent")).expect("downloads dir");
        let mut file = std::fs::File::create(&part_path).expect("part file");
        let mut sink = RecordingSink::default();
        let cancel = AtomicBool::new(false);
        let chunks: Vec<Result<Vec<u8>, std::io::Error>> =
            vec![Ok(vec![7u8; 1024]), Ok(vec![7u8; 1024]), Ok(vec![7u8; 16])];

        let err = run_stream(stream::iter(chunks), &mut file, 2048, Some(4112), &cancel, &mut sink)
            .unwrap_err();

        assert_eq!(err.code, ERR_TOO_LARGE);
        assert_eq!(err.downloaded, 2048);
        drop(file);
        assert_eq!(std::fs::metadata(&part_path).expect("part metadata").len(), 2048);
        discard_part(&part_path);
        assert!(!part_path.exists());
        assert!(!final_path.exists());
    }

    /// Threat matrix: cookies are never stored or replayed — structurally, the
    /// reqwest cookie feature is absent and the builder never enables a store.
    #[test]
    fn client_has_no_cookie_store() {
        let manifest = include_str!("../../Cargo.toml");
        let reqwest_line = manifest
            .lines()
            .find(|line| line.trim_start().starts_with("reqwest"))
            .expect("reqwest dependency line");
        assert!(reqwest_line.contains("default-features = false"), "{reqwest_line}");
        assert!(reqwest_line.contains("rustls-tls"), "{reqwest_line}");
        assert!(reqwest_line.contains("stream"), "{reqwest_line}");
        assert!(!reqwest_line.contains("cookies"), "{reqwest_line}");

        let source = include_str!("download.rs");
        let builder = source
            .split("fn build_download_client")
            .nth(1)
            .expect("build_download_client body")
            .split("\n}")
            .next()
            .expect("builder body");
        assert!(!builder.contains("cookie_store"), "no cookie store may be configured");
    }

    /// Threat matrix: path traversal through the transfer id or format cannot
    /// escape `{app_data}/tmp/downloads`.
    #[test]
    fn download_paths_sanitize_and_stay_inside_downloads() {
        let dir = tempfile::tempdir().expect("tempdir");
        let downloads = downloads_dir(dir.path());

        for host in ["../../evil", "..\\..\\evil", "a/b/c", "", "..", "with space/id"] {
            let (part, final_path) = download_paths(dir.path(), host, Some("../../sh"));
            assert_eq!(part.parent(), Some(downloads.as_path()), "{host}");
            assert_eq!(final_path.parent(), Some(downloads.as_path()), "{host}");
            assert!(part.starts_with(&downloads), "{host}");
            assert!(final_path.starts_with(&downloads), "{host}");
            assert!(part.to_string_lossy().ends_with(".part"), "{host}");
            assert!(is_inside_downloads_dir(dir.path(), &part), "{host}");
        }

        let (part, _) = download_paths(dir.path(), "abc/../def", None);
        assert_eq!(part.file_name().expect("file name").to_string_lossy(), "abcdef.epub.part");

        assert!(download_paths(dir.path(), "id", Some("EPUB")).1.ends_with("id.epub"));
        assert!(download_paths(dir.path(), "id", Some("pdf")).1.ends_with("id.pdf"));
        assert!(download_paths(dir.path(), "id", Some("")).1.ends_with("id.epub"));
        assert!(download_paths(dir.path(), "id", None).1.ends_with("id.epub"));
        assert!(download_paths(dir.path(), "id", Some("waytoolongext")).1.ends_with("id.epub"));
        assert!(download_paths(dir.path(), "id", Some("../../book.EPUB")).1.ends_with("id.epub"));
    }

    /// Threat matrix: discarding an arbitrary path is refused and deletes
    /// nothing; only a path inside `{app_data}/tmp/downloads` is removed.
    #[test]
    fn discard_refuses_path_outside_downloads_dir() {
        let dir = tempfile::tempdir().expect("tempdir");
        let outside = dir.path().join("outside.epub");
        std::fs::write(&outside, b"precious bytes").expect("seed outside file");

        let err = discard_remote_download(dir.path(), &outside.to_string_lossy()).unwrap_err();
        assert_eq!(err.code, ERR_BAD_PATH);
        assert!(outside.exists(), "a path outside the downloads dir must never be deleted");

        let traversal = downloads_dir(dir.path()).join("..").join("..").join("outside.epub");
        let err = discard_remote_download(dir.path(), &traversal.to_string_lossy()).unwrap_err();
        assert_eq!(err.code, ERR_BAD_PATH);
        assert!(outside.exists());

        let inside = downloads_dir(dir.path()).join("book.epub");
        std::fs::create_dir_all(inside.parent().expect("downloads dir")).expect("downloads dir");
        std::fs::write(&inside, b"bytes").expect("seed inside file");
        assert!(discard_remote_download(dir.path(), &inside.to_string_lossy()).expect("discard"));
        assert!(!inside.exists());
        assert!(!discard_remote_download(dir.path(), &inside.to_string_lossy()).expect("no-op"));
    }

    /// Threat matrix: cancelling one transfer leaves the other running.
    #[test]
    fn cancel_is_isolated_per_transfer_id() {
        let first = register_transfer("cancel-isolation-first");
        let second = register_transfer("cancel-isolation-second");

        assert!(cancel_transfer("cancel-isolation-first"));
        assert!(first.load(Ordering::SeqCst));
        assert!(!second.load(Ordering::SeqCst), "the other transfer keeps running");

        assert!(cancel_transfer("cancel-isolation-second"));
        assert!(second.load(Ordering::SeqCst));
        finish_transfer("cancel-isolation-first");
        finish_transfer("cancel-isolation-second");
    }

    /// Threat matrix: cancelling a finished transfer is a harmless no-op.
    #[test]
    fn cancel_after_completion_is_noop() {
        let flag = register_transfer("cancel-after-completion");
        finish_transfer("cancel-after-completion");

        assert!(!flag.load(Ordering::SeqCst));
        assert!(!cancel_transfer("cancel-after-completion"));
        assert!(!cancel_transfer("cancel-never-registered"));
    }

    #[test]
    fn should_emit_throttles_by_time_or_bytes() {
        assert!(!should_emit(0, 0));
        assert!(!should_emit(PROGRESS_THROTTLE_MS - 1, PROGRESS_MIN_BYTES - 1));
        assert!(should_emit(PROGRESS_THROTTLE_MS, 0));
        assert!(should_emit(0, PROGRESS_MIN_BYTES));
    }

    /// Threat matrix: progress is throttled instead of one event per chunk and
    /// exactly one terminal event is emitted per transfer. The stream layer only
    /// reports progress; the terminal event is owned by `perform_download`.
    #[test]
    fn throttles_progress_and_emits_single_terminal_event() {
        let _serial = runtime_guard();
        let chunks: Vec<Result<Vec<u8>, std::io::Error>> =
            (0..64).map(|_| Ok(vec![9u8; 4096])).collect();
        let mut body: Vec<u8> = Vec::new();
        let mut sink = RecordingSink::default();
        let cancel = AtomicBool::new(false);

        let outcome = run_stream(
            stream::iter(chunks),
            &mut body,
            4 * MAX_DOWNLOAD_BYTES,
            Some(PROGRESS_MIN_BYTES),
            &cancel,
            &mut sink,
        )
        .expect("stream completes");

        assert_eq!(outcome, StreamOutcome::Completed { downloaded: PROGRESS_MIN_BYTES });
        assert!(sink.progress_count() < 64, "64 chunks must not produce 64 events");
        assert!(sink.progress_count() >= 1);
        assert_eq!(sink.last().downloaded, PROGRESS_MIN_BYTES);
        assert_eq!(sink.last().total, Some(PROGRESS_MIN_BYTES));
        assert_eq!(sink.terminal_count(), 0, "the stream layer reports progress only");

        // One transfer through the real command path: exactly one terminal event.
        let dir = tempfile::tempdir().expect("tempdir");
        let client = test_client();
        let server = spawn_server(vec![write(ok_response("epub-body"))]);
        let mut sink = RecordingSink::default();
        let input = input_for("terminal-transfer", &server.url("/book.epub"));
        let result = run_download(&client, dir.path(), &input, &mut sink).expect("transfer");

        assert_eq!(result.bytes, 9);
        assert_eq!(sink.terminal_count(), 1);
        assert_eq!(sink.last().phase, PHASE_COMPLETED);
    }

    /// Threat matrix: errors carry stable codes and never an upstream body.
    #[test]
    fn errors_carry_codes_not_upstream_bodies() {
        let _serial = runtime_guard();
        const SECRET: &str = "SECRET-UPSTREAM-BODY-MUST-NOT-SURFACE";

        let response = send_through_fresh_server(|| {
            let server = spawn_server(vec![write(status_response(500, SECRET))]);
            let url = server.url("/boom.epub");
            (server, url)
        })
        .expect("the server answers");
        let err = guard_response(&response).unwrap_err();
        assert_eq!(err.code, format!("{ERR_HTTP_PREFIX}500"));
        assert!(!err.detail.contains(SECRET));
        assert!(!err.to_string().contains(SECRET));
        drop(response);

        let dir = tempfile::tempdir().expect("tempdir");
        let client = test_client();
        let server = spawn_server(vec![write(status_response(503, SECRET))]);
        let mut sink = RecordingSink::default();
        let input = input_for("http-error-transfer", &server.url("/boom.epub"));
        let err = run_download(&client, dir.path(), &input, &mut sink).unwrap_err();

        assert_eq!(err.code, "BOOK_DOWNLOAD_HTTP_503");
        assert!(!err.to_string().contains(SECRET));
        assert_eq!(sink.terminal_count(), 1);
        assert_eq!(sink.last().phase, PHASE_FAILED);
        assert!(download_entries(dir.path()).is_empty());
    }

    #[test]
    fn progress_payload_serializes_the_camel_case_ipc_shape() {
        let progress = DownloadProgressPayload {
            transfer_id: "t-1".to_string(),
            downloaded: 512,
            total: Some(1024),
            phase: PHASE_PROGRESS,
        };
        assert_eq!(
            serde_json::to_string(&progress).expect("serialize"),
            r#"{"transferId":"t-1","downloaded":512,"total":1024,"phase":"progress"}"#
        );

        let cancelled = DownloadProgressPayload {
            transfer_id: "t-2".to_string(),
            downloaded: 0,
            total: None,
            phase: PHASE_CANCELLED,
        };
        assert_eq!(
            serde_json::to_string(&cancelled).expect("serialize"),
            r#"{"transferId":"t-2","downloaded":0,"total":null,"phase":"cancelled"}"#
        );
    }

    /// A completed transfer lands a final file with its sha256 and no `.part`.
    #[test]
    fn successful_transfer_lands_a_final_file_with_sha256() {
        let _serial = runtime_guard();
        let dir = tempfile::tempdir().expect("tempdir");
        let client = test_client();
        let server = spawn_server(vec![write(ok_response("epub-body"))]);
        let mut sink = RecordingSink::default();
        let input = input_for("success-transfer", &server.url("/book.epub"));

        let result = run_download(&client, dir.path(), &input, &mut sink).expect("transfer");

        let expected = download_paths(dir.path(), "success-transfer", Some("epub")).1;
        assert_eq!(result.file_path, expected.to_string_lossy().to_string());
        assert_eq!(result.bytes, 9);
        assert_eq!(std::fs::read(&expected).expect("final file"), b"epub-body");
        assert_eq!(result.sha256, format!("{:x}", Sha256::digest(b"epub-body")));
        assert_eq!(download_entries(dir.path()), vec!["success-transfer.epub".to_string()]);
        assert_eq!(sink.terminal_count(), 1);
        assert_eq!(sink.last().phase, PHASE_COMPLETED);
        assert_eq!(sink.last().downloaded, 9);
        assert_eq!(sink.last().total, Some(9));
    }

    /// Threat matrix: a mid-stream cancel aborts, removes the `.part`, creates
    /// no final file and reports the partial byte count.
    #[test]
    fn cancel_mid_stream_removes_the_part_and_reports_cancelled() {
        let _serial = runtime_guard();
        let dir = tempfile::tempdir().expect("tempdir");
        let dir_path = dir.path().to_path_buf();
        // 256 KiB per half, so the first half crosses the progress byte
        // threshold and the test can observe that it was durably written.
        let half = PROGRESS_MIN_BYTES as usize;
        let total = half * 2;

        let head =
            format!("HTTP/1.1 200 OK\r\nContent-Length: {total}\r\nConnection: close\r\n\r\n");
        let server = spawn_server(vec![vec![
            ServerStep::Write(format!("{head}{}", "x".repeat(half))),
            ServerStep::Sleep(Duration::from_millis(800)),
            ServerStep::Write("x".repeat(half)),
        ]]);
        let url = server.url("/slow.epub");

        let sink = SharedSink::default();
        let recorder = sink.clone();
        let reported_dir = dir_path.clone();
        let thread_dir = dir_path.clone();
        let handle = std::thread::spawn(move || {
            let client = test_client();
            let mut recorder = recorder;
            let input = input_for("cancel-mid-stream", &url);
            run_download(&client, &thread_dir, &input, &mut recorder)
        });

        // The first half must land and be reported before the cancel: the
        // transfer cannot end (terminal event) while the second half is still
        // held back by `ServerStep::Sleep`, so this waits for a real progress
        // event rather than for an outcome. A transport failure would surface as
        // a terminal event instead, and the assertions below then fail.
        let mut waited = 0;
        while sink.progress_count() == 0 && sink.recorded().terminal_count() == 0 {
            std::thread::sleep(Duration::from_millis(5));
            waited += 1;
            assert!(
                waited < 600,
                "the transfer neither wrote a chunk nor ended: {:?}",
                sink.recorded().events
            );
        }

        assert!(cancel_transfer("cancel-mid-stream"), "an in-flight transfer is cancellable");

        let result = handle.join().expect("download thread");
        let sink = sink.recorded();
        assert_eq!(result.unwrap_err().code, ERR_CANCELLED);
        assert_eq!(sink.terminal_count(), 1);
        assert_eq!(sink.last().phase, PHASE_CANCELLED);
        assert_eq!(sink.last().downloaded, half as u64);
        assert_eq!(sink.last().total, Some(total as u64));
        assert!(download_entries(reported_dir.as_path()).is_empty(), "the .part file is removed");
    }

    /// Writes land per chunk (never one buffered blob) and an absent
    /// `content-length` is reported as an unknown total, not as an error.
    #[test]
    fn stream_to_file_writes_incrementally_and_reports_unknown_total() {
        let _serial = runtime_guard();
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("out.bin");
        let mut file = std::fs::File::create(&path).expect("sink");
        let mut sink = RecordingSink::default();
        let cancel = AtomicBool::new(false);
        let chunks: Vec<Result<Vec<u8>, std::io::Error>> =
            vec![Ok(vec![1u8; 10]), Ok(vec![2u8; 5])];

        let outcome = run_stream(
            stream::iter(chunks),
            &mut file,
            MAX_DOWNLOAD_BYTES,
            None,
            &cancel,
            &mut sink,
        )
        .expect("stream completes");

        assert_eq!(outcome, StreamOutcome::Completed { downloaded: 15 });
        assert_eq!(outcome.downloaded(), 15);
        drop(file);
        assert_eq!(std::fs::read(&path).expect("written file").len(), 15);
        // 15 fast bytes are below both throttle thresholds: progress only.
        assert_eq!(sink.progress_count(), 0);
        assert_eq!(sink.terminal_count(), 0);

        // Incrementality is proved at the sink: one write per chunk.
        let mut counting = CountingSink::default();
        let mut sink = RecordingSink::default();
        let chunks: Vec<Result<Vec<u8>, std::io::Error>> =
            vec![Ok(vec![3u8; 10]), Ok(vec![4u8; 5]), Ok(vec![5u8; PROGRESS_MIN_BYTES as usize])];
        let outcome = run_stream(
            stream::iter(chunks),
            &mut counting,
            MAX_DOWNLOAD_BYTES,
            None,
            &cancel,
            &mut sink,
        )
        .expect("stream completes");

        assert_eq!(outcome.downloaded(), PROGRESS_MIN_BYTES + 15);
        assert_eq!(counting.writes, vec![10, 5, PROGRESS_MIN_BYTES as usize]);
        assert_eq!(sink.progress_count(), 1, "one byte-threshold emit for ~256 KiB");
        assert_eq!(sink.last().total, None, "an unknown total is reported as null");
    }

    #[test]
    fn stream_to_file_cancels_and_reports_the_partial_count() {
        let _serial = runtime_guard();
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("out.bin");
        let mut file = std::fs::File::create(&path).expect("sink");
        let mut sink = RecordingSink::default();
        let cancel = AtomicBool::new(true);
        let chunks: Vec<Result<Vec<u8>, std::io::Error>> = vec![Ok(vec![1u8; 10])];

        let outcome = run_stream(
            stream::iter(chunks),
            &mut file,
            MAX_DOWNLOAD_BYTES,
            Some(10),
            &cancel,
            &mut sink,
        )
        .expect("cancel is an outcome, not an error");

        assert_eq!(outcome, StreamOutcome::Cancelled { downloaded: 0 });
        assert_eq!(sink.events.len(), 0, "nothing was written, so nothing is reported");
    }

    #[test]
    fn stream_to_file_reports_stream_errors_with_the_downloaded_count() {
        let _serial = runtime_guard();
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("out.bin");
        let mut file = std::fs::File::create(&path).expect("sink");
        let mut sink = RecordingSink::default();
        let cancel = AtomicBool::new(false);
        let chunks: Vec<Result<Vec<u8>, std::io::Error>> =
            vec![Ok(vec![1u8; 4]), Err(std::io::Error::other("stream exploded"))];

        let err = run_stream(
            stream::iter(chunks),
            &mut file,
            MAX_DOWNLOAD_BYTES,
            None,
            &cancel,
            &mut sink,
        )
        .unwrap_err();

        assert_eq!(err.code, ERR_NETWORK);
        assert_eq!(err.downloaded, 4);
    }

    /// Regression guard for the loopback harness itself (the defect that made
    /// `cargo test download` intermittently fail one transport test per run).
    ///
    /// The accept loop needs a non-blocking LISTENER to honour its stop flag and
    /// idle limit, but on Windows the socket returned by `accept()` INHERITS the
    /// listener's non-blocking mode. A non-blocking accepted stream makes the
    /// request read return `WSAEWOULDBLOCK` (os error 10035) immediately instead
    /// of waiting; the request then stays unconsumed, and closing a socket that
    /// still has unread inbound data makes Windows send an RST instead of a FIN.
    /// The client sees `WSAECONNRESET` (os error 10054) in the connect/send
    /// phase, which is why the failure landed in a different test each run and
    /// never in the module's logic assertions.
    ///
    /// `spawn_server` therefore switches every accepted stream back to blocking
    /// and drains the request head before answering. This pins the property that
    /// makes that work: an accepted stream must WAIT for a request that arrives
    /// after the connection was accepted. A stream left non-blocking returns at
    /// once; the blocking stream stays parked until its read timeout.
    #[test]
    fn accepted_loopback_streams_wait_for_the_request() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let port = listener.local_addr().expect("local addr").port();
        listener.set_nonblocking(true).expect("non blocking accept");

        let server = std::thread::spawn(move || loop {
            match listener.accept() {
                Ok((mut stream, _)) => {
                    // Exactly what `spawn_server` does per accepted stream.
                    let _ = stream.set_nonblocking(false);
                    let _ = stream.set_read_timeout(Some(REQUEST_READ_TIMEOUT));
                    let mut buffer = [0u8; 128];
                    let started = Instant::now();
                    let result = stream.read(&mut buffer);
                    return (result, started.elapsed());
                }
                Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(2));
                }
                Err(err) => panic!("accept failed: {err}"),
            }
        });

        // Connect and send NOTHING: the exact window in which the inherited
        // non-blocking read used to return WouldBlock and abandon the request.
        let _client = std::net::TcpStream::connect(("127.0.0.1", port)).expect("connect");
        let (read_result, waited) = server.join().expect("server thread");

        let err = read_result.expect_err("no bytes were sent, so the read cannot succeed");
        assert!(
            matches!(err.kind(), std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock),
            "expected the read to end on its timeout, got {err:?}"
        );
        assert!(
            waited >= Duration::from_millis(400),
            "the accepted stream must wait for the request instead of returning immediately \
             (which is what an inherited non-blocking socket does): {waited:?}"
        );
    }
}
