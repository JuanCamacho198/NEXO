use std::fs;
use std::path::{Path, PathBuf};

use chrono::Utc;
use rusqlite::OptionalExtension;
use rusqlite::{params, Connection};
use tauri::{AppHandle, Manager};

use crate::error::{AppError, AppResult};

const MIGRATIONS: [(&str, &str); 20] = [
    ("0001_init", include_str!("../migrations/0001_init.sql")),
    ("0002_books", include_str!("../migrations/0002_books.sql")),
    ("0003_highlights", include_str!("../migrations/0003_highlights.sql")),
    ("0004_desktop_feature_parity", include_str!("../migrations/0004_desktop_feature_parity.sql")),
    ("0005_hidden_books", include_str!("../migrations/0005_hidden_books.sql")),
    ("0006_collections", include_str!("../migrations/0006_collections.sql")),
    (
        "0007_highlight_note_and_page_contract",
        include_str!("../migrations/0007_highlight_note_and_page_contract.sql"),
    ),
    ("0008_queue_and_perf_indexes", include_str!("../migrations/0008_queue_and_perf_indexes.sql")),
    ("0009_dictionary_tags", include_str!("../migrations/0009_dictionary_tags.sql")),
    ("0010_book_genre", include_str!("../migrations/0010_book_genre.sql")),
    ("0011_book_metadata", include_str!("../migrations/0011_book_metadata.sql")),
    ("0012_reading_status", include_str!("../migrations/0012_reading_status.sql")),
    ("0013_sync_outbox", include_str!("../migrations/0013_sync_outbox.sql")),
    ("0014_reading_sessions_sync", include_str!("../migrations/0014_reading_sessions_sync.sql")),
    ("0015_dictionary_sync", include_str!("../migrations/0015_dictionary_sync.sql")),
    ("0016_discover_cache", include_str!("../migrations/0016_discover_cache.sql")),
    ("0017_addon_registry", include_str!("../migrations/0017_addon_registry.sql")),
    ("0018_addon_consent", include_str!("../migrations/0018_addon_consent.sql")),
    (
        "0019_dictionary_rich_entries",
        include_str!("../migrations/0019_dictionary_rich_entries.sql"),
    ),
    (
        "0020_drop_dictionary_favorite",
        include_str!("../migrations/0020_drop_dictionary_favorite.sql"),
    ),
];

pub fn resolve_db_path(app: &AppHandle) -> AppResult<PathBuf> {
    let app_data_dir = app.path().app_data_dir()?;
    fs::create_dir_all(&app_data_dir)?;
    Ok(app_data_dir.join("nexo.db"))
}

pub fn open_and_migrate(db_path: &Path) -> AppResult<Connection> {
    let mut connection = Connection::open(db_path)?;
    connection.execute_batch("PRAGMA foreign_keys = ON;")?;

    connection.execute(
        "CREATE TABLE IF NOT EXISTS schema_migrations (
           name TEXT PRIMARY KEY,
           applied_at TEXT NOT NULL
         )",
        [],
    )?;

    for (name, sql) in MIGRATIONS {
        let applied: Option<i32> = connection
            .query_row("SELECT 1 FROM schema_migrations WHERE name = ?1 LIMIT 1", [name], |row| {
                row.get(0)
            })
            .optional()?;
        if applied.is_some() {
            continue;
        }

        let tx = connection.transaction()?;
        let migration_result = tx.execute_batch(sql);
        match migration_result {
            Ok(_) => {}
            Err(error)
                if (name == "0002_books" && is_duplicate_column_replay_safe(&tx)?)
                    || (name == "0005_hidden_books" && has_column(&tx, "books", "hidden_at")?)
                    || (name == "0007_highlight_note_and_page_contract"
                        && has_column(&tx, "highlights", "note")?)
                    || (name == "0010_book_genre" && has_column(&tx, "books", "genre")?)
                    || (name == "0011_book_metadata" && has_column(&tx, "books", "language")?)
                    || (name == "0015_dictionary_sync"
                        && has_column(&tx, "dictionary_words", "user_id")?) =>
            {
                // Existing DBs may already have these columns from pre-tracker launches.
                // Treat as previously applied once all expected columns are present.
                let _ = error;
            }
            Err(error) => return Err(error.into()),
        }

        tx.execute(
            "INSERT INTO schema_migrations (name, applied_at) VALUES (?1, ?2)",
            (name, Utc::now().to_rfc3339()),
        )?;
        tx.commit()?;
    }

    ensure_queue_schema(&connection)?;
    recover_expired_job_leases(&connection)?;

    Ok(connection)
}

