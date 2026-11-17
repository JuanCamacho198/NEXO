//! Notification history commands.
//!
//! Errors are `CommandError`: the JSON `{code, message, recoverable}` text the
//! frontend's `parseCommandError` already parses. A poisoned repository mutex is
//! reported as `INTERNAL_ERROR` through `lock_repository`, so the category
//! survives instead of dissolving into `Mutex`'s `Display` text.

use super::{lock_repository, CommandError};
use crate::models::NotificationDto;
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listNotifications(state: State<'_, AppState>) -> Result<Vec<NotificationDto>, CommandError> {
    let repository = lock_repository(&state)?;
    repository.list_notifications().map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn saveNotification(
    state: State<'_, AppState>,
    notification: NotificationDto,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    repository.save_notification(notification).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn markNotificationRead(
    state: State<'_, AppState>,
    id: String,
    readAt: i64,
) -> Result<bool, CommandError> {
    let repository = lock_repository(&state)?;
    repository.mark_notification_read(&id, readAt).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn markAllNotificationsRead(
    state: State<'_, AppState>,
    readAt: i64,
) -> Result<i64, CommandError> {
    let repository = lock_repository(&state)?;
    repository.mark_all_notifications_read(readAt).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn clearNotifications(state: State<'_, AppState>) -> Result<i64, CommandError> {
    let repository = lock_repository(&state)?;
    repository.clear_notifications().map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn pruneNotifications(
    state: State<'_, AppState>,
    nowEpochMs: i64,
) -> Result<i64, CommandError> {
    let repository = lock_repository(&state)?;
    repository.apply_notification_retention(nowEpochMs).map_err(CommandError::from)
}
