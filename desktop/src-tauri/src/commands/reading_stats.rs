use super::map_command_error;
use crate::models::{ActivityPoint, ReadingStatsSummaryDto, RemoteReadingSessionRow};
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getReadingActivity(
    state: State<'_, AppState>,
    period: String,
    granularity: String,
    book_id: Option<String>,
    zone_offset_minutes: i32,
) -> Result<Vec<ActivityPoint>, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    if !repository.has_desktop_parity_schema().unwrap_or(true) {
        return Ok(vec![]);
    }
    repository
        .get_reading_activity(&period, &granularity, book_id.as_deref(), zone_offset_minutes)
        .map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getReadingStatsForRange(
    state: State<'_, AppState>,
    from: String,
    to: String,
    book_id: Option<String>,
) -> Result<ReadingStatsSummaryDto, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    if !repository.has_desktop_parity_schema().unwrap_or(true) {
        return Ok(ReadingStatsSummaryDto {
            total_minutes_read: 0,
            total_sessions: 0,
            books_started: 0,
            books_completed: 0,
            avg_progress_percentage: 0.0,
        });
    }
    repository
        .get_reading_stats_for_range(&from, &to, book_id.as_deref())
        .map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getReadingStreak(
    state: State<'_, AppState>,
    book_id: Option<String>,
    user_id: String,
    zone_offset_minutes: i32,
) -> Result<i64, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    if !repository.has_desktop_parity_schema().unwrap_or(true) {
        return Ok(0);
    }
    repository
        .get_reading_streak(book_id.as_deref(), &user_id, zone_offset_minutes)
        .map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getDailyGoalMinutes(
    state: State<'_, AppState>,
    user_id: Option<String>,
) -> Result<i64, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    if !repository.has_desktop_parity_schema().unwrap_or(true) {
        return Ok(20);
    }
    repository.get_daily_goal_minutes(user_id.as_deref()).map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn saveDailyGoalMinutes(
    state: State<'_, AppState>,
    minutes: i64,
    user_id: Option<String>,
) -> Result<(), String> {
    let mut repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    if !repository.has_desktop_parity_schema().unwrap_or(true) {
        return Ok(());
    }
    repository.save_daily_goal_minutes(minutes, user_id.as_deref()).map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn getTodayMinutes(
    state: State<'_, AppState>,
    user_id: String,
    book_id: Option<String>,
    zone_offset_minutes: i32,
) -> Result<i64, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    if !repository.has_desktop_parity_schema().unwrap_or(true) {
        return Ok(0);
    }
    repository
        .get_today_minutes(&user_id, book_id.as_deref(), zone_offset_minutes)
        .map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn upsertRemoteReadingSessions(
    state: State<'_, AppState>,
    rows: Vec<RemoteReadingSessionRow>,
) -> Result<i64, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    repository.upsert_remote_reading_sessions(&rows).map_err(map_command_error)
}
