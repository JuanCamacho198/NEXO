use std::collections::HashSet;
use std::path::Path;

use super::sql_text;
use super::LibraryRepository;
use crate::error::{AppError, AppResult};
use crate::models::{
    BookDeleteInput, BookDto, BookImportInput, LibraryBookDto, ScanFolderResultDto,
    ScannedBookFileDto,
};
use chrono::Utc;
use epub::doc::EpubDoc;
use rusqlite::{params, OptionalExtension};
use std::fs;
use std::path::PathBuf;
use tauri::Manager;
use uuid::Uuid;

pub fn scan_folder(repo: &LibraryRepository, path: &str) -> AppResult<ScanFolderResultDto> {
    let root = PathBuf::from(path);
    if !root.exists() {
        return Err(AppError::InvalidInput(format!("Folder does not exist: {}", path)));
    }
    if !root.is_dir() {
        return Err(AppError::InvalidInput(format!("Path is not a folder: {}", path)));
    }

    let existing_filenames = repo.existing_book_filenames_lowercase()?;
    let mut files: Vec<ScannedBookFileDto> = Vec::new();
    let mut skipped_unsupported_count: i64 = 0;
    let mut skipped_unreadable_count: i64 = 0;
    let mut pending_dirs = vec![root];

    while let Some(current_dir) = pending_dirs.pop() {
        let entries = match fs::read_dir(&current_dir) {
            Ok(entries) => entries,
            Err(_) => {
                skipped_unreadable_count += 1;
                continue;
            }
        };

        for entry_result in entries {
            let entry = match entry_result {
                Ok(entry) => entry,
                Err(_) => {
                    skipped_unreadable_count += 1;
                    continue;
                }
            };

            let file_type = match entry.file_type() {
                Ok(file_type) => file_type,
                Err(_) => {
                    skipped_unreadable_count += 1;
                    continue;
                }
            };

            let entry_path = entry.path();
            if file_type.is_dir() {
                pending_dirs.push(entry_path);
                continue;
            }
            if !file_type.is_file() {
                skipped_unsupported_count += 1;
                continue;
            }

            let extension = entry_path
                .extension()
                .and_then(|value| value.to_str())
                .map(|value| value.to_ascii_lowercase());
            let Some(format) = extension else {
                skipped_unsupported_count += 1;
                continue;
            };
            if format != "pdf" && format != "epub" {
                skipped_unsupported_count += 1;
                continue;
            }

            let file_name = entry_path
                .file_name()
                .and_then(|value| value.to_str())
                .map(|value| value.to_string())
                .unwrap_or_else(|| entry_path.to_string_lossy().to_string());

            let file_name_lower = file_name.to_ascii_lowercase();
            let is_duplicate = existing_filenames.contains(&file_name_lower);

            files.push(ScannedBookFileDto {
                full_path: entry_path.to_string_lossy().to_string(),
                file_name,
                format,
                is_duplicate,
            });
        }
    }

    files.sort_by(|a, b| a.full_path.cmp(&b.full_path));

    Ok(ScanFolderResultDto { files, skipped_unsupported_count, skipped_unreadable_count })
}

