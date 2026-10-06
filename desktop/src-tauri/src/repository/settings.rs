use rusqlite::{params, OptionalExtension};

use crate::error::{AppError, AppResult};
use serde_json::Value;

use crate::models::AppSettingDto;

use super::{LibraryRepository, MAX_SETTING_BATCH};

pub const READING_DAILY_GOAL_KEY: &str = "reading.dailyGoalMinutes";
pub const DEFAULT_DAILY_GOAL_MINUTES: i64 = 20;
pub const ALLOWED_GOALS: [i64; 4] = [10, 20, 30, 45];

fn sanitize_ranged_number(value: i64, min: i64, max: i64) -> i64 {
    value.clamp(min, max)
}

pub fn sanitize_daily_goal(raw: i64) -> i64 {
    let clamped = sanitize_ranged_number(raw, 10, 60);
    if clamped == 60 {
        return 45;
    }
    // nearest allowed
    let mut best = ALLOWED_GOALS[0];
    let mut best_dist = (clamped - best).abs();
    for &opt in &ALLOWED_GOALS[1..] {
        let dist = (clamped - opt).abs();
        if dist < best_dist {
            best = opt;
            best_dist = dist;
        }
    }
    best
}

fn per_user_daily_goal_key(user_id: &str) -> String {
    format!("{}_{}", READING_DAILY_GOAL_KEY, user_id)
}

fn parse_goal_value(value_json: &str) -> Option<i64> {
    serde_json::from_str::<serde_json::Value>(value_json)
        .ok()
        .and_then(|v| v.as_i64().or_else(|| v.as_f64().map(|f| f as i64)))
}

pub fn get_daily_goal_minutes_for_user(
    repo: &LibraryRepository,
    user_id: Option<&str>,
) -> AppResult<i64> {
    let uid = user_id.map(|s| s.trim()).filter(|s| !s.is_empty());

    // per-user key (signed-in values take precedence over the global key)
    if let Some(uid) = uid {
        let per_key = per_user_daily_goal_key(uid);
        let per_val: Option<String> = repo
            .connection
            .prepare("SELECT value_json FROM app_settings WHERE key = ?1")?
            .query_row(params![per_key], |row| row.get(0))
            .optional()?;
        if let Some(json) = per_val {
            if let Some(n) = parse_goal_value(&json) {
                return Ok(sanitize_daily_goal(n));
            }
        }
    }

    // fallback global — also the only source for anonymous (no user id) reads
    let global_val: Option<String> = repo
        .connection
        .prepare("SELECT value_json FROM app_settings WHERE key = ?1")?
        .query_row(params![READING_DAILY_GOAL_KEY], |row| row.get(0))
        .optional()?;
    if let Some(json) = global_val {
        if let Some(n) = parse_goal_value(&json) {
            return Ok(sanitize_daily_goal(n));
        }
    }

    Ok(DEFAULT_DAILY_GOAL_MINUTES)
}

pub fn save_daily_goal_minutes(
    repo: &mut LibraryRepository,
    minutes: i64,
    user_id: Option<&str>,
) -> AppResult<()> {
    let uid = user_id.map(|s| s.trim()).filter(|s| !s.is_empty());
    let sanitized = sanitize_daily_goal(minutes);
    // Anonymous saves go to the global key so they can carry over to a
    // signed-in read (which falls back to the global key when no per-user
    // value exists). Signed-in saves keep writing the per-user key.
    let key = match uid {
        Some(uid) => per_user_daily_goal_key(uid),
        None => READING_DAILY_GOAL_KEY.to_string(),
    };
    let value_json = sanitized.to_string();
    let dto = AppSettingDto {
        key: key.clone(),
        value_json: value_json.clone(),
        updated_at: chrono::Utc::now().to_rfc3339(),
    };
    LibraryRepository::validate_setting(&dto)?;
    let now = chrono::Utc::now().to_rfc3339();
    repo.connection.execute(
        "INSERT INTO app_settings (key, value_json, updated_at)
         VALUES (?1, ?2, ?3)
         ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at",
        params![key, value_json, now],
    )?;
    Ok(())
}

pub fn get_settings(repo: &LibraryRepository) -> AppResult<Vec<AppSettingDto>> {
    let mut statement = repo.connection.prepare(
        "SELECT key, value_json, updated_at
         FROM app_settings
         ORDER BY key ASC",
    )?;

    let rows = statement.query_map([], |row| {
        Ok(AppSettingDto { key: row.get(0)?, value_json: row.get(1)?, updated_at: row.get(2)? })
    })?;

    let settings = rows.collect::<Result<Vec<_>, _>>()?;
    Ok(settings)
}

