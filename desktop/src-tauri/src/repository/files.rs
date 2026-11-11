use super::LibraryRepository;
use crate::error::{AppError, AppResult};
use crate::filename::{canonical_extension, canonical_stem};
use chrono::Utc;
use rusqlite::{params, OptionalExtension};
use std::fs;
use std::io::Cursor;
use std::path::{Path, PathBuf};
use tauri::Manager;

/// Result of reading a book file's declared metadata, at the seam where the
/// bytes are already in memory. The three-way shape is the point:
///
/// - `NotApplicable` — nothing to read: a non-EPUB format, or bytes that are
///   not a readable EPUB. The file was not (or could not be) inspected.
/// - `Read(None)` — the OPF WAS read and declares no `<dc:language>`. The book
///   genuinely has no language, and storing NULL is the CORRECT answer.
/// - `Read(Some(tag))` — the OPF declares a language, already normalised.
///
/// The distinction between the last two is what keeps a book with no language
/// out of the "we could not read it" bucket, so the sync path never invents a
/// language it did not find.
enum DeclaredLanguage {
    NotApplicable,
    Read(Option<String>),
}

/// Extract the language declared by an EPUB's OPF.
///
/// The bytes are read through [`Cursor`], a seekable in-memory reader, so the
/// ZIP index is walkable without touching the filesystem: the sync/download
/// path has the bytes in memory at the seam this is called from, not a path.
/// The extraction itself is the same `EpubDoc::mdata("language")` call the
/// manual-import path uses — there is deliberately no second parser here.
fn read_declared_language(format: &str, data: &[u8]) -> DeclaredLanguage {
    if format != "epub" {
        return DeclaredLanguage::NotApplicable;
    }
    // `from_reader` parses the ZIP from a reader that implements `Read + Seek`;
    // `Cursor` over the in-memory bytes is exactly that, so no file is opened.
    let Ok(doc) = epub::doc::EpubDoc::from_reader(Cursor::new(data)) else {
        // Unreadable bytes are "not inspected", NOT "no language": the two must
        // never collapse into one, or an unreadable file would be written with a
        // language it does not declare.
        return DeclaredLanguage::NotApplicable;
    };
    DeclaredLanguage::Read(
        doc.mdata("language").map(|meta| meta.value.trim().to_string()).filter(|v| !v.is_empty()),
    )
}

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

    // The OPF language is read HERE, from the bytes already in memory, because
    // the sync/download path (`saveBookFile` over IPC) never supplies it as
    // metadata. `Cursor` makes those bytes a seekable reader, so the ZIP is
    // walkable without a second open of the file we just wrote. Manual import
    // does not route through here, so its own extraction is unaffected.
    let declared_language = match read_declared_language(fmt, data) {
        DeclaredLanguage::Read(language) => language,
        DeclaredLanguage::NotApplicable => None,
    };

    if let Err(err) = commit_book_file_row(
        repo,
        BookFileRow {
            book_id,
            destination: &destination,
            repaired,
            title,
            author,
            fmt,
            language: declared_language,
        },
    ) {
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
///
/// `language` is the value read from the file's own OPF (see
/// [`read_declared_language`]). It is filled on INSERT, and on conflict only
/// when it carries a real value (`COALESCE`-style: a non-empty `excluded.language`
/// wins, otherwise the stored one survives). That conditional fill is what keeps
/// an absent language from erasing one an earlier path already stored — e.g. a
/// manual import's language must not be clobbered by a later metadata-less sync
/// of the same book when the EPUB itself declares none.
struct BookFileRow<'a> {
    book_id: &'a str,
    destination: &'a Path,
    repaired: bool,
    title: Option<&'a str>,
    author: Option<&'a str>,
    fmt: &'a str,
    language: Option<String>,
}

