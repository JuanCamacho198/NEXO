use super::{lock_repository, CommandError};
use crate::models::{AddDictionaryWordInput, DictionaryWordDto};
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listDictionaryWords(
    state: State<'_, AppState>,
) -> Result<Vec<DictionaryWordDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository.list_dictionary_words().map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn addDictionaryWord(
    state: State<'_, AppState>,
    payload: AddDictionaryWordInput,
) -> Result<DictionaryWordDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository.add_dictionary_word(payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn removeDictionaryWord(state: State<'_, AppState>, id: String) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.remove_dictionary_word(&id).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn updateDictionaryWord(
    state: State<'_, AppState>,
    payload: crate::models::UpdateDictionaryWordInput,
) -> Result<DictionaryWordDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository.update_dictionary_word(payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn updateDictionaryEvidence(
    state: State<'_, AppState>,
    payload: crate::models::UpdateDictionaryEvidenceInput,
) -> Result<DictionaryWordDto, CommandError> {
    let repository = lock_repository(&state)?;
    repository.update_dictionary_evidence(payload).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn searchDictionaryWords(
    state: State<'_, AppState>,
    payload: crate::models::SearchDictionaryWordsInput,
) -> Result<Vec<DictionaryWordDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository
        .search_dictionary_words(
            &payload.query,
            payload.limit.unwrap_or(20),
            payload.fuzzy.unwrap_or(false),
            payload.user_id.as_deref(),
        )
        .map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn exportDictionary(
    state: State<'_, AppState>,
    format: String,
) -> Result<String, CommandError> {
    let repository = lock_repository(&state)?;
    repository.export_dictionary(&format).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn importDictionary(
    state: State<'_, AppState>,
    payload: String,
    format: String,
    user_id: Option<String>,
) -> Result<crate::models::ImportDictionaryResult, CommandError> {
    let repository = lock_repository(&state)?;
    repository.import_dictionary(&payload, &format, user_id.as_deref()).map_err(CommandError::from)
}
