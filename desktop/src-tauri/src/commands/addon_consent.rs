//! Addon consent CRUD — per-addon network-consent markers over `addon_consent` (0018).
//!
//! Fail-closed: an absent row means denied (including newly installed addons).
//! `deleteInstalledAddon` additionally calls [`delete_addon_consent`] on the
//! SAME connection, so uninstall revokes consent atomically and a reinstall
//! cannot inherit it.
//!
//! No user_books/outbox/sync access: the table is a separate consent namespace,
//! so isolation is structural.

use serde::{Deserialize, Serialize};
use tauri::State;

use super::{lock_repository, CommandError};
use crate::state::AppState;

/// Durable per-addon consent row, exactly as stored (mirrors the TS store shape).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AddonConsentDto {
    pub addon_id: String,
    pub granted: bool,
    pub updated_at: i64,
}

/// List every stored consent row, ordered by addon id. A fresh addon has no
/// row here, which the frontend reads as denied (fail-closed default).
pub fn list_addon_consents(conn: &rusqlite::Connection) -> rusqlite::Result<Vec<AddonConsentDto>> {
    let mut statement = conn
        .prepare("SELECT addon_id, granted, updated_at FROM addon_consent ORDER BY addon_id ASC")?;
    let rows = statement.query_map([], |row| {
        Ok(AddonConsentDto {
            addon_id: row.get(0)?,
            granted: row.get::<_, i64>(1)? != 0,
            updated_at: row.get(2)?,
        })
    })?;
    rows.collect()
}

/// Grant or withdraw consent for one addon (upsert keyed by addon id).
/// Withdrawal keeps the row with `granted = 0` so the denial itself survives
/// a restart; uninstall removes the row entirely (see [`delete_addon_consent`]).
pub fn set_addon_consent(
    conn: &rusqlite::Connection,
    addon_id: &str,
    granted: bool,
    updated_at: i64,
) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO addon_consent (addon_id, granted, updated_at) VALUES (?1, ?2, ?3)
         ON CONFLICT(addon_id) DO UPDATE SET granted = excluded.granted,
                                             updated_at = excluded.updated_at",
        rusqlite::params![addon_id, granted as i64, updated_at],
    )?;
    Ok(())
}

