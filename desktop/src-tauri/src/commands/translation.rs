//! Translation preferences and translation history commands (T3/T4).
//!
//! The account is never taken from the IPC payload. Every command below reads
//! `state.active_user.require_user_id()` and passes that down, so a caller
//! cannot read or write another account's preferences or history by sending a
//! different `userId`. That is also why the preference write is REJECTED
//! without an account while the history read returns an empty list: a lost
//! preference is a silent data loss, an empty history is just the truth.
//!
//! No cache read/write happens here. The cache is consulted by
//! `translateSelection` (`commands/translate.rs`), which owns the request path;
//! this module only exposes the history view over the table that call fills.

use super::map_command_error;
use crate::models::{TranslationCacheEntryDto, TranslationPreferencesDto};
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getTranslationPreferences(
    state: State<'_, AppState>,
) -> Result<Option<TranslationPreferencesDto>, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let user_id = state.active_user.require_user_id();
    repository.get_translation_preferences(&user_id).map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn saveTranslationPreferences(
    state: State<'_, AppState>,
    source_language: Option<String>,
    target_language: Option<String>,
) -> Result<TranslationPreferencesDto, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let user_id = state.active_user.require_user_id();
    repository
        .save_translation_preferences(
            &user_id,
            source_language.as_deref(),
            target_language.as_deref(),
        )
        .map_err(map_command_error)
}

/// Persists the definition language for the active account (D6).
///
/// Its own command rather than an extra argument on `saveTranslationPreferences`
/// because it is saved by a different gesture: the pair has one Save button for
/// both sides, this selector saves on change. See the repository function.
#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn saveDefinitionLanguage(
    state: State<'_, AppState>,
    definition_language: Option<String>,
) -> Result<TranslationPreferencesDto, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let user_id = state.active_user.require_user_id();
    repository
        .save_definition_language(&user_id, definition_language.as_deref())
        .map_err(map_command_error)
}

/// The account's translation history, newest first.
///
/// This is the cache table itself, not a separate history: there is only one
/// fact here ("this account translated this text this way at this time") and a
/// second table would mean a second writer for it.
#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listTranslationHistory(
    state: State<'_, AppState>,
) -> Result<Vec<TranslationCacheEntryDto>, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let user_id = state.active_user.require_user_id();
    repository.list_translation_history(&user_id).map_err(map_command_error)
}

#[cfg(test)]
mod tests {
    use crate::repository::tests::new_repository;
    use crate::state::ActiveUser;

    /// `State<'_, AppState>` cannot be constructed outside a running Tauri app:
    /// `AppState` binds a live repository, queue worker and logger. The commands
    /// are the same three lines every time — lock the repository, read
    /// `require_user_id()`, delegate — so the behavior that matters is the
    /// account resolution plus the repository contract, both exercised here
    /// directly rather than through a fabricated `State`.
    ///
    /// The one thing this pins that the repository tests cannot: the command
    /// layer resolves the account from `AppState`, and an anonymous
    /// `require_user_id()` is the EMPTY STRING, which is what makes the cache
    /// short-circuit (option A) instead of writing a shared row.
    #[test]
    fn anonymous_active_user_resolves_to_the_empty_owner() {
        let active_user = ActiveUser::new();
        assert_eq!(active_user.require_user_id(), "");
    }

    #[test]
    fn signed_in_active_user_resolves_to_its_own_owner() {
        let active_user = ActiveUser::new();
        active_user.set(Some("user-42".to_string()));
        assert_eq!(active_user.require_user_id(), "user-42");
    }

    #[test]
    fn the_resolved_owner_drives_both_stores() {
        let repo = new_repository();
        let active_user = ActiveUser::new();
        active_user.set(Some("user-42".to_string()));

        let owner = active_user.require_user_id();
        assert_eq!(owner, "user-42");

        repo.save_translation_preferences(&owner, Some("es"), Some("en")).unwrap();
        assert!(repo.cache_translation(&owner, "es", "en", "hola", "hello").unwrap());

        let preferences = repo.get_translation_preferences(&owner).unwrap().unwrap();
        assert_eq!(preferences.source_language.as_deref(), Some("es"));
        assert_eq!(repo.list_translation_history(&owner).unwrap().len(), 1);
    }

    #[test]
    fn signing_out_stops_both_stores_without_failing() {
        let repo = new_repository();
        let active_user = ActiveUser::new();
        active_user.set(Some("user-42".to_string()));
        repo.cache_translation(&active_user.require_user_id(), "es", "en", "hola", "hello")
            .unwrap();

        active_user.clear();
        let owner = active_user.require_user_id();
        assert_eq!(owner, "");

        // A history read degrades to empty rather than erroring...
        assert!(repo.list_translation_history(&owner).unwrap().is_empty());
        // ...a write is refused (a lost preference would be silent data loss)...
        assert!(repo.save_translation_preferences(&owner, Some("es"), Some("en")).is_err());
        // ...and a cache write is a reported no-op, not an error.
        assert!(!repo.cache_translation(&owner, "es", "en", "hola", "hello").unwrap());
    }
}
