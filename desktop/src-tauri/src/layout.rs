//! WU6 version-gated, idempotent on-disk layout migration (FR-01, FR-02, FR-05,
//! INV-5).
//!
//! The legacy desktop tree scatters user data across `books/`, `covers/`,
//! `epub_cache/`, `parsed_epubs/`, `tmp/` and the `*.json` secret files at the
//! app-data root. The target tree groups them:
//!
//! ```text
//! data/books/      book binaries (id-derived names, FR-05/INV-3)
//! data/covers/     cover images
//! secrets/         DPAPI envelope files, adopted IN PLACE (never decrypted)
//! state/           non-secret operational state (cleanup queue/log, marker)
//! cache/           unified, purely-derivable cache (epub + parsed + tmp)
//! layout.json      version marker at the root (missing == version 0)
//! ```
//!
//! Order of operations is the safety mechanism: move files first, rewrite the
//! stored paths second, run the verification pass third, and bump the version
//! marker ONLY after verification succeeds. An interrupted run resumes: every
//! move skips an already-present destination, so re-running neither duplicates
//! nor loses a file. A failed verification leaves the marker unbumped and the
//! app on the legacy tree.
//!
//! 0.3.5 DPAPI secret files are moved byte-for-byte by `fs::rename` and are
//! never decrypted or re-encrypted here. Verification of the secrets directory
//! reads only the JSON envelope header (`v`/`alg`) of files that already parse
//! as JSON; it never calls DPAPI unprotect.

use std::fs;
use std::path::{Path, PathBuf};

use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};

use crate::error::{AppError, AppResult};

/// Current layout schema version written to `layout.json`.
pub const LAYOUT_VERSION: u32 = 1;

pub const DATA_DIR: &str = "data";
pub const SECRETS_DIR: &str = "secrets";
pub const STATE_DIR: &str = "state";
pub const CACHE_DIR: &str = "cache";
pub const BOOKS_SUBDIR: &str = "books";
pub const COVERS_SUBDIR: &str = "covers";

pub const MARKER_FILE: &str = "layout.json";
pub const JOURNAL_FILE: &str = "migration.json";

/// Legacy secret files (0.3.5 DPAPI envelopes) adopted under `secrets/`.
const SECRET_FILES: [&str; 3] = ["auth.json", "drive.json", "supabase-session.json"];
/// Legacy operational state files adopted under `state/`.
const STATE_FILES: [&str; 2] = ["cover_cleanup_queue.txt", "cover_cleanup.log"];

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LayoutMarker {
    pub version: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub migrated_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct MigrationJournal {
    #[serde(default)]
    pub steps: Vec<String>,
}

/// Outcome of one verification pass.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VerificationReport {
    pub rows_checked: u64,
    pub files_ok: u64,
    pub missing: Vec<String>,
    pub orphans: u64,
}

/// Result of running the migration.
#[derive(Debug, Clone)]
pub enum MigrationOutcome {
    /// Marker already at [`LAYOUT_VERSION`]; nothing to do.
    AlreadyCurrent { version: u32 },
    /// Moves, path rewrite and verification all succeeded; marker bumped.
    Migrated { version: u32, report: VerificationReport },
    /// Verification found missing files; marker NOT bumped, legacy tree live.
    VerificationFailed { report: VerificationReport },
}

// ── Path helpers ───────────────────────────────────────────────────────────

pub fn marker_path(root: &Path) -> PathBuf {
    root.join(MARKER_FILE)
}

pub fn journal_path(root: &Path) -> PathBuf {
    root.join(JOURNAL_FILE)
}

pub fn data_dir(root: &Path) -> PathBuf {
    root.join(DATA_DIR)
}

pub fn books_dir(root: &Path) -> PathBuf {
    data_dir(root).join(BOOKS_SUBDIR)
}

pub fn covers_dir(root: &Path) -> PathBuf {
    data_dir(root).join(COVERS_SUBDIR)
}

pub fn secrets_dir(root: &Path) -> PathBuf {
    root.join(SECRETS_DIR)
}

pub fn state_dir(root: &Path) -> PathBuf {
    root.join(STATE_DIR)
}

pub fn cache_dir(root: &Path) -> PathBuf {
    root.join(CACHE_DIR)
}

