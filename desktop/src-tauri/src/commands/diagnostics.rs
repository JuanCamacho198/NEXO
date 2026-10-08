use crate::logger::{ErrorEventDto, LogEventDto, Logger, BUNDLE_LOG_TAIL_CAP};
use crate::models::AppSettingDto;
use crate::services::diagnostics::DiagnoseResult;
use crate::state::AppState;
use serde::{Deserialize, Serialize};
use tauri::State;

/// Minimal safe settings allowlist (task 4.1 fallback).
///
/// The team has NOT confirmed the exact non-sensitive key set for the
/// diagnostics bundle. Per the tasks artifact fallback, ship the smallest safe
/// subset now: the app version (carried as the bundle's top-level `appVersion`
/// field, not an `app_settings` row) plus the UI locale. Every other
/// `app_settings` key is excluded, so credentials, keys, tokens, DSNs and paths
/// can never travel through `settings`.
///
/// Deferred decision (tracked as a PR-description item): widen this constant
/// only with recorded team confirmation.
const BUNDLE_SETTINGS_ALLOWLIST: &[&str] = &["ui.locale"];

/// L2 diagnostics bundle assembled on explicit user action only.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiagnosticsBundle {
    pub app_version: String,
    pub release_id: String,
    pub session_id: String,
    pub settings: serde_json::Value,
    pub diagnose: DiagnoseResult,
    pub log_tail: Vec<String>,
    pub metrics_summary: MetricsSummary,
}

/// Aggregate-only timings carrier. Phase 6 computes the p50/p95 aggregates
/// locally in the frontend (`summarizeTimings`, surfaced in the debug panel);
/// they are never wired into the bundle, so the command ships this field empty
/// rather than fabricating metrics or egressing exact durations.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MetricsSummary {
    pub operations: Vec<OperationTiming>,
}

/// p50/p95 per operation. Aggregates only; exact durations never egress.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OperationTiming {
    pub operation: String,
    pub p50_ms: u64,
    pub p95_ms: u64,
}

/// Sentry release shape `<prefix>@<version>+<sha12>` (spec C1), mirroring
/// `sentry_init`. Kept local to the bundle to hold the diagnostics scope; if
/// the shared prefix moves, update both call sites.
fn release_id() -> String {
    format!("nexo-desktop@{}+{}", env!("CARGO_PKG_VERSION"), env!("NEXO_GIT_SHA"))
}

/// Backend-scoped per-run session id.
///
/// The frontend `MetricsStore` owns its own per-session UUID, but the bundle
/// command takes no argument by design, so the bundle carries a backend run id
/// generated once per process. Correlating it with the frontend session id can
/// be wired in a later phase.
fn session_id() -> String {
    static SESSION_ID: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    SESSION_ID.get_or_init(|| uuid::Uuid::new_v4().to_string()).clone()
}

/// Project `app_settings` rows down to [`BUNDLE_SETTINGS_ALLOWLIST`]. Values
/// are parsed as JSON scalars (the repository guarantees scalar JSON); a parse
/// failure falls back to the raw string so nothing is silently dropped.
fn allowlisted_settings(settings: &[AppSettingDto]) -> serde_json::Value {
    let mut map = serde_json::Map::new();
    for setting in settings {
        if !BUNDLE_SETTINGS_ALLOWLIST.contains(&setting.key.as_str()) {
            continue;
        }
        let value = serde_json::from_str(&setting.value_json)
            .unwrap_or_else(|_| serde_json::Value::String(setting.value_json.clone()));
        map.insert(setting.key.clone(), value);
    }
    serde_json::Value::Object(map)
}

/// Re-apply the shared redaction boundary over one bundle section by
/// round-tripping it through `serde_json::Value`. Defense-in-depth against any
/// source path that assembled unredacted data.
fn redact_section<T>(section: &T) -> Result<T, String>
where
    T: serde::Serialize + serde::de::DeserializeOwned,
{
    let mut value =
        serde_json::to_value(section).map_err(|e| format!("Failed to serialize section: {e}"))?;
    Logger::redact_json_value(&mut value);
    serde_json::from_value(value).map_err(|e| format!("Failed to deserialize section: {e}"))
}

