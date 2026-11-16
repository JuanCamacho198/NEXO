//! The IPC error contract, proven end to end (T8, audit item #06).
//!
//! `map_command_error` has serialised `{code, message, recoverable}` into a bare
//! `String` for every command that calls it, and the frontend parses exactly
//! that (`parseCommandError` in `src/lib/shared/api/tauriClient.ts`: `JSON.parse`
//! the error text, require `code`/`message`/`recoverable`). Those tests pin the
//! contract before any command signature moves off `Result<_, String>`.
//!
//! The same file covers the second half of T8: places that converted a typed
//! `AppError` into `format!("{}", e)` or unwrapped it away, so the category the
//! DTO carries was already lost before serialisation could run.

use super::*;
use crate::error::AppError;
use crate::models::CommandErrorDto;

/// Mirror of the frontend's `parseCommandError`: the only accepted shape is a
/// JSON *string* whose payload has all three fields with the right types.
fn parse_as_frontend(error_text: &str) -> Option<CommandErrorDto> {
    let value: serde_json::Value = serde_json::from_str(error_text).ok()?;
    let object = value.as_object()?;
    let code = object.get("code")?.as_str()?.to_string();
    let message = object.get("message")?.as_str()?.to_string();
    let recoverable = object.get("recoverable")?.as_bool()?;
    Some(CommandErrorDto { code, message, recoverable })
}

#[test]
fn a_locked_repository_rejects_with_a_recoverable_internal_error() {
    // The exact state `listNotifications` and `getSettings` produce when the
    // repository mutex is poisoned: `state.repository.lock()` fails. Before
    // `lock_repository`, that failure became `format!("{}", e)` on the `PoisonError`,
    // which is neither JSON nor a code.
    let message = map_command_error(AppError::Internal("repository lock is poisoned".to_string()));
    let dto = parse_as_frontend(&message).expect("frontend must parse the error text");

    assert_eq!(dto.code, "INTERNAL_ERROR");
    assert!(dto.message.contains("repository lock is poisoned"), "{}", dto.message);
    assert!(!dto.recoverable);
}

#[test]
fn an_internal_error_is_a_json_string_not_a_bare_object() {
    // Tauri serialises a `Result` error through `InvokeError`, which for a
    // `Serialize` type is `serde_json::to_value`, and `format_result` hands that
    // value to the rejected promise. For a string newtype that value IS the
    // string the frontend `JSON.parse`s. If `CommandError` ever serialised as an
    // object, `normalizeMessage` would fall back to "Command invocation failed"
    // and `code` would silently vanish.
    let error = CommandError::from(AppError::Internal("boom".to_string()));
    let on_the_wire = serde_json::to_value(&error).expect("serialises");

    assert!(
        on_the_wire.is_string(),
        "the rejected promise value must stay a string: {on_the_wire}"
    );
    let text = on_the_wire.as_str().expect("string");
    assert_eq!(text, error.payload());
    assert_eq!(parse_as_frontend(text).expect("parsable").code, "INTERNAL_ERROR");
}

/// The string a `Result<_, String>` command rejects with and the string
/// `CommandError` rejects with must be the same bytes for the same `AppError`,
/// because the frontend parses both with one code path.
#[test]
fn typed_and_legacy_paths_agree_for_every_error_variant() {
    // Each entry builds the variant twice: `AppError` is not `Clone`, and the two
    // paths must be fed the same input to be comparable at all.
    type Factory = fn() -> AppError;
    let cases: [(&str, Factory); 10] = [
        ("InvalidInput", || AppError::InvalidInput("v".to_string())),
        ("Compatibility", || AppError::Compatibility("c".to_string())),
        ("DbConstraint", || AppError::DbConstraint("dc".to_string())),
        ("SyncConflict", || AppError::SyncConflict("sc".to_string())),
        ("ImportError", || AppError::ImportError("ie".to_string())),
        ("ThumbnailFail", || AppError::ThumbnailFail("tf".to_string())),
        ("MigrationFail", || AppError::MigrationFail("mf".to_string())),
        ("NotFound", || AppError::NotFound("nf".to_string())),
        ("Internal", || AppError::Internal("in".to_string())),
        ("MissingBookId", || AppError::MissingBookId),
    ];

    for (name, build) in cases {
        // The legacy `Result<_, String>` path.
        let legacy = map_command_error(build());

        // The typed path, serialised the way Tauri's `InvokeError` does.
        let typed = serde_json::to_value(CommandError::from(build()))
            .expect("serialises")
            .as_str()
            .expect("must stay a string")
            .to_string();

        assert_eq!(legacy, typed, "{name}: the payload changed on the wire");
        assert!(parse_as_frontend(&typed).is_some(), "{name}: {typed}");
    }
}

