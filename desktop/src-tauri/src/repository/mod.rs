// TRANSITION FACADE: LibraryRepository remains the stable public API while domain slices are extracted.
// REMOVE ONLY AFTER VERIFY: remove facade delegation only when parity + serde contract checks are green in verify.

pub mod bookmarks;
pub mod collections;
pub mod covers;
pub mod dictionary;
pub mod files;
pub mod highlights;
pub mod library;
pub mod metrics;
pub mod notifications;
pub mod progress;
pub mod reading_stats;
pub mod reading_status;
pub mod search;
pub mod settings;
pub mod tags;

use std::collections::HashSet;

use std::path::{Path, PathBuf};

use rusqlite::Connection;

use crate::error::{AppError, AppResult};
use crate::models::{
    ActivityPoint, AddDictionaryWordInput, AppSettingDto, BookCoverDto, BookDeleteInput, BookDto,
    BookImportInput, BookmarkDto, CollectionDto, CreateTagInput, DictionaryWordDto, HighlightDto,
    IndexBookTextInput, LibraryBookDto, NotificationDto, ReadingProgressDto, ReadingSessionInput,
    ReadingSessionSavedDto, ReadingStatsSummaryDto, RemoteHighlightRow, RemoteReadingSessionRow,
    SaveBookmarkInput, SaveHighlightInput, SaveHighlightTagsInput, SaveProgressInput,
    ScanFolderResultDto, SearchBookTextInput, SearchBookTextResponse, TagDto, UpdateHighlightInput,
    UpsertRemoteSummary,
};

const MAX_SETTING_BATCH: usize = 100;
const MAX_SEARCH_PAGE_SIZE: i64 = 200;
const DEFAULT_SEARCH_PAGE_SIZE: i64 = 50;

pub struct LibraryRepository {
    connection: Connection,
}

impl LibraryRepository {
    pub fn new(connection: Connection) -> Self {
        Self { connection }
    }

    pub fn connection(&self) -> &Connection {
        &self.connection
    }

    pub fn list_books(&self) -> AppResult<Vec<BookDto>> {
        library::list_books(self)
    }

    pub fn upsert_book(&self, book: BookDto) -> AppResult<()> {
        library::upsert_book(self, book)
    }

    pub fn get_settings(&self) -> AppResult<Vec<AppSettingDto>> {
        settings::get_settings(self)
    }
    pub fn upsert_settings(&mut self, settings: Vec<AppSettingDto>) -> AppResult<()> {
        self::settings::upsert_settings(self, settings)
    }

    pub fn get_daily_goal_minutes(&self, user_id: Option<&str>) -> AppResult<i64> {
        settings::get_daily_goal_minutes_for_user(self, user_id)
    }

    pub fn save_daily_goal_minutes(
        &mut self,
        minutes: i64,
        user_id: Option<&str>,
    ) -> AppResult<()> {
        settings::save_daily_goal_minutes(self, minutes, user_id)
    }

    // ─── Notification history (NOTIF-02) ───

    pub fn list_notifications(&self) -> AppResult<Vec<NotificationDto>> {
        notifications::list_notifications(self)
    }

    pub fn save_notification(&self, notification: NotificationDto) -> AppResult<()> {
        notifications::insert_notification(self, &notification)
    }

    pub fn mark_notification_read(&self, id: &str, read_at: i64) -> AppResult<bool> {
        notifications::mark_notification_read(self, id, read_at)
    }

    pub fn mark_all_notifications_read(&self, read_at: i64) -> AppResult<i64> {
        notifications::mark_all_notifications_read(self, read_at)
    }

    pub fn clear_notifications(&self) -> AppResult<i64> {
        notifications::delete_all_notifications(self)
    }

    /// Sweep both durable retention bounds using the documented defaults.
    pub fn apply_notification_retention(&self, now_ms: i64) -> AppResult<i64> {
        notifications::apply_notification_retention(
            self,
            now_ms,
            notifications::MAX_NOTIFICATION_COUNT,
            notifications::MAX_NOTIFICATION_AGE_MS,
        )
    }

