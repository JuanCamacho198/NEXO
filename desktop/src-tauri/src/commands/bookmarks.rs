//! Bookmark commands.
//!
//! Errors are `CommandError`: the repository layer already returns a typed
//! `AppError`, so the `{code, message, recoverable}` category now survives to
//! the frontend instead of being flattened into a message string.

use super::{lock_repository, CommandError};
use crate::models::{BookmarkDto, SaveBookmarkInput};
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listBookmarks(
    state: State<'_, AppState>,
    book_id: Option<String>,
) -> Result<Vec<BookmarkDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository.list_bookmarks(book_id.as_deref()).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn saveBookmark(
    state: State<'_, AppState>,
    payload: SaveBookmarkInput,
) -> Result<BookmarkDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository.save_bookmark(payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn deleteBookmark(state: State<'_, AppState>, id: String) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.delete_bookmark(&id).map_err(CommandError::from)
}
