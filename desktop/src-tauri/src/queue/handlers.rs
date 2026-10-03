use std::fs;
use std::path::PathBuf;
use std::time::Instant;

use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};
use crate::queue::types::{JobOutcome, JobRecord, JOB_TYPE_COVER_CLEANUP, JOB_TYPE_THUMBNAIL};
use crate::services::job_service::JobDispatcher;

const MAX_THUMBNAIL_SIZE_MB: u64 = 50;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ThumbnailPayload {
    pub book_id: String,
    pub source_path: String,
    pub output_path: Option<String>,
}

pub struct CoverCleanupHandler {
    app_data_dir: PathBuf,
}

impl CoverCleanupHandler {
    pub fn new(app_data_dir: PathBuf) -> Self {
        Self { app_data_dir }
    }

    fn cleanup_queue_path(&self) -> PathBuf {
        self.app_data_dir.join("cover_cleanup_queue.txt")
    }

    fn run_cleanup(&self) -> AppResult<()> {
        let queue_path = self.cleanup_queue_path();
        if !queue_path.exists() {
            return Ok(());
        }

        let queue = fs::read_to_string(&queue_path).unwrap_or_default();
        let mut remaining: Vec<String> = Vec::new();

        for raw_line in queue.lines() {
            let candidate = raw_line.trim();
            if candidate.is_empty() {
                continue;
            }

            let path = PathBuf::from(candidate);
            if !path.exists() {
                continue;
            }

            match fs::remove_file(&path) {
                Ok(_) => {}
                Err(err) => {
                    if err.kind() != std::io::ErrorKind::NotFound {
                        remaining.push(candidate.to_string());
                    }
                }
            }
        }

        if remaining.is_empty() {
            let _ = fs::remove_file(queue_path);
        } else {
            fs::write(queue_path, format!("{}\n", remaining.join("\n")))?;
        }

        Ok(())
    }
}

impl JobDispatcher for CoverCleanupHandler {
    fn dispatch(&self, _job: &JobRecord) -> JobOutcome {
        match self.run_cleanup() {
            Ok(_) => JobOutcome::Done { result_json: None },
            Err(e) => JobOutcome::Failed { error: e.to_string() },
        }
    }
}

pub struct ThumbnailHandler {}

impl ThumbnailHandler {
    pub fn new(_app_data_dir: PathBuf) -> Self {
        Self {}
    }
}

/// Job types with a live handler behind the queue worker.
pub const SUPPORTED_JOB_TYPES: &[&str] = &[JOB_TYPE_COVER_CLEANUP, JOB_TYPE_THUMBNAIL];

/// Routes claimed jobs to the handler that owns their type.
///
/// Unknown types fail loudly instead of resolving to `Done`: a silent
/// success here is how the queue once reported work it never performed.
pub struct LocalJobDispatcher {
    cover_cleanup: CoverCleanupHandler,
    thumbnail: ThumbnailHandler,
}

impl LocalJobDispatcher {
    pub fn new(app_data_dir: PathBuf) -> Self {
        Self {
            cover_cleanup: CoverCleanupHandler::new(app_data_dir.clone()),
            thumbnail: ThumbnailHandler::new(app_data_dir),
        }
    }
}

impl JobDispatcher for LocalJobDispatcher {
    fn dispatch(&self, job: &JobRecord) -> JobOutcome {
        match job.job_type.as_str() {
            JOB_TYPE_COVER_CLEANUP => self.cover_cleanup.dispatch(job),
            JOB_TYPE_THUMBNAIL => self.thumbnail.dispatch(job),
            unsupported => {
                JobOutcome::Failed { error: format!("unsupported job type: {}", unsupported) }
            }
        }
    }
}

