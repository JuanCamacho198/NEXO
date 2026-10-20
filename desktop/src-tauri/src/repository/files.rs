use super::LibraryRepository;
use crate::error::{AppError, AppResult};
use crate::filename::{canonical_extension, canonical_stem};
use chrono::Utc;
use rusqlite::{params, OptionalExtension};
use std::fs;
use std::path::{Path, PathBuf};
use tauri::Manager;

pub fn update_book_progress(
    repo: &LibraryRepository,
    book_id: &str,
    current_page: i32,
) -> AppResult<()> {
    if book_id.trim().is_empty() {
        return Err(AppError::MissingBookId);
    }

    let now = Utc::now().to_rfc3339();
    repo.connection.execute(
        "UPDATE books SET current_page = ?1, updated_at = ?2, version = version + 1 WHERE id = ?3",
        params![current_page, now, book_id],
    )?;
    Ok(())
}

pub fn save_book_file(
    repo: &LibraryRepository,
    app: &tauri::AppHandle,
    id: &str,
    data: &[u8],
    title: Option<&str>,
    author: Option<&str>,
    format: Option<&str>,
) -> AppResult<()> {
    let book_id = id.trim();
    if book_id.is_empty() {
        return Err(AppError::MissingBookId);
    }

    let root = app.path().app_data_dir()?;
    let books_dir = crate::layout::resolved_books_dir(&root);
    save_book_file_under(repo, &books_dir, book_id, data, title, author, format)
}

/// File-first, then DB: the destination is written (temp `.part` → rename)
/// BEFORE any row is committed, so a file-I/O failure can never leave a durable
/// `books` row whose file does not exist. The `books` writes then run in one
/// transaction; a DB failure after the file landed best-effort removes the file
/// for a brand-new row so no orphan remains. A legacy unsanitized stored path is
/// never reused: the retry targets the sanitized destination and repairs the
/// row's `file_path`. Split out from the app-handle lookup so the ordering is
/// unit-testable with a temp directory.
fn save_book_file_under(
    repo: &LibraryRepository,
    books_dir: &Path,
    book_id: &str,
    data: &[u8],
    title: Option<&str>,
    author: Option<&str>,
    format: Option<&str>,
) -> AppResult<()> {
    let existing_path: Option<String> = repo
        .connection
        .query_row(
            "SELECT file_path
                 FROM books
                 WHERE id = ?1 AND deleted_at IS NULL
                 LIMIT 1",
            params![book_id],
            |row| row.get(0),
        )
        .optional()?;

    let fmt = format.unwrap_or("epub").trim_start_matches('.');
    let is_new_row = existing_path.is_none();

    // Always target the sanitized destination. A legacy row (created before the
    // sanitizer existed) stores the unsanitized colon path, which on Windows is
    // an NTFS alternate data stream and fails the rename with
    // ERROR_INVALID_PARAMETER; reusing it verbatim would reproduce the failure
    // forever. A healthy row already stores exactly this path, so the stored
    // value is reused unchanged and no existing file is orphaned. Fresh install
    // / app-data loss also lands here, so recovery import never hard-fails.
    // The catalog id (`gutendex:2701`) is not a valid Windows path segment:
    // `:` starts an NTFS ADS. Sanitize the stem and keep the persisted `format`
    // untouched for the row below.
    let stem = canonical_stem(book_id);
    let ext = canonical_extension(Some(fmt));
    let sanitized = books_dir.join(format!("{stem}.{ext}"));
    let destination = match existing_path.as_deref() {
        Some(path) if Path::new(path) == sanitized.as_path() => PathBuf::from(path),
        _ => sanitized,
    };

    // `repaired` is the negation of the match guard above: true only when a
    // stored path existed and differed from the sanitized target (a legacy
    // unsanitized path). A brand-new row has no stored path and is already
    // visible, and a healthy row stores exactly the target, so neither counts as
    // a repair. Only a repair may clear a hidden flag: background sync writes a
    // missing file through this same path and must not resurrect a hidden book.
    let repaired =
        existing_path.as_deref().is_some_and(|path| Path::new(path) != destination.as_path());

    write_book_file(&destination, data)?;

    if let Err(err) =
        commit_book_file_row(repo, book_id, &destination, repaired, title, author, fmt)
    {
        if is_new_row {
            let _ = fs::remove_file(&destination);
        }
        return Err(err);
    }

    Ok(())
}

