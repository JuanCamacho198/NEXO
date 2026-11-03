use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn setActiveUser(state: State<'_, AppState>, user_id: Option<String>) -> Result<(), String> {
    state.active_user.set(user_id);
    Ok(())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn clearActiveUser(state: State<'_, AppState>) -> Result<(), String> {
    state.active_user.clear();
    Ok(())
}

#[cfg(test)]
mod tests {
    use crate::state::ActiveUser;

    // `State<'_, AppState>` cannot be constructed outside a running Tauri app:
    // `AppState` requires a live `LibraryRepository`, `QueueRepository` and
    // `Logger`, all of which bind to on-disk resources. The commands above are
    // pure delegations to `ActiveUser`, so the behavior under test lives there
    // and is exercised directly here instead of through a fabricated `State`.
    #[test]
    fn set_then_require_user_id_returns_the_id() {
        let active_user = ActiveUser::new();

        active_user.set(Some("user-42".to_string()));

        assert_eq!(active_user.require_user_id(), "user-42");
        assert_eq!(active_user.current_user_id(), Some("user-42".to_string()));
        assert!(!active_user.is_anonymous());
    }

    #[test]
    fn set_with_none_clears_the_active_user() {
        let active_user = ActiveUser::new();
        active_user.set(Some("user-42".to_string()));

        active_user.set(None);

        assert_eq!(active_user.require_user_id(), "");
        assert!(active_user.is_anonymous());
    }

    #[test]
    fn clear_drops_the_active_user_id() {
        let active_user = ActiveUser::new();
        active_user.set(Some("user-42".to_string()));

        active_user.clear();

        assert_eq!(active_user.current_user_id(), None);
        assert!(active_user.is_anonymous());
    }

    #[test]
    fn local_user_ids_are_not_accepted_as_active_user() {
        let active_user = ActiveUser::new();

        active_user.set(Some("local-abc".to_string()));

        assert!(active_user.is_anonymous());
        assert_eq!(active_user.require_user_id(), "");
    }
}