pub fn import_book(
    repo: &LibraryRepository,
    app: tauri::AppHandle,
    input: BookImportInput,
) -> AppResult<BookDto> {
    let source_path = PathBuf::from(&input.source_path);
    if !source_path.exists() {
        return Err(AppError::InvalidInput(format!(
            "Source file does not exist: {}",
            input.source_path
        )));
    }

    let app_data_dir =
        app.path().app_data_dir().map_err(|err| AppError::InvalidInput(err.to_string()))?;
    let books_dir = app_data_dir.join("books");
    std::fs::create_dir_all(&books_dir).map_err(|err| {
        AppError::InvalidInput(format!("Failed to create books directory: {}", err))
    })?;

    let file_name = source_path
        .file_name()
        .ok_or_else(|| AppError::InvalidInput("Invalid file name".to_string()))?
        .to_string_lossy()
        .to_string();

    let dest_path = books_dir.join(&file_name);
    if dest_path.exists() {
        let stem = source_path
            .file_stem()
            .ok_or_else(|| AppError::InvalidInput("Invalid file name".to_string()))?
            .to_string_lossy()
            .to_string();
        let ext = source_path.extension().unwrap_or_default().to_string_lossy().to_string();
        let unique_name = format!("{}_{}.{}", stem, Uuid::new_v4(), ext);
        let dest_path = books_dir.join(unique_name);
        std::fs::copy(&source_path, &dest_path)
            .map_err(|err| AppError::InvalidInput(format!("Failed to copy file: {}", err)))?;
    } else {
        std::fs::copy(&source_path, &dest_path)
            .map_err(|err| AppError::InvalidInput(format!("Failed to copy file: {}", err)))?;
    }

    let now = Utc::now().to_rfc3339();
    let title = input.title.unwrap_or_else(|| {
        source_path.file_stem().unwrap_or_default().to_string_lossy().to_string()
    });
    let author = input.author.unwrap_or_default();
    let format = input.format;
    let genre = input.genre.and_then(|value| {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.to_string())
        }
    });

    // Extract language and publication_date from EPUB or PDF metadata
    let (language, publication_date): (Option<String>, Option<String>) = match format.as_str() {
        "epub" => match EpubDoc::new(&dest_path) {
            Ok(doc) => {
                let lang = doc.mdata("language").and_then(|v| {
                    let trimmed = v.value.trim().to_string();
                    if trimmed.is_empty() {
                        None
                    } else {
                        Some(trimmed)
                    }
                });
                let date = doc.mdata("date").and_then(|v| {
                    let trimmed = v.value.trim().to_string();
                    if trimmed.is_empty() {
                        None
                    } else {
                        Some(trimmed)
                    }
                });
                (lang, date)
            }
            Err(_) => (None, None),
        },
        "pdf" => {
            let date = lopdf::Document::load(&dest_path).ok().and_then(|doc| {
                doc.trailer.get(b"Info").ok().and_then(|info| {
                    if let Ok(info_dict) = info.as_dict() {
                        info_dict.get(b"/CreationDate").ok().and_then(|v| match v {
                            lopdf::Object::String(s, _) => {
                                let s = String::from_utf8_lossy(s).to_string();
                                if s.is_empty() {
                                    None
                                } else {
                                    Some(s)
                                }
                            }
                            _ => None,
                        })
                    } else {
                        None
                    }
                })
            });
            (None, date)
        }
        _ => (None, None),
    };

    let book = BookDto {
        id: Uuid::new_v4().to_string(),
        title,
        author,
        file_path: dest_path.to_string_lossy().to_string(),
        format,
        sync_status: "local".to_string(),
        current_page: 0,
        total_pages: 0,
        created_at: now.clone(),
        updated_at: now,
        genre: genre.clone(),
        language: language.clone(),
        publication_date: publication_date.clone(),
    };

    repo.connection.execute(
            "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version, genre, language, publication_date)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, 1, ?11, ?12, ?13)",
            params![
                book.id,
                book.title,
                book.author,
                book.file_path,
                book.format,
                book.sync_status,
                book.current_page,
                book.total_pages,
                book.created_at,
                book.updated_at,
                genre,
                language,
                publication_date,
            ],
        )?;

    // Try sidecar cover first (user-provided image next to the source file)
    let has_sidecar =
        if let Some(cover_source_path) = LibraryRepository::find_cover_source_path(&source_path) {
            if let Err(err) = repo.upsert_book_cover_from_file(&app, &book.id, &cover_source_path) {
                let _ = repo.log_recoverable_cover_error(
                    &app,
                    &format!(
                        "cover_ingest_failed book_id={} source={} error={}",
                        book.id,
                        cover_source_path.display(),
                        err
                    ),
                );
                false
            } else {
                true
            }
        } else {
            false
        };

    // If no sidecar cover and the book is EPUB, extract cover from inside the EPUB
    if !has_sidecar && book.format == "epub" {
        if let Err(err) = repo.extract_epub_cover(&app, &dest_path, &book.id) {
            let _ = repo.log_recoverable_cover_error(
                &app,
                &format!(
                    "epub_cover_extract_failed book_id={} path={} error={}",
                    book.id,
                    dest_path.display(),
                    err
                ),
            );
        }
    }

    let _ = repo.run_deferred_cover_cleanup(&app);

    Ok(book)
}

