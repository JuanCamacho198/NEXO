pub mod domain;
pub mod dto;
pub mod mapper;

pub use dto::SyncOutboxRowDto;

use serde::{Deserialize, Serialize};

use crate::error::AppError;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HighlightDto {
    pub id: String,
    pub book_id: String,
    pub color: String,
    pub text: String,
    pub page: i32,
    pub rect_left: f64,
    pub rect_right: f64,
    pub rect_top: f64,
    pub rect_bottom: f64,
    pub cfi: Option<String>,
    pub note: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SaveHighlightInput {
    /// Client-generated id (UUID). The backend MUST honor it so that
    /// re-applying a remote highlight (sync pull) upserts instead of
    /// inserting a duplicate row.
    pub id: Option<String>,
    pub book_id: String,
    pub color: String,
    pub text: String,
    pub page_number: Option<i32>,
    pub page: Option<i32>,
    pub rect_left: f64,
    pub rect_right: f64,
    pub rect_top: f64,
    pub rect_bottom: f64,
    pub cfi: Option<String>,
    pub note: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateHighlightInput {
    pub id: String,
    #[serde(default)]
    pub color: Option<String>,
    #[serde(default)]
    pub note: Option<String>,
    #[serde(default)]
    pub page: Option<i32>,
    #[serde(default)]
    pub page_number: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TagDto {
    pub id: String,
    pub name: String,
    pub color: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SaveHighlightTagsInput {
    pub highlight_id: String,
    pub tag_ids: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateTagInput {
    pub name: String,
    pub color: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DictionaryWordDto {
    pub id: String,
    pub word: String,
    pub created_at: String,
    #[serde(default)]
    pub normalized_word: Option<String>,
    #[serde(default)]
    pub user_id: Option<String>,
    #[serde(default)]
    pub tags: Option<Vec<String>>,
    #[serde(default)]
    pub srs_stage: Option<i32>,
    #[serde(default)]
    pub updated_at: Option<String>,
    #[serde(default)]
    pub deleted_at: Option<String>,
    #[serde(default)]
    pub synced_at: Option<String>,
    #[serde(default)]
    pub definition: Option<String>,
    #[serde(default)]
    pub part_of_speech: Option<String>,
    #[serde(default)]
    pub phonetic: Option<String>,
    #[serde(default)]
    pub example: Option<String>,
    #[serde(default)]
    pub quote: Option<String>,
    #[serde(default)]
    pub source_book_id: Option<String>,
    #[serde(default)]
    pub source_book_title: Option<String>,
    #[serde(default)]
    pub source_book_author: Option<String>,
    #[serde(default)]
    pub source_chapter: Option<String>,
    #[serde(default)]
    pub source_locator: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AddDictionaryWordInput {
    pub word: String,
    #[serde(default)]
    pub user_id: Option<String>,
    #[serde(default)]
    pub tags: Option<Vec<String>>,
    #[serde(default)]
    pub srs_stage: Option<i32>,
    #[serde(default)]
    pub definition: Option<String>,
    #[serde(default)]
    pub part_of_speech: Option<String>,
    #[serde(default)]
    pub phonetic: Option<String>,
    #[serde(default)]
    pub example: Option<String>,
    #[serde(default)]
    pub quote: Option<String>,
    #[serde(default)]
    pub source_book_id: Option<String>,
    #[serde(default)]
    pub source_book_title: Option<String>,
    #[serde(default)]
    pub source_book_author: Option<String>,
    #[serde(default)]
    pub source_chapter: Option<String>,
    #[serde(default)]
    pub source_locator: Option<String>,
}

/// User-authored edits only. Evidence fields are intentionally absent so that
/// an evidence write through the update payload cannot compile (REQ-DRE-007);
/// evidence is written exclusively by the dedicated re-capture command.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateDictionaryWordInput {
    pub id: String,
    #[serde(default)]
    pub word: Option<String>,
    #[serde(default)]
    pub tags: Option<Vec<String>>,
    #[serde(default)]
    pub srs_stage: Option<i32>,
    #[serde(default)]
    pub definition: Option<String>,
    #[serde(default)]
    pub part_of_speech: Option<String>,
    #[serde(default)]
    pub phonetic: Option<String>,
    #[serde(default)]
    pub example: Option<String>,
}

/// Captured evidence only. The four user-authored fields are intentionally
/// absent so that a re-capture can never clear them (REQ-DRE-008); evidence is
/// written exclusively through this input.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateDictionaryEvidenceInput {
    pub id: String,
    #[serde(default)]
    pub quote: Option<String>,
    #[serde(default)]
    pub source_book_id: Option<String>,
    #[serde(default)]
    pub source_book_title: Option<String>,
    #[serde(default)]
    pub source_book_author: Option<String>,
    #[serde(default)]
    pub source_chapter: Option<String>,
    #[serde(default)]
    pub source_locator: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchDictionaryWordsInput {
    pub query: String,
    #[serde(default)]
    pub limit: Option<i64>,
    #[serde(default)]
    pub fuzzy: Option<bool>,
    #[serde(default)]
    pub user_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportDictionaryResult {
    pub imported: i64,
    pub errors: Vec<ImportDictionaryError>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportDictionaryError {
    pub row: i64,
    pub reason: String,
}

impl SaveHighlightInput {
    pub fn resolve_page_number(&self) -> Result<i32, AppError> {
        match (self.page_number, self.page) {
            (Some(page_number), Some(page)) if page_number != page => {
                Err(AppError::InvalidInput(format!(
                    "Highlight payload has conflicting page fields: pageNumber={} page={}",
                    page_number, page
                )))
            }
            (Some(page_number), _) => Ok(page_number),
            (None, Some(page)) => Ok(page),
            (None, None) => Err(AppError::InvalidInput(
                "Highlight payload requires pageNumber (or legacy page)".to_string(),
            )),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BookmarkDto {
    pub id: String,
    pub book_id: String,
    pub page: i32,
    pub position: f64,
    pub title: Option<String>,
    pub created_at: String,
    pub cfi_location: Option<String>,
    pub locator_json: Option<String>,
    pub updated_at: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SaveBookmarkInput {
    pub book_id: String,
    pub page: i32,
    pub position: f64,
    pub title: Option<String>,
    /// Canonical CFI location for EPUB (page is derived via LocatorCodec.fromCfi).
    #[serde(default)]
    pub cfi_location: Option<String>,
    #[serde(default)]
    pub locator_json: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BookDto {
    pub id: String,
    pub title: String,
    pub author: String,
    pub file_path: String,
    pub format: String,
    pub sync_status: String,
    pub current_page: i32,
    pub total_pages: i32,
    pub created_at: String,
    pub updated_at: String,
    pub genre: Option<String>,
    pub language: Option<String>,
    pub publication_date: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BookImportInput {
    pub source_path: String,
    pub title: Option<String>,
    pub author: Option<String>,
    pub format: String,
    pub genre: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScannedBookFileDto {
    pub full_path: String,
    pub file_name: String,
    pub format: String,
    pub is_duplicate: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanFolderResultDto {
    pub files: Vec<ScannedBookFileDto>,
    pub skipped_unsupported_count: i64,
    pub skipped_unreadable_count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReadingProgressDto {
    pub id: String,
    pub book_id: String,
    pub cfi_location: String,
    pub percentage: f64,
    pub updated_at: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SaveProgressInput {
    pub book_id: String,
    pub cfi_location: String,
    pub percentage: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppSettingDto {
    pub key: String,
    pub value_json: String,
    pub updated_at: String,
}

/// One persisted notification (NOTIF-02). Mirrors the frontend `Notification`
/// model; `i18n_params` and `target` travel as opaque JSON text so the backend
/// never has to understand the localization or navigation contract.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NotificationDto {
    pub id: String,
    /// Epoch milliseconds.
    pub created_at: i64,
    pub source: String,
    pub category: String,
    pub severity: String,
    pub interruption: String,
    pub i18n_key: String,
    /// JSON object text, or None.
    pub i18n_params: Option<String>,
    /// JSON object text, or None.
    pub target: Option<String>,
    /// Epoch milliseconds while unread is null.
    pub read_at: Option<i64>,
    pub dedup_key: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BookCoverDto {
    pub book_id: String,
    pub storage_path: String,
    pub mime_type: String,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub byte_size: i64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpsertBookCoverInput {
    pub book_id: String,
    pub data: Vec<u8>,
    pub mime_type: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryBookDto {
    pub id: String,
    pub title: String,
    pub author: String,
    pub format: String,
    pub current_page: i32,
    pub total_pages: i32,
    pub progress_percentage: f64,
    pub cover_path: Option<String>,
    pub minutes_read: i64,
    pub updated_at: String,
    pub created_at: String,
    pub collection_ids: Vec<i64>,
    pub genre: Option<String>,
    pub language: Option<String>,
    pub publication_date: Option<String>,
    pub cover_user_deleted: Option<bool>,
    pub reading_status: Option<String>,
    /// Absolute path to the book file. Empty on sync stubs — placeholder rows
    /// created by the highlights FK guard for books that were never downloaded
    /// here. Consumers treat an empty path as "not a real book".
    #[serde(default)]
    pub file_path: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ListLibraryBooksInput {
    pub response_version: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReadingStatsSummaryDto {
    pub total_minutes_read: i64,
    pub total_sessions: i64,
    pub books_started: i64,
    pub books_completed: i64,
    pub avg_progress_percentage: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivityPoint {
    pub bucket: String,
    pub minutes: i64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReadingSessionInput {
    pub user_id: String,
    pub book_id: String,
    pub started_at: String,
    pub ended_at: Option<String>,
    pub duration_seconds: i64,
    pub start_percentage: Option<f64>,
    pub end_percentage: Option<f64>,
    /// Explicit zone offset in minutes (positive = east of UTC) supplied by the
    /// client. Falls back to the offset carried by `started_at` when absent.
    #[serde(default)]
    pub zone_offset_minutes: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReadingSessionSavedDto {
    pub id: String,
    pub duration_minutes: i64,
    pub date: String,
    pub updated_at_epoch_millis: i64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteReadingSessionRow {
    pub id: String,
    pub user_id: String,
    pub book_id: String,
    pub started_at: String,
    pub duration_minutes: i64,
    pub date: String,
    pub updated_at_epoch_millis: i64,
    pub start_percentage: Option<f64>,
    pub end_percentage: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteHighlightRow {
    pub id: String,
    pub user_id: String,
    pub book_id: String,
    pub cfi_range: Option<String>,
    pub text_content: String,
    pub note: Option<String>,
    pub color: String,
    pub page: Option<i64>,
    pub updated_at_epoch_millis: i64,
    pub deleted_at_epoch_millis: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpsertRemoteSummary {
    pub applied: i64,
    pub skipped_unknown_book: i64,
    pub skipped_invalid: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResultDto {
    pub chunk_id: String,
    pub book_id: String,
    pub locator: String,
    pub snippet: String,
    pub rank: f64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexBookTextInput {
    pub book_id: String,
    pub chunks: Vec<IndexBookTextChunkInput>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexBookTextChunkInput {
    pub locator: String,
    pub chunk_index: i32,
    pub text_content: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchBookTextInput {
    pub book_id: String,
    pub query: String,
    pub page: i64,
    pub page_size: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchBookTextResponse {
    pub items: Vec<SearchResultDto>,
    pub total: i64,
    pub page: i64,
    pub page_size: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandErrorDto {
    pub code: String,
    pub message: String,
    pub recoverable: bool,
}

impl CommandErrorDto {
    pub fn validation(message: impl Into<String>) -> Self {
        Self { code: "VALIDATION_ERROR".to_string(), message: message.into(), recoverable: true }
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self { code: "INTERNAL_ERROR".to_string(), message: message.into(), recoverable: false }
    }

    pub fn compatibility(message: impl Into<String>) -> Self {
        Self { code: "COMPATIBILITY_ERROR".to_string(), message: message.into(), recoverable: true }
    }

    pub fn db_constraint(message: impl Into<String>) -> Self {
        Self { code: "DB_CONSTRAINT".to_string(), message: message.into(), recoverable: false }
    }

    pub fn sync_conflict(message: impl Into<String>) -> Self {
        Self { code: "SYNC_CONFLICT".to_string(), message: message.into(), recoverable: true }
    }

    pub fn import_error(message: impl Into<String>) -> Self {
        Self { code: "IMPORT_ERROR".to_string(), message: message.into(), recoverable: true }
    }

    pub fn thumbnail_error(message: impl Into<String>) -> Self {
        Self { code: "THUMBNAIL_ERROR".to_string(), message: message.into(), recoverable: true }
    }

    pub fn migration_fail(message: impl Into<String>) -> Self {
        Self { code: "MIGRATION_FAIL".to_string(), message: message.into(), recoverable: false }
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self { code: "NOT_FOUND".to_string(), message: message.into(), recoverable: true }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BookDeleteInput {
    pub book_id: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HideBookInput {
    pub book_id: String,
}

/// Per-account selection-menu language preferences (T3, D6).
///
/// The sides are optional and mirror the Android model consumed by the
/// frontend `translationLanguageResolution.ts`: `None` means "infer" — source
/// from the book, target from the app locale. The pair lives in its own table
/// rather than two `app_settings` keys so it cannot desynchronise, and because
/// `validate_setting` rejects the nested object that would be needed to keep it
/// atomic there.
///
/// `definition_language` (D6) rides the SAME row rather than a second table:
/// it is per-account, singular, and read on the same event as the pair — the
/// selection menu. A second table would be a second place for the
/// account-scoping rule to be forgotten.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TranslationPreferencesDto {
    /// ISO 639-1 code, or `None` to infer the source from the book.
    #[serde(default)]
    pub source_language: Option<String>,
    /// ISO 639-1 code, or `None` to use the app locale.
    #[serde(default)]
    pub target_language: Option<String>,
    /// ISO 639-1 code the "Definir" result is written in, or `None` to use the
    /// app locale. This is the `readerLang` the frontend sends to
    /// `defineSelection`; before D6 that value was always the app locale.
    #[serde(default)]
    pub definition_language: Option<String>,
    /// RFC 3339 write timestamp; empty when no preference has ever been saved.
    #[serde(default)]
    pub updated_at: String,
}

/// One cached translation, which is also the per-account translation history
/// (T4). There is deliberately no second history table: a duplicate would mean
/// two writers for one fact.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TranslationCacheEntryDto {
    /// SHA-256 of the canonical `owner|source|target|text` key, lowercase hex.
    pub cache_key: String,
    pub source_language: String,
    pub target_language: String,
    pub original_text: String,
    pub translated_text: String,
    /// RFC 3339.
    pub created_at: String,
}

/// One definition line, in the shape the reader renders it (D4).
///
/// This mirrors `commands::define::DefinitionItem` and is the STORED shape: the
/// cache serialises a `Vec` of these to JSON in `definition_cache.definitions`.
/// It is a separate type from the command's DTO because the repository must not
/// depend on a command module — the command maps between the two, which is the
/// same boundary `translate.rs` keeps with MyMemory.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DefinitionItemDto {
    pub text: String,
    #[serde(default)]
    pub part_of_speech: Option<String>,
}

/// Where a cached definition came from, persisted with it (D4).
///
/// The value is stored as the SAME lowercase string the frontend receives
/// (`native` / `translated`), so the wire format and the storage format cannot
/// drift apart. It is kept as a small enum rather than a bare `String` because
/// the distinction is meaningful to the reader — a machine-translated gloss is
/// flagged in the UI — and an unknown value from a hand-edited row must not be
/// silently presented as native. `from_stored` maps anything unrecognised to
/// `Unknown`, which the UI can only render as "we do not know".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LibraryDefinitionSource {
    Native,
    Translated,
    /// A stored value this build does not recognise. Never written; it exists so
    /// an unreadable row degrades to "unknown" instead of claiming `native`.
    #[serde(other)]
    Unknown,
}

impl LibraryDefinitionSource {
    /// The exact string stored in `definition_cache.source`.
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Native => "native",
            Self::Translated => "translated",
            Self::Unknown => "",
        }
    }

    /// Parse a stored value. An empty or unrecognised string is `Unknown`, never
    /// a guess.
    pub fn from_stored(value: &str) -> Self {
        match value {
            "native" => Self::Native,
            "translated" => Self::Translated,
            _ => Self::Unknown,
        }
    }
}

/// A resolved definition result as the repository persists and returns it (D4).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryDefinitionResult {
    pub word: String,
    pub definitions: Vec<DefinitionItemDto>,
    /// `None` is the honest "every source missed" — the empty result. It is a
    /// legitimate value to BUILD but one the cache refuses to store.
    #[serde(default)]
    pub source: Option<LibraryDefinitionSource>,
    /// CC BY-SA attribution. Stored rather than re-derived, because the license
    /// requires it to accompany the text.
    #[serde(default)]
    pub attribution: String,
}

/// One cached definition, which is also the per-account definition history (D4).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DefinitionCacheEntryDto {
    /// SHA-256 of the canonical `owner|reader|wordLanguage|word` key, hex.
    pub cache_key: String,
    pub word: String,
    pub word_language: String,
    pub reader_language: String,
    /// The stored `source` string, verbatim.
    pub source: String,
    /// RFC 3339.
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CollectionDto {
    pub id: i64,
    pub name: String,
    pub color: Option<String>,
    pub is_system: bool,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateCollectionInput {
    pub name: String,
    pub color: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BookCollectionInput {
    pub book_id: String,
    pub collection_id: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_resolve_page_number_with_page_number_only() {
        let input = SaveHighlightInput {
            id: None,
            book_id: "b1".to_string(),
            color: "yellow".to_string(),
            text: "test".to_string(),
            page_number: Some(42),
            page: None,
            rect_left: 0.0,
            rect_right: 10.0,
            rect_top: 0.0,
            rect_bottom: 10.0,
            cfi: None,
            note: None,
        };
        assert_eq!(input.resolve_page_number().unwrap(), 42);
    }

    #[test]
    fn test_resolve_page_number_with_legacy_page_only() {
        let input = SaveHighlightInput {
            id: None,
            book_id: "b1".to_string(),
            color: "yellow".to_string(),
            text: "test".to_string(),
            page_number: None,
            page: Some(99),
            rect_left: 0.0,
            rect_right: 10.0,
            rect_top: 0.0,
            rect_bottom: 10.0,
            cfi: None,
            note: None,
        };
        assert_eq!(input.resolve_page_number().unwrap(), 99);
    }

    #[test]
    fn test_resolve_page_number_with_both_matching() {
        let input = SaveHighlightInput {
            id: None,
            book_id: "b1".to_string(),
            color: "yellow".to_string(),
            text: "test".to_string(),
            page_number: Some(7),
            page: Some(7),
            rect_left: 0.0,
            rect_right: 10.0,
            rect_top: 0.0,
            rect_bottom: 10.0,
            cfi: None,
            note: None,
        };
        assert_eq!(input.resolve_page_number().unwrap(), 7);
    }

    #[test]
    fn test_resolve_page_number_with_conflicting_fields() {
        let input = SaveHighlightInput {
            id: None,
            book_id: "b1".to_string(),
            color: "yellow".to_string(),
            text: "test".to_string(),
            page_number: Some(7),
            page: Some(99),
            rect_left: 0.0,
            rect_right: 10.0,
            rect_top: 0.0,
            rect_bottom: 10.0,
            cfi: None,
            note: None,
        };
        let err = input.resolve_page_number().unwrap_err();
        match err {
            AppError::InvalidInput(msg) => {
                assert!(msg.contains("pageNumber"));
                assert!(msg.contains("page"));
            }
            _ => panic!("Expected InvalidInput error"),
        }
    }

    #[test]
    fn test_resolve_page_number_with_neither_field() {
        let input = SaveHighlightInput {
            id: None,
            book_id: "b1".to_string(),
            color: "yellow".to_string(),
            text: "test".to_string(),
            page_number: None,
            page: None,
            rect_left: 0.0,
            rect_right: 10.0,
            rect_top: 0.0,
            rect_bottom: 10.0,
            cfi: None,
            note: None,
        };
        let err = input.resolve_page_number().unwrap_err();
        match err {
            AppError::InvalidInput(msg) => {
                assert!(msg.contains("pageNumber"));
            }
            _ => panic!("Expected InvalidInput error"),
        }
    }
}
