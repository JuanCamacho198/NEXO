//! Discover cache commands (migration 0016 `discover_cache`) — the durable
//! half of the Discover rail cache.
//!
//! `discoverCacheRead` is the stale-while-revalidate seam: it returns the
//! resident row verbatim, WITHOUT judging TTL and WITHOUT deleting it. Rust's
//! existing `discover_cache_get` evicts expired rows eagerly, which would
//! destroy exactly the entry the frontend needs to serve while its background
//! refresh runs, so TTL judgement lives in `PersistentDiscoverCache` (TS) and
//! this command only reads. `discover_cache_get`/`discover_cache_put` are
//! untouched.
//!
//! No user_books/outbox/sync access: the table is a separate cache namespace,
//! so isolation is structural.

use serde::{Deserialize, Serialize};
use tauri::State;

use super::{lock_repository, CommandError};
use crate::db::{discover_cache_put, discover_cache_read};
use crate::state::AppState;

/// Mirrors the TS `DiscoverCacheRowDto` (camelCase IPC).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoverCacheRowDto {
    pub payload: String,
    pub fetched_at: i64,
    pub ttl_s: i64,
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn discoverCacheRead(
    state: State<'_, AppState>,
    key: String,
) -> Result<Option<DiscoverCacheRowDto>, CommandError> {
    let repository = lock_repository(&state)?;
    discover_cache_read(repository.connection(), &key)
        .map(|row| {
            row.map(|row| DiscoverCacheRowDto {
                payload: row.payload,
                fetched_at: row.fetched_at,
                ttl_s: row.ttl_s,
            })
        })
        .map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn discoverCachePut(
    state: State<'_, AppState>,
    key: String,
    payload: String,
    fetched_at: i64,
    ttl_s: i64,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    discover_cache_put(repository.connection(), &key, &payload, fetched_at, ttl_s)
        .map_err(CommandError::from)
}

#[cfg(test)]
mod discover_cache_tests {
    use super::*;

    fn cache_connection() -> rusqlite::Connection {
        let conn = rusqlite::Connection::open_in_memory().unwrap();
        conn.execute_batch(include_str!("../../migrations/0016_discover_cache.sql")).unwrap();
        conn
    }

    /// The IPC contract the TS port reads: camelCase keys, exact field values.
    #[test]
    fn row_dto_serializes_the_camel_case_ipc_shape() {
        let dto = DiscoverCacheRowDto {
            payload: "{\"n\":1}".to_string(),
            fetched_at: 1_000,
            ttl_s: 21_600,
        };
        let json = serde_json::to_string(&dto).unwrap();
        assert_eq!(json, r#"{"payload":"{\"n\":1}","fetchedAt":1000,"ttlS":21600}"#);
        // And it deserializes from the same shape (invoke args round-trip).
        let parsed: DiscoverCacheRowDto = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, dto);
    }

    /// The command helpers serve a stale row verbatim so the TS mirror can seed
    /// from it; a second read proves nothing was deleted on the way.
    #[test]
    fn read_serves_a_stale_row_and_keeps_it_resident() {
        let conn = cache_connection();
        let key = "f:v2:builtin:gutendex:NEWEST";
        discover_cache_put(&conn, key, "{\"n\":1}", 1_000, 10).unwrap();

        let first = discover_cache_read(&conn, key).unwrap().expect("row is resident");
        assert_eq!(first.payload, "{\"n\":1}");
        assert_eq!(first.fetched_at, 1_000);
        assert_eq!(first.ttl_s, 10);

        let second = discover_cache_read(&conn, key).unwrap();
        assert_eq!(second.map(|row| row.payload), Some("{\"n\":1}".to_string()));
    }

    #[test]
    fn put_upserts_the_same_key() {
        let conn = cache_connection();
        let key = "f:v2:builtin:openlibrary:NEWEST";
        discover_cache_put(&conn, key, "{\"n\":1}", 1_000, 21_600).unwrap();
        discover_cache_put(&conn, key, "{\"n\":2}", 2_000, 21_600).unwrap();
        let row = discover_cache_read(&conn, key).unwrap().expect("row is resident");
        assert_eq!(row.payload, "{\"n\":2}");
        assert_eq!(row.fetched_at, 2_000);
    }

    #[test]
    fn read_misses_unknown_key_without_writing() {
        let conn = cache_connection();
        assert_eq!(discover_cache_read(&conn, "p:v2:missing:1").unwrap(), None);
        let count: i64 =
            conn.query_row("SELECT COUNT(*) FROM discover_cache", [], |row| row.get(0)).unwrap();
        assert_eq!(count, 0);
    }
}