/// Stored book files resolve under `data/books/` only after the migration has
/// bumped the marker; before that they stay on the legacy `books/` root.
pub fn resolved_books_dir(root: &Path) -> PathBuf {
    if read_layout_version(root) >= LAYOUT_VERSION {
        books_dir(root)
    } else {
        root.join(BOOKS_SUBDIR)
    }
}

pub fn resolved_covers_dir(root: &Path) -> PathBuf {
    if read_layout_version(root) >= LAYOUT_VERSION {
        covers_dir(root)
    } else {
        root.join(COVERS_SUBDIR)
    }
}

// ── Marker + journal ───────────────────────────────────────────────────────

/// Read the layout version. A missing marker means version 0 (legacy tree),
/// never a clean install.
pub fn read_layout_version(root: &Path) -> u32 {
    let path = marker_path(root);
    let Ok(raw) = fs::read_to_string(&path) else {
        return 0;
    };
    serde_json::from_str::<LayoutMarker>(&raw).map(|marker| marker.version).unwrap_or(0)
}

pub fn write_layout_marker(root: &Path, version: u32) -> AppResult<()> {
    fs::create_dir_all(root)?;
    let marker = LayoutMarker { version, migrated_at: Some(chrono::Utc::now().to_rfc3339()) };
    let raw = serde_json::to_string_pretty(&marker)
        .map_err(|e| AppError::InvalidInput(format!("layout marker serialize: {e}")))?;
    let tmp = marker_path(root).with_extension("json.tmp");
    fs::write(&tmp, raw)?;
    fs::rename(&tmp, marker_path(root))?;
    Ok(())
}

fn write_journal(root: &Path, steps: &[String]) -> AppResult<()> {
    let journal = MigrationJournal { steps: steps.to_vec() };
    let raw = serde_json::to_string_pretty(&journal)
        .map_err(|e| AppError::InvalidInput(format!("migration journal serialize: {e}")))?;
    fs::write(journal_path(root), raw)?;
    Ok(())
}

// ── Migration ──────────────────────────────────────────────────────────────

/// Run the one-time layout migration. Idempotent: safe to call on every start.
/// Moves files first, rewrites stored paths in one transaction second, verifies
/// third, and bumps the marker only after verification succeeds.
pub fn migrate_layout(root: &Path, conn: &Connection) -> AppResult<MigrationOutcome> {
    let version = read_layout_version(root);
    if version >= LAYOUT_VERSION {
        return Ok(MigrationOutcome::AlreadyCurrent { version });
    }

    let mut steps = Vec::new();
    ensure_dirs(root)?;

    // 1. Move files first (each move skips an already-present destination).
    move_contents(&root.join(BOOKS_SUBDIR), &books_dir(root))?;
    steps.push("move:books".to_string());
    move_contents(&root.join(COVERS_SUBDIR), &covers_dir(root))?;
    steps.push("move:covers".to_string());
    move_contents(&root.join("epub_cache"), &cache_dir(root).join("epub"))?;
    steps.push("move:epub_cache".to_string());
    move_contents(&root.join("parsed_epubs"), &cache_dir(root).join("parsed"))?;
    steps.push("move:parsed_epubs".to_string());
    move_contents(&root.join("tmp"), &cache_dir(root).join("tmp"))?;
    steps.push("move:tmp".to_string());

    // The 0.3.5 DPAPI secret files are adopted IN PLACE at the app-data root:
    // their bytes are NEVER touched, decrypted, re-encrypted, or relocated. The
    // `secrets/` directory is reserved/created for future use, but moving the
    // live secret files would break every pre-change client's read path (and is
    // forbidden by the design's adopt-in-place rule).
    steps.push("adopt:secrets-in-place".to_string());
    for name in STATE_FILES {
        move_file(&root.join(name), &state_dir(root).join(name))?;
    }
    steps.push("move:state".to_string());
    write_journal(root, &steps)?;

    // 2. Rewrite stored paths second, in one transaction.
    rewrite_stored_paths(root, conn)?;
    steps.push("rewrite:paths".to_string());
    write_journal(root, &steps)?;

    // 3. Verification pass third.
    let report = verify_layout(root, conn)?;
    steps.push("verify".to_string());
    write_journal(root, &steps)?;

    if !report.missing.is_empty() {
        // 4. Marker NOT bumped; the app stays on the legacy tree.
        return Ok(MigrationOutcome::VerificationFailed { report });
    }

    // Sealed secrets must still be present and parseable as before (header only).
    if let Err(err) = verify_secret_headers(root) {
        return Ok(MigrationOutcome::VerificationFailed {
            report: VerificationReport { missing: vec![err.to_string()], ..report },
        });
    }

    // 5. Bump the marker LAST.
    write_layout_marker(root, LAYOUT_VERSION)?;
    Ok(MigrationOutcome::Migrated { version: LAYOUT_VERSION, report })
}

