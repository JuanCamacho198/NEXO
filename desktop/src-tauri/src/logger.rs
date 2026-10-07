use serde::{Deserialize, Serialize};
use std::fmt;
use std::fs::{self, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::sync::Mutex;

pub const DEFAULT_MAX_LOG_LINES: usize = 1000;
pub const SETTING_MAX_LOG_LINES_KEY: &str = "observability.maxLogLines";

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

pub struct Logger {
    log_path: PathBuf,
    redaction_patterns: Vec<String>,
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
        let log_path = app_data_dir.join("recent-errors.jsonl");
        Self { log_path, redaction_patterns: Self::default_redaction_patterns() }
    }

    pub fn log_to_file(&self, event: &ErrorEventDto, max_lines: usize) -> Result<(), String> {
        if !level_for_severity(&event.severity).passes(&LogLevel::current_threshold()) {
            return Ok(());
        }
        if let Some(parent) = self.log_path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Failed to create log dir: {}", e))?;
        }

        let redacted_event = self.redact_event(event);
        let json_line = serde_json::to_string(&redacted_event)
            .map_err(|e| format!("Failed to serialize event: {}", e))?;

        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.log_path)
            .map_err(|e| format!("Failed to open log file: {}", e))?;

        writeln!(file, "{}", json_line).map_err(|e| format!("Failed to write to log: {}", e))?;

        self.trim_old_lines(max_lines)?;

        Ok(())
    }

    /// Build a stateless Logger bound to an empty path, for callers that only need
    /// the redaction helpers (e.g. Sentry `before_send`). Does NOT touch the filesystem.
    pub fn for_redaction_only() -> Self {
        Self { log_path: PathBuf::new(), redaction_patterns: Self::default_redaction_patterns() }
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

    fn trim_old_lines(&self, max_lines: usize) -> Result<(), String> {
        if !self.log_path.exists() {
            return Ok(());
        }

        let file = fs::File::open(&self.log_path)
            .map_err(|e| format!("Failed to open log file for trimming: {}", e))?;
        let reader = BufReader::new(file);

        let lines: Vec<String> = reader.lines().map_while(Result::ok).collect();
        let total_lines = lines.len();

        if total_lines > max_lines {
            let skip_count = total_lines - max_lines;
            let lines_to_keep: Vec<String> = lines.into_iter().skip(skip_count).collect();

            let mut file = OpenOptions::new()
                .write(true)
                .truncate(true)
                .open(&self.log_path)
                .map_err(|e| format!("Failed to open log file for trimming: {}", e))?;

            for line in lines_to_keep {
                writeln!(file, "{}", line)
                    .map_err(|e| format!("Failed to write trimmed log: {}", e))?;
            }
        }

        Ok(())
    }

    pub fn log_generic(&self, event: &LogEventDto, max_lines: usize) -> Result<(), String> {
        self.log_generic_with_threshold(event, max_lines, &LogLevel::current_threshold())
            .map(|_| ())
    }

    /// Level-gated generic write. Events below `threshold` are dropped before
    /// redaction and persistence with zero disk side effect. Returns true when
    /// the (redacted) event was persisted. Exposed for tests and the Phase 2
    /// ring gate, which reuses the same threshold semantics pre-insert.
    pub fn log_generic_with_threshold(
        &self,
        event: &LogEventDto,
        max_lines: usize,
        threshold: &LogLevel,
    ) -> Result<bool, String> {
        if !event.level.passes(threshold) {
            return Ok(false);
        }
        if let Some(parent) = self.log_path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Failed to create log dir: {}", e))?;
        }

        let redacted_event = self.redact_log_event(event);
        let json_line = serde_json::to_string(&redacted_event)
            .map_err(|e| format!("Failed to serialize log event: {}", e))?;

        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.log_path)
            .map_err(|e| format!("Failed to open log file: {}", e))?;

        writeln!(file, "{}", json_line).map_err(|e| format!("Failed to write log: {}", e))?;

        self.trim_old_lines(max_lines)?;
        Ok(true)
    }

    pub fn get_log_path(&self) -> &PathBuf {
        &self.log_path
    }

    pub fn get_recent_errors(&self, limit: usize) -> Result<Vec<ErrorEventDto>, String> {
        if !self.log_path.exists() {
            return Ok(vec![]);
        }

        let file = fs::File::open(&self.log_path)
            .map_err(|e| format!("Failed to open log file: {}", e))?;
        let reader = BufReader::new(file);

        let mut events: Vec<ErrorEventDto> = reader
            .lines()
            .map_while(Result::ok)
            .filter_map(|line| serde_json::from_str(&line).ok())
            .collect();

        events.reverse();
        events.truncate(limit);
        Ok(events)
    }

    pub fn read_all_logs(&self) -> Result<Vec<String>, String> {
        if !self.log_path.exists() {
            return Ok(vec![]);
        }

        let file = fs::File::open(&self.log_path)
            .map_err(|e| format!("Failed to open log file: {}", e))?;
        let reader = BufReader::new(file);

        let lines: Vec<String> = reader.lines().map_while(Result::ok).collect();
        Ok(lines)
    }

    pub fn get_log_contents(&self) -> Result<String, String> {
        if !self.log_path.exists() {
            return Ok(String::new());
        }

        fs::read_to_string(&self.log_path).map_err(|e| format!("Failed to read log file: {}", e))
    }
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
    /// RED: `redact_log_event` does not exist yet — this fails to compile.
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
    /// generic, bundle-tail JSON). RED: absolute-path/home-dir scrubbing
    /// is missing, so the path assertions fail.
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
    /// ERROR MUST. RED: `passes`/`release_threshold` do not exist yet.
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

    /// DEBUG events MUST be dropped before any disk write with zero side
    /// effect; INFO events persist redacted. RED: the gated helper is missing.
    #[test]
    fn log_generic_drops_debug_before_persistence() {
        let dir = std::env::temp_dir().join(format!(
            "nexo-logger-red-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos()
        ));
        let logger = Logger::new(dir.clone());
        let threshold = LogLevel::release_threshold();

        let mut debug_event = generic_fixture_event();
        debug_event.level = LogLevel::Debug;
        let retained =
            logger.log_generic_with_threshold(&debug_event, 1000, &threshold).expect("gate runs");
        assert!(!retained, "DEBUG must be dropped pre-retention");
        assert!(!logger.get_log_path().exists(), "dropped DEBUG must cause zero disk side effect");

        let retained_info = logger
            .log_generic_with_threshold(&generic_fixture_event(), 1000, &threshold)
            .expect("gate runs");
        assert!(retained_info);
        let body = logger.get_log_contents().expect("reads back");
        assert!(!body.contains("live_secret_123"), "persisted leak: {}", body);
        assert!(!body.contains("best of times"), "persisted leak: {}", body);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
