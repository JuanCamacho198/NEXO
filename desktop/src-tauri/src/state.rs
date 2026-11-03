use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use crate::logger::Logger;
use crate::queue::repository::QueueRepository;
use crate::queue::types::WorkerConfig;
use crate::queue::worker::QueueWorkerRuntime;
use crate::repository::LibraryRepository;
use crate::services::job_service::JobService;

pub struct AppState {
    pub repository: Arc<Mutex<LibraryRepository>>,
    pub queue_repository: Arc<Mutex<QueueRepository>>,
    pub job_service: Arc<JobService>,
    pub queue_worker: Arc<Mutex<Option<QueueWorkerRuntime>>>,
    pub logger: Arc<Mutex<Logger>>,
    pub active_user: ActiveUser,
}

/// Single source of truth for which account is active on the desktop.
///
/// A missing owner ("anonymous") is represented deliberately: `require_user_id`
/// returns the empty string, which as an SQL filter (`user_id = ''`) matches
/// zero rows, because no persisted row carries an empty owner. That deny-all
/// behavior is intentional: with no active account, nothing is visible — never
/// every account's data.
pub struct ActiveUser {
    user_id: Mutex<Option<String>>,
}

const LOCAL_USER_PREFIX: &str = "local-";

impl ActiveUser {
    pub fn new() -> Self {
        Self { user_id: Mutex::new(None) }
    }

    pub fn set(&self, user_id: Option<String>) {
        let mut guard = self.user_id.lock().unwrap_or_else(|e| e.into_inner());
        *guard = Self::normalize(user_id);
    }

    pub fn clear(&self) {
        self.set(None);
    }

    pub fn require_user_id(&self) -> String {
        self.current_user_id().unwrap_or_default()
    }

    pub fn current_user_id(&self) -> Option<String> {
        let guard = self.user_id.lock().unwrap_or_else(|e| e.into_inner());
        guard.clone()
    }

    pub fn is_anonymous(&self) -> bool {
        let guard = self.user_id.lock().unwrap_or_else(|e| e.into_inner());
        guard.is_none()
    }

    pub fn is_local_user_id(user_id: &str) -> bool {
        user_id.starts_with(LOCAL_USER_PREFIX)
    }

    fn normalize(user_id: Option<String>) -> Option<String> {
        let candidate = user_id?;
        if candidate.trim().is_empty() || Self::is_local_user_id(&candidate) {
            return None;
        }
        Some(candidate)
    }
}

impl Default for ActiveUser {
    fn default() -> Self {
        Self::new()
    }
}

impl AppState {
    pub fn new(
        repository: LibraryRepository,
        queue_repository: QueueRepository,
        app_data_dir: PathBuf,
        db_path: PathBuf,
    ) -> Self {
        let queue_repository = Arc::new(Mutex::new(queue_repository));
        let job_service = Arc::new(JobService::new(queue_repository.clone()));
        let worker =
            QueueWorkerRuntime::start(db_path, WorkerConfig::default(), app_data_dir.clone());

        Self {
            repository: Arc::new(Mutex::new(repository)),
            queue_repository,
            job_service,
            queue_worker: Arc::new(Mutex::new(Some(worker))),
            logger: Arc::new(Mutex::new(Logger::new(app_data_dir))),
            active_user: ActiveUser::new(),
        }
    }

    pub fn shutdown_queue_worker(&self) {
        if let Ok(mut worker) = self.queue_worker.lock() {
            if let Some(worker_runtime) = worker.as_mut() {
                worker_runtime.stop();
            }
            *worker = None;
        }
    }
}

impl Drop for AppState {
    fn drop(&mut self) {
        self.shutdown_queue_worker();
    }
}

#[cfg(test)]
mod tests {
    use super::ActiveUser;

    #[test]
    fn active_user_starts_anonymous() {
        let active_user = ActiveUser::new();

        assert!(active_user.current_user_id().is_none());
        assert_eq!(active_user.require_user_id(), "");
        assert!(active_user.is_anonymous());
    }

    #[test]
    fn active_user_set_stores_owner() {
        let active_user = ActiveUser::new();
        active_user.set(Some("abc-123".to_string()));

        assert_eq!(active_user.current_user_id().as_deref(), Some("abc-123"));
        assert_eq!(active_user.require_user_id(), "abc-123");
        assert!(!active_user.is_anonymous());
    }

    #[test]
    fn active_user_set_empty_string_is_anonymous() {
        let active_user = ActiveUser::new();
        active_user.set(Some("".to_string()));

        assert!(active_user.is_anonymous());
        assert_eq!(active_user.require_user_id(), "");
    }

    #[test]
    fn active_user_set_whitespace_is_anonymous() {
        let active_user = ActiveUser::new();
        active_user.set(Some("   ".to_string()));

        assert!(active_user.is_anonymous());
        assert!(active_user.current_user_id().is_none());
    }

    #[test]
    fn active_user_set_local_prefix_is_anonymous() {
        let active_user = ActiveUser::new();
        active_user.set(Some("local-abc".to_string()));

        assert!(active_user.is_anonymous());
        assert_eq!(active_user.require_user_id(), "");
    }

    #[test]
    fn active_user_set_uppercase_local_prefix_keeps_owner() {
        let active_user = ActiveUser::new();
        active_user.set(Some("LOCAL-ABC".to_string()));

        assert!(!active_user.is_anonymous());
        assert_eq!(active_user.require_user_id(), "LOCAL-ABC");
    }

    #[test]
    fn active_user_clear_removes_owner() {
        let active_user = ActiveUser::new();
        active_user.set(Some("abc-123".to_string()));
        active_user.clear();

        assert!(active_user.is_anonymous());
        assert!(active_user.current_user_id().is_none());
    }

    #[test]
    fn is_local_user_id_detects_prefix_case_sensitively() {
        assert!(ActiveUser::is_local_user_id("local-x"));
        assert!(!ActiveUser::is_local_user_id("LOCAL-x"));
        assert!(!ActiveUser::is_local_user_id("x"));
    }
}