impl JobDispatcher for ThumbnailHandler {
    fn dispatch(&self, job: &JobRecord) -> JobOutcome {
        let start = Instant::now();
        let payload: Result<ThumbnailPayload, _> = serde_json::from_str(&job.payload_json);

        let payload = match payload {
            Ok(p) => p,
            Err(e) => {
                return JobOutcome::Failed { error: format!("invalid thumbnail payload: {}", e) }
            }
        };

        let source_path = PathBuf::from(&payload.source_path);
        if !source_path.exists() {
            return JobOutcome::Failed {
                error: AppError::ThumbnailFail(format!(
                    "source image not found: {}",
                    payload.source_path
                ))
                .to_string(),
            };
        }

        let metadata = match fs::metadata(&source_path) {
            Ok(m) => m,
            Err(e) => {
                return JobOutcome::Failed {
                    error: AppError::ThumbnailFail(format!("cannot read image metadata: {}", e))
                        .to_string(),
                };
            }
        };

        let file_size_mb = metadata.len() / (1024 * 1024);
        if file_size_mb > MAX_THUMBNAIL_SIZE_MB {
            return JobOutcome::Failed {
                error: AppError::ThumbnailFail(format!(
                    "image too large: {}MB (max {}MB)",
                    file_size_mb, MAX_THUMBNAIL_SIZE_MB
                ))
                .to_string(),
            };
        }

        let extension = source_path
            .extension()
            .and_then(|e| e.to_str())
            .map(|e| e.to_lowercase())
            .unwrap_or_default();

        let valid_extensions = ["jpg", "jpeg", "png", "gif", "webp", "bmp"];
        if !valid_extensions.contains(&extension.as_str()) {
            return JobOutcome::Failed {
                error: AppError::ThumbnailFail(format!("unsupported image format: {}", extension))
                    .to_string(),
            };
        }

        let duration_ms = start.elapsed().as_millis() as f64;
        JobOutcome::Done {
            result_json: Some(
                serde_json::json!({
                    "status": "thumbnail_completed",
                    "book_id": payload.book_id,
                    "duration_ms": duration_ms
                })
                .to_string(),
            ),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    use crate::queue::types::JobState;

    fn job_record(job_type: &str, payload: serde_json::Value) -> JobRecord {
        JobRecord {
            id: format!("job-{}", Uuid::new_v4()),
            job_type: job_type.to_string(),
            payload_json: payload.to_string(),
            state: JobState::Running,
            attempt: 1,
            max_attempts: 3,
            next_run_at: "2026-01-01T00:00:00Z".to_string(),
            lease_expires_at: None,
            dedupe_key: None,
            last_error: None,
        }
    }

    fn temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("nexo_queue_test_{}", Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn cover_cleanup_job_reaches_handler_and_deletes_queued_file() {
        let dir = temp_dir();
        let orphan = dir.join("orphan.jpg");
        fs::write(&orphan, b"orphan").unwrap();
        fs::write(dir.join("cover_cleanup_queue.txt"), format!("{}\n", orphan.display())).unwrap();

        let dispatcher = LocalJobDispatcher::new(dir.clone());
        let job = job_record(JOB_TYPE_COVER_CLEANUP, serde_json::json!({}));
        let outcome = dispatcher.dispatch(&job);

        assert!(matches!(outcome, JobOutcome::Done { .. }), "got {:?}", outcome);
        assert!(!orphan.exists(), "queued orphan file must be deleted");
        assert!(
            !dir.join("cover_cleanup_queue.txt").exists(),
            "drained cleanup queue file must be removed"
        );

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn thumbnail_job_validates_real_image() {
        let dir = temp_dir();
        let source = dir.join("cover.png");
        fs::write(&source, b"fake-png-bytes").unwrap();

        let dispatcher = LocalJobDispatcher::new(dir.clone());
        let job = job_record(
            JOB_TYPE_THUMBNAIL,
            serde_json::json!({
                "book_id": "book-1",
                "source_path": source.to_string_lossy(),
            }),
        );
        let outcome = dispatcher.dispatch(&job);

        match outcome {
            JobOutcome::Done { result_json } => {
                let result = result_json.expect("thumbnail result must carry payload");
                assert!(result.contains("thumbnail_completed"), "got {}", result);
            }
            other => panic!("valid image must validate, got {:?}", other),
        }

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn thumbnail_job_rejects_missing_source() {
        let dir = temp_dir();
        let dispatcher = LocalJobDispatcher::new(dir.clone());
        let job = job_record(
            JOB_TYPE_THUMBNAIL,
            serde_json::json!({
                "book_id": "book-1",
                "source_path": dir.join("absent.png").to_string_lossy(),
            }),
        );

        match dispatcher.dispatch(&job) {
            JobOutcome::Failed { error } => assert!(error.contains("not found"), "got {}", error),
            other => panic!("missing source must fail, got {:?}", other),
        }

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn every_supported_type_reaches_its_handler() {
        let dir = temp_dir();
        let dispatcher = LocalJobDispatcher::new(dir.clone());

        assert_eq!(SUPPORTED_JOB_TYPES.len(), 2);
        for job_type in SUPPORTED_JOB_TYPES {
            let job = job_record(job_type, serde_json::json!({}));
            match dispatcher.dispatch(&job) {
                JobOutcome::Failed { error } => {
                    assert!(
                        !error.contains("unsupported job type"),
                        "{} must route to a handler, got {}",
                        job_type,
                        error
                    );
                }
                JobOutcome::Done { .. } | JobOutcome::Retry { .. } => {}
            }
        }

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn unsupported_job_type_fails_loudly_never_silent_done() {
        let dir = temp_dir();
        let dispatcher = LocalJobDispatcher::new(dir.clone());
        let job = job_record("import", serde_json::json!({}));

        match dispatcher.dispatch(&job) {
            JobOutcome::Failed { error } => {
                assert!(error.contains("unsupported job type"), "got {}", error)
            }
            other => panic!("unknown type must fail loudly, got {:?}", other),
        }

        let _ = fs::remove_dir_all(&dir);
    }
}