/// Atomic file write: temp file then rename. A failed write or rename removes
/// the `.part` it created so `books/` never accumulates artifacts.
fn write_book_file(path: &Path, data: &[u8]) -> AppResult<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp_path = path.with_extension("part");
    if let Err(err) = fs::write(&temp_path, data) {
        let _ = fs::remove_file(&temp_path);
        return Err(err.into());
    }
    if let Err(err) = fs::rename(&temp_path, path) {
        let _ = fs::remove_file(&temp_path);
        return Err(err.into());
    }
    Ok(())
}

/// One transaction for the row so the file-path repair, `sync_status = 'synced'`
/// and the hidden-flag clear cannot be half applied: either the row is repaired
/// as synced, or nothing was committed.
///
/// The INSERT always runs. For an existing row its `ON CONFLICT(id) DO UPDATE`
/// is the only place that repairs a legacy unsanitized `file_path`; it sets
/// `file_path`/`format`/`updated_at`/`version` and only *conditionally*
/// fills `title`/`author`, so an existing row's title, author and reading
/// progress are never clobbered.
///
/// The conditional title/author fill exists because the FK guard used to create
/// placeholder rows titled `Unknown Title (sync stub) …` for books that had
/// never been downloaded. When the real file later arrives (background sync
/// calls `saveBookFile` with no metadata; Discover passes real metadata) the
/// row's `file_path` was repaired but its title stayed a placeholder forever —
/// the row kept claiming to be a stub while holding the real book. A supplied
/// non-empty value wins; an absent or blank one leaves the stored value alone,
/// so a metadata-less sync can never overwrite a good title with the raw id.
///
/// `repaired` is true only when this call rewrote a legacy unsanitized
/// destination. The hidden-flag clear is conditional on it, so a write that was
/// not user-initiated (background sync filling in a missing file) cannot un-hide
/// a book the user hid.
fn commit_book_file_row(
    repo: &LibraryRepository,
    book_id: &str,
    destination: &Path,
    repaired: bool,
    title: Option<&str>,
    author: Option<&str>,
    fmt: &str,
) -> AppResult<()> {
    let now = Utc::now().to_rfc3339();
    // A blank string is treated the same as absent: the caller has no metadata
    // to offer, so the stored value must survive.
    let supplied_title = title.map(str::trim).filter(|v| !v.is_empty());
    let supplied_author = author.map(str::trim).filter(|v| !v.is_empty());
    let transaction = repo.connection.unchecked_transaction()?;
    transaction.execute(
        "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version)
             VALUES (?1, ?2, ?3, ?4, ?5, 'local', 0, 0, ?6, ?6, 1)
             ON CONFLICT(id) DO UPDATE SET
                 file_path = excluded.file_path,
                 format = excluded.format,
                 title = CASE WHEN ?7 IS NOT NULL THEN ?7 ELSE books.title END,
                 author = CASE WHEN ?8 IS NOT NULL THEN ?8 ELSE books.author END,
                 updated_at = excluded.updated_at,
                 version = version + 1",
        params![
            book_id,
            title.unwrap_or(book_id),
            author.unwrap_or_default(),
            destination.to_string_lossy().to_string(),
            fmt,
            now,
            supplied_title,
            supplied_author
        ],
    )?;
    // A cleared flag is warranted only for a repair: a legacy row created before
    // the sanitizer existed stores a colon `file_path`, and the library lists
    // filter `hidden_at IS NULL`, so without the clear the path repair would be
    // inert and the book would stay invisible. The clear runs only after the file
    // landed, inside this transaction, so a book can never be un-hidden on a path
    // that did not write a file, and never by a non-repair write.
    transaction.execute(
        "UPDATE books
             SET sync_status = 'synced',
                 hidden_at = CASE WHEN ?3 THEN NULL ELSE hidden_at END,
                 updated_at = ?1,
                 version = version + 1
             WHERE id = ?2",
        params![now, book_id, repaired],
    )?;
    transaction.commit()?;
    // The book row now exists with a real path, so any highlight deferred while
    // it was absent can be applied without risking the FK.
    repo.replay_pending_highlights_for_book(book_id);
    Ok(())
}