fn ensure_dirs(root: &Path) -> AppResult<()> {
    for dir in
        [books_dir(root), covers_dir(root), secrets_dir(root), state_dir(root), cache_dir(root)]
    {
        fs::create_dir_all(&dir)?;
    }
    Ok(())
}

/// Move every regular file under `from` into `to`, preserving relative paths.
/// Idempotent: a file whose destination already exists is skipped and the
/// source copy is removed, so a re-run after a partial move converges.
fn move_contents(from: &Path, to: &Path) -> AppResult<()> {
    if !from.exists() {
        return Ok(());
    }
    fs::create_dir_all(to)?;
    move_entries(from, to)?;
    // Remove the now-empty legacy tree; ignore a non-empty dir left by a
    // concurrent writer (the next run will finish it).
    let _ = remove_empty_dirs(from);
    Ok(())
}

fn move_entries(from: &Path, to: &Path) -> AppResult<()> {
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let source = entry.path();
        let target = to.join(entry.file_name());
        if source.is_dir() {
            fs::create_dir_all(&target)?;
            move_entries(&source, &target)?;
        } else {
            move_file(&source, &target)?;
        }
    }
    Ok(())
}

fn move_file(source: &Path, target: &Path) -> AppResult<()> {
    if !source.exists() {
        return Ok(());
    }
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    if target.exists() {
        fs::remove_file(source)?;
        return Ok(());
    }
    fs::rename(source, target)?;
    Ok(())
}

/// Recursively delete empty directories, deepest-first. Never deletes a
/// directory that still holds a file.
fn remove_empty_dirs(dir: &Path) -> std::io::Result<()> {
    if !dir.is_dir() {
        return Ok(());
    }
    for entry in fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_dir() {
            remove_empty_dirs(&path)?;
        }
    }
    if fs::read_dir(dir)?.next().is_none() {
        fs::remove_dir(dir)?;
    }
    Ok(())
}

/// Rewrite every stored path that points into a legacy directory to its target
/// location, in one transaction. Only `books.file_path` and
/// `book_covers.storage_path` carry on-disk library paths.
fn rewrite_stored_paths(root: &Path, conn: &Connection) -> AppResult<()> {
    let legacy_books = root.join(BOOKS_SUBDIR);
    let legacy_covers = root.join(COVERS_SUBDIR);
    let new_books = books_dir(root);
    let new_covers = covers_dir(root);

    let transaction = conn.unchecked_transaction()?;

    for (table, column, from_prefix, to_prefix) in [
        ("books", "file_path", &legacy_books, &new_books),
        ("book_covers", "storage_path", &legacy_covers, &new_covers),
    ] {
        let rows: Vec<(String, String)> = {
            let sql = format!("SELECT id, {column} FROM {table} WHERE {column} IS NOT NULL");
            let mut stmt = transaction.prepare(&sql)?;
            let mapped =
                stmt.query_map([], |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)))?;
            mapped.collect::<Result<Vec<_>, _>>()?
        };
        let update_sql = format!("UPDATE {table} SET {column} = ?1 WHERE id = ?2");
        for (id, stored) in rows {
            let Some(rewritten) = rewrite_prefix(&stored, from_prefix, to_prefix) else {
                continue;
            };
            transaction.execute(&update_sql, params![rewritten, id])?;
        }
    }

    transaction.commit()?;
    Ok(())
}

/// Map `stored` from the legacy prefix to the target prefix, or `None` when it
/// does not live under the legacy prefix (already migrated or external).
fn rewrite_prefix(stored: &str, from: &Path, to: &Path) -> Option<String> {
    let from_str = from.to_string_lossy();
    let rest = stored.strip_prefix(from_str.as_ref())?;
    let rest = rest.trim_start_matches(['/', '\\']);
    Some(to.join(rest).to_string_lossy().to_string())
}

