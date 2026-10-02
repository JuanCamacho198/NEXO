use std::fs;
use std::path::{Path, PathBuf};

use rusqlite::OptionalExtension;

use crate::error::{AppError, AppResult};

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageStats {
    pub total_bytes: u64,
    pub db_bytes: u64,
    pub covers_bytes: u64,
    pub temp_bytes: u64,
    pub cache_bytes: u64,
    pub cover_bytes: u64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PerBookSize {
    pub id: String,
    pub title: String,
    pub bytes: u64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClearCacheResult {
    pub freed_bytes: u64,
}

fn map_io_error(err: std::io::Error, ctx: &str) -> AppError {
    if err.kind() == std::io::ErrorKind::PermissionDenied {
        AppError::InvalidInput(format!("storage.permission_denied: {}: {}", ctx, err))
    } else {
        AppError::Io(err)
    }
}

fn dir_size_recursive(path: &Path) -> AppResult<u64> {
    if !path.exists() {
        return Ok(0);
    }
    let metadata =
        fs::metadata(path).map_err(|e| map_io_error(e, &format!("metadata {}", path.display())))?;
    if metadata.is_file() {
        return Ok(metadata.len());
    }
    if !metadata.is_dir() {
        return Ok(0);
    }
    let entries =
        fs::read_dir(path).map_err(|e| map_io_error(e, &format!("read_dir {}", path.display())))?;
    let mut total: u64 = 0;
    for entry in entries {
        let entry =
            entry.map_err(|e| map_io_error(e, &format!("read_dir entry {}", path.display())))?;
        let p = entry.path();
        let m = match fs::metadata(&p) {
            Ok(m) => m,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(e) => return Err(map_io_error(e, &format!("metadata {}", p.display()))),
        };
        if m.is_dir() {
            total += dir_size_recursive(&p)?;
        } else {
            total += m.len();
        }
    }
    Ok(total)
}

pub fn get_db_size(db_path: &Path) -> AppResult<u64> {
    match fs::metadata(db_path) {
        Ok(m) => Ok(m.len()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(0),
        Err(e) => Err(map_io_error(e, &format!("db {}", db_path.display()))),
    }
}

pub fn vacuum_db(conn: &rusqlite::Connection) -> AppResult<()> {
    conn.execute("VACUUM", []).map_err(AppError::Database)?;
    Ok(())
}

pub fn compute_storage_stats(app_data_dir: &Path, db_path: &Path) -> AppResult<StorageStats> {
    let db_bytes = get_db_size(db_path)?;
    let covers_dir = crate::layout::covers_dir(app_data_dir);
    let legacy_covers_dir = app_data_dir.join("covers");
    let covers_tmp_dir = covers_dir.join("tmp");
    let legacy_covers_tmp_dir = legacy_covers_dir.join("tmp");
    let epub_cache_dir = crate::layout::cache_dir(app_data_dir).join("epub");
    let legacy_epub_cache_dir = app_data_dir.join("epub_cache");
    let tmp_dir = crate::layout::cache_dir(app_data_dir).join("tmp");
    let legacy_tmp_dir = app_data_dir.join("tmp");
    let parsed_tmp_dir = crate::layout::cache_dir(app_data_dir).join("parsed");
    let legacy_parsed_tmp_dir = app_data_dir.join("parsed_epubs");

    let covers_total = dir_size_recursive(&covers_dir)? + dir_size_recursive(&legacy_covers_dir)?;
    let covers_tmp_bytes =
        dir_size_recursive(&covers_tmp_dir)? + dir_size_recursive(&legacy_covers_tmp_dir)?;
    let covers_bytes = covers_total.saturating_sub(covers_tmp_bytes);

    let epub_cache_bytes =
        dir_size_recursive(&epub_cache_dir)? + dir_size_recursive(&legacy_epub_cache_dir)?;
    let tmp_bytes_raw = dir_size_recursive(&tmp_dir)? + dir_size_recursive(&legacy_tmp_dir)?;
    let parsed_tmp_bytes =
        dir_size_recursive(&parsed_tmp_dir)? + dir_size_recursive(&legacy_parsed_tmp_dir)?;

    let temp_bytes = covers_tmp_bytes + epub_cache_bytes + tmp_bytes_raw + parsed_tmp_bytes;
    let cache_bytes = covers_bytes + temp_bytes;
    let total_bytes = db_bytes + covers_bytes + temp_bytes;

    Ok(StorageStats {
        total_bytes,
        db_bytes,
        covers_bytes,
        temp_bytes,
        cache_bytes,
        cover_bytes: covers_bytes,
    })
}

fn remove_dir_contents_freed(path: &Path) -> AppResult<u64> {
    if !path.exists() {
        return Ok(0);
    }
    let metadata = match fs::metadata(path) {
        Ok(m) => m,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(0),
        Err(e) => return Err(map_io_error(e, &format!("metadata {}", path.display()))),
    };
    if metadata.is_file() {
        let size = metadata.len();
        fs::remove_file(path)
            .map_err(|e| map_io_error(e, &format!("remove_file {}", path.display())))?;
        return Ok(size);
    }
    let size = dir_size_recursive(path)?;
    fs::remove_dir_all(path)
        .map_err(|e| map_io_error(e, &format!("remove_dir_all {}", path.display())))?;
    fs::create_dir_all(path)
        .map_err(|e| map_io_error(e, &format!("create_dir_all {}", path.display())))?;
    Ok(size)
}

pub fn clear_cache(
    app_data_dir: &Path,
    kind: &str,
    deep: bool,
    conn: &rusqlite::Connection,
) -> AppResult<ClearCacheResult> {
    let covers_dirs = [crate::layout::covers_dir(app_data_dir), app_data_dir.join("covers")];
    let covers_tmp_dirs = [covers_dirs[0].join("tmp"), covers_dirs[1].join("tmp")];
    let cache = crate::layout::cache_dir(app_data_dir);
    let epub_cache_dirs = [cache.join("epub"), app_data_dir.join("epub_cache")];
    let tmp_dirs = [cache.join("tmp"), app_data_dir.join("tmp")];
    let parsed_tmp_dirs = [cache.join("parsed"), app_data_dir.join("parsed_epubs")];

    let mut freed: u64 = 0;
    match kind {
        "covers" => {
            for dir in &covers_dirs {
                freed += remove_dir_contents_freed(dir)?;
            }
        }
        "temp" => {
            for dir in &covers_tmp_dirs {
                freed += remove_dir_contents_freed(dir)?;
            }
            for dir in epub_cache_dirs.iter().chain(tmp_dirs.iter()).chain(parsed_tmp_dirs.iter()) {
                freed += remove_dir_contents_freed(dir)?;
            }
        }
        "all" => {
            for dir in &covers_dirs {
                freed += remove_dir_contents_freed(dir)?;
            }
            for dir in epub_cache_dirs.iter().chain(tmp_dirs.iter()).chain(parsed_tmp_dirs.iter()) {
                freed += remove_dir_contents_freed(dir)?;
            }
        }
        other => return Err(AppError::InvalidInput(format!("invalid clearCache kind: {}", other))),
    }

    if deep {
        vacuum_db(conn)?;
    }

    Ok(ClearCacheResult { freed_bytes: freed })
}

pub fn get_per_book_sizes(conn: &rusqlite::Connection) -> AppResult<Vec<PerBookSize>> {
    let mut stmt = conn
        .prepare(
            "SELECT id, title, file_path FROM books WHERE deleted_at IS NULL ORDER BY title ASC",
        )
        .map_err(AppError::Database)?;
    let rows = stmt
        .query_map([], |row| {
            let id: String = row.get(0)?;
            let title: String = row.get(1)?;
            let file_path: String = row.get(2)?;
            Ok((id, title, file_path))
        })
        .map_err(AppError::Database)?;

    let mut out = Vec::new();
    for r in rows {
        let (id, title, file_path) = r.map_err(AppError::Database)?;
        let bytes = match fs::metadata(&file_path) {
            Ok(m) => m.len(),
            Err(_) => 0,
        };
        out.push(PerBookSize { id, title, bytes });
    }
    Ok(out)
}

pub fn delete_book_data(
    conn: &rusqlite::Connection,
    app_data_dir: &Path,
    book_id: &str,
) -> AppResult<()> {
    let file_path: Option<String> = conn
        .query_row("SELECT file_path FROM books WHERE id = ?1 LIMIT 1", [book_id], |r| r.get(0))
        .optional()
        .map_err(AppError::Database)?;
    if let Some(fp) = file_path {
        let p = PathBuf::from(&fp);
        if p.exists() {
            let _ = fs::remove_file(&p);
        }
        let cover_path: Option<String> = conn
            .query_row(
                "SELECT storage_path FROM book_covers WHERE book_id = ?1 LIMIT 1",
                [book_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(AppError::Database)?;
        if let Some(cp) = cover_path {
            let _ = fs::remove_file(PathBuf::from(cp));
        }
        for cache_dir in [
            crate::layout::cache_dir(app_data_dir).join("epub").join(book_id),
            app_data_dir.join("epub_cache").join(book_id),
        ] {
            if cache_dir.exists() {
                let _ = fs::remove_dir_all(cache_dir);
            }
        }
    }
    Ok(())
}

pub fn cleanup_orphans(conn: &rusqlite::Connection, app_data_dir: &Path) -> AppResult<u64> {
    let mut removed: u64 = 0;
    let mut stmt =
        conn.prepare("SELECT id, storage_path FROM book_covers").map_err(AppError::Database)?;
    let rows = stmt
        .query_map([], |row| {
            let id: String = row.get(0)?;
            let storage_path: String = row.get(1)?;
            Ok((id, storage_path))
        })
        .map_err(AppError::Database)?;
    let mut orphan_ids: Vec<String> = Vec::new();
    for r in rows {
        let (_id, storage_path) = r.map_err(AppError::Database)?;
        if !Path::new(&storage_path).exists() {
            orphan_ids.push(_id);
        }
    }
    let covers_dirs = [crate::layout::covers_dir(app_data_dir), app_data_dir.join("covers")];
    for covers_dir in &covers_dirs {
        if !covers_dir.exists() {
            continue;
        }
        let known_paths: std::collections::HashSet<String> = {
            let mut s = std::collections::HashSet::new();
            let mut stmt2 =
                conn.prepare("SELECT storage_path FROM book_covers").map_err(AppError::Database)?;
            let rows2 =
                stmt2.query_map([], |row| row.get::<_, String>(0)).map_err(AppError::Database)?;
            s.extend(rows2.flatten());
            s
        };
        if let Ok(entries) = fs::read_dir(covers_dir) {
            for entry in entries.flatten() {
                let p = entry.path();
                if p.is_file() {
                    let ps = p.to_string_lossy().to_string();
                    if !known_paths.contains(&ps) {
                        if let Ok(m) = fs::metadata(&p) {
                            let sz = m.len();
                            if fs::remove_file(&p).is_ok() {
                                removed += sz;
                            }
                        } else if fs::remove_file(&p).is_ok() {
                            removed += 1;
                        }
                    }
                }
            }
        }
    }
    for oid in orphan_ids {
        conn.execute("DELETE FROM book_covers WHERE id = ?1", [&oid])
            .map_err(AppError::Database)?;
        removed += 1;
    }
    let book_ids: std::collections::HashSet<String> = {
        let mut s = std::collections::HashSet::new();
        let mut stmt3 = conn
            .prepare("SELECT id FROM books WHERE deleted_at IS NULL")
            .map_err(AppError::Database)?;
        let rows3 =
            stmt3.query_map([], |row| row.get::<_, String>(0)).map_err(AppError::Database)?;
        s.extend(rows3.flatten());
        s
    };
    let epub_cache_bases =
        [crate::layout::cache_dir(app_data_dir).join("epub"), app_data_dir.join("epub_cache")];
    for epub_cache_base in &epub_cache_bases {
        if !epub_cache_base.exists() {
            continue;
        }
        if let Ok(entries) = fs::read_dir(epub_cache_base) {
            for entry in entries.flatten() {
                let p = entry.path();
                if p.is_dir() {
                    if let Some(name) = p.file_name().and_then(|n| n.to_str()) {
                        if !book_ids.contains(name) {
                            if let Ok(sz) = dir_size_recursive(&p) {
                                if fs::remove_dir_all(&p).is_ok() {
                                    removed += sz;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Ok(removed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    #[test]
    fn dir_size_empty_returns_zero() {
        let tmp = TempDir::new().unwrap();
        let size = dir_size_recursive(tmp.path()).unwrap();
        assert_eq!(size, 0);
    }

    #[test]
    fn dir_size_sums_files() {
        let tmp = TempDir::new().unwrap();
        fs::write(tmp.path().join("a.txt"), vec![0u8; 100]).unwrap();
        fs::write(tmp.path().join("b.txt"), vec![0u8; 200]).unwrap();
        let size = dir_size_recursive(tmp.path()).unwrap();
        assert_eq!(size, 300);
    }

    #[test]
    fn dir_size_recursive_nested() {
        let tmp = TempDir::new().unwrap();
        let sub = tmp.path().join("sub");
        fs::create_dir(&sub).unwrap();
        fs::write(sub.join("c.txt"), vec![0u8; 50]).unwrap();
        fs::write(tmp.path().join("a.txt"), vec![0u8; 10]).unwrap();
        assert_eq!(dir_size_recursive(tmp.path()).unwrap(), 60);
    }

    #[test]
    fn get_db_size_missing_returns_zero() {
        let tmp = TempDir::new().unwrap();
        let db = tmp.path().join("missing.db");
        assert_eq!(get_db_size(&db).unwrap(), 0);
    }

    #[test]
    fn compute_storage_stats_basic() {
        let tmp = TempDir::new().unwrap();
        let db_path = tmp.path().join("nexo.db");
        fs::write(&db_path, vec![0u8; 1024]).unwrap();
        let covers = tmp.path().join("covers");
        fs::create_dir_all(&covers).unwrap();
        fs::write(covers.join("cover1.jpg"), vec![0u8; 512]).unwrap();
        let stats = compute_storage_stats(tmp.path(), &db_path).unwrap();
        assert_eq!(stats.db_bytes, 1024);
        assert_eq!(stats.covers_bytes, 512);
        assert!(stats.total_bytes >= 1536);
    }
}
