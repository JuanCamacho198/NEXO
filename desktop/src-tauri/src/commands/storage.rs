use tauri::{AppHandle, Manager, State};

use crate::state::AppState;

use super::map_command_error;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getStorageStats(
    app: AppHandle,
    _state: State<'_, AppState>,
) -> Result<crate::services::storage_stats::StorageStats, String> {
    let db_path = app.path().app_data_dir().map_err(|e| format!("{}", e))?.join("nexo.db");
    let app_data_dir = app.path().app_data_dir().map_err(|e| format!("{}", e))?;
    crate::services::storage_stats::compute_storage_stats(&app_data_dir, &db_path)
        .map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn clearCache(
    app: AppHandle,
    state: State<'_, AppState>,
    kind: String,
    deep: Option<bool>,
) -> Result<crate::services::storage_stats::ClearCacheResult, String> {
    let app_data_dir = app.path().app_data_dir().map_err(|e| format!("{}", e))?;
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let conn = repository.connection();
    let deep_flag = deep.unwrap_or(false);
    crate::services::storage_stats::clear_cache(&app_data_dir, &kind, deep_flag, conn)
        .map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getPerBookSizes(
    state: State<'_, AppState>,
) -> Result<Vec<crate::services::storage_stats::PerBookSize>, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let conn = repository.connection();
    crate::services::storage_stats::get_per_book_sizes(conn).map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn deleteBookData(
    app: AppHandle,
    state: State<'_, AppState>,
    book_id: String,
) -> Result<(), String> {
    let app_data_dir = app.path().app_data_dir().map_err(|e| format!("{}", e))?;
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let conn = repository.connection();
    crate::services::storage_stats::delete_book_data(conn, &app_data_dir, &book_id)
        .map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn cleanupOrphans(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<serde_json::Value, String> {
    let app_data_dir = app.path().app_data_dir().map_err(|e| format!("{}", e))?;
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let conn = repository.connection();
    let removed = crate::services::storage_stats::cleanup_orphans(conn, &app_data_dir)
        .map_err(map_command_error)?;
    Ok(serde_json::json!({ "removed": removed }))
}