/// Verification pass: every live book file and live cover must exist on disk,
/// and the managed directories must not hold unreferenced orphans.
pub fn verify_layout(root: &Path, conn: &Connection) -> AppResult<VerificationReport> {
    let mut report = VerificationReport::default();
    let mut referenced: std::collections::HashSet<String> = std::collections::HashSet::new();

    for (table, column, live_predicate) in [
        ("books", "file_path", "deleted_at IS NULL"),
        ("book_covers", "storage_path", "deleted_at IS NULL"),
    ] {
        let sql =
            format!("SELECT {column} FROM {table} WHERE {column} IS NOT NULL AND {live_predicate}");
        let mut stmt = conn.prepare(&sql)?;
        let paths =
            stmt.query_map([], |row| row.get::<_, String>(0))?.collect::<Result<Vec<_>, _>>()?;
        for path in paths {
            report.rows_checked += 1;
            referenced.insert(path.clone());
            if Path::new(&path).exists() {
                report.files_ok += 1;
            } else {
                report.missing.push(path);
            }
        }
    }

    report.orphans = count_orphans(&[books_dir(root), covers_dir(root)], &referenced)?;
    Ok(report)
}

fn count_orphans(
    dirs: &[PathBuf],
    referenced: &std::collections::HashSet<String>,
) -> AppResult<u64> {
    let mut orphans: u64 = 0;
    for dir in dirs {
        if !dir.is_dir() {
            continue;
        }
        for entry in fs::read_dir(dir)? {
            let path = entry?.path();
            if path.is_file() && !referenced.contains(&path.to_string_lossy().to_string()) {
                orphans += 1;
            }
        }
    }
    Ok(orphans)
}

