//! App-setting commands (`app_settings` key/value store).
//!
//! Errors are `CommandError`: the JSON `{code, message, recoverable}` text the
//! frontend's `parseCommandError` already parses. A poisoned repository mutex is
//! reported as `INTERNAL_ERROR` through `lock_repository`.

use super::{lock_repository, CommandError};
use crate::models::AppSettingDto;
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getSettings(state: State<'_, AppState>) -> Result<Vec<AppSettingDto>, CommandError> {
    let repository = lock_repository(&state)?;
    // Return empty if schema not ready
    if !repository.has_desktop_parity_schema().unwrap_or(true) {
        return Ok(vec![]);
    }
    repository.get_settings().map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn upsertSettings(
    state: State<'_, AppState>,
    settings: Vec<AppSettingDto>,
) -> Result<(), CommandError> {
    let mut repository = lock_repository(&state)?;
    // No-op if schema not ready
    if !repository.has_desktop_parity_schema().unwrap_or(true) {
        return Ok(());
    }
    repository.upsert_settings(settings).map_err(CommandError::from)
}