pub fn delete_book(
    repo: &mut LibraryRepository,
    app: tauri::AppHandle,
    input: BookDeleteInput,
) -> AppResult<()> {
    let book_id = input.book_id.trim();
    let maybe_cover_path = repo.delete_book_metadata(book_id)?;

    if let Some(storage_path) = maybe_cover_path {
        let remove_result = fs::remove_file(PathBuf::from(&storage_path));
        if let Err(err) = remove_result {
            if err.kind() != std::io::ErrorKind::NotFound {
                repo.enqueue_cover_cleanup(&app, &storage_path)?;
                repo.log_recoverable_cover_error(
                    &app,
                    &format!(
                        "deferred_cover_cleanup_queued book_id={} path={} error={}",
                        book_id, storage_path, err
                    ),
                )?;
            }
        }
    }

    let _ = repo.run_deferred_cover_cleanup(&app);
    Ok(())
}

pub fn list_books(repo: &LibraryRepository) -> AppResult<Vec<BookDto>> {
    let mut statement = repo.connection.prepare(
        "SELECT id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, genre, language, publication_date\n         FROM books\n         WHERE deleted_at IS NULL\n           AND hidden_at IS NULL\n         ORDER BY updated_at DESC",
    )?;

    let rows = statement.query_map([], |row| {
        Ok(BookDto {
            id: row.get(0)?,
            // A legacy row can hold bytes that are not valid UTF-8; SQLite then
            // reports the column as BLOB and a plain `String` read fails the
            // whole list. Read tolerantly and let the stored bytes surface as
            // the replacement character instead of an empty library.
            title: sql_text::text(row, 1)?,
            author: sql_text::text(row, 2)?,
            file_path: sql_text::text(row, 3)?,
            format: row.get(4)?,
            sync_status: row.get(5)?,
            current_page: row.get(6)?,
            total_pages: row.get(7)?,
            created_at: row.get(8)?,
            updated_at: row.get(9)?,
            genre: sql_text::text_opt(row, 10)?,
            language: sql_text::text_opt(row, 11)?,
            publication_date: sql_text::text_opt(row, 12)?,
        })
    })?;

    let books = rows.collect::<Result<Vec<_>, _>>()?;
    Ok(books)
}

pub fn upsert_book(repo: &LibraryRepository, book: BookDto) -> AppResult<()> {
    let book_id = book.id.clone();
    repo.connection.execute(
        "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version, genre, language, publication_date)\n         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, 1, ?11, ?12, ?13)\n         ON CONFLICT(id) DO UPDATE SET\n           title = excluded.title,\n           author = excluded.author,\n           file_path = excluded.file_path,\n           format = excluded.format,\n           sync_status = excluded.sync_status,\n           current_page = excluded.current_page,\n           total_pages = excluded.total_pages,\n           updated_at = excluded.updated_at,\n           genre = excluded.genre,\n           language = excluded.language,\n           publication_date = excluded.publication_date,\n           version = version + 1",
        params![
            book.id,
            book.title,
            book.author,
            book.file_path,
            book.format,
            book.sync_status,
            book.current_page,
            book.total_pages,
            book.created_at,
            book.updated_at,
            book.genre,
            book.language,
            book.publication_date,
        ],
    )?;
    // Catalog landed (or refreshed) this book: replay highlights that were
    // deferred because it was not present locally yet.
    repo.replay_pending_highlights_for_book(&book_id);
    Ok(())
}

