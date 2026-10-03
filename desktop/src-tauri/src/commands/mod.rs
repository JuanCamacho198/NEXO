// Facade cleanup: only camelCase commands remain.
// All snake_case variants and their camelCase aliases have been merged.
// Internal helpers (_internal suffix) kept as snake_case.
//
// Command bodies live in their domain module; this file declares the modules,
// re-exports them so `commands::<name>` keeps resolving for main.rs, and keeps
// the two helpers that are not commands.

pub mod addon_consent;
pub mod addon_fetch;
pub mod addon_registry;
pub mod bookmarks;
pub mod collections;
pub mod devtools;
pub mod diagnostics;
pub mod dictionary;
pub mod discover_cache;
pub mod download;
pub mod epub_reader;
pub mod files;
pub mod highlights;
pub mod library;
pub mod outbox;
pub mod progress;
pub mod reading_stats;
pub mod reading_status;
pub mod search;
pub mod secrets;
pub mod settings;
pub mod storage;

#[allow(unused_imports)]
pub use addon_consent::*;
#[allow(unused_imports)]
pub use addon_fetch::*;
#[allow(unused_imports)]
pub use addon_registry::*;
#[allow(unused_imports)]
pub use bookmarks::*;
#[allow(unused_imports)]
pub use collections::*;
#[allow(unused_imports)]
pub use devtools::*;
#[allow(unused_imports)]
pub use diagnostics::*;
#[allow(unused_imports)]
pub use dictionary::*;
#[allow(unused_imports)]
pub use discover_cache::*;
#[allow(unused_imports)]
pub use download::*;
#[allow(unused_imports)]
pub use epub_reader::*;
#[allow(unused_imports)]
pub use files::*;
#[allow(unused_imports)]
pub use highlights::*;
#[allow(unused_imports)]
pub use library::*;
#[allow(unused_imports)]
pub use outbox::*;
#[allow(unused_imports)]
pub use progress::*;
#[allow(unused_imports)]
pub use reading_stats::*;
#[allow(unused_imports)]
pub use reading_status::*;
#[allow(unused_imports)]
pub use search::*;
#[allow(unused_imports)]
pub use secrets::*;
#[allow(unused_imports)]
pub use settings::*;
#[allow(unused_imports)]
pub use storage::*;

use crate::error::AppError;
use crate::models::{CommandErrorDto, LibraryBookDto, ListLibraryBooksInput};

const LIBRARY_RESPONSE_VERSION: i32 = 1;

// All command functions use camelCase for IPC compatibility with the Frontend.
// #[allow(non_snake_case)] suppresses the Rust convention warning.

fn map_command_error(error: AppError) -> String {
    let dto = match error {
        AppError::InvalidInput(message) => CommandErrorDto::validation(message),
        AppError::Compatibility(message) => CommandErrorDto::compatibility(message),
        AppError::DbConstraint(message) => CommandErrorDto::db_constraint(message),
        AppError::SyncConflict(message) => CommandErrorDto::sync_conflict(message),
        AppError::ImportError(message) => CommandErrorDto::import_error(message),
        AppError::ThumbnailFail(message) => CommandErrorDto::thumbnail_error(message),
        AppError::MigrationFail(message) => CommandErrorDto::migration_fail(message),
        AppError::NotFound(message) => CommandErrorDto::not_found(message),
        other => CommandErrorDto::internal(other.to_string()),
    };

    serde_json::to_string(&dto).unwrap_or_else(|_| {
        "{\"code\":\"INTERNAL_ERROR\",\"message\":\"Command failed\",\"recoverable\":false}"
            .to_string()
    })
}

pub fn list_library_books_internal(
    repository: &crate::repository::LibraryRepository,
    payload: Option<ListLibraryBooksInput>,
) -> Result<Vec<LibraryBookDto>, AppError> {
    if let Some(input) = payload {
        if let Some(version) = input.response_version {
            if version != LIBRARY_RESPONSE_VERSION {
                return Err(AppError::Compatibility(format!(
                    "Unsupported listLibraryBooks responseVersion {} (supported: {})",
                    version, LIBRARY_RESPONSE_VERSION
                )));
            }
        }
    }

    if !repository.has_desktop_parity_schema()? {
        // Schema not ready - return empty list, don't fail
        return Ok(vec![]);
    }

    repository.list_library_books()
}
