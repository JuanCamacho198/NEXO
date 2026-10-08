use rusqlite::params;

use crate::error::{AppError, AppResult};
use crate::models::NotificationDto;
use serde_json::Value;

use super::LibraryRepository;

/// Durable retention bounds for the local notification history (NOTIF-02).
///
/// 200 entries is far more than a heavy week of events can produce while
/// staying tiny on disk, and 30 days matches the app-wide tombstone window
/// (`retention::RETENTION_DAYS`). The frontend mirrors these values in
/// `src/lib/shared/services/notificationRetention.ts`; both sides are pinned by
/// tests, so the two definitions must be changed together.
pub const MAX_NOTIFICATION_COUNT: i64 = 200;
pub const MAX_NOTIFICATION_AGE_MS: i64 = 30 * 24 * 60 * 60 * 1000;

const MAX_NOTIFICATION_ID_LEN: usize = 128;
const MAX_I18N_KEY_LEN: usize = 128;
const MAX_TEXT_FIELD_LEN: usize = 512;
const MAX_JSON_LEN: usize = 8 * 1024;

const SOURCES: [&str; 7] = ["import", "sync", "addons", "update", "library", "streak", "goal"];
const CATEGORIES: [&str; 2] = ["system", "nudge"];
const SEVERITIES: [&str; 4] = ["success", "info", "warning", "error"];
const INTERRUPTIONS: [&str; 3] = ["silent", "in-app", "system"];

fn validate_json_object(label: &str, raw: Option<&str>) -> AppResult<()> {
    let Some(raw) = raw else {
        return Ok(());
    };
    if raw.len() > MAX_JSON_LEN {
        return Err(AppError::InvalidInput(format!(
            "Notification {} exceeds {} bytes",
            label, MAX_JSON_LEN
        )));
    }
    match serde_json::from_str::<Value>(raw) {
        Ok(Value::Object(_)) => Ok(()),
        Ok(_) => {
            Err(AppError::InvalidInput(format!("Notification {} must be a JSON object", label)))
        }
        Err(_) => {
            Err(AppError::InvalidInput(format!("Notification {} must contain valid JSON", label)))
        }
    }
}

/// Single input gate for every notification write: closed enum sets, bounded
/// strings, positive epoch-millisecond timestamps and JSON-object params.
pub fn validate_notification(dto: &NotificationDto) -> AppResult<()> {
    let id = dto.id.trim();
    if id.is_empty() || id.len() > MAX_NOTIFICATION_ID_LEN {
        return Err(AppError::InvalidInput(
            "Notification id is required (max 128 characters)".to_string(),
        ));
    }
    if dto.created_at <= 0 {
        return Err(AppError::InvalidInput(
            "Notification createdAt must be a positive epoch millisecond".to_string(),
        ));
    }
    if !SOURCES.contains(&dto.source.as_str()) {
        return Err(AppError::InvalidInput(format!(
            "Unsupported notification source: {}",
            dto.source
        )));
    }
    if !CATEGORIES.contains(&dto.category.as_str()) {
        return Err(AppError::InvalidInput(format!(
            "Unsupported notification category: {}",
            dto.category
        )));
    }
    if !SEVERITIES.contains(&dto.severity.as_str()) {
        return Err(AppError::InvalidInput(format!(
            "Unsupported notification severity: {}",
            dto.severity
        )));
    }
    if !INTERRUPTIONS.contains(&dto.interruption.as_str()) {
        return Err(AppError::InvalidInput(format!(
            "Unsupported notification interruption: {}",
            dto.interruption
        )));
    }
    if dto.i18n_key.trim().is_empty() || dto.i18n_key.len() > MAX_I18N_KEY_LEN {
        return Err(AppError::InvalidInput(
            "Notification i18nKey is required (max 128 characters)".to_string(),
        ));
    }
    if let Some(read_at) = dto.read_at {
        if read_at <= 0 {
            return Err(AppError::InvalidInput(
                "Notification readAt must be a positive epoch millisecond".to_string(),
            ));
        }
    }
    if let Some(dedup_key) = &dto.dedup_key {
        if dedup_key.len() > MAX_TEXT_FIELD_LEN {
            return Err(AppError::InvalidInput(
                "Notification dedupKey exceeds 512 characters".to_string(),
            ));
        }
    }

    validate_json_object("i18nParams", dto.i18n_params.as_deref())?;
    validate_json_object("target", dto.target.as_deref())?;
    Ok(())
}

pub fn insert_notification(repo: &LibraryRepository, dto: &NotificationDto) -> AppResult<()> {
    validate_notification(dto)?;
    repo.connection.execute(
        "INSERT OR REPLACE INTO notifications
           (id, created_at, source, category, severity, interruption, i18n_key, i18n_params, target, read_at, dedup_key)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
        params![
            dto.id,
            dto.created_at,
            dto.source,
            dto.category,
            dto.severity,
            dto.interruption,
            dto.i18n_key,
            dto.i18n_params,
            dto.target,
            dto.read_at,
            dto.dedup_key,
        ],
    )?;
    Ok(())
}