fn ensure_queue_schema(connection: &Connection) -> AppResult<()> {
    let jobs_table_exists: Option<i32> = connection
        .query_row(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='jobs' LIMIT 1",
            [],
            |row| row.get(0),
        )
        .optional()?;

    if jobs_table_exists.is_none() {
        return Err(AppError::InvalidInput(
            "Queue schema missing after migration: jobs table not found".to_string(),
        ));
    }

    let due_index_exists: Option<i32> = connection
        .query_row(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_jobs_state_next_run_at' LIMIT 1",
            [],
            |row| row.get(0),
        )
        .optional()?;

    if due_index_exists.is_none() {
        return Err(AppError::InvalidInput(
            "Queue schema missing after migration: idx_jobs_state_next_run_at not found"
                .to_string(),
        ));
    }

    Ok(())
}

fn recover_expired_job_leases(connection: &Connection) -> AppResult<()> {
    let now = Utc::now().to_rfc3339();
    connection.execute(
        "UPDATE jobs
         SET state = 'queued',
             lease_expires_at = NULL,
             next_run_at = ?1,
             updated_at = ?1,
             last_error = COALESCE(last_error, 'lease expired before startup recovery')
         WHERE state = 'running'
           AND lease_expires_at IS NOT NULL
           AND lease_expires_at <= ?1",
        params![now],
    )?;

    Ok(())
}

fn is_duplicate_column_replay_safe(connection: &Connection) -> AppResult<bool> {
    let mut statement = connection.prepare("PRAGMA table_info(books)")?;
    let rows = statement.query_map([], |row| row.get::<_, String>(1))?;
    let mut names: Vec<String> = Vec::new();
    for name in rows {
        names.push(name?);
    }

    Ok(["sync_status", "current_page", "total_pages"]
        .iter()
        .all(|required| names.iter().any(|name| name == required)))
}

pub fn verify_queue_health(connection: &Connection) -> AppResult<QueueHealth> {
    let jobs_table_exists: Option<i32> = connection
        .query_row(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='jobs' LIMIT 1",
            [],
            |row| row.get(0),
        )
        .optional()?;

    if jobs_table_exists.is_none() {
        return Ok(QueueHealth {
            status: HealthStatus::Missing,
            warnings: vec!["jobs table does not exist".to_string()],
        });
    }

    let due_index_exists: Option<i32> = connection
        .query_row(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_jobs_state_next_run_at' LIMIT 1",
            [],
            |row| row.get(0),
        )
        .optional()?;

    let dedupe_index_exists: Option<i32> = connection
        .query_row(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_jobs_dedupe_key' LIMIT 1",
            [],
            |row| row.get(0),
        )
        .optional()?;

    let mut warnings = Vec::new();
    if due_index_exists.is_none() {
        warnings.push("idx_jobs_state_next_run_at index missing".to_string());
    }
    if dedupe_index_exists.is_none() {
        warnings.push("idx_jobs_dedupe_key index missing".to_string());
    }

    let now = Utc::now().to_rfc3339();
    let orphaned: i32 = connection.query_row(
        "SELECT COUNT(*) FROM jobs
         WHERE state = 'running'
           AND lease_expires_at IS NOT NULL
           AND lease_expires_at <= ?1",
        [&now],
        |row| row.get(0),
    )?;

    if orphaned > 0 {
        warnings.push(format!("{} orphaned running jobs found", orphaned));
    }

    let status = if warnings.is_empty() { HealthStatus::Healthy } else { HealthStatus::Degraded };

    Ok(QueueHealth { status, warnings })
}

