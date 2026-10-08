use super::map_command_error;
use crate::models::NotificationDto;
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listNotifications(state: State<'_, AppState>) -> Result<Vec<NotificationDto>, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    repository.list_notifications().map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn saveNotification(
    state: State<'_, AppState>,
    notification: NotificationDto,
) -> Result<(), String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    repository.save_notification(notification).map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn markNotificationRead(
    state: State<'_, AppState>,
    id: String,
    readAt: i64,
) -> Result<bool, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    repository.mark_notification_read(&id, readAt).map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn markAllNotificationsRead(state: State<'_, AppState>, readAt: i64) -> Result<i64, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    repository.mark_all_notifications_read(readAt).map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn clearNotifications(state: State<'_, AppState>) -> Result<i64, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    repository.clear_notifications().map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn pruneNotifications(state: State<'_, AppState>, nowEpochMs: i64) -> Result<i64, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    repository.apply_notification_retention(nowEpochMs).map_err(map_command_error)
}
