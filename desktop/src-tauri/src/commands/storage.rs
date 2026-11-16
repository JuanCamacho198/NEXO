use tauri::{AppHandle, Manager, State};

use crate::state::AppState;

use super::{lock_repository, CommandError};

/// `app.path().app_data_dir()` fails when the platform cannot resolve a data
/// directory at all. That is an installation problem the reader cannot retry
/// away, and it is reported as an internal error rather than as a bare message.
fn app_data_dir(app: &AppHandle) -> Result<std::path::PathBuf, CommandError> {
    app.path().app_data_dir().map_err(CommandError::internal)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getStorageStats(
    app: AppHandle,
    _state: State<'_, AppState>,
) -> Result<crate::services::storage_stats::StorageStats, CommandError> {
    let app_data_dir = app_data_dir(&app)?;
    let db_path = app_data_dir.join("nexo.db");
    crate::services::storage_stats::compute_storage_stats(&app_data_dir, &db_path)
        .map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn clearCache(
    app: AppHandle,
    state: State<'_, AppState>,
    kind: String,
    deep: Option<bool>,
) -> Result<crate::services::storage_stats::ClearCacheResult, CommandError> {
    let app_data_dir = app_data_dir(&app)?;
    let repository = lock_repository(&state)?;
    let conn = repository.connection();
    let deep_flag = deep.unwrap_or(false);
    crate::services::storage_stats::clear_cache(&app_data_dir, &kind, deep_flag, conn)
        .map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getPerBookSizes(
    state: State<'_, AppState>,
) -> Result<Vec<crate::services::storage_stats::PerBookSize>, CommandError> {
    let repository = lock_repository(&state)?;
    let conn = repository.connection();
    crate::services::storage_stats::get_per_book_sizes(conn).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn deleteBookData(
    app: AppHandle,
    state: State<'_, AppState>,
    book_id: String,
) -> Result<(), CommandError> {
    let app_data_dir = app_data_dir(&app)?;
    let repository = lock_repository(&state)?;
    let conn = repository.connection();
    crate::services::storage_stats::delete_book_data(conn, &app_data_dir, &book_id)
        .map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn cleanupOrphans(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<serde_json::Value, CommandError> {
    let app_data_dir = app_data_dir(&app)?;
    let repository = lock_repository(&state)?;
    let conn = repository.connection();
    let removed = crate::services::storage_stats::cleanup_orphans(conn, &app_data_dir)
        .map_err(CommandError::from)?;
    Ok(serde_json::json!({ "removed": removed }))
}
