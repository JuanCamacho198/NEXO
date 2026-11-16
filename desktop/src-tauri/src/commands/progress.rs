use super::{lock_repository, CommandError};
use crate::models::{
    ReadingProgressDto, ReadingSessionInput, ReadingSessionSavedDto, ReadingStatsSummaryDto,
    SaveProgressInput,
};
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getProgress(
    state: State<'_, AppState>,
    book_id: String,
) -> Result<Option<ReadingProgressDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository.get_progress(&book_id).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn saveProgress(
    state: State<'_, AppState>,
    payload: SaveProgressInput,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.save_progress(payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn upsertProgress(
    state: State<'_, AppState>,
    progress: ReadingProgressDto,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.upsert_progress(progress).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn saveReadingSession(
    state: State<'_, AppState>,
    payload: ReadingSessionInput,
) -> Result<ReadingSessionSavedDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository.save_reading_session(payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getReadingStats(
    state: State<'_, AppState>,
    book_id: Option<String>,
    user_id: String,
) -> Result<ReadingStatsSummaryDto, CommandError> {
    let repository = lock_repository(&state)?;
    // Return default stats if schema not ready
    if !repository.has_desktop_parity_schema().unwrap_or(true) {
        return Ok(ReadingStatsSummaryDto {
            total_minutes_read: 0,
            total_sessions: 0,
            books_started: 0,
            books_completed: 0,
            avg_progress_percentage: 0.0,
        });
    }
    repository.get_reading_stats(book_id.as_deref(), &user_id).map_err(CommandError::from)
}