pub fn hide_book_from_library(repo: &LibraryRepository, book_id: &str) -> AppResult<()> {
    let normalized_book_id = book_id.trim();
    if normalized_book_id.is_empty() {
        return Err(AppError::MissingBookId);
    }

    let existing_hidden_at: Option<Option<String>> = repo
        .connection
        .query_row(
            "SELECT hidden_at
                 FROM books
                 WHERE id = ?1 AND deleted_at IS NULL
                 LIMIT 1",
            params![normalized_book_id],
            |row| row.get(0),
        )
        .optional()?;

    match existing_hidden_at {
        None => {
            return Err(AppError::InvalidInput(format!(
                "Book not found for id {}",
                normalized_book_id
            )));
        }
        Some(Some(_)) => return Ok(()),
        Some(None) => {}
    }

    let now = Utc::now().to_rfc3339();
    repo.connection.execute(
        "UPDATE books
             SET hidden_at = COALESCE(hidden_at, ?1), updated_at = ?1, version = version + 1
             WHERE id = ?2 AND deleted_at IS NULL",
        params![now, normalized_book_id],
    )?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::repository::tests::new_repository;

    #[test]
    fn save_book_file_lands_a_sanitized_file_and_commits_a_synced_row() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        let books_dir = dir.path().join("books");

        save_book_file_under(
            &repo,
            &books_dir,
            "gutendex:2701",
            b"epub-bytes",
            Some("Moby Dick"),
            Some("Herman Melville"),
            Some("epub"),
        )
        .expect("save");

        let (file_path, sync_status): (String, String) = repo
            .connection
            .query_row(
                "SELECT file_path, sync_status FROM books WHERE id = ?1",
                params!["gutendex:2701"],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("row");
        assert!(file_path.ends_with("gutendex2701.epub"), "{file_path}");
        assert_eq!(sync_status, "synced");
        let written = PathBuf::from(&file_path);
        assert!(written.exists(), "the file must exist at its committed path");
        assert_eq!(fs::read(&written).expect("read"), b"epub-bytes");
        assert!(
            !written.with_extension("part").exists(),
            "no .part artifact may remain after a successful rename"
        );
    }

    /// F2: a file-I/O failure must not leave a committed `books` row, so the
    /// library can never show a phantom book the user cannot open.
    #[test]
    fn save_book_file_leaves_no_row_when_the_file_write_fails() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        // A regular file where the books directory should be makes
        // `create_dir_all` fail deterministically.
        let books_dir = dir.path().join("books");
        fs::write(&books_dir, b"not a directory").expect("seed blocker file");

        let err = save_book_file_under(
            &repo,
            &books_dir,
            "gutendex:2701",
            b"epub-bytes",
            Some("Moby Dick"),
            Some("Herman Melville"),
            Some("epub"),
        )
        .expect_err("the file write must fail");
        assert!(matches!(err, AppError::Io(_)), "{err}");

        let rows: i64 = repo
            .connection
            .query_row(
                "SELECT COUNT(*) FROM books WHERE id = ?1",
                params!["gutendex:2701"],
                |row| row.get(0),
            )
            .expect("count");
        assert_eq!(rows, 0, "a failed file write must not leave a committed row");
    }

    /// X1: a row created before the sanitizer stores the legacy colon path
    /// (`books/gutendex:2701.epub`), which on Windows is an NTFS alternate data
    /// stream and fails the rename with ERROR_INVALID_PARAMETER. The retry must
    /// write the sanitized file, repair `file_path` and un-hide the row.
    #[test]
    fn save_book_file_repairs_a_legacy_colon_path() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        let books_dir = dir.path().join("books");
        seed_legacy_hidden_row(&repo, &books_dir, "gutendex:2701");

        save_book_file_under(
            &repo,
            &books_dir,
            "gutendex:2701",
            b"epub-bytes",
            Some("Moby Dick"),
            Some("Herman Melville"),
            Some("epub"),
        )
        .expect("repair");

        let (file_path, sync_status, hidden_at): (String, String, Option<String>) = repo
            .connection
            .query_row(
                "SELECT file_path, sync_status, hidden_at FROM books WHERE id = ?1",
                params!["gutendex:2701"],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .expect("row");
        assert!(file_path.ends_with("gutendex2701.epub"), "{file_path}");
        assert_eq!(sync_status, "synced");
        assert_eq!(hidden_at, None, "a repaired row must become visible again");
        let written = PathBuf::from(&file_path);
        assert!(written.exists(), "the file must exist at the repaired path");
        assert_eq!(fs::read(&written).expect("read"), b"epub-bytes");
        assert!(!written.with_extension("part").exists());
    }

    /// X1 idempotence: re-running the repair targets the same path, keeps exactly
    /// one file and leaves no `.part` artifact behind.
    #[test]
    fn save_book_file_repair_is_idempotent() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        let books_dir = dir.path().join("books");
        seed_legacy_hidden_row(&repo, &books_dir, "gutendex:2701");

        for _ in 0..2 {
            save_book_file_under(
                &repo,
                &books_dir,
                "gutendex:2701",
                b"epub-bytes",
                Some("Moby Dick"),
                Some("Herman Melville"),
                Some("epub"),
            )
            .expect("save");
        }

        let file_path: String = repo
            .connection
            .query_row(
                "SELECT file_path FROM books WHERE id = ?1",
                params!["gutendex:2701"],
                |row| row.get(0),
            )
            .expect("row");
        assert!(file_path.ends_with("gutendex2701.epub"), "{file_path}");
        let mut entries: Vec<String> = fs::read_dir(&books_dir)
            .expect("books dir")
            .map(|entry| entry.expect("entry").file_name().to_string_lossy().to_string())
            .collect();
        entries.sort();
        assert_eq!(entries, vec!["gutendex2701.epub".to_string()], "{entries:?}");
    }

    /// Regression guard: a healthy row already stores exactly the sanitized
    /// destination, so a successful write is not a repair and must NOT clear a
    /// user's hidden flag. Background sync writes a missing file through this
    /// same path, so clearing here would resurrect a book the user hid/removed.
    #[test]
    fn save_book_file_keeps_a_hidden_flag_when_no_path_was_repaired() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        let books_dir = dir.path().join("books");
        seed_hidden_row_at_sanitized_path(&repo, &books_dir, "gutendex:2701");

        save_book_file_under(
            &repo,
            &books_dir,
            "gutendex:2701",
            b"epub-bytes",
            Some("Moby Dick"),
            Some("Herman Melville"),
            Some("epub"),
        )
        .expect("save");

        let (file_path, sync_status, hidden_at): (String, String, Option<String>) = repo
            .connection
            .query_row(
                "SELECT file_path, sync_status, hidden_at FROM books WHERE id = ?1",
                params!["gutendex:2701"],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .expect("row");
        assert!(file_path.ends_with("gutendex2701.epub"), "{file_path}");
        assert_eq!(sync_status, "synced");
        assert!(hidden_at.is_some(), "a non-repair write must not un-hide a book the user hid");
        let written = PathBuf::from(&file_path);
        assert!(written.exists(), "the file must exist at the stored path");
        assert_eq!(fs::read(&written).expect("read"), b"epub-bytes");
        assert!(!written.with_extension("part").exists());
    }

    /// Regression: the FK guard created placeholder rows titled
    /// `Unknown Title (sync stub) …` for books never downloaded here. Background
    /// sync then wrote the real file through this path with NO metadata, which
    /// repaired `file_path` but left the placeholder title in place forever — the
    /// row kept presenting itself as a stub while holding the real book.
    #[test]
    fn save_book_file_keeps_the_placeholder_title_when_no_metadata_is_supplied() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        let books_dir = dir.path().join("books");
        seed_stub_row(&repo, "5b4ba1c1-1b6f-4301-816a-40385be603fa");

        // Exactly what SyncService.ts:987 does: bytes only, no meta.
        save_book_file_under(
            &repo,
            &books_dir,
            "5b4ba1c1-1b6f-4301-816a-40385be603fa",
            b"epub-bytes",
            None,
            None,
            None,
        )
        .expect("save");

        let (file_path, title, author, sync_status): (String, String, String, String) = repo
            .connection
            .query_row(
                "SELECT file_path, title, author, sync_status FROM books WHERE id = ?1",
                params!["5b4ba1c1-1b6f-4301-816a-40385be603fa"],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .expect("row");
        assert_eq!(sync_status, "synced");
        assert!(file_path.ends_with("5b4ba1c1-1b6f-4301-816a-40385be603fa.epub"), "{file_path}");
        // A metadata-less sync must not rewrite the title as the raw id.
        assert!(
            title.contains("sync stub"),
            "no metadata supplied: the stored title must survive, got {title:?}"
        );
        assert_eq!(author, "Unknown", "no metadata supplied: author must survive");
        assert!(PathBuf::from(&file_path).exists());
    }

    /// The other half: when the caller DOES have real metadata (Discover import),
    /// it must replace the stale placeholder title rather than being swallowed.
    #[test]
    fn save_book_file_replaces_a_placeholder_title_when_metadata_is_supplied() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        let books_dir = dir.path().join("books");
        let book_id = "5b4ba1c1-1b6f-4301-816a-40385be603fa";
        seed_stub_row(&repo, book_id);

        save_book_file_under(
            &repo,
            &books_dir,
            book_id,
            b"epub-bytes",
            Some("Prisioneros de la geografía"),
            Some("Some Author"),
            Some("epub"),
        )
        .expect("save");

        let (title, author): (String, String) = repo
            .connection
            .query_row("SELECT title, author FROM books WHERE id = ?1", params![book_id], |row| {
                Ok((row.get(0)?, row.get(1)?))
            })
            .expect("row");
        assert_eq!(title, "Prisioneros de la geografía");
        assert_eq!(author, "Some Author");
    }

    /// A blank string carries no information, so it must be treated exactly like
    /// an absent one instead of erasing the stored title.
    #[test]
    fn save_book_file_treats_a_blank_title_as_absent() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        let books_dir = dir.path().join("books");
        seed_stub_row(&repo, "5b4ba1c1-1b6f-4301-816a-40385be603fa");

        save_book_file_under(
            &repo,
            &books_dir,
            "5b4ba1c1-1b6f-4301-816a-40385be603fa",
            b"epub-bytes",
            Some("   "),
            Some(""),
            Some("epub"),
        )
        .expect("save");

        let (title, author): (String, String) = repo
            .connection
            .query_row(
                "SELECT title, author FROM books WHERE id = ?1",
                params!["5b4ba1c1-1b6f-4301-816a-40385be603fa"],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("row");
        assert!(title.contains("sync stub"), "{title:?}");
        assert_eq!(author, "Unknown");
    }

    /// Seeds a placeholder row exactly as the FK guard left it: a stub title, an
    /// empty `file_path`, and a row that is not hidden.
    fn seed_stub_row(repo: &LibraryRepository, book_id: &str) {
        let now = Utc::now().to_rfc3339();
        let title = format!("Unknown Title (sync stub) {}", &book_id[..4]);
        repo.connection
            .execute(
                "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version)
                 VALUES (?1, ?2, 'Unknown', '', 'epub', 'synced', 0, 0, ?3, ?3, 1)",
                params![book_id, title, now],
            )
            .expect("seed stub row");
    }

    /// Seeds a row exactly as the pre-sanitizer app left it: the unsanitized
    /// colon `file_path` and a set `hidden_at`.
    fn seed_legacy_hidden_row(repo: &LibraryRepository, books_dir: &Path, book_id: &str) {
        let now = Utc::now().to_rfc3339();
        let legacy_path = books_dir.join(format!("{book_id}.epub")).to_string_lossy().to_string();
        seed_hidden_row(repo, book_id, &legacy_path, &now);
    }

    /// Seeds a healthy hidden row whose stored `file_path` already equals the
    /// sanitized destination, as a hide/remove followed by a background-sync
    /// write would encounter.
    fn seed_hidden_row_at_sanitized_path(
        repo: &LibraryRepository,
        books_dir: &Path,
        book_id: &str,
    ) {
        let now = Utc::now().to_rfc3339();
        let stem = canonical_stem(book_id);
        let ext = canonical_extension(Some("epub"));
        let sanitized_path = books_dir.join(format!("{stem}.{ext}")).to_string_lossy().to_string();
        seed_hidden_row(repo, book_id, &sanitized_path, &now);
    }

    fn seed_hidden_row(repo: &LibraryRepository, book_id: &str, file_path: &str, now: &str) {
        repo.connection
            .execute(
                "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version, hidden_at)
                 VALUES (?1, 'Moby Dick', 'Herman Melville', ?2, 'epub', 'local', 0, 0, ?3, ?3, 1, ?3)",
                params![book_id, file_path, now],
            )
            .expect("seed hidden row");
    }
}
