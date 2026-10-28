use super::LibraryRepository;
use crate::error::{AppError, AppResult};
use crate::models::{BookmarkDto, SaveBookmarkInput};
use chrono::Utc;
use rusqlite::params;
use uuid::Uuid;

pub fn list_bookmarks(
    repo: &LibraryRepository,
    book_id: Option<&str>,
) -> AppResult<Vec<BookmarkDto>> {
    let mut statement = repo.connection.prepare(
        "SELECT id, book_id, page, position, title, created_at, cfi_location, locator_json, updated_at
             FROM bookmarks
             WHERE (?1 IS NULL OR book_id = ?1) AND deleted_at IS NULL
             ORDER BY page ASC, position ASC",
    )?;

    let rows = statement.query_map(params![book_id], |row| {
        Ok(BookmarkDto {
            id: row.get(0)?,
            book_id: row.get(1)?,
            page: row.get(2)?,
            position: row.get(3)?,
            title: row.get(4)?,
            created_at: row.get(5)?,
            cfi_location: row.get(6)?,
            locator_json: row.get(7)?,
            updated_at: row.get(8)?,
        })
    })?;

    let bookmarks = rows.collect::<Result<Vec<_>, _>>()?;
    Ok(bookmarks)
}

pub fn save_bookmark(
    repo: &LibraryRepository,
    payload: SaveBookmarkInput,
) -> AppResult<BookmarkDto> {
    if payload.book_id.trim().is_empty() {
        return Err(AppError::MissingBookId);
    }

    // Canonical cfi_location: when present, page is derived via LocatorCodec (EPUB fallback 1).
    // For EPUB, prefer cfi_location; page is display-only. We keep storing `page`
    // but ensure it is at least 1 when a CFI is supplied.
    let mut page = payload.page;
    if let Some(cfi) = payload.cfi_location.as_ref() {
        if !cfi.trim().is_empty() && cfi.starts_with("epubcfi(") && page < 1 {
            page = 1;
        }
    }

    let now = Utc::now().to_rfc3339();
    let id = Uuid::new_v4().to_string();

    repo.connection.execute(
        "INSERT INTO bookmarks (id, book_id, page, position, title, created_at, updated_at, cfi_location, locator_json, version)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6, ?7, ?8, 1)",
        params![
            id,
            payload.book_id,
            page,
            payload.position,
            payload.title,
            now,
            payload.cfi_location,
            payload.locator_json
        ],
    )?;

    Ok(BookmarkDto {
        id,
        book_id: payload.book_id,
        page,
        position: payload.position,
        title: payload.title,
        created_at: now.clone(),
        cfi_location: payload.cfi_location,
        locator_json: payload.locator_json,
        updated_at: now,
    })
}

pub fn delete_bookmark(repo: &LibraryRepository, id: &str) -> AppResult<()> {
    let now = Utc::now().to_rfc3339();
    repo.connection.execute(
        "UPDATE bookmarks SET deleted_at = ?1, version = version + 1 WHERE id = ?2",
        params![now, id],
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::SaveBookmarkInput;
    use crate::repository::tests::new_repository;

    fn insert_book(repo: &LibraryRepository) {
        let now = Utc::now().to_rfc3339();
        repo.connection
            .execute(
                "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version)
                 VALUES (?1, 'Book', 'Author', 'C:/book.epub', 'epub', 'local', 0, 100, ?2, ?2, 1)",
                params!["book-1", now],
            )
            .unwrap();
    }

    fn input(cfi: Option<&str>) -> SaveBookmarkInput {
        SaveBookmarkInput {
            book_id: "book-1".to_string(),
            page: 7,
            position: 0.42,
            title: Some("Page 7".to_string()),
            cfi_location: cfi.map(str::to_string),
            locator_json: cfi.map(|_| r#"{"href":"ch1.xhtml"}"#.to_string()),
        }
    }

    /// A bookmark saved with a CFI must read back with it. Without this the local
    /// row cannot resolve to a place in the book, so a bookmark pulled from
    /// Supabase renders nowhere.
    #[test]
    fn saved_cfi_survives_the_round_trip() {
        let repo = new_repository();
        insert_book(&repo);
        let cfi = "epubcfi(/6/4!/4/2/2)";

        save_bookmark(&repo, input(Some(cfi))).unwrap();
        let listed = list_bookmarks(&repo, Some("book-1")).unwrap();

        assert_eq!(1, listed.len());
        assert_eq!(Some(cfi.to_string()), listed[0].cfi_location);
        assert_eq!(Some(r#"{"href":"ch1.xhtml"}"#.to_string()), listed[0].locator_json);
    }

    /// LWW needs a clock on the row. docs/sync.md compares `remote.updatedAt >
    /// local`; a bookmark with no `updated_at` cannot be ordered at all.
    #[test]
    fn saved_bookmark_carries_an_updated_at_clock() {
        let repo = new_repository();
        insert_book(&repo);

        let saved = save_bookmark(&repo, input(Some("epubcfi(/6/4!/4/2/2)"))).unwrap();
        let listed = list_bookmarks(&repo, Some("book-1")).unwrap();

        assert!(!saved.updated_at.is_empty());
        assert_eq!(saved.updated_at, listed[0].updated_at);
    }

    /// A page-only bookmark (no CFI) must still work and must not invent a locator.
    #[test]
    fn page_only_bookmark_still_saves_without_a_locator() {
        let repo = new_repository();
        insert_book(&repo);

        save_bookmark(&repo, input(None)).unwrap();
        let listed = list_bookmarks(&repo, Some("book-1")).unwrap();

        assert_eq!(1, listed.len());
        assert_eq!(7, listed[0].page);
        assert_eq!(None, listed[0].cfi_location);
    }
}