    pub fn get_today_minutes(
        &self,
        user_id: &str,
        book_id: Option<&str>,
        zone_offset_minutes: i32,
    ) -> AppResult<i64> {
        progress::get_today_minutes(self, user_id, book_id, zone_offset_minutes)
    }
    pub fn is_feature_enabled(&self, feature_name: &str) -> AppResult<bool> {
        metrics::is_feature_enabled(self, feature_name)
    }

    pub fn has_metrics_table(&self) -> AppResult<bool> {
        metrics::has_metrics_table(self)
    }

    pub fn ensure_metrics_table(&self) -> AppResult<()> {
        metrics::ensure_metrics_table(self)
    }

    pub fn record_metric(&self, name: &str, value: f64, tags: Option<&str>) -> AppResult<()> {
        metrics::record_metric(self, name, value, tags)
    }

    pub fn get_metrics(&self, name: &str, days: i32) -> AppResult<Vec<(String, f64, String)>> {
        metrics::get_metrics(self, name, days)
    }

    pub fn get_metrics_summary(&self, name: &str, days: i32) -> AppResult<(i64, f64, f64, f64)> {
        metrics::get_metrics_summary(self, name, days)
    }

    pub fn import_book(&self, app: tauri::AppHandle, input: BookImportInput) -> AppResult<BookDto> {
        library::import_book(self, app, input)
    }

    pub fn scan_folder(&self, path: &str) -> AppResult<ScanFolderResultDto> {
        library::scan_folder(self, path)
    }

    pub fn delete_book(&mut self, app: tauri::AppHandle, input: BookDeleteInput) -> AppResult<()> {
        library::delete_book(self, app, input)
    }

    pub fn delete_book_metadata(&mut self, book_id: &str) -> AppResult<Option<String>> {
        library::delete_book_metadata(self, book_id)
    }

    pub fn upsert_book_cover_from_file(
        &self,
        app: &tauri::AppHandle,
        book_id: &str,
        source_cover_path: &Path,
    ) -> AppResult<BookCoverDto> {
        covers::upsert_book_cover_from_file(self, app, book_id, source_cover_path)
    }

    pub fn upsert_book_cover_from_bytes(
        &self,
        app: &tauri::AppHandle,
        book_id: &str,
        data: &[u8],
        mime_type: Option<&str>,
    ) -> AppResult<BookCoverDto> {
        covers::upsert_book_cover_from_bytes(self, app, book_id, data, mime_type)
    }

    pub fn update_book_progress(&self, book_id: &str, current_page: i32) -> AppResult<()> {
        files::update_book_progress(self, book_id, current_page)
    }

    pub fn save_book_file(
        &self,
        app: &tauri::AppHandle,
        id: &str,
        data: &[u8],
        title: Option<&str>,
        author: Option<&str>,
        format: Option<&str>,
    ) -> AppResult<()> {
        files::save_book_file(self, app, id, data, title, author, format)
    }
    pub fn hide_book_from_library(&self, book_id: &str) -> AppResult<()> {
        files::hide_book_from_library(self, book_id)
    }

    pub fn get_progress(&self, book_id: &str) -> AppResult<Option<ReadingProgressDto>> {
        progress::get_progress(self, book_id)
    }

    pub fn save_progress(&self, payload: SaveProgressInput) -> AppResult<()> {
        progress::save_progress(self, payload)
    }

    pub fn upsert_progress(&self, progress: ReadingProgressDto) -> AppResult<()> {
        progress::upsert_progress(self, progress)
    }

    pub fn list_library_books(&self) -> AppResult<Vec<LibraryBookDto>> {
        library::list_library_books(self)
    }

    pub fn save_reading_session(
        &self,
        session: ReadingSessionInput,
    ) -> AppResult<ReadingSessionSavedDto> {
        progress::save_reading_session(self, session)
    }