/// Newest-first history. `id` is the tiebreaker so equal-millisecond rows have
/// a stable order across reads.
pub fn list_notifications(repo: &LibraryRepository) -> AppResult<Vec<NotificationDto>> {
    let mut statement = repo.connection.prepare(
        "SELECT id, created_at, source, category, severity, interruption,
                i18n_key, i18n_params, target, read_at, dedup_key
         FROM notifications
         ORDER BY created_at DESC, id DESC",
    )?;

    let rows = statement.query_map([], |row| {
        Ok(NotificationDto {
            id: row.get(0)?,
            created_at: row.get(1)?,
            source: row.get(2)?,
            category: row.get(3)?,
            severity: row.get(4)?,
            interruption: row.get(5)?,
            i18n_key: row.get(6)?,
            i18n_params: row.get(7)?,
            target: row.get(8)?,
            read_at: row.get(9)?,
            dedup_key: row.get(10)?,
        })
    })?;

    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

/// Marks one item read. Returns whether an unread row actually changed, so a
/// repeat call is a no-op instead of rewriting the timestamp.
pub fn mark_notification_read(repo: &LibraryRepository, id: &str, read_at: i64) -> AppResult<bool> {
    if id.trim().is_empty() {
        return Err(AppError::InvalidInput("Notification id is required".to_string()));
    }
    if read_at <= 0 {
        return Err(AppError::InvalidInput(
            "Notification readAt must be a positive epoch millisecond".to_string(),
        ));
    }

    let changed = repo.connection.execute(
        "UPDATE notifications SET read_at = ?2 WHERE id = ?1 AND read_at IS NULL",
        params![id, read_at],
    )?;
    Ok(changed > 0)
}

pub fn mark_all_notifications_read(repo: &LibraryRepository, read_at: i64) -> AppResult<i64> {
    if read_at <= 0 {
        return Err(AppError::InvalidInput(
            "Notification readAt must be a positive epoch millisecond".to_string(),
        ));
    }

    let changed = repo
        .connection
        .execute("UPDATE notifications SET read_at = ?1 WHERE read_at IS NULL", params![read_at])?;
    Ok(changed as i64)
}

pub fn delete_all_notifications(repo: &LibraryRepository) -> AppResult<i64> {
    let deleted = repo.connection.execute("DELETE FROM notifications", [])?;
    Ok(deleted as i64)
}

/// Applies both retention bounds and returns how many rows were removed:
/// first everything older than `max_age_ms`, then everything past the newest
/// `max_count` entries. Idempotent, so a restart-time sweep is always safe.
pub fn apply_notification_retention(
    repo: &LibraryRepository,
    now_ms: i64,
    max_count: i64,
    max_age_ms: i64,
) -> AppResult<i64> {
    if now_ms <= 0 {
        return Err(AppError::InvalidInput(
            "Retention now must be a positive epoch millisecond".to_string(),
        ));
    }
    if max_count <= 0 {
        return Err(AppError::InvalidInput("Retention maxCount must be positive".to_string()));
    }
    if max_age_ms <= 0 {
        return Err(AppError::InvalidInput("Retention maxAgeMs must be positive".to_string()));
    }

    let cutoff = now_ms - max_age_ms;
    let mut removed = repo
        .connection
        .execute("DELETE FROM notifications WHERE created_at < ?1", params![cutoff])?;

    removed += repo.connection.execute(
        "DELETE FROM notifications
         WHERE id IN (
            SELECT id FROM notifications
            ORDER BY created_at DESC, id DESC
            LIMIT -1 OFFSET ?1
         )",
        params![max_count],
    )?;

    Ok(removed as i64)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::repository::tests::new_repository;

    fn sample(id: &str, created_at: i64) -> NotificationDto {
        NotificationDto {
            id: id.to_string(),
            created_at,
            source: "sync".to_string(),
            category: "system".to_string(),
            severity: "error".to_string(),
            interruption: "in-app".to_string(),
            i18n_key: "notifications.kind.syncFailure".to_string(),
            i18n_params: Some("{\"detail\":\"Offline\",\"count\":2}".to_string()),
            target: Some("{\"kind\":\"route\",\"route\":\"/library\"}".to_string()),
            read_at: None,
            dedup_key: Some(format!("sync:{id}")),
        }
    }

    #[test]
    fn insert_and_list_returns_newest_first() {
        let repo = new_repository();
        insert_notification(&repo, &sample("b", 200)).unwrap();
        insert_notification(&repo, &sample("a", 100)).unwrap();
        insert_notification(&repo, &sample("c", 300)).unwrap();

        let listed = list_notifications(&repo).unwrap();
        assert_eq!(listed.iter().map(|n| n.id.as_str()).collect::<Vec<_>>(), vec!["c", "b", "a"]);
    }

    #[test]
    fn i18n_params_and_target_json_round_trip_intact() {
        let repo = new_repository();
        let dto = sample("json-1", 100);
        insert_notification(&repo, &dto).unwrap();

        let listed = list_notifications(&repo).unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].i18n_params.as_deref(), Some("{\"detail\":\"Offline\",\"count\":2}"));
        assert_eq!(
            listed[0].target.as_deref(),
            Some("{\"kind\":\"route\",\"route\":\"/library\"}")
        );
    }

    #[test]
    fn per_item_read_round_trips_and_is_idempotent() {
        let repo = new_repository();
        insert_notification(&repo, &sample("a", 100)).unwrap();
        insert_notification(&repo, &sample("b", 200)).unwrap();

        assert!(mark_notification_read(&repo, "a", 500).unwrap());
        // A second mark on an already-read row changes nothing.
        assert!(!mark_notification_read(&repo, "a", 900).unwrap());
        // Unknown id is a no-op, not an error.
        assert!(!mark_notification_read(&repo, "missing", 500).unwrap());

        let listed = list_notifications(&repo).unwrap();
        let a = listed.iter().find(|n| n.id == "a").unwrap();
        let b = listed.iter().find(|n| n.id == "b").unwrap();
        assert_eq!(a.read_at, Some(500));
        assert_eq!(b.read_at, None);
    }

    #[test]
    fn mark_all_read_only_touches_unread_rows() {
        let repo = new_repository();
        insert_notification(&repo, &sample("a", 100)).unwrap();
        insert_notification(&repo, &sample("b", 200)).unwrap();
        insert_notification(&repo, &sample("c", 300)).unwrap();
        mark_notification_read(&repo, "a", 400).unwrap();

        assert_eq!(mark_all_notifications_read(&repo, 500).unwrap(), 2);
        assert_eq!(mark_all_notifications_read(&repo, 600).unwrap(), 0);

        let listed = list_notifications(&repo).unwrap();
        assert!(listed.iter().all(|n| n.read_at.is_some()));
    }

    #[test]
    fn delete_all_empties_the_history() {
        let repo = new_repository();
        insert_notification(&repo, &sample("a", 100)).unwrap();
        insert_notification(&repo, &sample("b", 200)).unwrap();

        assert_eq!(delete_all_notifications(&repo).unwrap(), 2);
        assert!(list_notifications(&repo).unwrap().is_empty());
    }

    #[test]
    fn retention_age_bound_drops_old_rows() {
        let repo = new_repository();
        insert_notification(&repo, &sample("old", 1_000)).unwrap();
        insert_notification(&repo, &sample("fresh", 16_000)).unwrap();

        // now=20_000, keeping anything newer than now-5_000 (>= 15_000): "old" goes.
        assert_eq!(apply_notification_retention(&repo, 20_000, 50, 5_000).unwrap(), 1);
        let listed = list_notifications(&repo).unwrap();
        assert_eq!(listed.iter().map(|n| n.id.as_str()).collect::<Vec<_>>(), vec!["fresh"]);
    }

    #[test]
    fn retention_count_bound_keeps_newest_n() {
        let repo = new_repository();
        for i in 0..5 {
            insert_notification(&repo, &sample(&format!("n{i}"), 100 + i)).unwrap();
        }

        // Wide age window; count bound of 2 keeps n4, n3.
        assert_eq!(apply_notification_retention(&repo, 1_000_000, 2, 10_000_000).unwrap(), 3);
        let listed = list_notifications(&repo).unwrap();
        assert_eq!(listed.iter().map(|n| n.id.as_str()).collect::<Vec<_>>(), vec!["n4", "n3"]);
    }

    #[test]
    fn retention_applies_both_bounds_together() {
        let repo = new_repository();
        // Three rows inside the age window plus one stale row outside it.
        insert_notification(&repo, &sample("stale", 1_000)).unwrap();
        insert_notification(&repo, &sample("a", 9_000)).unwrap();
        insert_notification(&repo, &sample("b", 9_500)).unwrap();
        insert_notification(&repo, &sample("c", 9_900)).unwrap();

        // now=10_000, window 2_000 (>= 8_000), count 2 → stale and a are removed.
        assert_eq!(apply_notification_retention(&repo, 10_000, 2, 2_000).unwrap(), 2);
        let listed = list_notifications(&repo).unwrap();
        assert_eq!(listed.iter().map(|n| n.id.as_str()).collect::<Vec<_>>(), vec!["c", "b"]);
    }

    #[test]
    fn validation_rejects_unknown_enum_and_bad_json() {
        let repo = new_repository();
        let mut bad_source = sample("x", 100);
        bad_source.source = "wat".to_string();
        assert!(matches!(insert_notification(&repo, &bad_source), Err(AppError::InvalidInput(_))));

        let mut bad_json = sample("y", 100);
        bad_json.i18n_params = Some("not json".to_string());
        assert!(matches!(insert_notification(&repo, &bad_json), Err(AppError::InvalidInput(_))));

        let mut bad_array = sample("z", 100);
        bad_array.target = Some("[1,2,3]".to_string());
        assert!(matches!(insert_notification(&repo, &bad_array), Err(AppError::InvalidInput(_))));

        assert!(list_notifications(&repo).unwrap().is_empty());
    }
}
