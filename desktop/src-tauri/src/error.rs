use serde::{Deserialize, Serialize};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum AppError {
    #[error("{}", describe_database_error(.0))]
    Database(#[from] rusqlite::Error),

    #[error("io error: {0}")]
    Io(#[from] std::io::Error),

    #[error("tauri error: {0}")]
    Tauri(#[from] tauri::Error),

    #[error("book id is required")]
    MissingBookId,

    #[error("invalid input: {0}")]
    InvalidInput(String),

    #[error("compatibility error: {0}")]
    Compatibility(String),

    #[error("database constraint violation: {0}")]
    DbConstraint(String),

    #[error("migration failed: {0}")]
    MigrationFail(String),

    #[error("sync conflict: {0}")]
    SyncConflict(String),

    #[error("import error: {0}")]
    ImportError(String),

    #[error("thumbnail generation failed: {0}")]
    ThumbnailFail(String),

    #[error("not found: {0}")]
    NotFound(String),
}

/// Turn a `rusqlite` failure into a message a reader of the notification can act on.
///
/// The generic rendering (`Invalid column type Blob at index: 1`) names a column
/// *position* and hides the column name that rusqlite already carries, so it
/// cannot be traced to a row. The one case that matters here is a TEXT column
/// whose stored bytes are not valid UTF-8: SQLite reports such a value as a
/// BLOB, `row.get::<_, String>` refuses it, and the whole query fails. The
/// message says which column and which book, because that is what makes the
/// failure fixable.
fn describe_database_error(err: &rusqlite::Error) -> String {
    match err {
        rusqlite::Error::InvalidColumnType(index, column, rusqlite::types::Type::Blob) => format!(
            "database error: column `{column}` (index {index}) holds bytes that are not valid \
             UTF-8, so SQLite reports it as BLOB and the row cannot be read as text. Repair the \
             stored value or re-import the row."
        ),
        rusqlite::Error::InvalidColumnType(index, column, data_type) => format!(
            "database error: column `{column}` (index {index}) could not be read as the requested \
             type; SQLite reports {data_type}."
        ),
        rusqlite::Error::FromSqlConversionFailure(index, data_type, source) => format!(
            "database error: converting column at index {index} from {data_type} failed: {source}"
        ),
        other => format!("database error: {other}"),
    }
}

impl AppError {
    pub fn recoverable(&self) -> bool {
        match self {
            AppError::DbConstraint(_) => false,
            AppError::MigrationFail(_) => false,
            AppError::SyncConflict(_) => true,
            AppError::ImportError(_) => true,
            AppError::ThumbnailFail(_) => true,
            AppError::InvalidInput(_) => true,
            AppError::Compatibility(_) => true,
            AppError::NotFound(_) => true,
            AppError::MissingBookId => false,
            AppError::Database(_) => false,
            AppError::Io(_) => false,
            AppError::Tauri(_) => false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorCode {
    Database,
    Io,
    Tauri,
    MissingBookId,
    InvalidInput,
    Compatibility,
    DbConstraint,
    MigrationFail,
    SyncConflict,
    ImportError,
    ThumbnailFail,
    NotFound,
}

pub type AppResult<T> = Result<T, AppError>;

#[cfg(test)]
mod tests {
    use super::*;

    /// The notification the reader actually received read
    /// `database error: Conversion error from type Text at index: 1, invalid
    /// utf-8 sequence of 1 bytes from index 25` — an index, no column, no cause.
    /// A BLOB reported where TEXT is expected is the app's UTF-8 symptom, and
    /// the message must now name the column so the row can be found.
    #[test]
    fn a_blob_where_text_is_expected_names_the_column() {
        let err = AppError::Database(rusqlite::Error::InvalidColumnType(
            1,
            "title".to_string(),
            rusqlite::types::Type::Blob,
        ));
        let message = err.to_string();
        assert!(message.contains("title"), "the column must be named: {message}");
        assert!(message.contains("not valid UTF-8"), "the cause must be stated: {message}");
        assert!(message.contains("index 1"), "the position stays available: {message}");
    }

    #[test]
    fn an_ordinary_database_error_keeps_its_meaning() {
        let err = AppError::Database(rusqlite::Error::InvalidColumnType(
            3,
            "current_page".to_string(),
            rusqlite::types::Type::Text,
        ));
        let message = err.to_string();
        assert!(message.contains("current_page"), "{message}");
        assert!(message.contains("Text"), "{message}");
    }

    #[test]
    fn test_db_constraint_recoverable() {
        let err = AppError::DbConstraint("UNIQUE constraint failed: books.id".to_string());
        assert!(!err.recoverable());
    }

    #[test]
    fn test_migration_fail_recoverable() {
        let err = AppError::MigrationFail("schema version mismatch".to_string());
        assert!(!err.recoverable());
    }

    #[test]
    fn test_sync_conflict_recoverable() {
        let err = AppError::SyncConflict("local and remote changes conflict".to_string());
        assert!(err.recoverable());
    }

    #[test]
    fn test_import_error_recoverable() {
        let err = AppError::ImportError("invalid epub structure".to_string());
        assert!(err.recoverable());
    }

    #[test]
    fn test_thumbnail_fail_recoverable() {
        let err = AppError::ThumbnailFail("image too small".to_string());
        assert!(err.recoverable());
    }

    #[test]
    fn test_invalid_input_recoverable() {
        let err = AppError::InvalidInput("missing required field".to_string());
        assert!(err.recoverable());
    }

    #[test]
    fn test_database_not_recoverable() {
        let err = AppError::Database(rusqlite::Error::QueryReturnedNoRows);
        assert!(!err.recoverable());
    }
}