    pub fn get_reading_stats(&self, book_id: Option<&str>) -> AppResult<ReadingStatsSummaryDto> {
        progress::get_reading_stats(self, book_id)
    }

    pub fn get_reading_activity(
        &self,
        period: &str,
        granularity: &str,
        book_id: Option<&str>,
        zone_offset_minutes: i32,
    ) -> AppResult<Vec<ActivityPoint>> {
        progress::get_reading_activity(self, period, granularity, book_id, zone_offset_minutes)
    }

    pub fn get_reading_stats_for_range(
        &self,
        from: &str,
        to: &str,
        book_id: Option<&str>,
    ) -> AppResult<ReadingStatsSummaryDto> {
        progress::get_reading_stats_for_range(self, from, to, book_id)
    }

    pub fn get_reading_streak(
        &self,
        book_id: Option<&str>,
        user_id: &str,
        zone_offset_minutes: i32,
    ) -> AppResult<i64> {
        progress::get_reading_streak(self, book_id, user_id, zone_offset_minutes)
    }

    pub fn upsert_remote_reading_sessions(
        &self,
        rows: &[RemoteReadingSessionRow],
    ) -> AppResult<i64> {
        progress::upsert_remote_reading_sessions(self, rows)
    }

    pub fn index_book_text(&mut self, payload: IndexBookTextInput) -> AppResult<()> {
        search::index_book_text(self, payload)
    }

    pub fn search_book_text(
        &self,
        payload: SearchBookTextInput,
    ) -> AppResult<SearchBookTextResponse> {
        search::search_book_text(self, payload)
    }

    pub fn list_highlights(&self, book_id: Option<&str>) -> AppResult<Vec<HighlightDto>> {
        highlights::list_highlights(self, book_id)
    }

    pub fn save_highlight(&self, payload: SaveHighlightInput) -> AppResult<HighlightDto> {
        highlights::save_highlight(self, payload)
    }

    pub fn update_highlight(&self, input: UpdateHighlightInput) -> AppResult<HighlightDto> {
        highlights::update_highlight(self, input)
    }

    pub fn delete_highlight(&self, id: &str) -> AppResult<()> {
        highlights::delete_highlight(self, id)
    }

    pub fn upsert_remote_highlights(
        &self,
        rows: &[RemoteHighlightRow],
    ) -> AppResult<UpsertRemoteSummary> {
        highlights::upsert_remote_highlights(self, rows)
    }

    pub fn list_tags(&self) -> AppResult<Vec<TagDto>> {
        tags::list_tags(self)
    }

    pub fn create_tag(&self, input: CreateTagInput) -> AppResult<TagDto> {
        tags::create_tag(self, input)
    }

    pub fn list_tags_for_highlight(&self, highlight_id: &str) -> AppResult<Vec<TagDto>> {
        tags::list_tags_for_highlight(self, highlight_id)
    }

    pub fn save_highlight_tags(&mut self, input: SaveHighlightTagsInput) -> AppResult<Vec<TagDto>> {
        tags::save_highlight_tags(self, input)
    }

    pub fn list_dictionary_words(&self) -> AppResult<Vec<DictionaryWordDto>> {
        dictionary::list_dictionary_words(self)
    }

    pub fn add_dictionary_word(
        &self,
        input: AddDictionaryWordInput,
    ) -> AppResult<DictionaryWordDto> {
        dictionary::add_dictionary_word(self, input)
    }

    pub fn remove_dictionary_word(&self, id: &str) -> AppResult<()> {
        dictionary::remove_dictionary_word(self, id)
    }

    pub fn update_dictionary_word(
        &self,
        input: crate::models::UpdateDictionaryWordInput,
    ) -> AppResult<DictionaryWordDto> {
        dictionary::update_dictionary_word(self, input)
    }

    pub fn update_dictionary_evidence(
        &self,
        input: crate::models::UpdateDictionaryEvidenceInput,
    ) -> AppResult<DictionaryWordDto> {
        dictionary::update_dictionary_evidence(self, input)
    }

