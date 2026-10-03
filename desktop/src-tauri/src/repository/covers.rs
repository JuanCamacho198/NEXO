use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use chrono::Utc;

use rusqlite::{params, OptionalExtension};
use tauri::Manager;
use uuid::Uuid;

use super::LibraryRepository;
use crate::error::{AppError, AppResult};
use crate::filename::canonical_stem;
use crate::models::BookCoverDto;

pub(super) fn upsert_book_cover_from_file(
    repo: &LibraryRepository,
    app: &tauri::AppHandle,
    book_id: &str,
    source_cover_path: &Path,
) -> AppResult<BookCoverDto> {
    if book_id.trim().is_empty() {
        return Err(AppError::MissingBookId);
    }
    if !source_cover_path.exists() {
        return Err(AppError::InvalidInput(format!(
            "Cover source file does not exist: {}",
            source_cover_path.display()
        )));
    }

    let covers_dir = resolve_covers_dir(repo, app)?;
    fs::create_dir_all(&covers_dir)?;
    let extension = source_cover_path
        .extension()
        .and_then(|ext| ext.to_str())
        .map(|ext| ext.to_ascii_lowercase())
        .unwrap_or_else(|| "bin".to_string());
    // A catalog id contains `:`, which is an NTFS ADS separator, so the cover
    // filename uses the same sanitized stem as the book file.
    let stem = canonical_stem(book_id.trim());
    let storage_path = covers_dir.join(format!("{stem}.{extension}"));

    fs::copy(source_cover_path, &storage_path)?;
    let metadata = fs::metadata(&storage_path)?;
    let mime_type = mime_type_from_extension(&extension);
    let now = Utc::now().to_rfc3339();

    let existing_cover: Option<(String, String)> = repo
        .connection
        .query_row(
            "SELECT id, storage_path
             FROM book_covers
             WHERE book_id = ?1 AND deleted_at IS NULL
             LIMIT 1",
            params![book_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;

    let cover_id = existing_cover
        .as_ref()
        .map(|(id, _)| id.clone())
        .unwrap_or_else(|| Uuid::new_v4().to_string());

    repo.connection.execute(
        "INSERT INTO book_covers (id, book_id, storage_path, mime_type, width, height, byte_size, checksum, created_at, updated_at, deleted_at, version)
         VALUES (?1, ?2, ?3, ?4, NULL, NULL, ?5, NULL, ?6, ?6, NULL, 1)
         ON CONFLICT(id) DO UPDATE SET
           storage_path = excluded.storage_path,
           mime_type = excluded.mime_type,
           width = excluded.width,
           height = excluded.height,
           byte_size = excluded.byte_size,
           checksum = excluded.checksum,
           updated_at = excluded.updated_at,
           deleted_at = NULL,
           version = book_covers.version + 1",
        params![
            cover_id,
            book_id,
            storage_path.to_string_lossy().to_string(),
            mime_type,
            metadata.len() as i64,
            now,
        ],
    )?;

    if let Some((_, old_storage_path)) = existing_cover {
        let new_storage_path = storage_path.to_string_lossy().to_string();
        if old_storage_path != new_storage_path {
            let _ = fs::remove_file(old_storage_path);
        }
    }

    Ok(BookCoverDto {
        book_id: book_id.to_string(),
        storage_path: storage_path.to_string_lossy().to_string(),
        mime_type: mime_type.to_string(),
        width: None,
        height: None,
        byte_size: metadata.len() as i64,
    })
}

pub(super) fn upsert_book_cover_from_bytes(
    repo: &LibraryRepository,
    app: &tauri::AppHandle,
    book_id: &str,
    data: &[u8],
    mime_type: Option<&str>,
) -> AppResult<BookCoverDto> {
    if book_id.trim().is_empty() {
        return Err(AppError::MissingBookId);
    }
    if data.is_empty() {
        return Err(AppError::InvalidInput("Cover binary payload cannot be empty".to_string()));
    }

    let normalized_mime = mime_type
        .map(|value| value.trim().to_ascii_lowercase())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "image/png".to_string());

    let extension = extension_from_mime_type(&normalized_mime).ok_or_else(|| {
        AppError::InvalidInput(format!("Unsupported cover mime type: {}", normalized_mime))
    })?;

    let covers_dir = resolve_covers_dir(repo, app)?;
    fs::create_dir_all(&covers_dir)?;
    // Same sanitized stem as the book file: `:` cannot reach the filesystem.
    let stem = canonical_stem(book_id.trim());
    let storage_path = covers_dir.join(format!("{stem}.{extension}"));

    fs::write(&storage_path, data)?;
    let metadata = fs::metadata(&storage_path)?;
    let now = Utc::now().to_rfc3339();

    let existing_cover: Option<(String, String)> = repo
        .connection
        .query_row(
            "SELECT id, storage_path
             FROM book_covers
             WHERE book_id = ?1 AND deleted_at IS NULL
             LIMIT 1",
            params![book_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;

    let cover_id = existing_cover
        .as_ref()
        .map(|(id, _)| id.clone())
        .unwrap_or_else(|| Uuid::new_v4().to_string());

    repo.connection.execute(
        "INSERT INTO book_covers (id, book_id, storage_path, mime_type, width, height, byte_size, checksum, created_at, updated_at, deleted_at, version)
         VALUES (?1, ?2, ?3, ?4, NULL, NULL, ?5, NULL, ?6, ?6, NULL, 1)
         ON CONFLICT(id) DO UPDATE SET
           storage_path = excluded.storage_path,
           mime_type = excluded.mime_type,
           width = excluded.width,
           height = excluded.height,
           byte_size = excluded.byte_size,
           checksum = excluded.checksum,
           updated_at = excluded.updated_at,
           deleted_at = NULL,
           version = book_covers.version + 1",
        params![
            cover_id,
            book_id,
            storage_path.to_string_lossy().to_string(),
            normalized_mime,
            metadata.len() as i64,
            now,
        ],
    )?;

    if let Some((_, old_storage_path)) = existing_cover {
        let new_storage_path = storage_path.to_string_lossy().to_string();
        if old_storage_path != new_storage_path {
            let _ = fs::remove_file(old_storage_path);
        }
    }

    Ok(BookCoverDto {
        book_id: book_id.to_string(),
        storage_path: storage_path.to_string_lossy().to_string(),
        mime_type: normalized_mime,
        width: None,
        height: None,
        byte_size: metadata.len() as i64,
    })
}

fn resolve_covers_dir(_repo: &LibraryRepository, app: &tauri::AppHandle) -> AppResult<PathBuf> {
    let app_data_dir =
        app.path().app_data_dir().map_err(|err| AppError::InvalidInput(err.to_string()))?;
    Ok(crate::layout::resolved_covers_dir(&app_data_dir))
}

fn deferred_cleanup_queue_path(
    _repo: &LibraryRepository,
    app: &tauri::AppHandle,
) -> AppResult<PathBuf> {
    let app_data_dir =
        app.path().app_data_dir().map_err(|err| AppError::InvalidInput(err.to_string()))?;
    Ok(crate::layout::state_dir(&app_data_dir).join("cover_cleanup_queue.txt"))
}

fn deferred_cleanup_log_path(
    _repo: &LibraryRepository,
    app: &tauri::AppHandle,
) -> AppResult<PathBuf> {
    let app_data_dir =
        app.path().app_data_dir().map_err(|err| AppError::InvalidInput(err.to_string()))?;
    Ok(crate::layout::state_dir(&app_data_dir).join("cover_cleanup.log"))
}

pub(super) fn enqueue_cover_cleanup(
    repo: &LibraryRepository,
    app: &tauri::AppHandle,
    storage_path: &str,
) -> AppResult<()> {
    let queue_path = deferred_cleanup_queue_path(repo, app)?;
    if let Some(parent) = queue_path.parent() {
        fs::create_dir_all(parent)?;
    }

    let existing = fs::read_to_string(&queue_path).unwrap_or_default();
    if existing.lines().any(|line| line.trim() == storage_path) {
        return Ok(());
    }

    let mut file = OpenOptions::new().create(true).append(true).open(queue_path)?;
    writeln!(file, "{}", storage_path)?;
    Ok(())
}

pub(super) fn run_deferred_cover_cleanup(
    repo: &LibraryRepository,
    app: &tauri::AppHandle,
) -> AppResult<()> {
    let queue_path = deferred_cleanup_queue_path(repo, app)?;
    if !queue_path.exists() {
        return Ok(());
    }

    let queue = fs::read_to_string(&queue_path).unwrap_or_default();
    let mut remaining: Vec<String> = Vec::new();

    for raw_line in queue.lines() {
        let candidate = raw_line.trim();
        if candidate.is_empty() {
            continue;
        }

        let path = PathBuf::from(candidate);
        if !path.exists() {
            continue;
        }

        match fs::remove_file(&path) {
            Ok(_) => {}
            Err(err) => {
                if err.kind() == std::io::ErrorKind::NotFound {
                    continue;
                }
                remaining.push(candidate.to_string());
                log_recoverable_cover_error(
                    repo,
                    app,
                    &format!(
                        "deferred_cover_cleanup_retry_failed path={} error={}",
                        candidate, err
                    ),
                )?;
            }
        }
    }

    if remaining.is_empty() {
        let _ = fs::remove_file(queue_path);
    } else {
        fs::write(queue_path, format!("{}\n", remaining.join("\n")))?;
    }

    Ok(())
}

pub(super) fn log_recoverable_cover_error(
    repo: &LibraryRepository,
    app: &tauri::AppHandle,
    message: &str,
) -> AppResult<()> {
    let log_path = deferred_cleanup_log_path(repo, app)?;
    if let Some(parent) = log_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let now = Utc::now().to_rfc3339();
    let mut file = OpenOptions::new().create(true).append(true).open(log_path)?;
    writeln!(file, "[{}] {}", now, message)?;
    Ok(())
}

pub(super) fn find_cover_source_path(book_source_path: &Path) -> Option<PathBuf> {
    let stem = book_source_path.file_stem()?.to_str()?;
    let parent = book_source_path.parent()?;
    let supported_extensions = ["jpg", "jpeg", "png", "webp"];

    for ext in supported_extensions {
        let candidate = parent.join(format!("{}.{}", stem, ext));
        if candidate.exists() {
            return Some(candidate);
        }
    }

    None
}

fn mime_type_from_extension(ext: &str) -> &'static str {
    match ext {
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "webp" => "image/webp",
        _ => "application/octet-stream",
    }
}

fn extension_from_mime_type(mime_type: &str) -> Option<&'static str> {
    match mime_type {
        "image/jpeg" | "image/jpg" => Some("jpg"),
        "image/png" => Some("png"),
        "image/webp" => Some("webp"),
        _ => None,
    }
}

/// Extract cover image from an EPUB file and save it to the covers directory.
/// Chain: cover-image (manifest properties) → meta name=cover → guide type=cover → heuristic (cover|portada|cubierta) → first image
/// Returns Ok(Some(cover)) on success, Ok(None) if no cover found, Err on failure.
pub(super) fn extract_epub_cover(
    repo: &LibraryRepository,
    app: &tauri::AppHandle,
    epub_path: &Path,
    book_id: &str,
) -> AppResult<Option<BookCoverDto>> {
    if !epub_path.exists() {
        return Ok(None);
    }

    // Step 1-4 via OPF chain (epub_extractor::resolve_cover)
    if let Ok(doc) = epub::doc::EpubDoc::new(epub_path) {
        if let Some(href) = crate::services::epub_extractor::resolve_cover(&doc, epub_path) {
            if let Ok(file) = std::fs::File::open(epub_path) {
                if let Ok(mut archive) = zip::ZipArchive::new(file) {
                    // Try exact href and variants
                    let candidates = [href.clone(), href.replace('\\', "/")];
                    for name in &candidates {
                        if let Ok(mut entry) = archive.by_name(name) {
                            let mut data = Vec::new();
                            if entry.read_to_end(&mut data).is_ok() && !data.is_empty() {
                                let ext =
                                    name.rsplit('.').next().unwrap_or("jpg").to_ascii_lowercase();
                                let mime_type = mime_type_from_extension(&ext);
                                let cover = upsert_book_cover_from_bytes(
                                    repo,
                                    app,
                                    book_id,
                                    &data,
                                    Some(mime_type),
                                )?;
                                return Ok(Some(cover));
                            }
                        }
                    }
                    // Fallback: match by file name suffix (handles guide href without OEBPS prefix)
                    let target_file = Path::new(&href)
                        .file_name()
                        .and_then(|n| n.to_str())
                        .unwrap_or(&href)
                        .to_ascii_lowercase();
                    for i in 0..archive.len() {
                        if let Ok(mut entry) = archive.by_index(i) {
                            if entry.name().to_ascii_lowercase().ends_with(&target_file) {
                                let mut data = Vec::new();
                                if entry.read_to_end(&mut data).is_ok() && !data.is_empty() {
                                    let ext = entry
                                        .name()
                                        .rsplit('.')
                                        .next()
                                        .unwrap_or("jpg")
                                        .to_ascii_lowercase();
                                    let mime_type = mime_type_from_extension(&ext);
                                    let cover = upsert_book_cover_from_bytes(
                                        repo,
                                        app,
                                        book_id,
                                        &data,
                                        Some(mime_type),
                                    )?;
                                    return Ok(Some(cover));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Fallback heuristic (original behavior) — cover|portada|cubierta → first image
    let file = match std::fs::File::open(epub_path) {
        Ok(f) => f,
        Err(_) => return Ok(None),
    };

    let mut archive = match zip::ZipArchive::new(file) {
        Ok(a) => a,
        Err(_) => return Ok(None),
    };

    // Supported image extensions
    let image_exts = ["jpg", "jpeg", "png", "webp"];

    // Pass 1: Look for files with "cover" in the name (case-insensitive)
    let mut cover_idx: Option<usize> = None;
    let mut cover_ext: Option<String> = None;

    for i in 0..archive.len() {
        let Ok(entry) = archive.by_index(i) else { continue };
        let name_lower = entry.name().to_ascii_lowercase();

        if name_lower.contains("cover")
            || name_lower.contains("portada")
            || name_lower.contains("cubierta")
        {
            if let Some(ext) = name_lower.rsplit('.').next() {
                if image_exts.contains(&ext) {
                    cover_idx = Some(i);
                    cover_ext = Some(ext.to_string());
                    break;
                }
            }
        }
    }

    // Pass 2: If no cover-named file found, take the first image in the archive
    if cover_idx.is_none() {
        for i in 0..archive.len() {
            let Ok(entry) = archive.by_index(i) else { continue };
            let name_lower = entry.name().to_ascii_lowercase();
            if let Some(ext) = name_lower.rsplit('.').next() {
                if image_exts.contains(&ext) {
                    cover_idx = Some(i);
                    cover_ext = Some(ext.to_string());
                    break;
                }
            }
        }
    }

    let idx = match cover_idx {
        Some(i) => i,
        None => return Ok(None),
    };

    let ext = match &cover_ext {
        Some(e) => e.clone(),
        None => return Ok(None),
    };

    // Read the cover image data
    let mut entry = match archive.by_index(idx) {
        Ok(e) => e,
        Err(_) => return Ok(None),
    };

    let mut data = Vec::new();
    if entry.read_to_end(&mut data).is_err() || data.is_empty() {
        return Ok(None);
    }

    let mime_type = mime_type_from_extension(&ext);

    // Save via existing upsert method
    let cover = upsert_book_cover_from_bytes(repo, app, book_id, &data, Some(mime_type))?;
    Ok(Some(cover))
}

pub(super) fn delete_book_cover(
    repo: &LibraryRepository,
    app: &tauri::AppHandle,
    book_id: &str,
) -> AppResult<()> {
    let book_id = book_id.trim();
    if book_id.is_empty() {
        return Err(AppError::MissingBookId);
    }

    // Query the current cover storage_path
    let cover: Option<(String, String)> = repo
        .connection
        .query_row(
            "SELECT id, storage_path
             FROM book_covers
             WHERE book_id = ?1 AND deleted_at IS NULL
             LIMIT 1",
            params![book_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;

    let now = Utc::now().to_rfc3339();

    if let Some((_, storage_path)) = &cover {
        // Delete the cover file from disk
        let path = PathBuf::from(storage_path);
        if path.exists() {
            if let Err(err) = std::fs::remove_file(&path) {
                if err.kind() != std::io::ErrorKind::NotFound {
                    enqueue_cover_cleanup(repo, app, storage_path)?;
                    log_recoverable_cover_error(
                        repo,
                        app,
                        &format!(
                            "deferred_cover_cleanup_queued book_id={} path={} error={}",
                            book_id, storage_path, err
                        ),
                    )?;
                }
            }
        }
    }

    // Soft-delete and set user_deleted flag
    repo.connection.execute(
        "UPDATE book_covers
         SET deleted_at = ?1, user_deleted = 1
         WHERE book_id = ?2 AND deleted_at IS NULL",
        params![now, book_id],
    )?;

    let _ = run_deferred_cover_cleanup(repo, app);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use uuid::Uuid;

    #[test]
    fn cover_source_path_prefers_supported_sidecar_assets() {
        let temp_dir = std::env::temp_dir().join(format!("nexo_cover_test_{}", Uuid::new_v4()));
        fs::create_dir_all(&temp_dir).unwrap();

        let book_path = temp_dir.join("sample.epub");
        fs::write(&book_path, b"dummy").unwrap();
        let png_path = temp_dir.join("sample.png");
        fs::write(&png_path, b"cover").unwrap();

        let found = LibraryRepository::find_cover_source_path(&book_path);
        assert_eq!(found, Some(png_path));

        let _ = fs::remove_dir_all(&temp_dir);
    }
}
