use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::fmt;
use std::fs::{self, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// General ring capacity. Mirrors the Android `DebugLog.MAX_EVENTS` reference
/// implementation (dual ring: 500 general + 200 error-only).
///
/// Sizing decision (task 2.4): the design left ring/file sizing open; the
/// Android reference values are adopted as defaults here. Team confirmation of
/// these exact values is pending and tracked as a PR-description item.
pub const GENERAL_RING_CAP: usize = 500;

/// Error-only ring capacity. Error events land here AND in the general ring so
/// high-frequency generic events cannot evict them. Mirrors the Android
/// `DebugLog.MAX_ERROR_EVENTS` reference value.
pub const ERROR_RING_CAP: usize = 200;

/// Number of rotated JSONL files kept on disk. Two files (`app-log.0.jsonl`
/// active + `app-log.1.jsonl` previous) bound the sink at
/// `LOG_FILE_COUNT * LOG_FILE_SIZE_CAP_BYTES`. Mirrors the Android
/// `CrashLogStore` 2-file rotation semantics (mirrors semantics, not code).
pub const LOG_FILE_COUNT: usize = 2;

/// Per-file size cap for the rotated JSONL sink. 200 KB matches the Android
/// `CrashLogStore.DEFAULT_MAX_FILE_SIZE` precedent; the sink never exceeds
/// `LOG_FILE_COUNT * LOG_FILE_SIZE_CAP_BYTES` (400 KB) under sustained volume.
pub const LOG_FILE_SIZE_CAP_BYTES: u64 = 200 * 1024;

/// Cap for the diagnostics-bundle log tail (consumed in Phase 4). 200 lines
/// matches the Android `CrashLogStore.SNAPSHOT_LINE_LIMIT` precedent.
pub const BUNDLE_LOG_TAIL_CAP: usize = 200;

/// Legacy `recent-errors.jsonl` read-fallback window, in releases.
///
/// Decision (task 2.5, recorded 2026-10-07): the write path stopped using
/// `recent-errors.jsonl` when the 2-file rotation shipped. The read fallback
/// keeps pre-upgrade logs visible for two releases after that ship date, then
/// is retired. This is a design default kept because the team was unavailable;
/// an explicit team override would replace it. Tracked as a PR-description item.
pub const LEGACY_LOG_READ_FALLBACK_WINDOW_RELEASES: u8 = 2;

const ACTIVE_LOG_FILE_NAME: &str = "app-log.0.jsonl";
const PREVIOUS_LOG_FILE_NAME: &str = "app-log.1.jsonl";
const LEGACY_LOG_FILE_NAME: &str = "recent-errors.jsonl";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Debug,
    Info,
    Warn,
    Error,
}

impl fmt::Display for LogLevel {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            LogLevel::Debug => write!(f, "debug"),
            LogLevel::Info => write!(f, "info"),
            LogLevel::Warn => write!(f, "warn"),
            LogLevel::Error => write!(f, "error"),
        }
    }
}

impl LogLevel {
    fn rank(&self) -> u8 {
        match self {
            LogLevel::Debug => 0,
            LogLevel::Info => 1,
            LogLevel::Warn => 2,
            LogLevel::Error => 3,
        }
    }

    /// Level gate: true when this event level meets the threshold.
    /// Threshold changes apply to new events only — retention is never
    /// retroactively re-evaluated.
    pub fn passes(&self, threshold: &LogLevel) -> bool {
        self.rank() >= threshold.rank()
    }

    /// Release builds MUST NOT emit DEBUG/verbose logs.
    pub fn release_threshold() -> Self {
        LogLevel::Info
    }

    /// Effective threshold for this build: everything in dev, release
    /// threshold otherwise.
    pub fn current_threshold() -> Self {
        if cfg!(debug_assertions) {
            LogLevel::Debug
        } else {
            Self::release_threshold()
        }
    }
}