    pub fn search_dictionary_words(
        &self,
        query: &str,
        limit: i64,
        fuzzy: bool,
        user_id: Option<&str>,
    ) -> AppResult<Vec<DictionaryWordDto>> {
        dictionary::search_dictionary_words(self, query, limit, fuzzy, user_id)
    }

    pub fn export_dictionary(&self, format: &str) -> AppResult<String> {
        dictionary::export_dictionary(self, format)
    }

    pub fn import_dictionary(
        &self,
        payload: &str,
        format: &str,
        user_id: Option<&str>,
    ) -> AppResult<crate::models::ImportDictionaryResult> {
        dictionary::import_dictionary(self, payload, format, user_id)
    }

    pub fn list_bookmarks(&self, book_id: Option<&str>) -> AppResult<Vec<BookmarkDto>> {
        bookmarks::list_bookmarks(self, book_id)
    }

    pub fn save_bookmark(&self, payload: SaveBookmarkInput) -> AppResult<BookmarkDto> {
        bookmarks::save_bookmark(self, payload)
    }

    pub fn delete_bookmark(&self, id: &str) -> AppResult<()> {
        bookmarks::delete_bookmark(self, id)
    }

    pub fn create_collection(&self, name: &str, color: Option<&str>) -> AppResult<CollectionDto> {
        collections::create_collection(self, name, color)
    }

    pub fn delete_collection(&self, id: i64) -> AppResult<()> {
        collections::delete_collection(self, id)
    }

    pub fn list_collections(&self) -> AppResult<Vec<CollectionDto>> {
        collections::list_collections(self)
    }

    pub fn add_book_to_collection(&self, book_id: &str, collection_id: i64) -> AppResult<()> {
        collections::add_book_to_collection(self, book_id, collection_id)
    }

    pub fn remove_book_from_collection(&self, book_id: &str, collection_id: i64) -> AppResult<()> {
        collections::remove_book_from_collection(self, book_id, collection_id)
    }

    pub fn get_book_collections(&self, book_id: &str) -> AppResult<Vec<CollectionDto>> {
        collections::get_book_collections(self, book_id)
    }

    pub fn set_reading_status(&self, book_id: &str, status: Option<&str>) -> AppResult<()> {
        reading_status::set_reading_status(self, book_id, status)
    }

    pub fn get_reading_status(&self, book_id: &str) -> AppResult<Option<String>> {
        reading_status::get_reading_status(self, book_id)
    }

    fn validate_setting(setting: &AppSettingDto) -> AppResult<()> {
        settings::validate_setting(setting)
    }

    fn existing_book_filenames_lowercase(&self) -> AppResult<HashSet<String>> {
        library::existing_book_filenames_lowercase(self)
    }

    pub fn has_desktop_parity_schema(&self) -> AppResult<bool> {
        library::has_desktop_parity_schema(self)
    }

    fn validate_percentage(label: &str, value: Option<f64>) -> AppResult<()> {
        if let Some(v) = value {
            if !(0.0..=100.0).contains(&v) {
                return Err(AppError::InvalidInput(format!("{} must be between 0 and 100", label)));
            }
        }

        Ok(())
    }
    pub fn extract_epub_cover(
        &self,
        app: &tauri::AppHandle,
        epub_path: &Path,
        book_id: &str,
    ) -> AppResult<Option<BookCoverDto>> {
        covers::extract_epub_cover(self, app, epub_path, book_id)
    }

    pub fn delete_book_cover(&self, app: &tauri::AppHandle, book_id: &str) -> AppResult<()> {
        covers::delete_book_cover(self, app, book_id)
    }

    fn recompute_reading_stats_from_sessions(
        &self,
        book_id: Option<&str>,
    ) -> AppResult<ReadingStatsSummaryDto> {
        reading_stats::recompute_reading_stats_from_sessions(self, book_id)
    }

    fn reading_stats_drift_over_threshold(
        &self,
        book_id: Option<&str>,
        event_avg_progress: f64,
    ) -> AppResult<bool> {
        reading_stats::reading_stats_drift_over_threshold(self, book_id, event_avg_progress)
    }