pub fn delete_book_metadata(
    repo: &mut LibraryRepository,
    book_id: &str,
) -> AppResult<Option<String>> {
    let book_id = book_id.trim();
    if book_id.is_empty() {
        return Err(AppError::MissingBookId);
    }

    let now = Utc::now().to_rfc3339();
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

    let tx = repo.connection.transaction()?;
    tx.execute(
        "UPDATE books
         SET deleted_at = ?1, updated_at = ?1, version = version + 1
         WHERE id = ?2 AND deleted_at IS NULL",
        params![now, book_id],
    )?;

    if let Some((cover_id, _)) = &cover {
        tx.execute(
            "UPDATE book_covers
             SET deleted_at = ?1, updated_at = ?1, version = version + 1
             WHERE id = ?2",
            params![now, cover_id],
        )?;
    }
    tx.commit()?;

    Ok(cover.map(|(_, storage_path)| storage_path))
}
pub fn list_library_books(repo: &LibraryRepository) -> AppResult<Vec<LibraryBookDto>> {
    let mut statement = repo.connection.prepare(
        "SELECT b.id,
                b.title,
                b.author,
                b.format,
                b.current_page,
                b.total_pages,
                COALESCE(rp.percentage, 0.0) AS progress_percentage,
                bc.storage_path,
                COALESCE(CAST(ROUND(rs.total_duration_seconds / 60.0) AS INTEGER), 0) AS minutes_read,
                 b.updated_at,
                 b.created_at,
                (SELECT GROUP_CONCAT(collection_id, ',') FROM book_collections bc2 WHERE bc2.book_id = b.id AND bc2.collection_id NOT IN (2, 3)) AS collection_ids,
                b.genre,
                b.language,
                b.publication_date,
                (SELECT MAX(user_deleted) FROM book_covers bc2 WHERE bc2.book_id = b.id) AS cover_user_deleted,
                brs.status AS reading_status,
                b.file_path
         FROM books b
         LEFT JOIN reading_progress rp
           ON rp.book_id = b.id
          AND rp.deleted_at IS NULL
         LEFT JOIN book_covers bc
           ON bc.book_id = b.id
          AND bc.deleted_at IS NULL
         LEFT JOIN (
            SELECT book_id, SUM(duration_seconds) AS total_duration_seconds
            FROM reading_sessions
            GROUP BY book_id
         ) rs
           ON rs.book_id = b.id
         LEFT JOIN book_reading_status brs
           ON brs.book_id = b.id
         WHERE b.deleted_at IS NULL
           AND b.hidden_at IS NULL
         ORDER BY b.updated_at DESC, b.id ASC",
    )?;

    let rows = statement.query_map([], |row| {
        let collection_ids_str: Option<String> = row.get(11)?;
        let collection_ids: Vec<i64> = collection_ids_str
            .map(|s| s.split(',').filter_map(|x| x.parse().ok()).collect())
            .unwrap_or_default();
        Ok(LibraryBookDto {
            id: row.get(0)?,
            // Same tolerance as `list_books`: the shelf must render even when a
            // legacy row stores bytes that are not valid UTF-8.
            title: sql_text::text(row, 1)?,
            author: sql_text::text(row, 2)?,
            format: row.get(3)?,
            current_page: row.get(4)?,
            total_pages: row.get(5)?,
            progress_percentage: row.get(6)?,
            cover_path: sql_text::text_opt(row, 7)?,
            minutes_read: row.get(8)?,
            updated_at: row.get(9)?,
            created_at: row.get(10)?,
            collection_ids,
            genre: sql_text::text_opt(row, 12)?,
            language: sql_text::text_opt(row, 13)?,
            publication_date: sql_text::text_opt(row, 14)?,
            cover_user_deleted: row.get(15)?,
            reading_status: sql_text::text_opt(row, 16)?,
            file_path: sql_text::text(row, 17)?,
        })
    })?;

    let books = rows.collect::<Result<Vec<_>, _>>()?;
    Ok(books)
}
pub(super) fn existing_book_filenames_lowercase(
    repo: &LibraryRepository,
) -> AppResult<HashSet<String>> {
    let mut statement = repo.connection.prepare(
        "SELECT file_path
         FROM books
         WHERE deleted_at IS NULL",
    )?;

    let rows = statement.query_map([], |row| row.get::<_, String>(0))?;
    let mut names = HashSet::new();

    for file_path in rows {
        let file_path = file_path?;
        let file_name = Path::new(&file_path)
            .file_name()
            .and_then(|value| value.to_str())
            .map(|value| value.to_ascii_lowercase());
        if let Some(value) = file_name {
            names.insert(value);
        }
    }

    Ok(names)
}
pub(super) fn has_desktop_parity_schema(repo: &LibraryRepository) -> AppResult<bool> {
    const REQUIRED: [&str; 5] =
        ["app_settings", "book_covers", "reading_sessions", "book_text_chunks", "book_text_fts"];

    for table in REQUIRED {
        let exists: Option<i32> = repo
            .connection
            .query_row(
                "SELECT 1 FROM sqlite_master WHERE name = ?1 LIMIT 1",
                params![table],
                |row| row.get(0),
            )
            .optional()?;
        if exists.is_none() {
            return Ok(false);
        }
    }

    Ok(true)
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::repository::tests::{insert_book, new_repository};
    use chrono::Utc;

    #[test]
    fn delete_book_metadata_marks_cover_deleted_and_returns_path() {
        let mut repository = new_repository();
        insert_book(&repository, "book-cover-delete", "C:/library/book-cover-delete.epub");
        let now = Utc::now().to_rfc3339();

        repository
            .connection
            .execute(
                "INSERT INTO book_covers (id, book_id, storage_path, mime_type, width, height, byte_size, checksum, created_at, updated_at, deleted_at, version)
                 VALUES (?1, ?2, ?3, 'image/png', NULL, NULL, 10, NULL, ?4, ?4, NULL, 1)",
                params![
                    Uuid::new_v4().to_string(),
                    "book-cover-delete",
                    "C:/tmp/book-cover-delete.png",
                    now
                ],
            )
            .unwrap();

        let storage_path = repository.delete_book_metadata("book-cover-delete").unwrap();
        assert_eq!(storage_path.as_deref(), Some("C:/tmp/book-cover-delete.png"));

        let deleted_cover_rows: i64 = repository
            .connection
            .query_row(
                "SELECT COUNT(*) FROM book_covers WHERE book_id = ?1 AND deleted_at IS NOT NULL",
                params!["book-cover-delete"],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(deleted_cover_rows, 1);
    }

    /// The live defect. `books.title` held `Prisioneros de la geograf\xEDa` —
    /// a bare `0xED` where `0xC3 0xAD` belongs. SQLite applies no UTF-8
    /// validation, reports the value as BLOB, and `row.get::<_, String>` refused
    /// it, so EVERY book list query failed and the library would not open.
    ///
    /// The row must now come back readable, with the damaged byte surfaced as
    /// `U+FFFD`, and the healthy rows around it untouched.
    #[test]
    fn a_title_holding_invalid_utf8_does_not_break_the_book_lists() {
        let repository = new_repository();
        insert_book(&repository, "book-healthy", "C:/library/book-healthy.epub");

        let now = Utc::now().to_rfc3339();
        repository
            .connection
            .execute(
                "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version)
                 VALUES (?1, ?2, 'Tim Marshall', ?3, 'epub', 'local', 0, 0, ?4, ?4, 1)",
                params![
                    "book-damaged",
                    b"Prisioneros de la geograf\xeda" as &[u8],
                    "C:/library/book-damaged.epub",
                    now
                ],
            )
            .unwrap();

        let books = repository.list_books().expect("a damaged row must not fail the list");
        assert_eq!(books.len(), 2, "the healthy row must still be listed");

        let damaged = books.iter().find(|book| book.id == "book-damaged").expect("damaged row");
        assert_eq!(damaged.title, "Prisioneros de la geograf\u{FFFD}a");
        assert_eq!(damaged.author, "Tim Marshall");

        let shelf = repository.list_library_books().expect("the shelf must still load");
        assert_eq!(shelf.len(), 2, "the shelf must list both books");
    }

    #[test]
    fn list_library_books_scales_to_large_dataset() {
        let repository = new_repository();
        let now = Utc::now().to_rfc3339();

        for index in 0..1_000 {
            repository
                .connection
                .execute(
                    "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version)
                     VALUES (?1, ?2, 'Author', ?3, 'epub', 'local', 0, 100, ?4, ?4, 1)",
                    params![
                        format!("book-{index}"),
                        format!("Book {index}"),
                        format!("C:/library/book-{index}.epub"),
                        now
                    ],
                )
                .unwrap();
        }

        let rows = repository.list_library_books().unwrap();
        assert_eq!(rows.len(), 1_000);
    }

    #[test]
    fn hide_book_from_library_is_idempotent_and_removes_from_library_views() {
        let repository = new_repository();
        insert_book(&repository, "book-visible", "C:/library/book-visible.epub");

        let initial_library_rows = repository.list_library_books().unwrap();
        assert_eq!(initial_library_rows.len(), 1);
        assert_eq!(initial_library_rows[0].id, "book-visible");

        repository.hide_book_from_library("book-visible").unwrap();
        repository.hide_book_from_library("book-visible").unwrap();

        let remaining_library_rows = repository.list_library_books().unwrap();
        assert!(remaining_library_rows.is_empty());

        let remaining_books = repository.list_books().unwrap();
        assert!(remaining_books.is_empty());

        let hidden_at: Option<String> = repository
            .connection
            .query_row(
                "SELECT hidden_at FROM books WHERE id = ?1",
                params!["book-visible"],
                |row| row.get(0),
            )
            .unwrap();
        assert!(hidden_at.is_some());
    }

    #[test]
    fn hide_book_from_library_returns_error_for_unknown_book() {
        let repository = new_repository();
        let result = repository.hide_book_from_library("missing-book-id");
        assert!(matches!(result, Err(AppError::InvalidInput(_))));
    }
}
