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
