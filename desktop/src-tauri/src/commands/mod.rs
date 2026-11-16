// Facade cleanup: only camelCase commands remain.
// All snake_case variants and their camelCase aliases have been merged.
// Internal helpers (_internal suffix) kept as snake_case.
//
// Command bodies live in their domain module; this file declares the modules,
// re-exports them so `commands::<name>` keeps resolving for main.rs, and keeps
// the two helpers that are not commands.
//
// ERROR TYPE MIGRATION (T8, audit item #06) — COMPLETE for every command
// module, with three deliberate exceptions named below.
//
// `CommandError` (below) is the shape: it wraps the `{code, message,
// recoverable}` JSON text the frontend's `parseCommandError` already parses, and
// it cannot be built from an arbitrary string, so `format!("{}", e)` can no
// longer flatten a typed `AppError` on the way out. Every command signature in
// this tree returns `Result<T, CommandError>`; `map_command_error` is private to
// this file and is only reached through `CommandError::from`.
//
// Deliberately still `Result<T, String>` — NOT migrated, on purpose:
//   files.rs, confine.rs, epub_reader.rs
//     Path-guard surface added by 62e54d17. Their errors are a message for a
//     user who just lost a file operation, not a category the frontend can act
//     on differently. Converting them is a separate decision, not an oversight.
// Private helpers that still take/return `String` (each says so at its
// definition, and none is a command): `report_ownerless_outbox_rows`
// (outbox.rs), `redact_section` / `assemble_bundle` (diagnostics.rs),
// `fetch_wikitext` (define.rs).
//
// The mechanical recipe, kept for any new module:
//   1. `Result<T, String>` -> `Result<T, CommandError>`.
//   2. `.map_err(map_command_error)` -> `.map_err(CommandError::from)`.
//   3. `state.repository.lock().map_err(|e| format!("{}", e))?` ->
//      `lock_repository(&state)?`.
//   4. A module that classifies its own failures (`TRANSLATE_*`,
//      `BOOK_DOWNLOAD_*`, `ADDON_FETCH_*`, `CATALOG_FETCH_*`,
//      `UPDATE_FEED_*`) passes its code to `CommandError::coded` instead of
//      letting `Display` flatten it into prose.
//   5. If a helper still needs the string, it takes `.payload()`.

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

    /// A failure that carries a domain code of its own, for a module whose
    /// transport layer already classifies its errors (`TRANSLATE_*`,
    /// `BOOK_DOWNLOAD_*`, `ADDON_FETCH_*`) and whose frontend consumer branches
    /// on that code. The code travels instead of being flattened into the
    /// message; `recoverable` stays false because these are service conditions,
    /// not retryable input problems.
    pub fn coded(code: &str, message: impl Into<String>) -> Self {
        Self(CommandErrorDto::coded(code, message).into_wire_format())
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

    dto.into_wire_format()
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