#[derive(Debug, Clone)]
pub struct QueueHealth {
    pub status: HealthStatus,
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HealthStatus {
    Healthy,
    Degraded,
    Missing,
}

pub fn get_db_size(db_path: &Path) -> AppResult<u64> {
    match fs::metadata(db_path) {
        Ok(m) => Ok(m.len()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(0),
        Err(e) => {
            if e.kind() == std::io::ErrorKind::PermissionDenied {
                return Err(AppError::InvalidInput(format!("storage.permission_denied: {}", e)));
            }
            Err(AppError::Io(e))
        }
    }
}

pub fn vacuum(connection: &Connection) -> AppResult<()> {
    connection.execute("VACUUM", []).map_err(AppError::Database)?;
    Ok(())
}

/// Upsert a catalog cache entry. `fetched_at_epoch_secs` is epoch seconds;
/// expiry is evaluated lazily in [`discover_cache_get`].
pub fn discover_cache_put(
    connection: &Connection,
    key: &str,
    payload: &str,
    fetched_at_epoch_secs: i64,
    ttl_s: i64,
) -> AppResult<()> {
    connection
        .execute(
            "INSERT INTO discover_cache (key, payload, fetched_at, ttl_s)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(key) DO UPDATE SET payload = excluded.payload,
                                            fetched_at = excluded.fetched_at,
                                            ttl_s = excluded.ttl_s",
            params![key, payload, fetched_at_epoch_secs, ttl_s],
        )
        .map_err(AppError::Database)?;
    Ok(())
}

/// Read a catalog cache entry. Returns `None` on miss or TTL expiry;
/// expired rows are deleted eagerly so the table cannot grow unbounded.
pub fn discover_cache_get(
    connection: &Connection,
    key: &str,
    now_epoch_secs: i64,
) -> AppResult<Option<String>> {
    let row: Option<(String, i64, i64)> = connection
        .query_row(
            "SELECT payload, fetched_at, ttl_s FROM discover_cache WHERE key = ?1 LIMIT 1",
            [key],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()?;
    let Some((payload, fetched_at, ttl_s)) = row else {
        return Ok(None);
    };
    if now_epoch_secs - fetched_at > ttl_s {
        connection
            .execute("DELETE FROM discover_cache WHERE key = ?1", [key])
            .map_err(AppError::Database)?;
        return Ok(None);
    }
    Ok(Some(payload))
}

/// Resident Discover cache row, exactly as stored.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoverCacheRow {
    pub payload: String,
    pub fetched_at: i64,
    pub ttl_s: i64,
}

/// Resident read for stale-while-revalidate: returns the stored row WITHOUT
/// judging TTL and WITHOUT deleting anything, so a stale entry survives to be
/// served while a background refresh replaces it. TTL judgement belongs to the
/// frontend (`PersistentDiscoverCache`), which owns the stale/fresh decision.
///
/// [`discover_cache_get`] deliberately keeps its eager-eviction semantics and
/// is unchanged; this helper is the only new db seam.
pub fn discover_cache_read(
    connection: &Connection,
    key: &str,
) -> AppResult<Option<DiscoverCacheRow>> {
    let row: Option<(String, i64, i64)> = connection
        .query_row(
            "SELECT payload, fetched_at, ttl_s FROM discover_cache WHERE key = ?1 LIMIT 1",
            [key],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()?;
    Ok(row.map(|(payload, fetched_at, ttl_s)| DiscoverCacheRow { payload, fetched_at, ttl_s }))
}
fn has_column(connection: &Connection, table_name: &str, column_name: &str) -> AppResult<bool> {
    let mut statement = connection.prepare(&format!("PRAGMA table_info({})", table_name))?;
    let rows = statement.query_map([], |row| row.get::<_, String>(1))?;
    for name in rows {
        if name? == column_name {
            return Ok(true);
        }
    }
    Ok(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn migrate_in_memory() -> Connection {
        let connection = Connection::open_in_memory().expect("in-memory db");
        connection
            .execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations (
                   name TEXT PRIMARY KEY,
                   applied_at TEXT NOT NULL
                 )",
                [],
            )
            .unwrap();
        for (name, sql) in MIGRATIONS {
            connection.execute_batch(sql).expect("migration applies cleanly");
            connection
                .execute(
                    "INSERT INTO schema_migrations (name, applied_at) VALUES (?1, 'test')",
                    [name],
                )
                .unwrap();
        }
        connection
    }

    fn table_columns(connection: &Connection, table: &str) -> Vec<(String, String)> {
        let mut statement = connection.prepare(&format!("PRAGMA table_info({})", table)).unwrap();
        let rows =
            statement.query_map([], |row| Ok((row.get::<_, String>(1)?, row.get::<_, String>(2)?)));
        rows.expect("table_info works").map(|r| r.unwrap()).collect()
    }

    fn migrate_prefix_in_memory(count: usize) -> Connection {
        let connection = Connection::open_in_memory().expect("in-memory db");
        connection
            .execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations (
                   name TEXT PRIMARY KEY,
                   applied_at TEXT NOT NULL
                 )",
                [],
            )
            .unwrap();
        for (name, sql) in MIGRATIONS.iter().take(count) {
            connection.execute_batch(sql).expect("migration applies cleanly");
            connection
                .execute(
                    "INSERT INTO schema_migrations (name, applied_at) VALUES (?1, 'test')",
                    [name],
                )
                .unwrap();
        }
        connection
    }

    #[test]
    fn test_installed_addons_schema_after_migration() {
        let connection = migrate_in_memory();
        let columns = table_columns(&connection, "installed_addons");
        let names: Vec<&str> = columns.iter().map(|(n, _)| n.as_str()).collect();
        assert_eq!(names, vec!["id", "url", "manifest_json", "enabled", "added_at"]);
        let types: Vec<&str> = columns.iter().map(|(_, t)| t.as_str()).collect();
        assert_eq!(types, vec!["TEXT", "TEXT", "TEXT", "INTEGER", "INTEGER"]);

        let sql: String = connection
            .query_row(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='installed_addons'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert!(sql.contains("PRIMARY KEY"), "id must be PK: {}", sql);
        assert!(sql.contains("UNIQUE"), "url must be UNIQUE: {}", sql);
        assert!(sql.contains("CHECK"), "enabled must be CHECK-constrained: {}", sql);
    }

    #[test]
    fn test_installed_addons_reinstall_preserves_enabled_and_updates_manifest() {
        let connection = migrate_in_memory();
        connection
            .execute(
                "INSERT INTO installed_addons (id, url, manifest_json, enabled, added_at)
                 VALUES ('aaaaaaaaaaaaaaaa', 'https://example.com/m.json', '{\"v\":1}', 0, 100)",
                [],
            )
            .unwrap();
        connection
            .execute(
                "INSERT INTO installed_addons (id, url, manifest_json, enabled, added_at)
                 VALUES ('aaaaaaaaaaaaaaaa', 'https://example.com/m.json', '{\"v\":2}', 1, 200)
                 ON CONFLICT(url) DO UPDATE SET manifest_json = excluded.manifest_json,
                                              added_at = excluded.added_at",
                [],
            )
            .unwrap();
        let (manifest, enabled): (String, i32) = connection
            .query_row(
                "SELECT manifest_json, enabled FROM installed_addons WHERE url = 'https://example.com/m.json'",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(manifest, "{\"v\":2}");
        assert_eq!(enabled, 0, "reinstall must preserve enabled");
        let count: i32 = connection
            .query_row("SELECT COUNT(*) FROM installed_addons", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 1);
    }

    #[test]
    fn test_installed_addons_enabled_check_constraint() {
        let connection = migrate_in_memory();
        let result = connection.execute(
            "INSERT INTO installed_addons (id, url, manifest_json, enabled, added_at)
             VALUES ('bbbbbbbbbbbbbbbb', 'https://example.com/b.json', '{}', 2, 100)",
            [],
        );
        assert!(result.is_err(), "enabled must be CHECK-constrained to 0/1");
    }

    #[test]
    fn test_installed_addons_migration_scope_isolation() {
        let connection = migrate_in_memory();
        let foreign: i32 = connection
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master
                 WHERE type IN ('trigger', 'index')
                   AND tbl_name IN ('user_books', 'sync_outbox')
                   AND name LIKE '%addon%'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(foreign, 0, "0017 must not add triggers/indexes touching user_books/outbox");
        let outbox: i32 =
            connection.query_row("SELECT COUNT(*) FROM sync_outbox", [], |row| row.get(0)).unwrap();
        assert_eq!(outbox, 0, "migration must not write sync_outbox rows");
    }

    #[test]
    fn test_health_status_equality() {
        assert_eq!(HealthStatus::Healthy, HealthStatus::Healthy);
        assert_eq!(HealthStatus::Degraded, HealthStatus::Degraded);
        assert_eq!(HealthStatus::Missing, HealthStatus::Missing);
        assert_ne!(HealthStatus::Healthy, HealthStatus::Degraded);
        assert_ne!(HealthStatus::Healthy, HealthStatus::Missing);
    }

    #[test]
    fn test_queue_health_healthy_no_warnings() {
        let health = QueueHealth { status: HealthStatus::Healthy, warnings: vec![] };
        assert_eq!(health.status, HealthStatus::Healthy);
        assert!(health.warnings.is_empty());
    }

    #[test]
    fn test_queue_health_degraded_with_warnings() {
        let health = QueueHealth {
            status: HealthStatus::Degraded,
            warnings: vec!["missing index".to_string()],
        };
        assert_eq!(health.status, HealthStatus::Degraded);
        assert_eq!(health.warnings.len(), 1);
        assert_eq!(health.warnings[0], "missing index");
    }

    #[test]
    fn test_queue_health_missing() {
        let health = QueueHealth {
            status: HealthStatus::Missing,
            warnings: vec!["jobs table does not exist".to_string()],
        };
        assert_eq!(health.status, HealthStatus::Missing);
    }

    fn memory_db_with_cache_table() -> Connection {
        let connection = Connection::open_in_memory().expect("in-memory db");
        connection
            .execute_batch(include_str!("../migrations/0016_discover_cache.sql"))
            .expect("discover_cache migration applies cleanly");
        connection
    }

    #[test]
    fn test_discover_cache_hit_within_ttl() {
        let connection = memory_db_with_cache_table();
        discover_cache_put(&connection, "p:composite:pride:1", "{\"n\":1}", 1_000, 86_400)
            .expect("put succeeds");
        let hit = discover_cache_get(&connection, "p:composite:pride:1", 1_000 + 3_600)
            .expect("get succeeds");
        assert_eq!(hit.as_deref(), Some("{\"n\":1}"));
    }

    #[test]
    fn test_discover_cache_miss_and_expiry_evicts_row() {
        let connection = memory_db_with_cache_table();
        let miss =
            discover_cache_get(&connection, "p:composite:missing:1", 1_000).expect("get succeeds");
        assert_eq!(miss, None);
        discover_cache_put(&connection, "d:composite:gutendex:1", "{\"id\":1}", 1_000, 10)
            .expect("put succeeds");
        let expired = discover_cache_get(&connection, "d:composite:gutendex:1", 1_000 + 11)
            .expect("get succeeds");
        assert_eq!(expired, None);
        let remaining: i32 = connection
            .query_row("SELECT COUNT(*) FROM discover_cache", [], |row| row.get(0))
            .expect("count succeeds");
        assert_eq!(remaining, 0);
    }

    #[test]
    fn test_discover_cache_put_overwrites_existing_key() {
        let connection = memory_db_with_cache_table();
        discover_cache_put(&connection, "p:composite:pride:1", "{\"n\":1}", 1_000, 86_400)
            .expect("put succeeds");
        discover_cache_put(&connection, "p:composite:pride:1", "{\"n\":2}", 2_000, 86_400)
            .expect("put succeeds");
        let hit =
            discover_cache_get(&connection, "p:composite:pride:1", 2_001).expect("get succeeds");
        assert_eq!(hit.as_deref(), Some("{\"n\":2}"));
    }

    /// Stale-while-revalidate needs the expired row to SURVIVE the read:
    /// `discover_cache_read` returns it verbatim while `discover_cache_get`
    /// still evicts it eagerly.
    #[test]
    fn test_discover_cache_read_returns_expired_row_without_deleting() {
        let connection = memory_db_with_cache_table();
        discover_cache_put(&connection, "f:v2:builtin:gutendex:NEWEST", "{\"n\":1}", 1_000, 10)
            .expect("put succeeds");

        let resident = discover_cache_read(&connection, "f:v2:builtin:gutendex:NEWEST")
            .expect("read succeeds")
            .expect("expired row is still resident");
        assert_eq!(resident.payload, "{\"n\":1}");
        assert_eq!(resident.fetched_at, 1_000);
        assert_eq!(resident.ttl_s, 10);

        // No delete happened: the same row is still there on a later read.
        let again = discover_cache_read(&connection, "f:v2:builtin:gutendex:NEWEST")
            .expect("read succeeds");
        assert_eq!(again.as_ref().map(|row| row.payload.as_str()), Some("{\"n\":1}"));

        // `discover_cache_get` keeps its eager eviction, so the row then goes away.
        let evicted = discover_cache_get(&connection, "f:v2:builtin:gutendex:NEWEST", 1_000 + 11)
            .expect("get succeeds");
        assert_eq!(evicted, None);
        assert_eq!(
            discover_cache_read(&connection, "f:v2:builtin:gutendex:NEWEST")
                .expect("read succeeds"),
            None
        );
    }

    #[test]
    fn test_discover_cache_read_misses_unknown_key() {
        let connection = memory_db_with_cache_table();
        let miss = discover_cache_read(&connection, "p:v2:missing:1").expect("read succeeds");
        assert_eq!(miss, None);
    }

    #[test]
    fn test_dictionary_rich_entries_migration_adds_nullable_columns() {
        const NEW_COLUMNS: [&str; 10] = [
            "definition",
            "part_of_speech",
            "phonetic",
            "example",
            "quote",
            "source_book_id",
            "source_book_title",
            "source_book_author",
            "source_chapter",
            "source_locator",
        ];

        // Pre-0019 schema: the full chain up to (but excluding) 0019.
        let pre_0019_count = MIGRATIONS
            .iter()
            .position(|(name, _)| *name == "0019_dictionary_rich_entries")
            .expect("0019 must be registered");
        let (name, sql) = MIGRATIONS[pre_0019_count];
        assert_eq!(name, "0019_dictionary_rich_entries");

        let connection = migrate_prefix_in_memory(pre_0019_count);
        for new_column in NEW_COLUMNS {
            let exists = table_columns(&connection, "dictionary_words")
                .iter()
                .any(|(column, _)| column == new_column);
            assert!(!exists, "{new_column} must not exist before 0019");
        }

        connection
            .execute(
                "INSERT INTO dictionary_words (id, word, normalized_word, created_at)
                 VALUES ('pre-0019', 'pride', 'pride', '2026-01-01T00:00:00.000Z')",
                [],
            )
            .unwrap();

        connection.execute_batch(sql).expect("0019 applies cleanly");
        connection
            .execute("INSERT INTO schema_migrations (name, applied_at) VALUES (?1, 'test')", [name])
            .unwrap();

        let columns = table_columns(&connection, "dictionary_words");
        for new_column in NEW_COLUMNS {
            let exists =
                columns.iter().any(|(column, kind)| column == new_column && kind == "TEXT");
            assert!(exists, "{new_column} must exist as TEXT after 0019");
        }

        let select = NEW_COLUMNS.join(", ");
        let (word, created_at, nulls): (String, String, Vec<Option<String>>) = connection
            .query_row(
                &format!(
                    "SELECT word, created_at, {select} FROM dictionary_words WHERE id = 'pre-0019'"
                ),
                [],
                |row| {
                    let nulls: rusqlite::Result<Vec<Option<String>>> = (0..NEW_COLUMNS.len())
                        .map(|i| row.get::<_, Option<String>>(2 + i))
                        .collect();
                    Ok((row.get(0)?, row.get(1)?, nulls?))
                },
            )
            .unwrap();

        assert_eq!(word, "pride", "migration must preserve the existing word");
        assert_eq!(created_at, "2026-01-01T00:00:00.000Z", "migration must preserve created_at");
        assert!(
            nulls.iter().all(Option::is_none),
            "every new column must read NULL on a migrated row"
        );
    }

    #[test]
    fn test_dictionary_favorite_column_dropped_by_0020() {
        let pre_0020_count = MIGRATIONS
            .iter()
            .position(|(name, _)| *name == "0020_drop_dictionary_favorite")
            .expect("0020 must be registered");
        let (name, sql) = MIGRATIONS[pre_0020_count];
        assert_eq!(name, "0020_drop_dictionary_favorite");

        let connection = migrate_prefix_in_memory(pre_0020_count);
        let before: Vec<String> =
            table_columns(&connection, "dictionary_words").into_iter().map(|(n, _)| n).collect();
        assert!(before.iter().any(|c| c == "is_favorite"), "is_favorite must exist before 0020");

        connection
            .execute(
                "INSERT INTO dictionary_words (id, word, normalized_word, created_at)
                 VALUES ('pre-0020', 'pride', 'pride', '2026-01-01T00:00:00.000Z')",
                [],
            )
            .unwrap();
        connection
            .execute("UPDATE dictionary_words SET is_favorite = 1 WHERE id = 'pre-0020'", [])
            .unwrap();

        connection.execute_batch(sql).expect("0020 applies cleanly");

        let after: Vec<String> =
            table_columns(&connection, "dictionary_words").into_iter().map(|(n, _)| n).collect();
        assert!(!after.iter().any(|c| c == "is_favorite"), "is_favorite must be gone after 0020");
        let word: String = connection
            .query_row("SELECT word FROM dictionary_words WHERE id = 'pre-0020'", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(word, "pride", "the drop must preserve the existing row");
    }
}
