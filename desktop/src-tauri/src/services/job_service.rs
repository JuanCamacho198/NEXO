use std::sync::{Arc, Mutex};

use serde_json::Value;
use uuid::Uuid;

use crate::error::AppResult;
use crate::queue::repository::QueueRepository;
use crate::queue::types::{compute_retry_at, JobOutcome, JobRecord, NewJob};

pub trait JobEnqueuer: Send + Sync {
    fn enqueue_json(
        &self,
        job_type: &str,
        payload: Value,
        dedupe_key: Option<String>,
    ) -> AppResult<String>;
}

pub trait JobDispatcher: Send + Sync {
    fn dispatch(&self, job: &JobRecord) -> JobOutcome;
}

#[derive(Clone)]
pub struct JobService {
    queue_repository: Arc<Mutex<QueueRepository>>,
}

impl JobService {
    pub fn new(queue_repository: Arc<Mutex<QueueRepository>>) -> Self {
        Self { queue_repository }
    }

    pub fn finalize_failure(&self, job: &JobRecord, error: String) -> AppResult<JobOutcome> {
        if job.has_remaining_attempts() {
            let retry_at = compute_retry_at(job.attempt + 1);
            return Ok(JobOutcome::Retry { error, retry_at });
        }

        Ok(JobOutcome::Failed { error })
    }
}

impl JobEnqueuer for JobService {
    fn enqueue_json(
        &self,
        job_type: &str,
        payload: Value,
        dedupe_key: Option<String>,
    ) -> AppResult<String> {
        let job_id = Uuid::new_v4().to_string();
        let payload =
            NewJob::now(job_id.clone(), job_type.trim().to_string(), payload, dedupe_key)?;
        let mut repository = self
            .queue_repository
            .lock()
            .map_err(|error| crate::error::AppError::InvalidInput(error.to_string()))?;
        let accepted_id = repository.enqueue(payload)?;
        Ok(accepted_id)
    }
}