pub fn upsert_settings(
    repo: &mut LibraryRepository,
    settings: Vec<AppSettingDto>,
) -> AppResult<()> {
    if settings.len() > MAX_SETTING_BATCH {
        return Err(AppError::InvalidInput(format!(
            "Too many settings in one request (max {})",
            MAX_SETTING_BATCH
        )));
    }

    for setting in &settings {
        LibraryRepository::validate_setting(setting)?;
    }

    let now = chrono::Utc::now().to_rfc3339();
    let tx = repo.connection.transaction()?;
    for setting in settings {
        tx.execute(
            "INSERT INTO app_settings (key, value_json, updated_at)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(key) DO UPDATE SET
               value_json = excluded.value_json,
               updated_at = excluded.updated_at",
            params![setting.key, setting.value_json, now],
        )?;
    }
    tx.commit()?;

    Ok(())
}
pub(super) fn validate_setting(setting: &AppSettingDto) -> AppResult<()> {
    let key = setting.key.trim();
    if key.is_empty() {
        return Err(AppError::InvalidInput("Setting key is required".to_string()));
    }
    if key.len() > 128 {
        return Err(AppError::InvalidInput("Setting key exceeds 128 characters".to_string()));
    }
    if !key.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-') {
        return Err(AppError::InvalidInput(format!(
            "Setting key contains unsupported characters: {}",
            setting.key
        )));
    }

    let parsed: Value = serde_json::from_str(&setting.value_json).map_err(|_| {
        AppError::InvalidInput(format!(
            "Setting '{}' must contain valid JSON in valueJson",
            setting.key
        ))
    })?;

    if !matches!(parsed, Value::String(_) | Value::Bool(_) | Value::Number(_) | Value::Null) {
        return Err(AppError::InvalidInput(format!(
            "Setting '{}' value must be scalar JSON type",
            setting.key
        )));
    }

    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::repository::tests::new_repository;
    use chrono::Utc;

    #[test]
    fn settings_validation_rejects_invalid_payload_without_mutating_existing_values() {
        let mut repository = new_repository();

        repository
            .upsert_settings(vec![AppSettingDto {
                key: "ui.theme".to_string(),
                value_json: "\"light\"".to_string(),
                updated_at: Utc::now().to_rfc3339(),
            }])
            .unwrap();

        let result = repository.upsert_settings(vec![AppSettingDto {
            key: "ui.theme".to_string(),
            value_json: "{\"nested\":true}".to_string(),
            updated_at: Utc::now().to_rfc3339(),
        }]);

        assert!(matches!(result, Err(AppError::InvalidInput(_))));

        let settings = repository.get_settings().unwrap();
        assert_eq!(settings.len(), 1);
        assert_eq!(settings[0].key, "ui.theme");
        assert_eq!(settings[0].value_json, "\"light\"");
    }

    #[test]
    fn anonymous_daily_goal_round_trips_via_global_key() {
        let mut repository = new_repository();

        assert_eq!(repository.get_daily_goal_minutes(None).unwrap(), DEFAULT_DAILY_GOAL_MINUTES);

        repository.save_daily_goal_minutes(30, None).unwrap();

        assert_eq!(repository.get_daily_goal_minutes(None).unwrap(), 30);
    }

    #[test]
    fn anonymous_daily_goal_carries_over_to_signed_in_read() {
        let mut repository = new_repository();
        repository.save_daily_goal_minutes(30, None).unwrap();

        assert_eq!(repository.get_daily_goal_minutes(Some("user-1")).unwrap(), 30);
    }

    #[test]
    fn signed_in_daily_goal_wins_over_global_fallback() {
        let mut repository = new_repository();
        repository.save_daily_goal_minutes(30, None).unwrap();
        repository.save_daily_goal_minutes(45, Some("user-1")).unwrap();

        assert_eq!(repository.get_daily_goal_minutes(Some("user-1")).unwrap(), 45);
        // a different signed-in user still falls back to the anonymous global value
        assert_eq!(repository.get_daily_goal_minutes(Some("user-2")).unwrap(), 30);
    }

    #[test]
    fn blank_user_id_is_treated_as_anonymous() {
        let mut repository = new_repository();
        repository.save_daily_goal_minutes(10, Some("   ")).unwrap();

        assert_eq!(repository.get_daily_goal_minutes(None).unwrap(), 10);
    }
}