/// Verify the sealed secret files were adopted without touching their bytes:
/// a file that parses as a DPAPI envelope must still carry the `{v, alg}`
/// header. Legacy plaintext is accepted as-is. DPAPI is NEVER invoked.
fn verify_secret_headers(root: &Path) -> AppResult<()> {
    for name in SECRET_FILES {
        let path = root.join(name);
        if !path.exists() {
            continue;
        }
        let raw = fs::read_to_string(&path)?;
        if let Ok(value) = serde_json::from_str::<serde_json::Value>(&raw) {
            if value.get("v").is_some()
                && value.get("alg").is_some()
                && value.get("data").and_then(|d| d.as_str()).is_none()
            {
                return Err(AppError::InvalidInput(format!(
                    "sealed secret {name} is missing its data field"
                )));
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;

    fn test_conn() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE books (id TEXT PRIMARY KEY, file_path TEXT, deleted_at TEXT);
             CREATE TABLE book_covers (id TEXT PRIMARY KEY, storage_path TEXT, deleted_at TEXT);",
        )
        .unwrap();
        conn
    }

    fn seed_legacy_book(root: &Path, conn: &Connection, id: &str, file: &str) {
        let path = root.join("books").join(file);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, b"epub").unwrap();
        conn.execute(
            "INSERT INTO books (id, file_path, deleted_at) VALUES (?1, ?2, NULL)",
            params![id, path.to_string_lossy().to_string()],
        )
        .unwrap();
    }

    #[test]
    fn missing_marker_is_version_zero() {
        let tmp = tempfile::tempdir().unwrap();
        assert_eq!(read_layout_version(tmp.path()), 0);
    }

    #[test]
    fn migration_moves_files_rewrites_paths_and_bumps_last() {
        let tmp = tempfile::tempdir().unwrap();
        let conn = test_conn();
        seed_legacy_book(tmp.path(), &conn, "g:1", "gutendex1.epub");

        let cover = tmp.path().join("covers").join("gutendex1.jpg");
        fs::create_dir_all(cover.parent().unwrap()).unwrap();
        fs::write(&cover, b"jpg").unwrap();
        conn.execute(
            "INSERT INTO book_covers (id, storage_path, deleted_at) VALUES ('c1', ?1, NULL)",
            params![cover.to_string_lossy().to_string()],
        )
        .unwrap();

        let outcome = migrate_layout(tmp.path(), &conn).unwrap();
        let MigrationOutcome::Migrated { report, .. } = outcome else {
            panic!("expected migrated");
        };
        assert!(report.missing.is_empty());
        assert_eq!(report.files_ok, 2);
        assert_eq!(read_layout_version(tmp.path()), LAYOUT_VERSION);

        let stored: String =
            conn.query_row("SELECT file_path FROM books WHERE id='g:1'", [], |r| r.get(0)).unwrap();
        assert!(stored.contains("data/books/") || stored.contains("data\\books\\"), "{stored}");
        assert!(Path::new(&stored).exists());
        assert!(!tmp.path().join("books").exists(), "legacy books dir must be gone");
    }

    #[test]
    fn fresh_install_bumps_with_no_files() {
        let tmp = tempfile::tempdir().unwrap();
        let conn = test_conn();
        let outcome = migrate_layout(tmp.path(), &conn).unwrap();
        assert!(matches!(outcome, MigrationOutcome::Migrated { .. }));
        assert_eq!(read_layout_version(tmp.path()), LAYOUT_VERSION);
    }

    #[test]
    fn migration_is_idempotent_and_never_duplicates() {
        let tmp = tempfile::tempdir().unwrap();
        let conn = test_conn();
        seed_legacy_book(tmp.path(), &conn, "g:1", "gutendex1.epub");
        migrate_layout(tmp.path(), &conn).unwrap();
        let second = migrate_layout(tmp.path(), &conn).unwrap();
        assert!(
            matches!(second, MigrationOutcome::AlreadyCurrent { version } if version == LAYOUT_VERSION)
        );
        let files: Vec<_> = fs::read_dir(books_dir(tmp.path()))
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().to_string())
            .collect();
        assert_eq!(files, vec!["gutendex1.epub".to_string()]);
    }

    #[test]
    fn interrupted_migration_resumes_without_loss() {
        let tmp = tempfile::tempdir().unwrap();
        let conn = test_conn();
        seed_legacy_book(tmp.path(), &conn, "g:1", "gutendex1.epub");
        // Simulate an interruption after the file moved but before the rewrite.
        fs::create_dir_all(books_dir(tmp.path())).unwrap();
        fs::rename(
            tmp.path().join("books").join("gutendex1.epub"),
            books_dir(tmp.path()).join("gutendex1.epub"),
        )
        .unwrap();
        let outcome = migrate_layout(tmp.path(), &conn).unwrap();
        assert!(matches!(outcome, MigrationOutcome::Migrated { .. }));
        let stored: String =
            conn.query_row("SELECT file_path FROM books WHERE id='g:1'", [], |r| r.get(0)).unwrap();
        assert!(Path::new(&stored).exists(), "resumed path must resolve: {stored}");
    }

    #[test]
    fn failed_verification_keeps_marker_unbumped() {
        let tmp = tempfile::tempdir().unwrap();
        let conn = test_conn();
        // A row references a file that does not exist anywhere.
        conn.execute(
            "INSERT INTO books (id, file_path, deleted_at) VALUES ('missing', ?1, NULL)",
            params![tmp.path().join("books").join("gone.epub").to_string_lossy().to_string()],
        )
        .unwrap();
        let outcome = migrate_layout(tmp.path(), &conn).unwrap();
        assert!(matches!(outcome, MigrationOutcome::VerificationFailed { .. }));
        assert_eq!(read_layout_version(tmp.path()), 0, "marker must stay unbumped");
    }

    #[test]
    fn secrets_are_adopted_in_place_without_decryption() {
        let tmp = tempfile::tempdir().unwrap();
        let conn = test_conn();
        // A sealed envelope whose DPAPI bytes are NOT valid for this profile.
        let envelope = r#"{"v":1,"alg":"dpapi-current-user","data":"deadbeef"}"#;
        fs::write(tmp.path().join("auth.json"), envelope).unwrap();
        let outcome = migrate_layout(tmp.path(), &conn).unwrap();
        assert!(matches!(outcome, MigrationOutcome::Migrated { .. }));
        // Bytes and location untouched; the reserved secrets/ dir exists.
        let kept = fs::read_to_string(tmp.path().join("auth.json")).unwrap();
        assert_eq!(kept, envelope, "secret bytes must be untouched");
        assert!(secrets_dir(tmp.path()).is_dir());
    }

    #[test]
    fn verify_reports_zero_orphans_on_a_clean_library() {
        let tmp = tempfile::tempdir().unwrap();
        let conn = test_conn();
        seed_legacy_book(tmp.path(), &conn, "g:1", "gutendex1.epub");
        let report = verify_layout(tmp.path(), &conn).unwrap();
        assert_eq!(report.orphans, 0);
        assert!(report.missing.is_empty());
    }
}
