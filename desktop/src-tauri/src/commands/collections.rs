//! Collection commands.
//!
//! Errors are `CommandError`: the JSON `{code, message, recoverable}` text the
//! frontend's `parseCommandError` already parses. The repository layer already
//! classifies these (`InvalidInput`, `NotFound`), so no `format!("{}", e)` step
//! stands between the category and the DTO any more.

use super::{lock_repository, CommandError};
use crate::models::{BookCollectionInput, CollectionDto, CreateCollectionInput};
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn createCollection(
    state: State<'_, AppState>,
    payload: CreateCollectionInput,
) -> Result<CollectionDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository
        .create_collection(&payload.name, payload.color.as_deref())
        .map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn deleteCollection(state: State<'_, AppState>, id: i64) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.delete_collection(id).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listCollections(state: State<'_, AppState>) -> Result<Vec<CollectionDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository.list_collections().map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn addBookToCollection(
    state: State<'_, AppState>,
    payload: BookCollectionInput,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository
        .add_book_to_collection(&payload.book_id, payload.collection_id)
        .map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn removeBookFromCollection(
    state: State<'_, AppState>,
    payload: BookCollectionInput,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository
        .remove_book_from_collection(&payload.book_id, payload.collection_id)
        .map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getBookCollections(
    state: State<'_, AppState>,
    book_id: String,
) -> Result<Vec<CollectionDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository.get_book_collections(&book_id).map_err(CommandError::from)
}
