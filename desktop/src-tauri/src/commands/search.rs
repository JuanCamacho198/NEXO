use super::{lock_repository, CommandError};
use crate::models::{IndexBookTextInput, SearchBookTextInput, SearchBookTextResponse};
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn indexBookText(
    state: State<'_, AppState>,
    payload: IndexBookTextInput,
) -> Result<(), CommandError> {
    let mut repository = lock_repository(&state)?;
    repository.index_book_text(payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn searchBookText(
    state: State<'_, AppState>,
    payload: SearchBookTextInput,
) -> Result<SearchBookTextResponse, CommandError> {
    let repository = lock_repository(&state)?;
    repository.search_book_text(payload).map_err(CommandError::from)
}
