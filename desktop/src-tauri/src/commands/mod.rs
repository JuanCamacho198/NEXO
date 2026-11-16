// Facade cleanup: only camelCase commands remain.
// All snake_case variants and their camelCase aliases have been merged.
// Internal helpers (_internal suffix) kept as snake_case.
//
// Command bodies live in their domain module; this file declares the modules,
// re-exports them so `commands::<name>` keeps resolving for main.rs, and keeps
// the two helpers that are not commands.
//
// ERROR TYPE MIGRATION (T8, audit item #06) — INCREMENTAL, NOT FINISHED.
//
// `CommandError` (below) is the target shape: it wraps the `{code, message,
// recoverable}` JSON text the frontend's `parseCommandError` already parses, and
// it cannot be built from an arbitrary string, so `format!("{}", e)` can no
// longer flatten a typed `AppError` on the way out.
//
// Migrated so far (their signatures return `CommandError`):
//   notifications.rs, settings.rs, collections.rs
//
// Still `Result<T, String>` and still building the message by hand:
//   addon_*, bookmarks, catalog_fetch, define, diagnostics, dictionary,
//   discover_cache, download, highlights, library, outbox, progress,
//   reading_stats, reading_status, search, secrets, session, storage,
//   translate, translation, update_feed, wiktionary
//
// For those modules `map_command_error` keeps working unchanged and the wire
// format is identical, so nothing is broken — the category is just not enforced
// by the type. Convert one module per commit, with its test, rather than in one
// sweep: 120 command signatures is a 3000-line diff that no reviewer can check.
// The mechanical recipe:
//   1. `Result<T, String>` -> `Result<T, CommandError>`.
//   2. `.map_err(map_command_error)` -> `.map_err(CommandError::from)`.
//   3. `state.repository.lock().map_err(|e| format!("{}", e))?` ->
//      `lock_repository(&state)?`.
//   4. If a helper still needs the string, it takes `.payload()`.
//
// When the last module is converted, `map_command_error` becomes private to
// this file and `CommandError` becomes the only command error type.

pub mod addon_consent;
pub mod addon_fetch;
pub mod addon_registry;
pub mod bookmarks;
pub mod catalog_fetch;
pub mod collections;
pub mod confine;
pub mod define;
pub mod devtools;
pub mod diagnostics;
pub mod dictionary;
pub mod discover_cache;
pub mod download;
pub mod epub_reader;
pub mod files;
pub mod highlights;
pub mod library;
pub mod notifications;
pub mod outbox;
pub mod progress;
pub mod reading_stats;
pub mod reading_status;
pub mod search;
pub mod secrets;
pub mod session;
pub mod settings;
pub mod storage;
pub mod translate;
pub mod translation;
pub mod update_feed;
pub mod wiktionary;

#[allow(unused_imports)]
pub use addon_consent::*;
#[allow(unused_imports)]
pub use addon_fetch::*;
#[allow(unused_imports)]
pub use addon_registry::*;
#[allow(unused_imports)]
pub use bookmarks::*;
#[allow(unused_imports)]
pub use catalog_fetch::*;
#[allow(unused_imports)]
pub use collections::*;
#[allow(unused_imports)]
pub use define::*;
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
pub use notifications::*;
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
pub use session::*;
#[allow(unused_imports)]
pub use settings::*;
#[allow(unused_imports)]
pub use storage::*;
#[allow(unused_imports)]
pub use translate::*;
#[allow(unused_imports)]
pub use translation::*;
#[allow(unused_imports)]
pub use update_feed::*;
#[allow(unused_imports)]
pub use wiktionary::*;

use crate::error::AppError;
use crate::models::{CommandErrorDto, LibraryBookDto, ListLibraryBooksInput};
use serde::Serialize;
use std::sync::MutexGuard;

#[cfg(test)]
mod command_error_tests;

const LIBRARY_RESPONSE_VERSION: i32 = 1;

// All command functions use camelCase for IPC compatibility with the Frontend.
// #[allow(non_snake_case)] suppresses the Rust convention warning.

/// The error type every command in this tree returns.
///
/// It is a **newtype around the JSON string** the frontend already parses, not a
/// second DTO. `map_command_error` builds `{code, message, recoverable}` and
/// `parseCommandError` (`src/lib/shared/api/tauriClient.ts`) reads exactly that
/// text, so the payload on the wire is unchanged by this type.
///
/// Why the whole point is the *type* and not the format: `Result<_, String>` is
/// a template for loss. `format!("{}", e)` on an `AppError` compiles, throws away
/// the variant, and the frontend receives text it cannot classify. `CommandError`
/// is not constructible from a string, so that mistake stops being expressible;
/// the only ways in are a typed `AppError` or an explicitly-labelled `internal`.
///
/// `Serialize` writes the string as-is, which keeps Tauri's `InvokeError` payload
/// byte-identical to the `String` version (`serde_json::to_value` on a `String`
/// is a JSON string, and `format_result` sends it as the rejected promise value).
#[derive(Debug, Serialize)]
pub struct CommandError(String);

impl CommandError {
    /// The wire payload, for tests and for the rare caller that inspects it.
    pub fn payload(&self) -> &str {
        &self.0
    }

    /// A failure in command plumbing, reported as `INTERNAL_ERROR` /
    /// not recoverable. Replaces `map_err(|e| format!("{}", e))` on a lock.
    pub fn internal(message: impl std::fmt::Display) -> Self {
        Self(map_command_error(AppError::Internal(message.to_string())))
    }
}

impl std::fmt::Display for CommandError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for CommandError {}

impl From<AppError> for CommandError {
    fn from(error: AppError) -> Self {
        Self(map_command_error(error))
    }
}

/// Take the repository lock, mapping a poisoned mutex to a typed
/// `INTERNAL_ERROR` instead of `Mutex`'s `Display` text.
pub fn lock_repository(
    state: &crate::state::AppState,
) -> Result<MutexGuard<'_, crate::repository::LibraryRepository>, CommandError> {
    state.repository.lock().map_err(|_| CommandError::internal("repository lock is poisoned"))
}

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
        AppError::Internal(message) => CommandErrorDto::internal(message),
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