fn commit_book_file_row(repo: &LibraryRepository, row: BookFileRow<'_>) -> AppResult<()> {
    let BookFileRow { book_id, destination, repaired, title, author, fmt, language } = row;
    let now = Utc::now().to_rfc3339();
    // A blank string is treated the same as absent: the caller has no metadata
    // to offer, so the stored value must survive.
    let supplied_title = title.map(str::trim).filter(|v| !v.is_empty());
    let supplied_author = author.map(str::trim).filter(|v| !v.is_empty());
    let supplied_language = language.as_deref().map(str::trim).filter(|v| !v.is_empty());
    let transaction = repo.connection.unchecked_transaction()?;
    transaction.execute(
        "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version, language)
             VALUES (?1, ?2, ?3, ?4, ?5, 'local', 0, 0, ?6, ?6, 1, ?9)
             ON CONFLICT(id) DO UPDATE SET
                 file_path = excluded.file_path,
                 format = excluded.format,
                 title = CASE WHEN ?7 IS NOT NULL THEN ?7 ELSE books.title END,
                 author = CASE WHEN ?8 IS NOT NULL THEN ?8 ELSE books.author END,
                 language = CASE WHEN ?9 IS NOT NULL THEN ?9 ELSE books.language END,
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
            supplied_author,
            supplied_language
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

    /// Regression for the sync/download path: it used to INSERT the book row
    /// without a `language` column at all, so every downloaded book stored NULL
    /// even when its OPF declared `<dc:language>` — the manual-import path read
    /// it, so the same EPUB stored a language when imported and none when synced.
    #[test]
    fn save_book_file_stores_the_language_declared_by_the_opf() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        let books_dir = dir.path().join("books");
        let epub = build_epub_bytes("es");

        // Exactly what the sync path sends: bytes only, no metadata.
        save_book_file_under(
            &repo,
            &books_dir,
            "5b4ba1c1-1b6f-4301-816a-40385be603fa",
            &epub,
            None,
            None,
            Some("epub"),
        )
        .expect("save");

        let language: Option<String> = repo
            .connection
            .query_row(
                "SELECT language FROM books WHERE id = ?1",
                params!["5b4ba1c1-1b6f-4301-816a-40385be603fa"],
                |row| row.get(0),
            )
            .expect("row");
        assert_eq!(
            language.as_deref(),
            Some("es"),
            "the OPF declares `es`, so the synced row must store it"
        );
    }

    /// The other half, and the one that must NOT be "fixed": a book whose EPUB
    /// genuinely declares no `<dc:language>` must stay NULL. NULL here is the
    /// CORRECT answer, not a missing extraction — inventing a language would be
    /// worse than the unknown it replaces.
    #[test]
    fn save_book_file_leaves_language_null_when_the_opf_declares_none() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        let books_dir = dir.path().join("books");
        let epub = build_epub_bytes_no_language();

        save_book_file_under(
            &repo,
            &books_dir,
            "supercomunicadores-book-id",
            &epub,
            None,
            None,
            Some("epub"),
        )
        .expect("save");

        let language: Option<String> = repo
            .connection
            .query_row(
                "SELECT language FROM books WHERE id = ?1",
                params!["supercomunicadores-book-id"],
                |row| row.get(0),
            )
            .expect("row");
        assert_eq!(language, None, "a book with no declared language must stay NULL");
    }

    /// `read_declared_language` must tell "not an EPUB" apart from "an EPUB with
    /// no language". A non-EPUB format is never inspected, and the bytes of a
    /// truncated/garbage EPUB must not be treated as declaring no language either
    /// — both are `NotApplicable`, which is distinct from `Read(None)`.
    #[test]
    fn read_declared_language_does_not_conflate_unreadable_with_absent() {
        assert!(matches!(
            read_declared_language("pdf", b"%PDF-1.7 fake"),
            DeclaredLanguage::NotApplicable
        ));
        assert!(matches!(
            read_declared_language("epub", b"not a zip at all"),
            DeclaredLanguage::NotApplicable
        ));
        match read_declared_language("epub", &build_epub_bytes_no_language()) {
            DeclaredLanguage::Read(None) => {}
            _ => panic!("a readable OPF with no dc:language must be Read(None)"),
        }
        match read_declared_language("epub", &build_epub_bytes("es")) {
            DeclaredLanguage::Read(Some(tag)) => assert_eq!(tag, "es"),
            _ => panic!("a declared language must be Read(Some(tag))"),
        }
    }

    /// A synced row that already carries a language (e.g. from a manual import)
    /// must not have it clobbered by a later metadata-less sync of an EPUB whose
    /// OPF declares none — the conditional `language` fill mirrors the title and
    /// author rules for the same reason.
    #[test]
    fn save_book_file_keeps_a_stored_language_when_the_opf_declares_none() {
        let repo = new_repository();
        let dir = tempfile::tempdir().expect("tempdir");
        let books_dir = dir.path().join("books");
        let book_id = "5b4ba1c1-1b6f-4301-816a-40385be603fa";
        repo.connection
            .execute(
                "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version, language)
                 VALUES (?1, 'Book', 'Author', '', 'epub', 'local', 0, 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', 1, 'fr')",
                params![book_id],
            )
            .expect("seed row with a stored language");

        save_book_file_under(
            &repo,
            &books_dir,
            book_id,
            &build_epub_bytes_no_language(),
            None,
            None,
            Some("epub"),
        )
        .expect("save");

        let language: Option<String> = repo
            .connection
            .query_row("SELECT language FROM books WHERE id = ?1", params![book_id], |row| {
                row.get(0)
            })
            .expect("row");
        assert_eq!(language.as_deref(), Some("fr"), "a stored language must survive");
    }

    /// The download path has the bytes in memory at this seam (`save_book_file_under`
    /// receives `data: &[u8]`), and `EpubDoc::from_reader` accepts a `Cursor` over
    /// them — so the OPF is readable WITHOUT a second open. This proves the ZIP is
    /// walkable from those bytes alone, which is the constraint the task called out.
    #[test]
    fn the_epub_opf_is_readable_from_the_in_memory_bytes() {
        use std::io::Cursor;
        let bytes = build_epub_bytes("es");
        let doc = epub::doc::EpubDoc::from_reader(Cursor::new(&bytes))
            .expect("the in-memory bytes are a readable EPUB");
        let language = doc.mdata("language").map(|meta| meta.value.clone());
        assert_eq!(language.as_deref(), Some("es"));
    }

    /// Minimal single-chapter EPUB as raw bytes, with `<dc:language>` set to
    /// `language`. Built in memory so a test can hand the sync seam exactly the
    /// bytes the download path would supply.
    fn build_epub_bytes(language: &str) -> Vec<u8> {
        build_epub_bytes_with_metadata(&format!("<dc:language>{}</dc:language>", language))
    }

    /// Same EPUB with NO `<dc:language>` element at all — the
    /// "Supercomunicadores" shape: a readable OPF that declares no language.
    fn build_epub_bytes_no_language() -> Vec<u8> {
        build_epub_bytes_with_metadata("")
    }

    fn build_epub_bytes_with_metadata(metadata_extra: &str) -> Vec<u8> {
        use std::io::{Cursor, Write};
        let mut cursor = Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut cursor);
            let stored = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Stored);
            let deflated = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated);

            // `mimetype` must be first and uncompressed.
            zip.start_file("mimetype", stored).unwrap();
            zip.write_all(b"application/epub+zip").unwrap();

            zip.start_file("META-INF/container.xml", deflated).unwrap();
            zip.write_all(
                br#"<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"#,
            )
            .unwrap();

            let opf = format!(
                r#"<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:lang-test</dc:identifier><dc:title>Language Test</dc:title>{}</metadata><manifest><item id="c0" href="Text/chapter0.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c0"/></spine></package>"#,
                metadata_extra
            );
            zip.start_file("OEBPS/content.opf", deflated).unwrap();
            zip.write_all(opf.as_bytes()).unwrap();

            zip.start_file("OEBPS/Text/chapter0.xhtml", deflated).unwrap();
            zip.write_all(br#"<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>hi</p></body></html>"#).unwrap();

            zip.finish().unwrap();
        }
        cursor.into_inner()
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
