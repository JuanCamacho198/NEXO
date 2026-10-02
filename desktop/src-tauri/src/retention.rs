use chrono::{Duration, Utc};
use rusqlite::Connection;

use crate::db::vacuum;
use crate::error::AppResult;

/// FR-14: soft-deleted rows older than this many days are pruned.
pub const RETENTION_DAYS: i64 = 30;

/// Tables that may carry a `deleted_at` tombstone. A table absent from a given
/// database (or without the column) is skipped.
const TOMBSTONE_TABLES: [&str; 4] = ["books", "highlights", "bookmarks", "dictionary_words"];

#[derive(Debug, Default, PartialEq, Eq)]
pub struct RetentionReport {
    pub pruned: u64,
}

fn table_has_deleted_at(connection: &Connection, table: &str) -> AppResult<bool> {
    let mut statement = connection.prepare(&format!("PRAGMA table_info({table})"))?;
    let rows = statement.query_map([], |row| row.get::<_, String>(1))?;
    for name in rows {
        if name? == "deleted_at" {
            return Ok(true);
        }
    }
    Ok(false)
}

/// Prunes tombstoned rows older than `older_than_days` across every table that
/// carries a `deleted_at` column, then vacuums the database. Live rows
/// (`deleted_at IS NULL`) and newer tombstones are never touched.
pub fn run_retention(connection: &Connection, older_than_days: i64) -> AppResult<RetentionReport> {
    let cutoff = (Utc::now() - Duration::days(older_than_days)).to_rfc3339();
    let mut pruned: u64 = 0;
    for table in TOMBSTONE_TABLES {
        if !table_has_deleted_at(connection, table)? {
            continue;
        }
        let sql = format!("DELETE FROM {table} WHERE deleted_at IS NOT NULL AND deleted_at < ?1");
        pruned += connection.execute(&sql, [&cutoff])? as u64;
    }
    vacuum(connection)?;
    Ok(RetentionReport { pruned })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn count(connection: &Connection, sql: &str) -> i64 {
        connection.query_row(sql, [], |row| row.get(0)).unwrap()
    }

    #[test]
    fn prunes_only_old_soft_deletes_and_vacuums() {
        let connection = Connection::open_in_memory().unwrap();
        connection
            .execute_batch("CREATE TABLE books (id TEXT PRIMARY KEY, deleted_at TEXT);")
            .unwrap();

        let old = (Utc::now() - Duration::days(RETENTION_DAYS + 10)).to_rfc3339();
        let fresh = (Utc::now() - Duration::days(1)).to_rfc3339();
        connection
            .execute(
                "INSERT INTO books (id, deleted_at) VALUES ('live', NULL), ('old', ?1), ('fresh', ?2)",
                [&old, &fresh],
            )
            .unwrap();

        let report = run_retention(&connection, RETENTION_DAYS).unwrap();

        assert_eq!(report.pruned, 1);
        assert_eq!(count(&connection, "SELECT COUNT(*) FROM books"), 2);
        assert_eq!(
            count(&connection, "SELECT COUNT(*) FROM books WHERE id = 'old'"),
            0
        );
    }
}
