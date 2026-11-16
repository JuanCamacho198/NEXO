use super::{list_library_books_internal, lock_repository, CommandError};
use crate::models::{
    BookDeleteInput, BookDto, BookImportInput, HideBookInput, LibraryBookDto,
    ListLibraryBooksInput, ScanFolderResultDto, UpsertBookCoverInput,
};
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listBooks(state: State<'_, AppState>) -> Result<Vec<BookDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository.list_books().map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn upsertBook(state: State<'_, AppState>, book: BookDto) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.upsert_book(book).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listLibraryBooks(
    state: State<'_, AppState>,
    payload: Option<ListLibraryBooksInput>,
) -> Result<Vec<LibraryBookDto>, CommandError> {
    let repository = lock_repository(&state)?;
    list_library_books_internal(&repository, payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn scanFolder(
    state: State<'_, AppState>,
    path: String,
) -> Result<ScanFolderResultDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository.scan_folder(&path).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub async fn importBook(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    input: BookImportInput,
) -> Result<BookDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository.import_book(app, input).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn deleteBook(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    payload: BookDeleteInput,
) -> Result<(), CommandError> {
    let mut repository = lock_repository(&state)?;
    repository.delete_book(app, payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn hideBookFromLibrary(
    state: State<'_, AppState>,
    payload: HideBookInput,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.hide_book_from_library(&payload.book_id).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn upsertBookCover(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    payload: UpsertBookCoverInput,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository
        .upsert_book_cover_from_bytes(
            &app,
            &payload.book_id,
            &payload.data,
            payload.mime_type.as_deref(),
        )
        .map_err(CommandError::from)?;
    Ok(())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn deleteBookCover(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    book_id: String,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.delete_book_cover(&app, &book_id).map_err(CommandError::from)?;
    Ok(())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn extractEpubCover(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    book_id: String,
    file_path: String,
) -> Result<bool, CommandError> {
    let repository = lock_repository(&state)?;
    let path = std::path::PathBuf::from(&file_path);
    match repository.extract_epub_cover(&app, &path, &book_id) {
        Ok(Some(_)) => Ok(true),
        Ok(None) => Ok(false),
        // `false` and a failure are different answers: no cover is not an error.
        Err(e) => Err(CommandError::from(e)),
    }
}
