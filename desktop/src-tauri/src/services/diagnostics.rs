use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::db::verify_queue_health;
use crate::logger::Logger;
use crate::state::AppState;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiagnoseResult {
    pub database: String,
    pub queue: String,
    pub filesystem: String,
    pub log_file: String,
    pub details: HashMap<String, serde_json::Value>,
}

/// Run a full system diagnosis: database, queue, filesystem, and log file health.
pub fn run_diagnose(state: &AppState) -> DiagnoseResult {
    let mut details = HashMap::new();

    // Database health
    let (database_status, _db_detail) = match state.repository.lock() {
        Ok(repo) => {
            let conn = repo.connection();
            match conn.query_row("SELECT 1", [], |row| row.get::<_, i32>(0)) {
                Ok(1) => {
                    details.insert("db_query_ok".to_string(), serde_json::Value::Bool(true));
                    ("healthy".to_string(), "DB responded OK".to_string())
                }
                Ok(other) => {
                    details.insert(
                        "db_unexpected".to_string(),
                        serde_json::Value::Number(serde_json::Number::from(other)),
                    );
                    ("degraded".to_string(), format!("DB query returned unexpected value: {other}"))
                }
                Err(e) => {
                    details
                        .insert("db_error".to_string(), serde_json::Value::String(e.to_string()));
                    ("degraded".to_string(), format!("DB query failed: {e}"))
                }
            }
        }
        Err(e) => {
            details.insert("db_lock_error".to_string(), serde_json::Value::String(e.to_string()));
            ("unhealthy".to_string(), format!("DB lock failed: {e}"))
        }
    };

    // Queue health
    let (queue_status, _queue_detail) = match state.queue_repository.lock() {
        Ok(queue_repo) => {
            let conn = queue_repo.connection();
            match verify_queue_health(conn) {
                Ok(health) => {
                    let status = if health.warnings.is_empty() { "healthy" } else { "degraded" };
                    details.insert(
                        "queue_warnings".to_string(),
                        serde_json::Value::Array(
                            health
                                .warnings
                                .iter()
                                .map(|w| serde_json::Value::String(w.clone()))
                                .collect(),
                        ),
                    );
                    (status.to_string(), format!("Queue health: {:?}", health.status))
                }
                Err(e) => ("degraded".to_string(), format!("Queue check failed: {e}")),
            }
        }
        Err(e) => ("unhealthy".to_string(), format!("Queue lock failed: {e}")),
    };

    // Filesystem health
    let (fs_status, _fs_detail) = match state.repository.lock() {
        Ok(repo) => {
            let books = repo.list_books().unwrap_or_default();
            let missing =
                books.iter().filter(|b| !std::path::Path::new(&b.file_path).exists()).count();
            details.insert(
                "total_books".to_string(),
                serde_json::Value::Number(serde_json::Number::from(books.len())),
            );
            details.insert(
                "missing_files".to_string(),
                serde_json::Value::Number(serde_json::Number::from(missing)),
            );
            if missing > 0 {
                ("degraded".to_string(), format!("{missing} of {} book files missing", books.len()))
            } else {
                ("healthy".to_string(), format!("{} book files OK", books.len()))
            }
        }
        Err(e) => ("unhealthy".to_string(), format!("FS check failed: {e}")),
    };

    // Log file
    let (log_status, _log_detail) = match state.logger.lock() {
        Ok(logger) => {
            let log_path = logger.get_log_path().clone();
            if log_path.exists() {
                match log_path.metadata() {
                    Ok(meta) => {
                        details.insert(
                            "log_size_bytes".to_string(),
                            serde_json::Value::Number(serde_json::Number::from(meta.len())),
                        );
                        details.insert(
                            "log_path".to_string(),
                            serde_json::Value::String(log_path.to_string_lossy().to_string()),
                        );
                        ("healthy".to_string(), format!("Log file exists ({} bytes)", meta.len()))
                    }
                    Err(e) => ("degraded".to_string(), format!("Log metadata error: {e}")),
                }
            } else {
                ("healthy".to_string(), "No log file yet".to_string())
            }
        }
        Err(e) => ("unhealthy".to_string(), format!("Logger lock failed: {e}")),
    };

    // L2 bundle privacy: `details` can carry absolute filesystem paths (e.g.
    // `log_path`). Scrub them through the shared redaction boundary before the
    // result can reach the bundle.
    scrub_details_paths(&mut details);

    DiagnoseResult {
        database: database_status,
        queue: queue_status,
        filesystem: fs_status,
        log_file: log_status,
        details,
    }
}

/// Scrub absolute filesystem paths (and any other redaction-pattern values)
/// out of `diagnose.details` through the single Rust redaction boundary.
///
/// `log_path` is the main offender today; walking every value keeps the
/// service honest as new detail keys are added. Book data is counts-only by
/// construction (`total_books` / `missing_files`), so no book path is ever
/// inserted here. This is defense-in-depth: the bundle command also re-applies
/// redaction over every section at assembly.
pub(crate) fn scrub_details_paths(details: &mut HashMap<String, serde_json::Value>) {
    for value in details.values_mut() {
        Logger::redact_json_value(value);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn scrub_details_paths_redacts_absolute_windows_log_path() {
        let mut details: HashMap<String, serde_json::Value> = HashMap::new();
        details.insert("log_size_bytes".to_string(), json!(1234));
        details.insert(
            "log_path".to_string(),
            json!("C:\\Users\\juan\\AppData\\nexo\\app-log.0.jsonl"),
        );

        scrub_details_paths(&mut details);

        assert_eq!(details["log_path"], json!("[REDACTED_PATH]"));
        assert_eq!(details["log_size_bytes"], json!(1234));
    }

    #[test]
    fn scrub_details_paths_redacts_unix_home_log_path() {
        let mut details: HashMap<String, serde_json::Value> = HashMap::new();
        details
            .insert("log_path".to_string(), json!("/home/juan/.local/share/nexo/app-log.0.jsonl"));

        scrub_details_paths(&mut details);

        let path = details["log_path"].as_str().unwrap_or_default();
        assert!(!path.contains("/home/juan"), "path leak: {path}");
    }
}