    fn build_fts_match_query(query: &str) -> AppResult<String> {
        search::build_fts_match_query(query)
    }

    fn enqueue_cover_cleanup(&self, app: &tauri::AppHandle, storage_path: &str) -> AppResult<()> {
        covers::enqueue_cover_cleanup(self, app, storage_path)
    }

    fn run_deferred_cover_cleanup(&self, app: &tauri::AppHandle) -> AppResult<()> {
        covers::run_deferred_cover_cleanup(self, app)
    }

    fn log_recoverable_cover_error(&self, app: &tauri::AppHandle, message: &str) -> AppResult<()> {
        covers::log_recoverable_cover_error(self, app, message)
    }

    fn find_cover_source_path(book_source_path: &Path) -> Option<PathBuf> {
        covers::find_cover_source_path(book_source_path)
    }
}

#[cfg(test)]
mod tests {
    use chrono::Utc;
    use rusqlite::params;

    use super::*;

    fn apply_test_migrations(connection: &Connection) {
        connection.execute_batch(include_str!("../../migrations/0001_init.sql")).unwrap();
        connection.execute_batch(include_str!("../../migrations/0002_books.sql")).unwrap();
        connection.execute_batch(include_str!("../../migrations/0003_highlights.sql")).unwrap();
        connection
            .execute_batch(include_str!("../../migrations/0004_desktop_feature_parity.sql"))
            .unwrap();
        connection.execute_batch(include_str!("../../migrations/0005_hidden_books.sql")).unwrap();
        connection.execute_batch(include_str!("../../migrations/0006_collections.sql")).unwrap();
        connection
            .execute_batch(include_str!(
                "../../migrations/0007_highlight_note_and_page_contract.sql"
            ))
            .unwrap();
        connection
            .execute_batch(include_str!("../../migrations/0008_queue_and_perf_indexes.sql"))
            .unwrap();
        connection
            .execute_batch(include_str!("../../migrations/0009_dictionary_tags.sql"))
            .unwrap();
        connection.execute_batch(include_str!("../../migrations/0010_book_genre.sql")).unwrap();
        connection.execute_batch(include_str!("../../migrations/0011_book_metadata.sql")).unwrap();
        connection.execute_batch(include_str!("../../migrations/0012_reading_status.sql")).unwrap();
        connection.execute_batch(include_str!("../../migrations/0013_sync_outbox.sql")).unwrap();
        connection
            .execute_batch(include_str!("../../migrations/0014_reading_sessions_sync.sql"))
            .unwrap();
        connection
            .execute_batch(include_str!("../../migrations/0015_dictionary_sync.sql"))
            .unwrap();
        connection.execute_batch(include_str!("../../migrations/0017_addon_registry.sql")).unwrap();
        connection
            .execute_batch(include_str!("../../migrations/0019_dictionary_rich_entries.sql"))
            .unwrap();
        connection
            .execute_batch(include_str!("../../migrations/0020_drop_dictionary_favorite.sql"))
            .unwrap();
        connection.execute_batch(include_str!("../../migrations/0021_notifications.sql")).unwrap();
    }

    pub(crate) fn new_repository() -> LibraryRepository {
        let connection = Connection::open_in_memory().unwrap();
        apply_test_migrations(&connection);
        LibraryRepository::new(connection)
    }

    pub(crate) fn insert_book(repository: &LibraryRepository, id: &str, file_path: &str) {
        let now = Utc::now().to_rfc3339();
        repository
            .connection
            .execute(
                "INSERT INTO books (id, title, author, file_path, format, sync_status, current_page, total_pages, created_at, updated_at, version)
                 VALUES (?1, 'Book', 'Author', ?2, 'epub', 'local', 0, 100, ?3, ?3, 1)",
                params![id, file_path, now],
            )
            .unwrap();
    }

    // ─── reading-stats-real: new tests for activity, range, streak ───
}
