use super::LibraryRepository;
use crate::error::{AppError, AppResult};
use crate::models::CollectionDto;
use chrono::Utc;
use rusqlite::{params, OptionalExtension};

pub fn create_collection(
    repo: &LibraryRepository,
    name: &str,
    color: Option<&str>,
) -> AppResult<CollectionDto> {
    let name = name.trim();
    if name.is_empty() {
        return Err(AppError::InvalidInput("Collection name is required".to_string()));
    }

    let now = Utc::now().to_rfc3339();
    repo.connection.execute(
        "INSERT INTO collections (name, color, is_system, created_at) VALUES (?1, ?2, 0, ?3)",
        params![name, color, now],
    )?;

    let id = repo.connection.last_insert_rowid();
    Ok(CollectionDto {
        id,
        name: name.to_string(),
        color: color.map(String::from),
        is_system: false,
        created_at: now,
    })
}

pub fn delete_collection(repo: &LibraryRepository, id: i64) -> AppResult<()> {
    if id <= 0 {
        return Err(AppError::InvalidInput("Invalid collection id".to_string()));
    }

    let is_system: Option<i32> = repo
        .connection
        .query_row("SELECT is_system FROM collections WHERE id = ?1", params![id], |row| row.get(0))
        .optional()?;
    // One lookup, one classification: a missing row is `NotFound` (it was
    // reported as `InvalidInput` / `VALIDATION_ERROR`, which told the caller
    // their input was wrong when the row simply was not there).
    let is_system = match is_system {
        None => return Err(AppError::NotFound("Collection not found".to_string())),
        Some(flag) => flag,
    };
    if is_system == 1 {
        return Err(AppError::InvalidInput("Cannot delete system collection".to_string()));
    }

    repo.connection
        .execute("DELETE FROM book_collections WHERE collection_id = ?1", params![id])?;
    repo.connection.execute("DELETE FROM collections WHERE id = ?1", params![id])?;
    Ok(())
}

pub fn list_collections(repo: &LibraryRepository) -> AppResult<Vec<CollectionDto>> {
    let mut statement = repo.connection.prepare(
            "SELECT id, name, color, is_system, created_at FROM collections ORDER BY is_system DESC, name ASC",
        )?;

    let rows = statement.query_map([], |row| {
        Ok(CollectionDto {
            id: row.get(0)?,
            name: row.get(1)?,
            color: row.get(2)?,
            is_system: row.get::<_, i32>(3)? != 0,
            created_at: row.get(4)?,
        })
    })?;

    let collections = rows.collect::<Result<Vec<_>, _>>()?;
    Ok(collections)
}

pub fn add_book_to_collection(
    repo: &LibraryRepository,
    book_id: &str,
    collection_id: i64,
) -> AppResult<()> {
    let book_id = book_id.trim();
    if book_id.is_empty() {
        return Err(AppError::MissingBookId);
    }
    if collection_id <= 0 {
        return Err(AppError::InvalidInput("Invalid collection id".to_string()));
    }

    let collection_exists: Option<i32> = repo
        .connection
        .query_row("SELECT 1 FROM collections WHERE id = ?1", params![collection_id], |row| {
            row.get(0)
        })
        .optional()?;
    if collection_exists.is_none() {
        return Err(AppError::InvalidInput("Collection not found".to_string()));
    }

    let book_exists: Option<String> = repo
        .connection
        .query_row(
            "SELECT id FROM books WHERE id = ?1 AND deleted_at IS NULL",
            params![book_id],
            |row| row.get(0),
        )
        .optional()?;
    if book_exists.is_none() {
        return Err(AppError::InvalidInput("Book not found".to_string()));
    }

    repo.connection.execute(
        "INSERT OR IGNORE INTO book_collections (book_id, collection_id) VALUES (?1, ?2)",
        params![book_id, collection_id],
    )?;
    Ok(())
}

pub fn remove_book_from_collection(
    repo: &LibraryRepository,
    book_id: &str,
    collection_id: i64,
) -> AppResult<()> {
    let book_id = book_id.trim();
    if book_id.is_empty() {
        return Err(AppError::MissingBookId);
    }
    if collection_id <= 0 {
        return Err(AppError::InvalidInput("Invalid collection id".to_string()));
    }

    repo.connection.execute(
        "DELETE FROM book_collections WHERE book_id = ?1 AND collection_id = ?2",
        params![book_id, collection_id],
    )?;
    Ok(())
}

pub fn get_book_collections(
    repo: &LibraryRepository,
    book_id: &str,
) -> AppResult<Vec<CollectionDto>> {
    let book_id = book_id.trim();
    if book_id.is_empty() {
        return Err(AppError::MissingBookId);
    }

    let mut statement = repo.connection.prepare(
        "SELECT c.id, c.name, c.color, c.is_system, c.created_at
             FROM collections c
             JOIN book_collections bc ON bc.collection_id = c.id
             WHERE bc.book_id = ?1
             ORDER BY c.is_system DESC, c.name ASC",
    )?;

    let rows = statement.query_map(params![book_id], |row| {
        Ok(CollectionDto {
            id: row.get(0)?,
            name: row.get(1)?,
            color: row.get(2)?,
            is_system: row.get::<_, i32>(3)? != 0,
            created_at: row.get(4)?,
        })
    })?;

    let collections = rows.collect::<Result<Vec<_>, _>>()?;
    Ok(collections)
}
