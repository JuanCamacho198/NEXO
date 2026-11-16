use super::{lock_repository, CommandError};
use crate::models::{
    CreateTagInput, HighlightDto, RemoteHighlightRow, SaveHighlightInput, SaveHighlightTagsInput,
    TagDto, UpdateHighlightInput, UpsertRemoteSummary,
};
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn upsertRemoteHighlights(
    state: State<'_, AppState>,
    rows: Vec<RemoteHighlightRow>,
) -> Result<UpsertRemoteSummary, CommandError> {
    let repository = lock_repository(&state)?;
    repository.upsert_remote_highlights(&rows).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listHighlights(
    state: State<'_, AppState>,
    book_id: Option<String>,
) -> Result<Vec<HighlightDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository.list_highlights(book_id.as_deref()).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn saveHighlight(
    state: State<'_, AppState>,
    payload: SaveHighlightInput,
) -> Result<HighlightDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository.save_highlight(payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn deleteHighlight(state: State<'_, AppState>, id: String) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.delete_highlight(&id).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn updateHighlight(
    state: State<'_, AppState>,
    payload: UpdateHighlightInput,
) -> Result<HighlightDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository.update_highlight(payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listTags(state: State<'_, AppState>) -> Result<Vec<TagDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository.list_tags().map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listTagsForHighlight(
    state: State<'_, AppState>,
    highlight_id: String,
) -> Result<Vec<TagDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository.list_tags_for_highlight(&highlight_id).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn createTag(
    state: State<'_, AppState>,
    payload: CreateTagInput,
) -> Result<TagDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository.create_tag(payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn saveHighlightTags(
    state: State<'_, AppState>,
    payload: SaveHighlightTagsInput,
) -> Result<Vec<TagDto>, CommandError> {
    let mut repository = lock_repository(&state)?;
    repository.save_highlight_tags(payload).map_err(CommandError::from)
}
