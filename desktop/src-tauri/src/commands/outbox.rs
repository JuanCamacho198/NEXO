use super::map_command_error;
use crate::error::AppError;
use crate::models::SyncOutboxRowDto;
use crate::state::AppState;
use tauri::State;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn addSyncOutboxItem(
    state: State<'_, AppState>,
    entity_type: String,
    entity_id: Option<String>,
    operation: String,
    payload_json: String,
) -> Result<String, String> {
    use uuid::Uuid;
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let conn = repository.connection();
    let id = Uuid::new_v4().to_string();
    let now = chrono::Utc::now().to_rfc3339();
    conn.execute(
        "INSERT INTO sync_outbox (id, entity_type, entity_id, operation, payload_json, created_at, next_retry_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6)",
        rusqlite::params![id, entity_type, entity_id, operation, payload_json, now],
    )
    .map_err(|e| format!("Failed to insert outbox item: {}", e))?;
    Ok(id)
}

/// Transactional UPDATE-else-INSERT that coalesces an outbox row per
/// (entity_type, entity_id) + payload `userId` (D5, SR-4.1).
///
/// Latest-wins merge (D6): the row is updated only when the stored payload's
/// `updatedAt` is <= the incoming event's `updatedAt` (ISO-8601 strings are
/// lexicographically comparable). If no row matches the key yet, one is
/// inserted. Rows are NEVER deleted here — coalescing must never drop data.
///
/// R3 security: inputs are validated before touching the DB (non-empty entity
/// key, UPSERT-only, bounded payload, required `userId`/`updatedAt` strings).
/// Nothing is logged; the payload is stored verbatim and never contains tokens.
pub fn add_coalesced_sync_outbox_item_internal(
    conn: &rusqlite::Connection,
    entity_type: &str,
    entity_id: &str,
    operation: &str,
    payload_json: &str,
) -> Result<String, AppError> {
    // R3: validate inputs server-side.
    if entity_type.trim().is_empty() || entity_type.len() > 64 {
        return Err(AppError::InvalidInput(
            "entityType must be a non-empty string (<= 64 chars)".to_string(),
        ));
    }
    if entity_id.trim().is_empty() {
        return Err(AppError::InvalidInput("entityId must be non-empty".to_string()));
    }
    let is_dictionary = entity_type == "DICTIONARY_WORD";
    if operation != "UPSERT" && !(is_dictionary && operation == "DELETE") {
        return Err(AppError::InvalidInput(
            "addCoalescedSyncOutboxItem only supports UPSERT operations (DELETE allowed for DICTIONARY_WORD)".to_string(),
        ));
    }
    if payload_json.len() > MAX_COALESCE_PAYLOAD_BYTES {
        return Err(AppError::InvalidInput(format!(
            "payloadJson exceeds the {} byte coalescing limit",
            MAX_COALESCE_PAYLOAD_BYTES
        )));
    }
    let payload: serde_json::Value = serde_json::from_str(payload_json)
        .map_err(|e| AppError::InvalidInput(format!("payloadJson must be valid JSON: {}", e)))?;
    let user_id = payload
        .get("userId")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| {
            AppError::InvalidInput("payloadJson must contain a non-empty userId string".to_string())
        })?;
    let updated_at =
        payload.get("updatedAt").and_then(|v| v.as_str()).filter(|s| !s.is_empty()).ok_or_else(
            || {
                AppError::InvalidInput(
                    "payloadJson must contain a non-empty updatedAt string".to_string(),
                )
            },
        )?;

    let now = chrono::Utc::now().to_rfc3339();
    let tx = conn.unchecked_transaction()?;

    // Latest-wins UPDATE: only overwrite when the stored row is not newer.
    // For DICTIONARY_WORD DELETE, we coalesce to latest operation (DELETE wins over UPSERT if newer).
    let updated = tx.execute(
        "UPDATE sync_outbox
         SET payload_json = ?1, operation = ?2, retry_count = 0, last_error = NULL, next_retry_at = ?3
         WHERE entity_type = ?4 AND entity_id = ?5
           AND user_id = ?6
           AND json_extract(payload_json, '$.updatedAt') <= ?7",
        rusqlite::params![payload_json, operation, now, entity_type, entity_id, user_id, updated_at],
    )?;

    let id = if updated > 0 {
        tx.query_row(
            "SELECT id FROM sync_outbox
             WHERE entity_type = ?1 AND entity_id = ?2 AND user_id = ?3
             LIMIT 1",
            rusqlite::params![entity_type, entity_id, user_id],
            |row| row.get::<_, String>(0),
        )?
    } else {
        let id = uuid::Uuid::new_v4().to_string();
        tx.execute(
            "INSERT INTO sync_outbox (id, entity_type, entity_id, operation, payload_json, user_id, created_at, next_retry_at)
             SELECT ?1, ?2, ?3, ?4, ?5, ?7, ?6, ?6
             WHERE NOT EXISTS (
               SELECT 1 FROM sync_outbox
               WHERE entity_type = ?2 AND entity_id = ?3 AND user_id = ?7
             )",
            rusqlite::params![id, entity_type, entity_id, operation, payload_json, now, user_id],
        )?;
        id
    };

    tx.commit()?;
    Ok(id)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn addCoalescedSyncOutboxItem(
    state: State<'_, AppState>,
    entity_type: String,
    entity_id: Option<String>,
    operation: String,
    payload_json: String,
) -> Result<String, String> {
    let entity_id = entity_id.ok_or_else(|| {
        map_command_error(AppError::InvalidInput("entityId is required".to_string()))
    })?;
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let conn = repository.connection();
    add_coalesced_sync_outbox_item_internal(
        conn,
        &entity_type,
        &entity_id,
        &operation,
        &payload_json,
    )
    .map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listSyncOutboxReady(
    state: State<'_, AppState>,
    user_id: String,
) -> Result<Vec<SyncOutboxRowDto>, String> {
    if user_id.trim().is_empty() {
        return Err("userId must be a non-empty string".to_string());
    }
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let conn = repository.connection();
    list_sync_outbox_ready_for_user(conn, &user_id)
}

/// Ready-row read for one owner. Extracted from the command so the owner filter
/// is testable without a Tauri `State`.
fn list_sync_outbox_ready_for_user(
    conn: &rusqlite::Connection,
    user_id: &str,
) -> Result<Vec<SyncOutboxRowDto>, String> {
    let now = chrono::Utc::now().to_rfc3339();

    // Rows with no owner are never drained: their payload's `userId` decides who
    // they belong to, and a row that lacks one may belong to somebody else. The
    // drain must not block on them either, so they are counted, reported and
    // left in place.
    report_ownerless_outbox_rows(conn, &now)?;

    let mut statement = conn
        .prepare(
            "SELECT id, entity_type, entity_id, operation, payload_json, retry_count, last_error, created_at, next_retry_at
             FROM sync_outbox
             WHERE user_id = ?1 AND next_retry_at <= ?2 AND retry_count < 50
             ORDER BY created_at ASC",
        )
        .map_err(|e| format!("Failed to prepare: {}", e))?;
    let rows = statement
        .query_map(rusqlite::params![user_id, now], |row| {
            Ok(SyncOutboxRowDto {
                id: row.get(0)?,
                entity_type: row.get(1)?,
                entity_id: row.get(2)?,
                operation: row.get(3)?,
                payload_json: row.get(4)?,
                retry_count: row.get(5)?,
                last_error: row.get(6)?,
                created_at: row.get(7)?,
                next_retry_at: row.get(8)?,
            })
        })
        .map_err(|e| format!("Failed to query: {}", e))?;
    let items = rows.collect::<Result<Vec<_>, _>>().map_err(|e| format!("{}", e))?;
    Ok(items)
}

/// Count ready rows that have no owner and surface them through the same
/// failure channel as a failed push (`last_error` + backoff), so an ownerless
/// row is findable instead of silently invisible. The row is kept: a silent
/// failure is worse than a visible one, and dropping it would destroy data.
fn report_ownerless_outbox_rows(conn: &rusqlite::Connection, now: &str) -> Result<(), String> {
    let ownerless: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM sync_outbox
             WHERE (user_id IS NULL OR user_id = '')
               AND next_retry_at <= ?1
               AND retry_count < 50",
            rusqlite::params![now],
            |row| row.get(0),
        )
        .map_err(|e| format!("Failed to count ownerless outbox rows: {}", e))?;
    if ownerless == 0 {
        return Ok(());
    }
    let next_retry_at = (chrono::Utc::now() + chrono::Duration::seconds(60)).to_rfc3339();
    conn.execute(
        "UPDATE sync_outbox
         SET retry_count = retry_count + 1,
             last_error = 'outbox row has no owner (payload userId missing); skipped',
             next_retry_at = ?1
         WHERE (user_id IS NULL OR user_id = '')
           AND next_retry_at <= ?2
           AND retry_count < 50",
        rusqlite::params![next_retry_at, now],
    )
    .map_err(|e| format!("Failed to mark ownerless outbox rows: {}", e))?;
    eprintln!("sync_outbox: skipped {} ownerless row(s) with no payload userId", ownerless);
    Ok(())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn markSyncOutboxFailed(
    state: State<'_, AppState>,
    id: String,
    error: String,
) -> Result<(), String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let conn = repository.connection();
    // Read current retry_count
    let current_retry: i32 = conn
        .query_row(
            "SELECT retry_count FROM sync_outbox WHERE id = ?1",
            rusqlite::params![id],
            |row| row.get(0),
        )
        .map_err(|e| format!("Failed to read retry_count: {}", e))?;
    let next_delay = std::cmp::min(60, 2_i32.saturating_pow((current_retry + 1) as u32));
    let next_retry_at =
        (chrono::Utc::now() + chrono::Duration::seconds(next_delay as i64)).to_rfc3339();
    conn.execute(
        "UPDATE sync_outbox SET retry_count = retry_count + 1, last_error = ?1, next_retry_at = ?2 WHERE id = ?3",
        rusqlite::params![error, next_retry_at, id],
    )
    .map_err(|e| format!("Failed to update outbox item: {}", e))?;
    Ok(())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn deleteSyncOutboxItem(state: State<'_, AppState>, id: String) -> Result<(), String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let conn = repository.connection();
    conn.execute("DELETE FROM sync_outbox WHERE id = ?1", rusqlite::params![id])
        .map_err(|e| format!("Failed to delete outbox item: {}", e))?;
    Ok(())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn pruneSyncOutbox(state: State<'_, AppState>) -> Result<i32, String> {
    let repository = state.repository.lock().map_err(|e| format!("{}", e))?;
    let conn = repository.connection();
    let cutoff = (chrono::Utc::now() - chrono::Duration::days(7)).to_rfc3339();
    let deleted = conn
        .execute(
            "DELETE FROM sync_outbox WHERE created_at < ?1 AND retry_count >= 10 AND entity_type <> 'READING_SESSION'",
            rusqlite::params![cutoff],
        )
        .map_err(|e| format!("Failed to prune outbox: {}", e))?;
    Ok(deleted as i32)
}

/// Upper bound for a single coalesced outbox payload (R3: no unbounded IPC payloads).
/// A READING_PROGRESS payload is a few hundred bytes; 64 KiB is a generous cap.
const MAX_COALESCE_PAYLOAD_BYTES: usize = 64 * 1024;

#[cfg(test)]
mod outbox_tests {
    use super::*;
    use rusqlite::Connection;

    fn outbox_connection() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(include_str!("../../migrations/0013_sync_outbox.sql")).unwrap();
        conn.execute_batch(include_str!("../../migrations/0023_outbox_owner.sql")).unwrap();
        conn
    }

    fn progress_payload(user_id: &str, book_id: &str, updated_at: &str) -> String {
        format!(
            r#"{{"userId":"{}","bookId":"{}","cfiLocation":"epubcfi(/6/2)","percentage":12.5,"updatedAt":"{}"}}"#,
            user_id, book_id, updated_at
        )
    }

    fn count_rows(conn: &Connection) -> i64 {
        conn.query_row("SELECT COUNT(*) FROM sync_outbox", [], |r| r.get(0)).unwrap()
    }

    #[test]
    fn coalesce_updates_existing_row_latest_wins() {
        let conn = outbox_connection();
        let first = add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:00Z"),
        )
        .unwrap();
        // Newer event for the same (user, book) updates the row in place.
        let second = add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:30Z"),
        )
        .unwrap();
        assert_eq!(first, second, "UPDATE path must return the existing row id");
        assert_eq!(count_rows(&conn), 1, "latest-wins must never grow the table");
        let stored: String = conn
            .query_row("SELECT payload_json FROM sync_outbox WHERE id = ?1", [&first], |r| r.get(0))
            .unwrap();
        assert!(stored.contains("10:00:30Z"), "payload must hold the latest event");
    }

    #[test]
    fn coalesce_stale_event_never_creates_duplicate() {
        let conn = outbox_connection();
        add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:30Z"),
        )
        .unwrap();
        // Older event arriving late: must NOT overwrite, must NOT insert a duplicate.
        add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:00Z"),
        )
        .unwrap();
        assert_eq!(count_rows(&conn), 1, "stale event must not create a second row");
        let stored: String =
            conn.query_row("SELECT payload_json FROM sync_outbox", [], |r| r.get(0)).unwrap();
        assert!(stored.contains("10:00:30Z"), "newest payload must survive");
    }

    #[test]
    fn coalesce_distinct_users_distinct_rows() {
        let conn = outbox_connection();
        add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:00Z"),
        )
        .unwrap();
        add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("u2", "book-1", "2026-08-07T10:00:00Z"),
        )
        .unwrap();
        assert_eq!(count_rows(&conn), 2, "different users must keep separate rows");
    }

    #[test]
    fn coalesce_resets_retry_state() {
        let conn = outbox_connection();
        let id = add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:00Z"),
        )
        .unwrap();
        // Simulate a failed row with backoff.
        conn.execute(
            "UPDATE sync_outbox SET retry_count = 5, last_error = 'RLS denied', next_retry_at = '2030-01-01T00:00:00Z' WHERE id = ?1",
            [&id],
        )
        .unwrap();
        // A newer event coalesces: retry state must reset (fresh event, fresh attempt).
        add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:30Z"),
        )
        .unwrap();
        let (retry, last_error, next_retry): (i32, Option<String>, String) = conn
            .query_row(
                "SELECT retry_count, last_error, next_retry_at FROM sync_outbox WHERE id = ?1",
                [&id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .unwrap();
        assert_eq!(retry, 0);
        assert!(last_error.is_none(), "coalescing must clear last_error");
        assert!(next_retry.as_str() < "2030-01-01T00:00:00Z", "next_retry_at must be reset to now");
    }

    #[test]
    fn coalesce_never_drops_rows() {
        let conn = outbox_connection();
        let id = add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:00Z"),
        )
        .unwrap();
        // Coalescing must never delete: even after many merges the row survives.
        for i in 1..=10 {
            let ts = format!("2026-08-07T10:00:{}Z", i * 3);
            add_coalesced_sync_outbox_item_internal(
                &conn,
                "READING_PROGRESS",
                "book-1",
                "UPSERT",
                &progress_payload("u1", "book-1", &ts),
            )
            .unwrap();
        }
        let exists: i32 = conn
            .query_row("SELECT COUNT(*) FROM sync_outbox WHERE id = ?1", [&id], |r| r.get(0))
            .unwrap();
        assert_eq!(exists, 1, "row must survive coalescing");
    }

    #[test]
    fn coalesce_rejects_invalid_inputs() {
        let conn = outbox_connection();
        // Empty entity type
        assert!(add_coalesced_sync_outbox_item_internal(
            &conn,
            "",
            "book-1",
            "UPSERT",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:00Z")
        )
        .is_err());
        // Empty entity id
        assert!(add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "",
            "UPSERT",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:00Z")
        )
        .is_err());
        // DELETE operations are not coalescible (must not rewrite tombstones)
        assert!(add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "DELETE",
            &progress_payload("u1", "book-1", "2026-08-07T10:00:00Z")
        )
        .is_err());
        // Missing userId in payload
        assert!(add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            r#"{"bookId":"book-1","updatedAt":"2026-08-07T10:00:00Z"}"#,
        )
        .is_err());
        // Missing updatedAt in payload
        assert!(add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            r#"{"userId":"u1","bookId":"book-1"}"#,
        )
        .is_err());
        // Unparseable JSON
        assert!(add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            "not-json",
        )
        .is_err());
        // Oversized payload (R3 bound)
        let huge = format!(
            r#"{{"userId":"u1","bookId":"book-1","updatedAt":"2026-08-07T10:00:00Z","pad":"{}"}}"#,
            "x".repeat(MAX_COALESCE_PAYLOAD_BYTES)
        );
        assert!(add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &huge,
        )
        .is_err());
    }

    fn ready_ids_for(conn: &Connection, user_id: &str) -> Vec<String> {
        list_sync_outbox_ready_for_user(conn, user_id)
            .unwrap()
            .into_iter()
            .map(|row| row.id)
            .collect()
    }

    /// The defect this migration closes: account B's drain must never pick up
    /// account A's queued writes. Both rows are ready and in the same table.
    #[test]
    fn list_sync_outbox_ready_is_scoped_to_one_owner() {
        let conn = outbox_connection();
        let a_id = add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("user-a", "book-1", "2026-08-07T10:00:00Z"),
        )
        .unwrap();
        let b_id = add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-2",
            "UPSERT",
            &progress_payload("user-b", "book-2", "2026-08-07T10:00:00Z"),
        )
        .unwrap();

        assert_eq!(ready_ids_for(&conn, "user-a"), vec![a_id.clone()]);
        assert_eq!(ready_ids_for(&conn, "user-b"), vec![b_id]);
        assert_eq!(count_rows(&conn), 2, "a scoped read must never delete another owner's row");
    }

    /// Ownerless rows are skipped, not drained, and not dropped: the drain stays
    /// unblocked and the failure is recorded where it can be found.
    #[test]
    fn ownerless_rows_are_skipped_and_recorded() {
        let conn = outbox_connection();
        conn.execute(
            "INSERT INTO sync_outbox (id, entity_type, entity_id, operation, payload_json, created_at, next_retry_at)
             VALUES ('orphan', 'READING_PROGRESS', 'book-9', 'UPSERT', '{\"bookId\":\"book-9\"}', '2026-08-07T09:00:00Z', '2026-08-07T09:00:00Z')",
            [],
        )
        .unwrap();
        let owned = add_coalesced_sync_outbox_item_internal(
            &conn,
            "READING_PROGRESS",
            "book-1",
            "UPSERT",
            &progress_payload("user-a", "book-1", "2026-08-07T10:00:00Z"),
        )
        .unwrap();

        assert_eq!(ready_ids_for(&conn, "user-a"), vec![owned], "the orphan must not be drained");
        let (retry, last_error): (i32, Option<String>) = conn
            .query_row(
                "SELECT retry_count, last_error FROM sync_outbox WHERE id = 'orphan'",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(retry, 1, "the skipped row must be counted as a failed attempt");
        assert!(
            last_error.unwrap_or_default().contains("no owner"),
            "a skipped row must leave a findable last_error"
        );
        assert_eq!(count_rows(&conn), 2, "the orphan must survive the drain");
    }

    /// Backfill: a row written before 0023 has no `user_id`, but its payload
    /// names the owner, and that owner must be able to drain it.
    #[test]
    fn migration_backfills_owner_from_payload() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(include_str!("../../migrations/0013_sync_outbox.sql")).unwrap();
        conn.execute(
            "INSERT INTO sync_outbox (id, entity_type, entity_id, operation, payload_json, created_at, next_retry_at)
             VALUES ('legacy', 'READING_PROGRESS', 'book-1', 'UPSERT', ?1, '2026-08-07T09:00:00Z', '2026-08-07T09:00:00Z')",
            [progress_payload("user-a", "book-1", "2026-08-07T09:00:00Z")],
        )
        .unwrap();

        conn.execute_batch(include_str!("../../migrations/0023_outbox_owner.sql")).unwrap();

        assert_eq!(ready_ids_for(&conn, "user-a"), vec!["legacy".to_string()]);
        assert!(ready_ids_for(&conn, "user-b").is_empty());
    }
}