/// Pure bundle assembly: redacts every data-bearing section, caps the log tail
/// to [`BUNDLE_LOG_TAIL_CAP`] most-recent lines, and keeps book data as counts.
/// Split out from the command so privacy probes can exercise it directly
/// without an `AppState`.
#[allow(clippy::too_many_arguments)]
fn assemble_bundle(
    app_version: String,
    release_id: String,
    session_id: String,
    raw_settings: serde_json::Value,
    raw_diagnose: DiagnoseResult,
    raw_log_tail: Vec<String>,
    raw_metrics_summary: MetricsSummary,
) -> Result<DiagnosticsBundle, String> {
    let settings = redact_section(&raw_settings)?;
    let diagnose = redact_section(&raw_diagnose)?;

    let mut log_tail = raw_log_tail;
    log_tail.truncate(BUNDLE_LOG_TAIL_CAP);
    let log_tail = redact_section(&log_tail)?;

    let metrics_summary = redact_section(&raw_metrics_summary)?;

    Ok(DiagnosticsBundle {
        app_version,
        release_id,
        session_id,
        settings,
        diagnose,
        log_tail,
        metrics_summary,
    })
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn reportErrorEvent(state: State<'_, AppState>, event: ErrorEventDto) -> Result<(), String> {
    let mut logger = state.logger.lock().map_err(|e| format!("{}", e))?;
    logger.log_to_file(&event)?;

    // Forward to Sentry when initialized. The PII scrubber is already wired
    // via `before_send` in `sentry_init` (reuses `Logger::redact_json_value`),
    // so we attach the DTO as raw extras — `before_send` runs before egress.
    // The severity string is mapped to a Sentry Level; `code` and `source`
    // become tags for Sentry UI filtering.
    if crate::sentry_init::is_enabled() {
        let level = match event.severity.to_lowercase().as_str() {
            "debug" => sentry::Level::Debug,
            "info" => sentry::Level::Info,
            "warning" | "warn" => sentry::Level::Warning,
            "critical" | "fatal" => sentry::Level::Fatal,
            _ => sentry::Level::Error,
        };
        let mut sentry_event = sentry::protocol::Event::new();
        sentry_event.level = level;
        sentry_event.logger = Some(event.source.clone());
        sentry_event.message = Some(format!("[{}] {}", event.code, event.message));
        sentry_event.tags.insert("code".to_string(), event.code.clone());
        sentry_event.tags.insert("source".to_string(), event.source.clone());
        sentry_event.tags.insert("category".to_string(), event.category.clone());
        sentry_event.tags.insert("correlation_id".to_string(), event.correlation_id.clone());
        sentry_event.extra.insert("context".to_string(), event.context.clone());
        sentry_event.extra.insert("recoverable".to_string(), serde_json::json!(event.recoverable));
        sentry::capture_event(sentry_event);
    }

    Ok(())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn logEvent(state: State<'_, AppState>, event: LogEventDto) -> Result<(), String> {
    let mut logger = state.logger.lock().map_err(|e| format!("{}", e))?;
    logger.log_generic(&event)
}

#[tauri::command(rename_all = "camelCase")]
pub fn diagnose(state: State<'_, AppState>) -> crate::services::diagnostics::DiagnoseResult {
    crate::services::diagnostics::run_diagnose(&state)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getLogs(state: State<'_, AppState>) -> Result<Vec<String>, String> {
    let logger = state.logger.lock().map_err(|e| format!("{}", e))?;
    logger.read_all_logs()
}

/// Assemble the L2 diagnostics bundle. Explicit user action only (opt-in
/// feedback attachment); no timer, thread, or scheduled path exists here.
#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn collectDiagnosticsBundle(state: State<'_, AppState>) -> Result<DiagnosticsBundle, String> {
    let raw_settings = {
        let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
        if repository.has_desktop_parity_schema().unwrap_or(true) {
            repository.get_settings().map_err(|e| e.to_string())?
        } else {
            Vec::new()
        }
    };
    let settings = allowlisted_settings(&raw_settings);

    let diagnose = crate::services::diagnostics::run_diagnose(&state);

    let log_tail = {
        let logger = state.logger.lock().map_err(|e| format!("{}", e))?;
        logger.read_tail(BUNDLE_LOG_TAIL_CAP)?
    };

    // Phase 6 aggregates stay local to the frontend (`summarizeTimings`); the
    // bundle ships empty aggregates rather than fabricated metrics.
    let metrics_summary = MetricsSummary::default();

    assemble_bundle(
        env!("CARGO_PKG_VERSION").to_string(),
        release_id(),
        session_id(),
        settings,
        diagnose,
        log_tail,
        metrics_summary,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn raw_diagnose_with_leaks() -> DiagnoseResult {
        let mut details = std::collections::HashMap::new();
        details.insert(
            "log_path".to_string(),
            json!("C:\\Users\\juan\\AppData\\nexo\\app-log.0.jsonl"),
        );
        details.insert(
            "db_error".to_string(),
            json!("open failed for dsn:https://key@o1.ingest.sentry.io/9"),
        );
        details.insert("sentryDsn".to_string(), json!("https://key@o1.ingest.sentry.io/9"));
        details.insert("total_books".to_string(), json!(7));
        details.insert("missing_files".to_string(), json!(0));
        DiagnoseResult {
            database: "healthy".to_string(),
            queue: "healthy".to_string(),
            filesystem: "healthy".to_string(),
            log_file: "healthy".to_string(),
            details,
        }
    }

    fn assemble_fixture(
        settings: serde_json::Value,
        diagnose: DiagnoseResult,
        log_tail: Vec<String>,
    ) -> DiagnosticsBundle {
        assemble_bundle(
            env!("CARGO_PKG_VERSION").to_string(),
            release_id(),
            "session-fixture".to_string(),
            settings,
            diagnose,
            log_tail,
            MetricsSummary::default(),
        )
        .expect("assembles")
    }

    /// Task 4.3 — tail cap: more than `BUNDLE_LOG_TAIL_CAP` events yields at
    /// most the cap, keeping the most recent ones.
    #[test]
    fn bundle_log_tail_is_capped_to_newest_events() {
        let raw: Vec<String> =
            (0..(BUNDLE_LOG_TAIL_CAP + 50)).map(|i| format!("line-{i}")).rev().collect();

        let bundle = assemble_fixture(json!({}), raw_diagnose_with_leaks(), raw);

        assert_eq!(bundle.log_tail.len(), BUNDLE_LOG_TAIL_CAP);
        assert_eq!(bundle.log_tail[0], format!("line-{}", BUNDLE_LOG_TAIL_CAP + 49));
        assert!(!bundle.log_tail.iter().any(|line| line == "line-0"));
    }

    /// Task 4.3 — settings allowlist: only named non-sensitive keys survive;
    /// nothing else from `app_settings` can reach the bundle.
    #[test]
    fn allowlisted_settings_keeps_only_named_keys() {
        let settings = vec![
            AppSettingDto {
                key: "ui.locale".to_string(),
                value_json: "\"en\"".to_string(),
                updated_at: "2026-10-07T00:00:00Z".to_string(),
            },
            AppSettingDto {
                key: "supabase.apiKey".to_string(),
                value_json: "\"leak\"".to_string(),
                updated_at: "2026-10-07T00:00:00Z".to_string(),
            },
            AppSettingDto {
                key: "reading.dailyGoalMinutes".to_string(),
                value_json: "20".to_string(),
                updated_at: "2026-10-07T00:00:00Z".to_string(),
            },
        ];

        let result = allowlisted_settings(&settings);

        assert_eq!(result["ui.locale"], json!("en"));
        assert!(result.get("supabase.apiKey").is_none(), "non-allowlisted key leaked");
        assert!(result.get("reading.dailyGoalMinutes").is_none(), "non-allowlisted key leaked");
    }

    /// Task 4.3 — sensitive-settings probe: even a secret-valued entry handed
    /// to assembly is scrubbed by the shared boundary.
    #[test]
    fn bundle_assembly_scrubs_sensitive_settings() {
        let bundle = assemble_fixture(
            json!({
                "ui.locale": "en",
                "sentryDsn": "https://key@o1.ingest.sentry.io/9",
            }),
            raw_diagnose_with_leaks(),
            vec![],
        );

        assert_eq!(bundle.settings["ui.locale"], json!("en"));
        assert_eq!(bundle.settings["sentryDsn"], json!("[REDACTED]"));
    }

    /// Task 4.3 — diagnose-with-paths probe + book counts-only: no absolute
    /// path or DSN survives assembly, and book data stays numeric counts.
    #[test]
    fn bundle_assembly_scrubs_paths_and_keeps_counts_only() {
        let bundle = assemble_fixture(json!({}), raw_diagnose_with_leaks(), vec![]);

        let flat = serde_json::to_string(&bundle).expect("serializes");
        assert!(!flat.contains("ingest.sentry.io"), "DSN leak: {flat}");
        assert!(!flat.contains("juan"), "path/username leak: {flat}");
        assert!(!flat.contains("app-log.0.jsonl"), "path leak: {flat}");

        assert_eq!(bundle.diagnose.details["log_path"], json!("[REDACTED_PATH]"));
        assert_eq!(bundle.diagnose.details["total_books"], json!(7));
        assert_eq!(bundle.diagnose.details["missing_files"], json!(0));
    }

    /// Task 4.3 — assembly-time DSN leak probe: a DSN carried in any section
    /// (settings, diagnose details, log tail) is absent from the final bundle.
    #[test]
    fn bundle_assembly_scrubs_dsn_across_every_section() {
        let log_tail = vec![
            r#"{"message":"tail says dsn:https://key@o1.ingest.sentry.io/9"}"#.to_string(),
            r#"{"message":"plain line"}"#.to_string(),
        ];

        let bundle = assemble_fixture(
            json!({ "sentryDsn": "https://key@o1.ingest.sentry.io/9" }),
            raw_diagnose_with_leaks(),
            log_tail,
        );

        let flat = serde_json::to_string(&bundle).expect("serializes");
        assert!(!flat.contains("ingest.sentry.io"), "DSN leak in bundle: {flat}");
    }

    /// Task 4.3 — no background transmission. The bundle is request/response
    /// only: this module spawns no thread and opens no channel, and the bundle
    /// path itself never egresses. Guards against a future silent-upload
    /// regression. Scoped to the bundle command body so the legitimate
    /// `reportErrorEvent` Sentry forward is not flagged.
    #[test]
    fn diagnostics_module_has_no_background_or_egress_surface() {
        let source = include_str!("diagnostics.rs");
        let spawn = concat!("thread::", "spawn");
        let channel = concat!("std::sync::", "mpsc");
        let capture = concat!("capture_", "event");

        assert!(!source.contains(spawn), "diagnostics must not spawn background threads");
        assert!(!source.contains(channel), "diagnostics must not open channels");

        let bundle_body = source
            .split("fn collectDiagnosticsBundle")
            .nth(1)
            .and_then(|rest| rest.split("#[cfg(test)]").next())
            .unwrap_or_default();
        assert!(!bundle_body.is_empty(), "bundle command body must be scannable");
        assert!(
            !bundle_body.contains(capture),
            "bundle command must not capture/send events directly"
        );
    }

    /// Task 4.3 — idle assembly is deterministic and side-effect free (no
    /// filesystem, no transmission): identical inputs produce identical output.
    #[test]
    fn assemble_bundle_is_deterministic_and_side_effect_free() {
        let first = assemble_fixture(
            json!({ "ui.locale": "en" }),
            raw_diagnose_with_leaks(),
            vec!["line-a".to_string()],
        );
        let second = assemble_fixture(
            json!({ "ui.locale": "en" }),
            raw_diagnose_with_leaks(),
            vec!["line-a".to_string()],
        );

        // Normalize through `serde_json::Value` so the `HashMap`-backed
        // `diagnose.details` compares by content, not iteration order.
        let first_value = serde_json::to_value(&first).expect("serializes");
        let second_value = serde_json::to_value(&second).expect("serializes");
        assert_eq!(first_value, second_value);
    }
}