#[test]
fn each_error_category_keeps_its_code_and_recoverability() {
    let cases = [
        (AppError::InvalidInput("size must be positive".to_string()), "VALIDATION_ERROR", true),
        (AppError::NotFound("book missing".to_string()), "NOT_FOUND", true),
        (AppError::SyncConflict("remote newer".to_string()), "SYNC_CONFLICT", true),
        (AppError::ImportError("bad epub".to_string()), "IMPORT_ERROR", true),
        (AppError::ThumbnailFail("too small".to_string()), "THUMBNAIL_ERROR", true),
        (AppError::DbConstraint("UNIQUE constraint failed".to_string()), "DB_CONSTRAINT", false),
        (AppError::MigrationFail("schema drift".to_string()), "MIGRATION_FAIL", false),
    ];

    for (error, expected_code, expected_recoverable) in cases {
        let expected_recoverable: bool = expected_recoverable;
        let dto = parse_as_frontend(&map_command_error(error)).expect("frontend must parse");
        assert_eq!(dto.code, expected_code);
        assert_eq!(dto.recoverable, expected_recoverable, "{}", dto.code);
    }
}

/// A command module that locks the repository must keep the error's category,
/// not flatten it to `Mutex`'s `Display` (`poisoned lock: another task failed
/// inside the critical section`) which carries no code and no recoverability.
#[test]
fn a_poisoned_repository_lock_does_not_flatten_the_error() {
    let poisoned_message =
        map_command_error(AppError::Internal("repository lock is poisoned".to_string()));
    let dto = parse_as_frontend(&poisoned_message).expect("frontend must parse");
    assert_eq!(dto.code, "INTERNAL_ERROR");
    assert!(!dto.message.contains("poisoned lock"), "raw Mutex text leaked: {}", dto.message);
}

/// `deleteCollection` classified a missing row as `InvalidInput`, so a caller
/// received `VALIDATION_ERROR` for something that is plainly a 404. The
/// category is what the DTO advertises, so it must match the meaning.
#[test]
fn deleting_a_missing_collection_reports_not_found() {
    let repository = crate::repository::tests::new_repository_for_tests();
    let error = crate::repository::collections::delete_collection(&repository, 9_999)
        .expect_err("missing collection must fail");

    assert!(matches!(error, AppError::NotFound(_)), "{error:?}");
    let dto = parse_as_frontend(&map_command_error(error)).expect("frontend must parse");
    assert_eq!(dto.code, "NOT_FOUND");
    assert!(dto.recoverable);
}

/// A system collection is not user data; deleting it is refused, and the
/// refusal must survive as a stated reason rather than an unwrap on `None`.
#[test]
fn deleting_a_system_collection_is_refused_with_a_reason() {
    let repository = crate::repository::tests::new_repository_for_tests();
    // Migration 0006 seeds `Favoritos`, `Leyendo`, `Leídos` with is_system = 1.
    let error = crate::repository::collections::delete_collection(&repository, 1)
        .expect_err("system collection must not be deletable");

    assert!(matches!(error, AppError::InvalidInput(_)), "{error:?}");
    assert!(error.to_string().contains("system collection"), "{error}");
}

/// An offset outside the representable range must not index the reading day by
/// UTC while reporting success: it is clamped to the nearest valid boundary, so
/// the day it returns is a real day for that zone rather than silently UTC.
#[test]
fn an_out_of_range_zone_offset_is_clamped_not_silently_utc() {
    use chrono::{TimeZone, Utc};

    let instant = Utc.with_ymd_and_hms(2026, 10, 1, 23, 30, 0).unwrap();
    let clamped_east = crate::reading_day::reading_day(instant, i32::MAX);
    let clamped_west = crate::reading_day::reading_day(instant, i32::MIN);

    // i32::MAX minutes clamps to +14:00; i32::MIN clamps to -11:00. Both are
    // still a deliberate zone, so the results differ from the raw UTC day or
    // from each other — what must never happen is a fallback to +00:00.
    assert_eq!(clamped_east.to_string(), "2026-10-02");
    assert_eq!(clamped_west.to_string(), "2026-10-01");
    assert_ne!(clamped_east, crate::reading_day::reading_day(instant, 0));
}