/// Map a free-form severity string onto the level gate. Unknown values
/// retain (fail-open toward Error) so unclassified errors are never lost.
fn level_for_severity(severity: &str) -> LogLevel {
    match severity.to_lowercase().as_str() {
        "debug" => LogLevel::Debug,
        "info" => LogLevel::Info,
        "warn" | "warning" => LogLevel::Warn,
        _ => LogLevel::Error,
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorEventDto {
    pub timestamp: String,
    pub severity: String,
    pub category: String,
    pub code: String,
    pub message: String,
    pub context: serde_json::Value,
    pub correlation_id: String,
    pub source: String,
    pub recoverable: bool,
}

/// Generic log event with level — for info/warn/debug non-error events
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LogEventDto {
    pub timestamp: String,
    pub level: LogLevel,
    pub message: String,
    pub context: serde_json::Value,
    pub source: String,
}

/// A retained event in the general ring.
///
/// Error events are also retained in the dedicated [`Logger::error_ring`]
/// partition, so general-ring churn cannot evict them.
#[derive(Debug, Clone)]
pub enum StoredEvent {
    Error(ErrorEventDto),
    Generic(LogEventDto),
}

pub struct Logger {
    /// Active rotated file (`app-log.0.jsonl`).
    log_path: PathBuf,
    /// Legacy `recent-errors.jsonl` path, read-only fallback during rollout.
    legacy_log_path: PathBuf,
    redaction_patterns: Vec<String>,
    general: VecDeque<StoredEvent>,
    errors: VecDeque<ErrorEventDto>,
    general_cap: usize,
    error_cap: usize,
    file_size_cap_bytes: u64,
}

impl Logger {
    fn default_redaction_patterns() -> Vec<String> {
        vec![
            "password".to_string(),
            "token".to_string(),
            "secret".to_string(),
            "api_key".to_string(),
            "api-key".to_string(),
            "apikey".to_string(),
            "accesstoken".to_string(),
            "refreshtoken".to_string(),
            "idtoken".to_string(),
            "authorization".to_string(),
            "supabase".to_string(),
            "dsn".to_string(),
            "highlight".to_string(),
            "book_content".to_string(),
            "bookcontent".to_string(),
        ]
    }

    pub fn new(app_data_dir: PathBuf) -> Self {
        let log_path = app_data_dir.join(ACTIVE_LOG_FILE_NAME);
        let legacy_log_path = app_data_dir.join(LEGACY_LOG_FILE_NAME);
        Self {
            log_path,
            legacy_log_path,
            redaction_patterns: Self::default_redaction_patterns(),
            general: VecDeque::new(),
            errors: VecDeque::new(),
            general_cap: GENERAL_RING_CAP,
            error_cap: ERROR_RING_CAP,
            file_size_cap_bytes: LOG_FILE_SIZE_CAP_BYTES,
        }
    }

    /// Test-only constructor with tuned caps so ring/rotation assertions stay
    /// fast and deterministic without weakening the production constants.
    #[cfg(test)]
    pub(crate) fn with_test_limits(
        app_data_dir: PathBuf,
        general_cap: usize,
        error_cap: usize,
        file_size_cap_bytes: u64,
    ) -> Self {
        let mut logger = Self::new(app_data_dir);
        logger.general_cap = general_cap;
        logger.error_cap = error_cap;
        logger.file_size_cap_bytes = file_size_cap_bytes;
        logger
    }

    /// Error-path write: level-gate, redact, persist to the rotated sink, and
    /// retain in BOTH rings (general + error-only partition).
    pub fn log_to_file(&mut self, event: &ErrorEventDto) -> Result<(), String> {
        if !level_for_severity(&event.severity).passes(&LogLevel::current_threshold()) {
            return Ok(());
        }

        let redacted_event = self.redact_event(event);
        let json_line = serde_json::to_string(&redacted_event)
            .map_err(|e| format!("Failed to serialize event: {}", e))?;
        self.append_json_line(&json_line)?;

        self.push_error(redacted_event.clone());
        self.push_general(StoredEvent::Error(redacted_event));
        Ok(())
    }

    /// Build a stateless Logger bound to an empty path, for callers that only need
    /// the redaction helpers (e.g. Sentry `before_send`). Does NOT touch the filesystem.
    pub fn for_redaction_only() -> Self {
        Self {
            log_path: PathBuf::new(),
            legacy_log_path: PathBuf::new(),
            redaction_patterns: Self::default_redaction_patterns(),
            general: VecDeque::new(),
            errors: VecDeque::new(),
            general_cap: GENERAL_RING_CAP,
            error_cap: ERROR_RING_CAP,
            file_size_cap_bytes: LOG_FILE_SIZE_CAP_BYTES,
        }
    }

    /// Redact PII from an [`ErrorEventDto`]. Single source of truth for the
    /// secret-key / DSN / highlight / book-content patterns plus absolute-path
    /// and home-dir scrubbing. Made `pub` so the Sentry `before_send` hook
    /// (and any other egress sink) can reuse it.
    pub fn redact_event(&self, event: &ErrorEventDto) -> ErrorEventDto {
        let redacted_message = self.redact_string(&event.message);
        let redacted_context = self.redact_value(&event.context);

        ErrorEventDto {
            timestamp: event.timestamp.clone(),
            severity: event.severity.clone(),
            category: event.category.clone(),
            code: event.code.clone(),
            message: redacted_message,
            context: redacted_context,
            correlation_id: event.correlation_id.clone(),
            source: event.source.clone(),
            recoverable: event.recoverable,
        }
    }

    /// Redact PII from a generic [`LogEventDto`]. P0 universal boundary:
    /// the generic path gets the exact same scrubbing as [`Self::redact_event`]
    /// — secrets, DSN values, absolute paths, home dirs, book content, and
    /// highlight text. Callers MUST pass through here before persistence,
    /// export, or transmission; see [`Self::log_generic`].
    pub fn redact_log_event(&self, event: &LogEventDto) -> LogEventDto {
        LogEventDto {
            timestamp: event.timestamp.clone(),
            level: event.level.clone(),
            message: self.redact_string(&event.message),
            context: self.redact_value(&event.context),
            source: event.source.clone(),
        }
    }

    /// Redact PII from an arbitrary `serde_json::Value` in place.
    ///
    /// Contract: the input `value` is walked recursively. Object keys whose
    /// lowercased name contains any redaction pattern (`password` / `token` /
    /// `secret` / `api_key` / `dsn` / `highlight` / `book_content` / …) are
    /// replaced with the literal string `"[REDACTED]"`. String scalars are
    /// scanned for colon-suffixed secrets (`password:xyz`, `dsn:abc`, …),
    /// absolute paths (Windows drive-letter and rooted multi-segment Unix
    /// paths), and home-dir references (`~`, `/home/<user>`) — all replaced
    /// with `"[REDACTED]"` / `"[REDACTED_PATH]"`. Other shapes pass through.
    /// URLs without a filesystem path (e.g. `https://host/cb?code=x`) are
    /// preserved verbatim.
    ///
    /// This is the same contract as [`Self::redact_event`] minus the
    /// `ErrorEventDto` field copying — designed for sinks that carry raw
    /// `serde_json::Value` payloads (Sentry `event.extra`, breadcrumbs, etc.).
    pub fn redact_json_value(value: &mut serde_json::Value) {
        let logger = Self::for_redaction_only();
        *value = logger.redact_value_inner(value);
    }

    fn redact_string(&self, input: &str) -> String {
        let mut result = input.to_string();
        for pattern in &self.redaction_patterns {
            let pattern_escaped = regex::escape(pattern);
            let regex_pattern = format!(r"(?i){}:[^\s,}}]+", pattern_escaped);
            if let Ok(re) = regex::Regex::new(&regex_pattern) {
                result = re.replace_all(&result, format!("{}:[REDACTED]", pattern)).to_string();
            }
        }
        // Absolute filesystem paths and home-dir references. Each pattern
        // captures a boundary char (or start-of-string) in group 1 so URLs
        // stay intact: in `https://host/cb?code=x` every `/` is preceded by
        // `:`, `/`, or a word char, none of which the boundary allows.
        const PATH_PATTERNS: &[(&str, &str)] = &[
            (r#"((?:^|[^\w:]))([a-zA-Z]:[\\/][^\s,"'\]}]*)"#, "${1}[REDACTED_PATH]"),
            (
                r#"((?i)(?:^|[^:\w/]))(/(?:home|users|tmp|var|etc|opt|data|storage|mnt|root|private)[^\s,"'\]}]*)"#,
                "${1}[REDACTED_PATH]",
            ),
            (r#"((?:^|[^:\w/]))(/(?:[\w.\-]+/)+[\w.\-~]*)"#, "${1}[REDACTED_PATH]"),
            (r#"((?:^|[^\w]))~(/[^\s,"'\]}]*)?"#, "${1}[REDACTED_PATH]"),
        ];
        for (path_pattern, replacement) in PATH_PATTERNS {
            if let Ok(re) = regex::Regex::new(path_pattern) {
                result = re.replace_all(&result, *replacement).to_string();
            }
        }
        result
    }

    fn redact_value(&self, value: &serde_json::Value) -> serde_json::Value {
        self.redact_value_inner(value)
    }

    fn redact_value_inner(&self, value: &serde_json::Value) -> serde_json::Value {
        match value {
            serde_json::Value::Object(map) => {
                let mut new_map = serde_json::Map::new();
                for (k, v) in map {
                    let lower_key = k.to_lowercase();
                    let should_redact =
                        self.redaction_patterns.iter().any(|p| lower_key.contains(p));
                    new_map.insert(
                        k.clone(),
                        if should_redact {
                            serde_json::Value::String("[REDACTED]".to_string())
                        } else {
                            self.redact_value_inner(v)
                        },
                    );
                }
                serde_json::Value::Object(new_map)
            }
            serde_json::Value::Array(arr) => {
                serde_json::Value::Array(arr.iter().map(|v| self.redact_value_inner(v)).collect())
            }
            serde_json::Value::String(s) => serde_json::Value::String(self.redact_string(s)),
            _ => value.clone(),
        }
    }

    // ---- Dual in-memory ring ----

    /// General ring snapshot (oldest first), including error events.
    pub fn general_ring(&self) -> &VecDeque<StoredEvent> {
        &self.general
    }

    /// Error-only ring snapshot (oldest first). Retained independently of
    /// general-ring eviction.
    pub fn error_ring(&self) -> &VecDeque<ErrorEventDto> {
        &self.errors
    }

    fn push_general(&mut self, event: StoredEvent) {
        if self.general_cap > 0 && self.general.len() >= self.general_cap {
            self.general.pop_front();
        }
        self.general.push_back(event);
    }

    fn push_error(&mut self, event: ErrorEventDto) {
        if self.error_cap > 0 && self.errors.len() >= self.error_cap {
            self.errors.pop_front();
        }
        self.errors.push_back(event);
    }

    // ---- 2-file rotation ----

    fn previous_log_path(&self) -> PathBuf {
        self.log_path
            .parent()
            .map(|dir| dir.join(PREVIOUS_LOG_FILE_NAME))
            .unwrap_or_else(|| PathBuf::from(PREVIOUS_LOG_FILE_NAME))
    }

    /// Append one JSON line to the active file, rotating when it exceeds the
    /// size cap. Rotation replaces the previous file (see [`Self::rotate`]).
    fn append_json_line(&self, json_line: &str) -> Result<(), String> {
        if let Some(parent) = self.log_path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Failed to create log dir: {}", e))?;
        }

        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.log_path)
            .map_err(|e| format!("Failed to open log file: {}", e))?;
        writeln!(file, "{}", json_line).map_err(|e| format!("Failed to write to log: {}", e))?;
        drop(file);

        let size = fs::metadata(&self.log_path).map(|m| m.len()).unwrap_or(0);
        if size > self.file_size_cap_bytes {
            self.rotate()?;
        }
        Ok(())
    }

    /// CrashLogStore-style rotation: drop the previous file, rename the active
    /// file to previous, and start a fresh empty active file.
    fn rotate(&self) -> Result<(), String> {
        let previous = self.previous_log_path();
        if previous.exists() {
            fs::remove_file(&previous)
                .map_err(|e| format!("Failed to remove old log file: {}", e))?;
        }
        if self.log_path.exists() {
            fs::rename(&self.log_path, &previous)
                .map_err(|e| format!("Failed to rotate log file: {}", e))?;
        }
        fs::File::create(&self.log_path)
            .map_err(|e| format!("Failed to create log file: {}", e))?;
        Ok(())
    }

    /// Chronological lines from the rotated files (previous first, then active).
    /// When both are empty, falls back to the legacy `recent-errors.jsonl` file
    /// so pre-rotation logs stay readable during the rollout window
    /// ([`LEGACY_LOG_READ_FALLBACK_WINDOW_RELEASES`]).
    fn read_rotated_lines(&self) -> Result<Vec<String>, String> {
        let mut lines = Vec::new();
        for path in [self.previous_log_path(), self.log_path.clone()] {
            lines.extend(read_lines_if_exists(&path)?);
        }
        if lines.is_empty() {
            lines.extend(read_lines_if_exists(&self.legacy_log_path)?);
        }
        Ok(lines)
    }

    /// Most-recent `cap` redacted lines, newest first. Read from the rotated
    /// files (legacy fallback included); used by the diagnostics bundle tail.
    pub fn read_tail(&self, cap: usize) -> Result<Vec<String>, String> {
        let mut lines = self.read_rotated_lines()?;
        lines.reverse();
        lines.truncate(cap);
        Ok(lines)
    }

    /// Startup maintenance: reclaim over-cap state written by an older version.
    /// Removes rotated files beyond [`LOG_FILE_COUNT`] and trims retained files
    /// back under [`LOG_FILE_SIZE_CAP_BYTES`]. Rides the existing FR-14 startup
    /// thread — no new thread is introduced.
    pub fn prune_to_caps(&self) -> Result<(), String> {
        if let Some(dir) = self.log_path.parent() {
            if let Ok(entries) = fs::read_dir(dir) {
                for entry in entries.flatten() {
                    let name = entry.file_name().to_string_lossy().to_string();
                    if let Some(index) = app_log_file_index(&name) {
                        if index >= LOG_FILE_COUNT {
                            let _ = fs::remove_file(entry.path());
                        }
                    }
                }
            }
        }
        for path in [self.log_path.clone(), self.previous_log_path()] {
            if path.exists() {
                let size = fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
                if size > self.file_size_cap_bytes {
                    trim_file_to_size(&path, self.file_size_cap_bytes)?;
                }
            }
        }
        Ok(())
    }

    pub fn log_generic(&mut self, event: &LogEventDto) -> Result<(), String> {
        self.log_generic_with_threshold(event, &LogLevel::current_threshold()).map(|_| ())
    }

    /// Level-gated generic write. Events below `threshold` are dropped before
    /// redaction, retention, and persistence with zero disk side effect.
    /// Returns true when the (redacted) event was persisted and ringed.
    pub fn log_generic_with_threshold(
        &mut self,
        event: &LogEventDto,
        threshold: &LogLevel,
    ) -> Result<bool, String> {
        if !event.level.passes(threshold) {
            return Ok(false);
        }

        let redacted_event = self.redact_log_event(event);
        let json_line = serde_json::to_string(&redacted_event)
            .map_err(|e| format!("Failed to serialize log event: {}", e))?;
        self.append_json_line(&json_line)?;

        self.push_general(StoredEvent::Generic(redacted_event));
        Ok(true)
    }

    pub fn get_log_path(&self) -> &PathBuf {
        &self.log_path
    }

    pub fn get_recent_errors(&self, limit: usize) -> Result<Vec<ErrorEventDto>, String> {
        let mut events: Vec<ErrorEventDto> = self
            .read_rotated_lines()?
            .iter()
            .filter_map(|line| serde_json::from_str::<ErrorEventDto>(line).ok())
            .collect();

        events.reverse();
        events.truncate(limit);
        Ok(events)
    }

    pub fn read_all_logs(&self) -> Result<Vec<String>, String> {
        self.read_rotated_lines()
    }

    pub fn get_log_contents(&self) -> Result<String, String> {
        Ok(self.read_rotated_lines()?.join("\n"))
    }
}

/// Reads a file as lines, returning an empty vector when it does not exist.
fn read_lines_if_exists(path: &Path) -> Result<Vec<String>, String> {
    if !path.exists() {
        return Ok(vec![]);
    }
    let file = fs::File::open(path).map_err(|e| format!("Failed to open log file: {}", e))?;
    let reader = BufReader::new(file);
    Ok(reader.lines().map_while(Result::ok).collect())
}

/// Index of an `app-log.<n>.jsonl` file name, or `None` when the name does not
/// match the rotated-file pattern.
fn app_log_file_index(name: &str) -> Option<usize> {
    name.strip_prefix("app-log.")
        .and_then(|rest| rest.strip_suffix(".jsonl"))
        .and_then(|n| n.parse().ok())
}

/// Trims a JSONL file from the front (oldest lines) until it fits `cap` bytes.
fn trim_file_to_size(path: &Path, cap: u64) -> Result<(), String> {
    let content =
        fs::read_to_string(path).map_err(|e| format!("Failed to read log file: {}", e))?;
    let lines: Vec<&str> = content.lines().collect();
    let mut total: u64 = lines.iter().map(|line| line.len() as u64 + 1).sum();
    let mut drop_count = 0;
    while total > cap && drop_count < lines.len() {
        total -= lines[drop_count].len() as u64 + 1;
        drop_count += 1;
    }
    if drop_count > 0 {
        let mut body = lines[drop_count..].join("\n");
        if !body.is_empty() {
            body.push('\n');
        }
        fs::write(path, body).map_err(|e| format!("Failed to trim log file: {}", e))?;
    }
    Ok(())
}

pub struct LoggerState {
    pub logger: Mutex<Logger>,
}

impl LoggerState {
    pub fn new(app_data_dir: PathBuf) -> Self {
        Self { logger: Mutex::new(Logger::new(app_data_dir)) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn temp_dir(tag: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "nexo-logger-{}-{}",
            tag,
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos()
        ))
    }

    fn small_generic(message: &str) -> LogEventDto {
        LogEventDto {
            timestamp: "2026-10-07T00:00:00Z".to_string(),
            level: LogLevel::Info,
            message: message.to_string(),
            context: json!({}),
            source: "test".to_string(),
        }
    }

    fn error_fixture(code: &str) -> ErrorEventDto {
        ErrorEventDto {
            timestamp: "2026-10-07T00:00:00Z".to_string(),
            severity: "error".to_string(),
            category: "test".to_string(),
            code: code.to_string(),
            message: format!("failure {}", code),
            context: json!({}),
            correlation_id: "corr".to_string(),
            source: "test".to_string(),
            recoverable: true,
        }
    }

    fn rotated_files(dir: &Path) -> Vec<PathBuf> {
        let mut files: Vec<PathBuf> = fs::read_dir(dir)
            .into_iter()
            .flatten()
            .flatten()
            .map(|entry| entry.path())
            .filter(|path| {
                path.file_name()
                    .map(|name| app_log_file_index(&name.to_string_lossy()).is_some())
                    .unwrap_or(false)
            })
            .collect();
        files.sort();
        files
    }

    /// The PII scrubber is the SINGLE SOURCE OF TRUTH for outbound events.
    /// It MUST redact OAuth-style colon-suffixed secrets, sensitive object
    /// keys, and EPUB file paths. Any change that weakens these guarantees
    /// is a PII regression. This is the test required by PR 2 (sentry).
    #[test]
    fn redact_event_strips_oauth_token_and_supabase_key() {
        let logger = Logger::for_redaction_only();
        let event = ErrorEventDto {
            timestamp: "2026-09-04T20:00:00Z".to_string(),
            severity: "error".to_string(),
            category: "auth".to_string(),
            code: "OAUTH_FAIL".to_string(),
            message:
                "callback failed with password:hunter2 and token:abc123 and api_key:xyz_secret"
                    .to_string(),
            context: json!({
                "supabaseKey": "should-be-redacted",
                "apiKey": "should-be-redacted",
                "safe_field": "kept verbatim",
                "nested": {
                    "token_header": "should-be-redacted",
                    "url": "https://oauth.example.com/cb?code=keep_me"
                }
            }),
            correlation_id: "corr-1".to_string(),
            source: "renderer".to_string(),
            recoverable: true,
        };

        let redacted = logger.redact_event(&event);

        // Colon-suffixed patterns are redacted
        assert!(redacted.message.contains("password:[REDACTED]"), "got: {}", redacted.message);
        assert!(redacted.message.contains("token:[REDACTED]"), "got: {}", redacted.message);
        assert!(redacted.message.contains("api_key:[REDACTED]"), "got: {}", redacted.message);
        // Non-sensitive message fragments remain
        assert!(redacted.message.contains("callback failed with"), "got: {}", redacted.message);

        // Sensitive object keys are replaced wholesale
        assert_eq!(redacted.context["supabaseKey"], json!("[REDACTED]"));
        assert_eq!(redacted.context["nested"]["token_header"], json!("[REDACTED]"));
        // Non-sensitive keys pass through unchanged
        assert_eq!(redacted.context["safe_field"], json!("kept verbatim"));
        assert_eq!(
            redacted.context["nested"]["url"],
            json!("https://oauth.example.com/cb?code=keep_me")
        );

        // Untouched fields are preserved
        assert_eq!(redacted.code, "OAUTH_FAIL");
        assert_eq!(redacted.severity, "error");
        assert_eq!(redacted.correlation_id, "corr-1");
        assert_eq!(redacted.source, "renderer");
    }

    #[test]
    fn redact_json_value_works_on_arbitrary_sentry_shape() {
        let mut payload = json!({
            "code": "IPC_FAIL",
            "apiKey": "leaky",
            "context": {
                "token": "leaky-too",
                "user_id": "ok"
            }
        });
        Logger::redact_json_value(&mut payload);

        assert_eq!(payload["code"], json!("IPC_FAIL"));
        assert_eq!(payload["apiKey"], json!("[REDACTED]"));
        assert_eq!(payload["context"]["token"], json!("[REDACTED]"));
        assert_eq!(payload["context"]["user_id"], json!("ok"));
    }

    fn generic_fixture_event() -> LogEventDto {
        LogEventDto {
            timestamp: "2026-10-06T20:00:00Z".to_string(),
            level: LogLevel::Info,
            message: "sync failed with api_key:live_secret_123 at \
                C:\\Users\\juan\\books\\novel.epub"
                .to_string(),
            context: json!({
                "sentryDsn": "https://publickey@o123456.ingest.sentry.io/42",
                "highlight": { "text": "It was the best of times, secret prose" },
                "bookContent": "Chapter 1 full raw chapter bytes",
                "safe_field": "kept verbatim"
            }),
            source: "renderer".to_string(),
        }
    }

    /// P0: the generic (non-error) path MUST scrub exactly like the error path.
    #[test]
    fn generic_path_redacts_key_path_dsn_highlight_and_book_content() {
        let logger = Logger::for_redaction_only();
        let redacted = logger.redact_log_event(&generic_fixture_event());

        assert!(
            redacted.message.contains("api_key:[REDACTED]"),
            "key leak in message: {}",
            redacted.message
        );
        assert!(
            !redacted.message.contains("C:\\Users\\juan"),
            "absolute path leak in message: {}",
            redacted.message
        );
        assert!(
            !redacted.message.contains("novel.epub"),
            "path fragment leak in message: {}",
            redacted.message
        );
        assert_eq!(redacted.context["sentryDsn"], json!("[REDACTED]"));
        assert_eq!(redacted.context["highlight"], json!("[REDACTED]"));
        assert_eq!(redacted.context["bookContent"], json!("[REDACTED]"));
        assert_eq!(redacted.context["safe_field"], json!("kept verbatim"));
        assert_eq!(redacted.source, "renderer");
    }

    /// Fixture matrix: keys, DSN, absolute paths, home dirs, book content,
    /// and highlight text MUST be scrubbed on EVERY event path (error,
    /// generic, bundle-tail JSON).
    #[test]
    fn redaction_fixture_matrix_covers_every_event_path() {
        let logger = Logger::for_redaction_only();
        let unix_path = "/home/juan/books/novel.epub";
        let home_path = "~/books/novel.epub";

        // Error path.
        let error_event = ErrorEventDto {
            timestamp: "2026-10-06T20:00:00Z".to_string(),
            severity: "error".to_string(),
            category: "reader".to_string(),
            code: "OPEN_FAIL".to_string(),
            message: format!("open failed at {} and {}", unix_path, home_path),
            context: json!({
                "dsn": "https://key@o1.ingest.sentry.io/9",
                "highlightText": "raw highlight prose",
                "book_content": "raw chapter prose",
            }),
            correlation_id: "corr-matrix".to_string(),
            source: "renderer".to_string(),
            recoverable: true,
        };
        let redacted_error = logger.redact_event(&error_event);
        assert!(!redacted_error.message.contains("/home/juan"), "{}", redacted_error.message);
        assert!(!redacted_error.message.contains("~/books"), "{}", redacted_error.message);
        assert_eq!(redacted_error.context["dsn"], json!("[REDACTED]"));
        assert_eq!(redacted_error.context["highlightText"], json!("[REDACTED]"));
        assert_eq!(redacted_error.context["book_content"], json!("[REDACTED]"));

        // Generic path.
        let redacted_generic = logger.redact_log_event(&generic_fixture_event());
        let serialized = serde_json::to_value(&redacted_generic).expect("serializes");
        let flat = serialized.to_string();
        assert!(!flat.contains("live_secret_123"), "key leak: {}", flat);
        assert!(!flat.contains("novel.epub"), "path leak: {}", flat);
        assert!(!flat.contains("best of times"), "highlight leak: {}", flat);
        assert!(!flat.contains("raw chapter bytes"), "book content leak: {}", flat);
        assert!(!flat.contains("ingest.sentry.io"), "DSN leak: {}", flat);

        // Bundle-tail path: raw JSON through the shared boundary.
        let mut tail = json!({
            "message": "kept context",
            "apiKey": "tail-key-leak",
            "path": "C:\\Users\\juan\\books\\novel.epub",
            "highlight": "tail highlight prose",
        });
        Logger::redact_json_value(&mut tail);
        assert_eq!(tail["apiKey"], json!("[REDACTED]"));
        assert_eq!(tail["highlight"], json!("[REDACTED]"));
        let path = tail["path"].as_str().unwrap_or_default();
        assert!(!path.contains("C:\\Users\\juan"), "tail path leak: {}", path);
    }

    /// Release gate: DEBUG MUST NOT pass the release threshold; INFO/WARN/
    /// ERROR MUST.
    #[test]
    fn log_level_gate_drops_debug_at_release_threshold() {
        let release = LogLevel::release_threshold();
        assert!(!LogLevel::Debug.passes(&release), "DEBUG must be dropped in release");
        assert!(LogLevel::Info.passes(&release));
        assert!(LogLevel::Warn.passes(&release));
        assert!(LogLevel::Error.passes(&release));
        // Raising the threshold drops new below-threshold events only.
        assert!(!LogLevel::Info.passes(&LogLevel::Warn));
        assert!(LogLevel::Warn.passes(&LogLevel::Warn));
    }

    /// DEBUG events MUST be dropped before any disk/ring change with zero side
    /// effect; INFO events persist redacted.
    #[test]
    fn log_generic_drops_debug_before_persistence() {
        let dir = temp_dir("debug-drop");
        let mut logger = Logger::new(dir.clone());
        let threshold = LogLevel::release_threshold();

        let mut debug_event = generic_fixture_event();
        debug_event.level = LogLevel::Debug;
        let retained =
            logger.log_generic_with_threshold(&debug_event, &threshold).expect("gate runs");
        assert!(!retained, "DEBUG must be dropped pre-retention");
        assert!(!logger.get_log_path().exists(), "dropped DEBUG must cause zero disk side effect");
        assert!(logger.general_ring().is_empty(), "dropped DEBUG must not enter the ring");

        let retained_info = logger
            .log_generic_with_threshold(&generic_fixture_event(), &threshold)
            .expect("gate runs");
        assert!(retained_info);
        let body = logger.get_log_contents().expect("reads back");
        assert!(!body.contains("live_secret_123"), "persisted leak: {}", body);
        assert!(!body.contains("best of times"), "persisted leak: {}", body);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Sizing decision (task 2.4): the design defaults are adopted and locked
    /// here so any change is intentional and reviewable.
    #[test]
    fn sizing_constants_lock_design_defaults() {
        assert_eq!(GENERAL_RING_CAP, 500);
        assert_eq!(ERROR_RING_CAP, 200);
        assert_eq!(LOG_FILE_COUNT, 2);
        assert_eq!(LOG_FILE_SIZE_CAP_BYTES, 200 * 1024);
        assert_eq!(BUNDLE_LOG_TAIL_CAP, 200);
        // Legacy read-fallback window (task 2.5): two releases.
        assert_eq!(LEGACY_LOG_READ_FALLBACK_WINDOW_RELEASES, 2);
    }

    /// Task 2.1: general-ring overflow evicts the oldest event and never
    /// exceeds its capacity.
    #[test]
    fn general_ring_overflow_evicts_oldest() {
        let dir = temp_dir("ring-general");
        let mut logger = Logger::with_test_limits(dir.clone(), 3, 2, LOG_FILE_SIZE_CAP_BYTES);

        for i in 0..5 {
            logger
                .log_generic_with_threshold(
                    &small_generic(&format!("event-{}", i)),
                    &LogLevel::Debug,
                )
                .expect("writes");
        }

        let messages: Vec<String> = logger
            .general_ring()
            .iter()
            .map(|stored| match stored {
                StoredEvent::Generic(event) => event.message.clone(),
                StoredEvent::Error(event) => event.message.clone(),
            })
            .collect();
        assert_eq!(messages.len(), 3);
        assert!(messages.contains(&"event-4".to_string()), "got: {:?}", messages);
        assert!(
            !messages.contains(&"event-0".to_string()),
            "oldest must be evicted: {:?}",
            messages
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Task 2.1: the error partition survives general churn and evicts its own
    /// oldest event on overflow.
    #[test]
    fn error_partition_survives_general_churn_and_evicts_oldest() {
        let dir = temp_dir("ring-error");
        let mut logger = Logger::with_test_limits(dir.clone(), 2, 2, LOG_FILE_SIZE_CAP_BYTES);

        logger.log_to_file(&error_fixture("E0")).expect("writes E0");
        logger.log_to_file(&error_fixture("E1")).expect("writes E1");

        for i in 0..5 {
            logger
                .log_generic_with_threshold(&small_generic(&format!("g{}", i)), &LogLevel::Debug)
                .expect("writes");
        }

        // General ring churned past the retained errors, but the error
        // partition still holds both errors.
        assert_eq!(logger.general_ring().len(), 2);
        assert_eq!(logger.error_ring().len(), 2);
        let codes: Vec<&str> = logger.error_ring().iter().map(|e| e.code.as_str()).collect();
        assert!(codes.contains(&"E0") && codes.contains(&"E1"), "got: {:?}", codes);

        // Error overflow evicts the oldest error only.
        logger.log_to_file(&error_fixture("E2")).expect("writes E2");
        assert_eq!(logger.error_ring().len(), 2);
        let codes: Vec<&str> = logger.error_ring().iter().map(|e| e.code.as_str()).collect();
        assert!(!codes.contains(&"E0"), "oldest error must be evicted: {:?}", codes);
        assert!(codes.contains(&"E2"), "newest error must be retained: {:?}", codes);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Task 2.2: rotation on the size cap keeps at most `LOG_FILE_COUNT` files
    /// and bounds total disk usage under sustained volume.
    #[test]
    fn rotation_caps_file_count_and_bounds_disk() {
        let dir = temp_dir("rotation");
        let cap: u64 = 400;
        let max_line: u64 = 300;
        let mut logger =
            Logger::with_test_limits(dir.clone(), GENERAL_RING_CAP, ERROR_RING_CAP, cap);

        for i in 0..20 {
            logger
                .log_generic_with_threshold(
                    &small_generic(&format!("rotation-line-{:03}", i)),
                    &LogLevel::Debug,
                )
                .expect("writes");
        }

        let files = rotated_files(&dir);
        assert!(files.len() <= LOG_FILE_COUNT, "too many files: {:?}", files);
        let total: u64 =
            files.iter().map(|path| fs::metadata(path).map(|m| m.len()).unwrap_or(0)).sum();
        assert!(
            total <= (LOG_FILE_COUNT as u64) * (cap + max_line),
            "disk not bounded: {} bytes",
            total
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Task 2.2: startup prune reclaims over-cap state (extra files and an
    /// oversize active file written by an older version).
    #[test]
    fn prune_to_caps_reclaims_over_cap_state() {
        let dir = temp_dir("prune");
        fs::create_dir_all(&dir).expect("mkdir");
        fs::write(dir.join("app-log.2.jsonl"), "stale-extra-file\n").expect("seed extra");
        let oversize = "x".repeat(10_000);
        fs::write(dir.join(ACTIVE_LOG_FILE_NAME), format!("{}\n", oversize)).expect("seed big");

        let cap: u64 = 512;
        let logger = Logger::with_test_limits(dir.clone(), GENERAL_RING_CAP, ERROR_RING_CAP, cap);
        logger.prune_to_caps().expect("prunes");

        assert!(!dir.join("app-log.2.jsonl").exists(), "extra rotated file must be removed");
        let active_size =
            fs::metadata(dir.join(ACTIVE_LOG_FILE_NAME)).map(|m| m.len()).unwrap_or(0);
        assert!(active_size <= cap, "active file not trimmed: {} bytes", active_size);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Task 2.2: `read_tail` returns the newest lines first, bounded by the cap.
    #[test]
    fn read_tail_returns_newest_lines_bounded() {
        let dir = temp_dir("tail");
        let mut logger = Logger::new(dir.clone());
        for i in 0..10 {
            logger
                .log_generic_with_threshold(
                    &small_generic(&format!("tail-{}", i)),
                    &LogLevel::Debug,
                )
                .expect("writes");
        }

        let tail = logger.read_tail(3).expect("reads tail");
        assert_eq!(tail.len(), 3);
        assert!(tail[0].contains("tail-9"), "newest first: {:?}", tail);
        assert!(tail[2].contains("tail-7"), "third newest: {:?}", tail);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Task 2.5: the legacy `recent-errors.jsonl` read fallback is used only
    /// while the rotated files carry no data.
    #[test]
    fn legacy_recent_errors_file_is_read_as_fallback_only() {
        let dir = temp_dir("legacy");
        fs::create_dir_all(&dir).expect("mkdir");
        fs::write(dir.join(LEGACY_LOG_FILE_NAME), "legacy-line-one\n").expect("seed legacy");

        let mut logger = Logger::new(dir.clone());
        let logs = logger.read_all_logs().expect("reads legacy fallback");
        assert_eq!(logs, vec!["legacy-line-one".to_string()]);

        logger
            .log_generic_with_threshold(&small_generic("new-line"), &LogLevel::Debug)
            .expect("writes");
        let logs = logger.read_all_logs().expect("reads rotated");
        assert!(logs.iter().any(|line| line.contains("new-line")), "got: {:?}", logs);
        assert!(
            !logs.iter().any(|line| line.contains("legacy-line-one")),
            "legacy must not be read once rotated files exist: {:?}",
            logs
        );
        let _ = std::fs::remove_dir_all(&dir);
    }
}