/// Revoke consent for one addon. Called from `delete_installed_addon` on the
/// SAME connection, so uninstall revokes atomically and a reinstall cannot
/// inherit consent. Idempotent: revoking an absent row is a no-op success.
pub fn delete_addon_consent(conn: &rusqlite::Connection, addon_id: &str) -> rusqlite::Result<()> {
    conn.execute("DELETE FROM addon_consent WHERE addon_id = ?1", [addon_id])?;
    Ok(())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listAddonConsents(state: State<'_, AppState>) -> Result<Vec<AddonConsentDto>, CommandError> {
    let repository = lock_repository(&state)?;
    list_addon_consents(repository.connection())
        .map_err(|e| CommandError::from(crate::error::AppError::Database(e)))
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn setAddonConsent(
    state: State<'_, AppState>,
    id: String,
    granted: bool,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    let updated_at = chrono::Utc::now().timestamp();
    set_addon_consent(repository.connection(), &id, granted, updated_at)
        .map_err(|e| CommandError::from(crate::error::AppError::Database(e)))
}

#[cfg(test)]
mod addon_consent_tests {
    use super::*;
    use crate::commands::addon_registry::{
        delete_installed_addon, upsert_installed_addon, InstalledAddonDto,
    };

    fn consent_connection() -> rusqlite::Connection {
        let conn = rusqlite::Connection::open_in_memory().unwrap();
        conn.execute_batch(include_str!("../../migrations/0001_init.sql")).unwrap();
        conn.execute_batch(include_str!("../../migrations/0002_books.sql")).unwrap();
        conn.execute_batch(include_str!("../../migrations/0017_addon_registry.sql")).unwrap();
        conn.execute_batch(include_str!("../../migrations/0018_addon_consent.sql")).unwrap();
        conn
    }

    fn installed_row(id: &str) -> InstalledAddonDto {
        InstalledAddonDto {
            id: id.to_string(),
            url: format!("https://{id}.example/manifest.json"),
            manifest_json: "{\"v\":1}".to_string(),
            enabled: true,
            added_at: 1,
        }
    }

    /// Fail-closed default: a fresh addon has no consent row at all.
    #[test]
    fn fresh_addon_has_no_consent_row() {
        let conn = consent_connection();
        let rows = list_addon_consents(&conn).unwrap();
        assert!(rows.is_empty(), "fresh addon must have no consent row (denied by default)");
    }

    /// The list command serves an empty vec for a fresh addon (never null, never an error).
    #[test]
    fn list_addon_consents_empty_for_fresh_addon() {
        let conn = consent_connection();
        upsert_installed_addon(&conn, &installed_row("addon-fresh")).unwrap();
        let rows = list_addon_consents(&conn).unwrap();
        assert_eq!(rows, vec![], "installed-but-never-granted addon lists zero consents");
    }

    /// Grant persists; withdrawal overwrites durably (granted = 0, row kept).
    #[test]
    fn grant_persists_and_withdrawal_overwrites() {
        let conn = consent_connection();
        set_addon_consent(&conn, "addon-a", true, 1_000).unwrap();
        let rows = list_addon_consents(&conn).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].addon_id, "addon-a");
        assert!(rows[0].granted, "grant must persist granted = true");
        assert_eq!(rows[0].updated_at, 1_000);

        set_addon_consent(&conn, "addon-a", false, 2_000).unwrap();
        let rows = list_addon_consents(&conn).unwrap();
        assert_eq!(rows.len(), 1, "withdrawal keeps exactly one row");
        assert!(!rows[0].granted, "withdrawal must persist granted = false");
        assert_eq!(rows[0].updated_at, 2_000, "withdrawal must bump updated_at");
    }

    /// Consent is per addon: granting one leaves the other absent (denied).
    #[test]
    fn consent_is_per_addon_not_global() {
        let conn = consent_connection();
        set_addon_consent(&conn, "addon-a", true, 1_000).unwrap();
        let rows = list_addon_consents(&conn).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].addon_id, "addon-a");
        assert!(
            !rows.iter().any(|row| row.addon_id == "addon-b"),
            "granting addon-a must not grant addon-b"
        );
    }

    /// Re-granting upserts the timestamp instead of duplicating the row.
    #[test]
    fn set_upserts_updated_at_without_duplicating() {
        let conn = consent_connection();
        set_addon_consent(&conn, "addon-a", true, 1_000).unwrap();
        set_addon_consent(&conn, "addon-a", true, 3_000).unwrap();
        let rows = list_addon_consents(&conn).unwrap();
        assert_eq!(rows.len(), 1, "PRIMARY KEY must keep exactly one row per addon");
        assert_eq!(rows[0].updated_at, 3_000);
        assert!(rows[0].granted);
    }

    /// Uninstall revokes consent on the same connection: the row is gone, so a
    /// reinstall cannot inherit it. A sibling addon's consent is untouched.
    #[test]
    fn uninstall_removes_the_consent_row_atomically() {
        let conn = consent_connection();
        upsert_installed_addon(&conn, &installed_row("addon-a")).unwrap();
        upsert_installed_addon(&conn, &installed_row("addon-b")).unwrap();
        set_addon_consent(&conn, "addon-a", true, 1_000).unwrap();
        set_addon_consent(&conn, "addon-b", true, 1_000).unwrap();

        assert!(delete_installed_addon(&conn, "addon-a").unwrap());

        let rows = list_addon_consents(&conn).unwrap();
        assert_eq!(rows.len(), 1, "only the uninstalled addon's consent is revoked");
        assert_eq!(rows[0].addon_id, "addon-b");
        assert!(rows[0].granted, "sibling consent survives the uninstall");
    }

    /// Scope isolation: consent writes never touch user_books or sync_outbox.
    #[test]
    fn consent_writes_leave_user_books_and_outbox_untouched() {
        let conn = consent_connection();
        conn.execute_batch(include_str!("../../migrations/0013_sync_outbox.sql")).unwrap();
        let books_before: i64 =
            conn.query_row("SELECT COUNT(*) FROM books", [], |row| row.get(0)).unwrap();
        let outbox_before: i64 =
            conn.query_row("SELECT COUNT(*) FROM sync_outbox", [], |row| row.get(0)).unwrap();
        set_addon_consent(&conn, "addon-a", true, 1_000).unwrap();
        set_addon_consent(&conn, "addon-a", false, 2_000).unwrap();
        delete_addon_consent(&conn, "addon-a").unwrap();
        let books_after: i64 =
            conn.query_row("SELECT COUNT(*) FROM books", [], |row| row.get(0)).unwrap();
        let outbox_after: i64 =
            conn.query_row("SELECT COUNT(*) FROM sync_outbox", [], |row| row.get(0)).unwrap();
        assert_eq!(books_before, books_after);
        assert_eq!(outbox_before, outbox_after);
    }

    /// The IPC DTO keeps the camelCase wire shape the TS store reads.
    #[test]
    fn consent_dto_serializes_the_camel_case_ipc_shape() {
        let dto =
            AddonConsentDto { addon_id: "addon-a".to_string(), granted: true, updated_at: 1_000 };
        let json = serde_json::to_string(&dto).unwrap();
        assert_eq!(json, r#"{"addonId":"addon-a","granted":true,"updatedAt":1000}"#);
        let parsed: AddonConsentDto = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, dto);
    }
}
