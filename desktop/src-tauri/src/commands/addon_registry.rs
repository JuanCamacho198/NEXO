//! Addon registry CRUD — thin Tauri commands over `installed_addons` (0017).
//! No user_books/outbox/sync access: isolation is structural.

use serde::{Deserialize, Serialize};
use tauri::State;

use super::{lock_repository, CommandError};
use crate::state::AppState;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstalledAddonDto {
    pub id: String,
    pub url: String,
    pub manifest_json: String,
    pub enabled: bool,
    pub added_at: i64,
}

pub fn list_installed_addons(
    conn: &rusqlite::Connection,
) -> rusqlite::Result<Vec<InstalledAddonDto>> {
    let mut statement = conn.prepare(
        "SELECT id, url, manifest_json, enabled, added_at FROM installed_addons ORDER BY added_at ASC, id ASC",
    )?;
    let rows = statement.query_map([], |row| {
        Ok(InstalledAddonDto {
            id: row.get(0)?,
            url: row.get(1)?,
            manifest_json: row.get(2)?,
            enabled: row.get::<_, i64>(3)? != 0,
            added_at: row.get(4)?,
        })
    })?;
    rows.collect()
}

/// UPSERT keyed by url-hash id. Reinstall semantics: update url/manifest,
/// preserve the existing `enabled` flag and original `added_at` (install order).
pub fn upsert_installed_addon(
    conn: &rusqlite::Connection,
    addon: &InstalledAddonDto,
) -> rusqlite::Result<()> {
    let existing: Option<(bool, i64)> = conn
        .query_row(
            "SELECT enabled, added_at FROM installed_addons WHERE id = ?1",
            [&addon.id],
            |row| Ok((row.get::<_, i64>(0)? != 0, row.get(1)?)),
        )
        .map(Some)
        .or_else(|e| if e == rusqlite::Error::QueryReturnedNoRows { Ok(None) } else { Err(e) })?;
    match existing {
        Some((enabled, added_at)) => {
            conn.execute(
                "UPDATE installed_addons SET url = ?2, manifest_json = ?3, enabled = ?4, added_at = ?5 WHERE id = ?1",
                rusqlite::params![addon.id, addon.url, addon.manifest_json, enabled as i64, added_at],
            )?;
        }
        None => {
            conn.execute(
                "INSERT INTO installed_addons (id, url, manifest_json, enabled, added_at) VALUES (?1, ?2, ?3, ?4, ?5)",
                rusqlite::params![addon.id, addon.url, addon.manifest_json, addon.enabled as i64, addon.added_at],
            )?;
        }
    }
    Ok(())
}

pub fn set_addon_enabled(
    conn: &rusqlite::Connection,
    id: &str,
    enabled: bool,
) -> rusqlite::Result<bool> {
    let changed = conn.execute(
        "UPDATE installed_addons SET enabled = ?2 WHERE id = ?1",
        rusqlite::params![id, enabled as i64],
    )?;
    Ok(changed > 0)
}

