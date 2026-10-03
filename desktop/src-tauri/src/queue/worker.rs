use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use rusqlite::Connection;

use crate::queue::handlers::LocalJobDispatcher;
use crate::queue::repository::QueueRepository;
use crate::queue::types::WorkerConfig;
use crate::services::job_service::JobDispatcher;

pub struct QueueWorkerRuntime {
    shutdown: Arc<AtomicBool>,
    join_handle: Option<JoinHandle<()>>,
}

impl QueueWorkerRuntime {
    pub fn start(db_path: PathBuf, config: WorkerConfig, app_data_dir: PathBuf) -> Self {
        let shutdown = Arc::new(AtomicBool::new(false));
        let thread_shutdown = shutdown.clone();

        let join_handle = thread::Builder::new()
            .name("nexo-queue-worker".to_string())
            .spawn(move || {
                let dispatcher = LocalJobDispatcher::new(app_data_dir);

                while !thread_shutdown.load(Ordering::Relaxed) {
                    let mut repository = match open_queue_repository(&db_path) {
                        Ok(repo) => repo,
                        Err(error) => {
                            eprintln!("queue worker: failed to open repository: {}", error);
                            thread::sleep(Duration::from_secs(2));
                            continue;
                        }
                    };

                    let _ = repository.recover_expired_running_jobs();

                    while !thread_shutdown.load(Ordering::Relaxed) {
                        if let Err(error) = process_tick(&mut repository, &dispatcher, &config) {
                            eprintln!("queue worker: processing tick failed: {}", error);
                            break;
                        }

                        thread::sleep(Duration::from_millis(config.poll_interval_ms));
                    }
                }
            })
            .ok();

        Self { shutdown, join_handle }
    }

    pub fn stop(&mut self) {
        self.shutdown.store(true, Ordering::Relaxed);
        if let Some(handle) = self.join_handle.take() {
            let _ = handle.join();
        }
    }
}

fn open_queue_repository(db_path: &PathBuf) -> Result<QueueRepository, rusqlite::Error> {
    let connection = Connection::open(db_path)?;
    connection.execute_batch("PRAGMA foreign_keys = ON;")?;
    Ok(QueueRepository::new(connection))
}

fn process_tick(
    repository: &mut QueueRepository,
    dispatcher: &impl JobDispatcher,
    config: &WorkerConfig,
) -> crate::error::AppResult<()> {
    let jobs = repository.claim_due_jobs(config.batch_size, config.lease_seconds)?;

    for job in jobs {
        let outcome = dispatcher.dispatch(&job);
        repository.apply_outcome(&job.id, outcome)?;
    }

    Ok(())
}
