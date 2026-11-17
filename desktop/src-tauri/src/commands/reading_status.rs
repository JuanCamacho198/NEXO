use super::{lock_repository, CommandError};
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn setReadingStatus(
    state: State<'_, AppState>,
    book_id: String,
    status: Option<String>,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.set_reading_status(&book_id, status.as_deref()).map_err(CommandError::from)
}