pub fn delete_installed_addon(conn: &rusqlite::Connection, id: &str) -> rusqlite::Result<bool> {
    // Uninstall revokes consent in the SAME transaction (unchecked_transaction
    // works on the shared `&Connection`, same pattern as commands/outbox.rs):
    // either both rows go or neither does, so a crash or a failing consent
    // revoke can never leave an orphaned grant behind. A reinstall therefore
    // always starts denied (0018, fail-closed). Any error rolls back and
    // propagates; the happy path is unchanged.
    let tx = conn.unchecked_transaction()?;
    let changed = tx.execute("DELETE FROM installed_addons WHERE id = ?1", [&id])?;
    super::addon_consent::delete_addon_consent(&tx, id)?;
    tx.commit()?;
    Ok(changed > 0)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn listInstalledAddons(
    state: State<'_, AppState>,
) -> Result<Vec<InstalledAddonDto>, CommandError> {
    let repository = lock_repository(&state)?;
    list_installed_addons(repository.connection())
        .map_err(|e| CommandError::from(crate::error::AppError::Database(e)))
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn upsertInstalledAddon(
    state: State<'_, AppState>,
    addon: InstalledAddonDto,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    upsert_installed_addon(repository.connection(), &addon)
        .map_err(|e| CommandError::from(crate::error::AppError::Database(e)))
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn setAddonEnabled(
    state: State<'_, AppState>,
    id: String,
    enabled: bool,
) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    set_addon_enabled(repository.connection(), &id, enabled)
        .map_err(|e| CommandError::from(crate::error::AppError::Database(e)))?;
    Ok(())
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn deleteInstalledAddon(state: State<'_, AppState>, id: String) -> Result<(), CommandError> {
    let repository = lock_repository(&state)?;
    delete_installed_addon(repository.connection(), &id)
        .map_err(|e| CommandError::from(crate::error::AppError::Database(e)))?;
    Ok(())
}

#[cfg(test)]
mod addon_registry_tests {
    use super::*;

    fn registry_connection() -> rusqlite::Connection {
        let conn = rusqlite::Connection::open_in_memory().unwrap();
        conn.execute_batch(include_str!("../../migrations/0001_init.sql")).unwrap();
        conn.execute_batch(include_str!("../../migrations/0002_books.sql")).unwrap();
        conn.execute_batch(include_str!("../../migrations/0017_addon_registry.sql")).unwrap();
        // delete_installed_addon also revokes the 0018 consent row on the same
        // connection, so the fixture must carry the consent table.
        conn.execute_batch(include_str!("../../migrations/0018_addon_consent.sql")).unwrap();
        conn
    }

    fn row(id: &str, url: &str, manifest: &str, enabled: bool, added_at: i64) -> InstalledAddonDto {
        InstalledAddonDto {
            id: id.to_string(),
            url: url.to_string(),
            manifest_json: manifest.to_string(),
            enabled,
            added_at,
        }
    }

    #[test]
    fn insert_then_list_preserves_order() {
        let conn = registry_connection();
        upsert_installed_addon(&conn, &row("b", "https://b.example/m.json", "{}", true, 200))
            .unwrap();
        upsert_installed_addon(&conn, &row("a", "https://a.example/m.json", "{}", true, 100))
            .unwrap();
        let rows = list_installed_addons(&conn).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].id, "a", "install order is added_at ASC");
        assert!(rows[0].enabled);
    }

    #[test]
    fn reinstall_updates_manifest_and_preserves_enabled() {
        let conn = registry_connection();
        upsert_installed_addon(
            &conn,
            &row("x", "https://x.example/m.json", "{\"v\":1}", false, 100),
        )
        .unwrap();
        upsert_installed_addon(
            &conn,
            &row("x", "https://x.example/m.json", "{\"v\":2}", true, 999),
        )
        .unwrap();
        let rows = list_installed_addons(&conn).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].manifest_json, "{\"v\":2}", "manifest updated");
        assert!(!rows[0].enabled, "enabled must be preserved on reinstall");
        assert_eq!(rows[0].added_at, 100, "original added_at preserved (install order)");
    }

    #[test]
    fn set_enabled_toggles_and_reports_missing() {
        let conn = registry_connection();
        upsert_installed_addon(&conn, &row("x", "https://x.example/m.json", "{}", true, 1))
            .unwrap();
        assert!(set_addon_enabled(&conn, "x", false).unwrap());
        assert!(!list_installed_addons(&conn).unwrap()[0].enabled);
        assert!(!set_addon_enabled(&conn, "missing", true).unwrap());
    }

    #[test]
    fn uninstall_removes_only_target_row() {
        let conn = registry_connection();
        upsert_installed_addon(&conn, &row("x", "https://x.example/m.json", "{}", true, 1))
            .unwrap();
        upsert_installed_addon(&conn, &row("y", "https://y.example/m.json", "{}", true, 2))
            .unwrap();
        assert!(delete_installed_addon(&conn, "x").unwrap());
        let rows = list_installed_addons(&conn).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].id, "y");
        assert!(!delete_installed_addon(&conn, "x").unwrap(), "double delete reports false");
    }

    /// Uninstall revokes consent in the SAME transaction: when the consent
    /// revoke fails, the uninstall must fail AND the addon row must survive
    /// (rollback — nothing partially deleted, no orphaned consent either).
    #[test]
    fn uninstall_rolls_back_when_consent_revoke_fails() {
        let conn = registry_connection();
        upsert_installed_addon(&conn, &row("x", "https://x.example/m.json", "{}", true, 1))
            .unwrap();
        crate::commands::addon_consent::set_addon_consent(&conn, "x", true, 1_000).unwrap();
        // Break the consent revoke: with no addon_consent table the second
        // DELETE fails, so the uninstall must fail atomically.
        conn.execute_batch("DROP TABLE addon_consent;").unwrap();

        let result = delete_installed_addon(&conn, "x");
        assert!(result.is_err(), "uninstall must surface the consent-revoke failure");

        let rows = list_installed_addons(&conn).unwrap();
        assert_eq!(rows.len(), 1, "failed uninstall must roll back the addon-row delete");
        assert_eq!(rows[0].id, "x");
    }

    /// Scope isolation: registry writes never touch user_books or sync_outbox.
    #[test]
    fn registry_writes_leave_user_books_and_outbox_untouched() {
        let conn = registry_connection();
        conn.execute_batch(include_str!("../../migrations/0013_sync_outbox.sql")).unwrap();
        let books_before: i64 =
            conn.query_row("SELECT COUNT(*) FROM books", [], |r| r.get(0)).unwrap();
        let outbox_before: i64 =
            conn.query_row("SELECT COUNT(*) FROM sync_outbox", [], |r| r.get(0)).unwrap();
        upsert_installed_addon(&conn, &row("x", "https://x.example/m.json", "{}", true, 1))
            .unwrap();
        upsert_installed_addon(&conn, &row("x", "https://x.example/m.json", "{\"v\":2}", false, 1))
            .unwrap();
        set_addon_enabled(&conn, "x", true).unwrap();
        delete_installed_addon(&conn, "x").unwrap();
        let books_after: i64 =
            conn.query_row("SELECT COUNT(*) FROM books", [], |r| r.get(0)).unwrap();
        let outbox_after: i64 =
            conn.query_row("SELECT COUNT(*) FROM sync_outbox", [], |r| r.get(0)).unwrap();
        assert_eq!(books_before, books_after);
        assert_eq!(outbox_before, outbox_after);
    }
}
