use std::fs;
use std::path::PathBuf;

use chrono::Utc;
use nexo_desktop::commands::list_library_books_internal;
use nexo_desktop::db::open_and_migrate;
use nexo_desktop::models::{
    ActivityPoint, AppSettingDto, BookDto, ListLibraryBooksInput, ReadingSessionInput,
    RemoteHighlightRow, RemoteReadingSessionRow,
};
use nexo_desktop::repository::LibraryRepository;
use uuid::Uuid;

fn temp_db_path() -> PathBuf {
    std::env::temp_dir().join(format!("nexo_desktop_it_{}.db", Uuid::new_v4()))
}

#[test]
fn migration_chain_creates_desktop_parity_schema() {
    let db_path = temp_db_path();
    let connection = open_and_migrate(&db_path).unwrap();
    let repository = LibraryRepository::new(connection);

    assert!(repository.has_desktop_parity_schema().unwrap());

    let _ = fs::remove_file(db_path);
}

#[test]
fn list_library_books_internal_enforces_response_version_and_schema_presence() {
    let db_path = temp_db_path();
    let connection = open_and_migrate(&db_path).unwrap();
    let repository = LibraryRepository::new(connection);

    let unsupported = list_library_books_internal(
        &repository,
        Some(ListLibraryBooksInput { response_version: Some(99) }),
    );
    assert!(unsupported.is_err());

    let supported = list_library_books_internal(
        &repository,
        Some(ListLibraryBooksInput { response_version: Some(1) }),
    );
    assert!(supported.is_ok());

    let _ = fs::remove_file(db_path);
}

#[test]
fn delete_book_metadata_soft_deletes_cover_metadata_and_returns_storage_path() {
    let db_path = temp_db_path();
    let connection = open_and_migrate(&db_path).unwrap();
    let mut repository = LibraryRepository::new(connection);
    let now = Utc::now().to_rfc3339();

    repository
        .upsert_book(BookDto {
            id: "book-delete".to_string(),
            title: "Delete Test".to_string(),
            author: "Tester".to_string(),
            file_path: "C:/library/delete-test.epub".to_string(),
            format: "epub".to_string(),
            sync_status: "local".to_string(),
            current_page: 0,
            total_pages: 100,
            created_at: now.clone(),
            updated_at: now,
            genre: None,
            language: None,
            publication_date: None,
        })
        .unwrap();

    let storage_path = repository.delete_book_metadata("book-delete").unwrap();
    assert!(storage_path.is_none());

    let remaining = repository.list_books().unwrap();
    assert!(remaining.is_empty());

    let _ = fs::remove_file(db_path);
}

#[test]
fn restart_roundtrip_preserves_settings_and_stats() {
    let db_path = temp_db_path();
    let now = Utc::now().to_rfc3339();

    {
        let connection = open_and_migrate(&db_path).unwrap();
        let mut repository = LibraryRepository::new(connection);

        repository
            .upsert_book(BookDto {
                id: "book-restart".to_string(),
                title: "Restart Test".to_string(),
                author: "Tester".to_string(),
                file_path: "C:/library/restart-test.epub".to_string(),
                format: "epub".to_string(),
                sync_status: "local".to_string(),
                current_page: 0,
                total_pages: 100,
                created_at: now.clone(),
                updated_at: now.clone(),
                genre: None,
                language: None,
                publication_date: None,
            })
            .unwrap();

        repository
            .upsert_settings(vec![AppSettingDto {
                key: "ui.theme".to_string(),
                value_json: "\"sepia\"".to_string(),
                updated_at: now.clone(),
            }])
            .unwrap();

        repository
            .save_reading_session(ReadingSessionInput {
                user_id: "u-parity".to_string(),
                book_id: "book-restart".to_string(),
                started_at: now.clone(),
                ended_at: Some((Utc::now() + chrono::Duration::seconds(300)).to_rfc3339()),
                duration_seconds: 300,
                start_percentage: Some(0.0),
                end_percentage: Some(45.0),
            })
            .unwrap();
    }

    {
        let connection = open_and_migrate(&db_path).unwrap();
        let repository = LibraryRepository::new(connection);

        let settings = repository.get_settings().unwrap();
        assert_eq!(settings.len(), 1);
        assert_eq!(settings[0].key, "ui.theme");
        assert_eq!(settings[0].value_json, "\"sepia\"");

        let stats = repository.get_reading_stats(Some("book-restart")).unwrap();
        assert_eq!(stats.total_sessions, 1);
        assert_eq!(stats.total_minutes_read, 5);
    }

    let _ = fs::remove_file(db_path);
}

#[test]
fn dto_camel_case_contract_and_mapper_roundtrip_are_preserved() {
    let dto = nexo_desktop::models::dto::ReadingProgressDto {
        id: "p1".to_string(),
        book_id: "b1".to_string(),
        cfi_location: "epubcfi(/6/2)".to_string(),
        percentage: 42.5,
        updated_at: Utc::now().to_rfc3339(),
    };

    let json = serde_json::to_value(&dto).unwrap();
    assert!(json.get("bookId").is_some());
    assert!(json.get("cfiLocation").is_some());
    assert!(json.get("updatedAt").is_some());

    let domain = nexo_desktop::models::mapper::progress_dto_to_domain(dto.clone());
    let back = nexo_desktop::models::mapper::progress_domain_to_dto(domain);
    assert_eq!(dto, back);
}

#[test]
fn repository_domain_sql_ownership_isolated_by_module_files() {
    let progress_src = include_str!("../src/repository/progress.rs");
    let highlights_src = include_str!("../src/repository/highlights.rs");
    let bookmarks_src = include_str!("../src/repository/bookmarks.rs");
    let collections_src = include_str!("../src/repository/collections.rs");
    let search_src = include_str!("../src/repository/search.rs");

    assert!(progress_src.contains("reading_progress") || progress_src.contains("reading_sessions"));
    assert!(highlights_src.contains("highlights"));
    assert!(bookmarks_src.contains("bookmarks"));
    assert!(
        collections_src.contains("collections") || collections_src.contains("book_collections")
    );
    assert!(search_src.contains("book_text_chunks") || search_src.contains("book_text_fts"));
}

#[test]
fn repository_mutability_boundaries_are_explicit_in_facade_signatures() {
    let repository_mod = include_str!("../src/repository/mod.rs");

    assert!(repository_mod.contains("pub fn list_books(&self"));
    assert!(repository_mod.contains("pub fn get_progress(&self"));
    assert!(repository_mod.contains("pub fn save_progress(&self"));

    assert!(repository_mod.contains("pub fn delete_book(&mut self"));
    assert!(repository_mod.contains("pub fn delete_book_metadata(&mut self"));
    assert!(repository_mod.contains("pub fn index_book_text(&mut self"));
}

#[test]
fn commands_feature_isolation_contract_is_enforced_by_module_layout() {
    let commands_mod = include_str!("../src/commands/mod.rs");
    let bookmarks_mod = include_str!("../src/commands/bookmarks.rs");

    assert!(commands_mod.contains("pub mod bookmarks;"));
    assert!(commands_mod.contains("pub use bookmarks::*;"));

    assert!(bookmarks_mod.contains("saveBookmark"));
    assert!(bookmarks_mod.contains("deleteBookmark"));
    assert!(!bookmarks_mod.contains("saveProgress"));
    assert!(!bookmarks_mod.contains("listHighlights"));
}

#[test]
fn domain_evolution_keeps_ipc_contract_stable_via_mapper_boundaries() {
    let domain = nexo_desktop::models::domain::ReadingProgress {
        id: "p2".to_string(),
        book_id: "b2".to_string(),
        locator: "epubcfi(/6/4)".to_string(),
        percent: 77.0,
        updated_at: Utc::now().to_rfc3339(),
    };

    let dto = nexo_desktop::models::mapper::progress_domain_to_dto(domain);
    let json = serde_json::to_value(dto).unwrap();

    assert!(json.get("bookId").is_some());
    assert!(json.get("cfiLocation").is_some());
    assert!(json.get("percentage").is_some());
    assert!(json.get("updatedAt").is_some());
}

#[test]
fn reading_stats_activity_command_round_trips_through_repository() {
    use chrono::Duration;

    let db_path = temp_db_path();
    let connection = open_and_migrate(&db_path).unwrap();
    let repository = LibraryRepository::new(connection);

    let today = Utc::now().date_naive();

    // Insert 3 books
    repository
        .upsert_book(BookDto {
            id: "book-1".to_string(),
            title: "Book One".to_string(),
            author: "Author".to_string(),
            file_path: "C:/library/book-1.epub".to_string(),
            format: "epub".to_string(),
            sync_status: "local".to_string(),
            current_page: 0,
            total_pages: 100,
            created_at: Utc::now().to_rfc3339(),
            updated_at: Utc::now().to_rfc3339(),
            genre: None,
            language: None,
            publication_date: None,
        })
        .unwrap();
    repository
        .upsert_book(BookDto {
            id: "book-2".to_string(),
            title: "Book Two".to_string(),
            author: "Author".to_string(),
            file_path: "C:/library/book-2.epub".to_string(),
            format: "epub".to_string(),
            sync_status: "local".to_string(),
            current_page: 0,
            total_pages: 100,
            created_at: Utc::now().to_rfc3339(),
            updated_at: Utc::now().to_rfc3339(),
            genre: None,
            language: None,
            publication_date: None,
        })
        .unwrap();
    repository
        .upsert_book(BookDto {
            id: "book-3".to_string(),
            title: "Book Three".to_string(),
            author: "Author".to_string(),
            file_path: "C:/library/book-3.epub".to_string(),
            format: "epub".to_string(),
            sync_status: "local".to_string(),
            current_page: 0,
            total_pages: 100,
            created_at: Utc::now().to_rfc3339(),
            updated_at: Utc::now().to_rfc3339(),
            genre: None,
            language: None,
            publication_date: None,
        })
        .unwrap();

    // Insert 3 reading sessions spanning 3 days (today, yesterday, day before)
    for i in 0..3_i64 {
        let session_time = (today - Duration::days(i)).and_hms_opt(10, 0, 0).unwrap().and_utc();
        repository
            .save_reading_session(ReadingSessionInput {
                user_id: "u-activity".to_string(),
                book_id: format!("book-{}", i + 1),
                started_at: session_time.to_rfc3339(),
                ended_at: Some((session_time + Duration::seconds(300)).to_rfc3339()),
                duration_seconds: 300,
                start_percentage: Some(0.0),
                end_percentage: Some(10.0),
            })
            .unwrap();
    }

    // Call get_reading_activity with week period, day granularity
    let activity = repository.get_reading_activity("week", "day", None).unwrap();

    // Assert exactly 7 ActivityPoints (one per day for a week)
    assert_eq!(activity.len(), 7);
    // Assert exactly 3 of them have minutes > 0
    let on_days: Vec<&ActivityPoint> = activity.iter().filter(|p| p.minutes > 0).collect();
    assert_eq!(on_days.len(), 3);
    // Each non-zero point should have exactly 5 minutes
    for point in &on_days {
        assert_eq!(point.minutes, 5);
    }

    let _ = fs::remove_file(db_path);
}

fn insert_test_book(repository: &LibraryRepository, id: &str) {
    let now = Utc::now().to_rfc3339();
    repository
        .upsert_book(BookDto {
            id: id.to_string(),
            title: "Sync Test".to_string(),
            author: "Tester".to_string(),
            file_path: format!("C:/library/{}.epub", id),
            format: "epub".to_string(),
            sync_status: "local".to_string(),
            current_page: 0,
            total_pages: 100,
            created_at: now.clone(),
            updated_at: now,
            genre: None,
            language: None,
            publication_date: None,
        })
        .unwrap();
}

#[test]
fn migration_0014_adds_reading_sessions_sync_columns() {
    let db_path = temp_db_path();
    let connection = open_and_migrate(&db_path).unwrap();
    let repository = LibraryRepository::new(connection);

    let columns: Vec<String> = repository
        .connection()
        .prepare("PRAGMA table_info(reading_sessions)")
        .unwrap()
        .query_map([], |row| row.get(1))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    assert!(columns.iter().any(|c| c == "user_id"));
    assert!(columns.iter().any(|c| c == "date"));
    assert!(columns.iter().any(|c| c == "updated_at_epoch_millis"));

    let _ = fs::remove_file(db_path);
}

#[test]
fn save_reading_session_returns_deterministic_id_and_dto_roundtrip() {
    let db_path = temp_db_path();
    let connection = open_and_migrate(&db_path).unwrap();
    let repository = LibraryRepository::new(connection);
    insert_test_book(&repository, "book-id-deterministic");

    let input = ReadingSessionInput {
        user_id: "u1".to_string(),
        book_id: "book-id-deterministic".to_string(),
        started_at: "2026-08-13T10:00:00Z".to_string(),
        ended_at: Some("2026-08-13T10:05:00Z".to_string()),
        duration_seconds: 300,
        start_percentage: Some(0.0),
        end_percentage: Some(5.0),
    };

    let saved = repository.save_reading_session(input).unwrap();

    // Independent known-good vector: sha256("u1|book-id-deterministic|1786615200000") hex[..32].
    assert_eq!(saved.id, "sess_32acb535647f5ecd3a4ce86be6b5dcf9");
    assert_eq!(saved.duration_minutes, 5);
    assert_eq!(saved.date, "2026-08-13T00:00:00+00:00");
    assert!(saved.updated_at_epoch_millis > 0);

    // Re-save same triple -> OR REPLACE -> exactly one row.
    let saved2 = repository
        .save_reading_session(ReadingSessionInput {
            user_id: "u1".to_string(),
            book_id: "book-id-deterministic".to_string(),
            started_at: "2026-08-13T10:00:00Z".to_string(),
            ended_at: Some("2026-08-13T10:06:00Z".to_string()),
            duration_seconds: 360,
            start_percentage: Some(0.0),
            end_percentage: Some(6.0),
        })
        .unwrap();
    assert_eq!(saved2.id, saved.id);

    let count: i64 = repository
        .connection()
        .query_row(
            "SELECT COUNT(*) FROM reading_sessions WHERE id = ?1",
            rusqlite::params![saved.id],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(count, 1);

    let _ = fs::remove_file(db_path);
}

#[test]
fn reading_streak_counts_legacy_and_user_scoped_rows_union() {
    use chrono::Duration;

    let db_path = temp_db_path();
    let connection = open_and_migrate(&db_path).unwrap();
    let repository = LibraryRepository::new(connection);
    insert_test_book(&repository, "book-streak-union");

    let today = Utc::now().date_naive().and_hms_opt(10, 0, 0).unwrap().and_utc();
    let yesterday = (today - Duration::days(1)).to_rfc3339();
    let today_rfc = today.to_rfc3339();

    // Legacy ('' user) session yesterday — still counted for any user.
    repository
        .save_reading_session(ReadingSessionInput {
            user_id: "".to_string(),
            book_id: "book-streak-union".to_string(),
            started_at: yesterday.clone(),
            ended_at: Some(
                (chrono::DateTime::parse_from_rfc3339(&yesterday).unwrap()
                    + Duration::seconds(300))
                .to_rfc3339(),
            ),
            duration_seconds: 300,
            start_percentage: Some(0.0),
            end_percentage: Some(10.0),
        })
        .unwrap();

    // User-scoped session today.
    repository
        .save_reading_session(ReadingSessionInput {
            user_id: "u1".to_string(),
            book_id: "book-streak-union".to_string(),
            started_at: today_rfc.clone(),
            ended_at: Some(
                (chrono::DateTime::parse_from_rfc3339(&today_rfc).unwrap()
                    + Duration::seconds(300))
                .to_rfc3339(),
            ),
            duration_seconds: 300,
            start_percentage: Some(0.0),
            end_percentage: Some(10.0),
        })
        .unwrap();

    // u1: today (u1) + yesterday (legacy '') -> 2-day streak.
    let streak = repository.get_reading_streak(None, "u1").unwrap();
    assert_eq!(streak, 2);

    let _ = fs::remove_file(db_path);
}

#[test]
fn upsert_remote_reading_sessions_merges_through_facade() {
    let db_path = temp_db_path();
    let connection = open_and_migrate(&db_path).unwrap();
    let repository = LibraryRepository::new(connection);
    insert_test_book(&repository, "book-remote-merge");

    let rows = vec![RemoteReadingSessionRow {
        id: "sess_remote_1".to_string(),
        user_id: "u-remote".to_string(),
        book_id: "book-remote-merge".to_string(),
        started_at: "2026-08-13T09:00:00Z".to_string(),
        duration_minutes: 12,
        date: "2026-08-13T00:00:00+00:00".to_string(),
        updated_at_epoch_millis: 1786615200000,
        start_percentage: Some(0.0),
        end_percentage: Some(25.0),
    }];

    let applied = repository.upsert_remote_reading_sessions(&rows).unwrap();
    assert_eq!(applied, 1);

    let (seconds, ended_at, user_id): (i64, Option<String>, String) = repository
        .connection()
        .query_row(
            "SELECT duration_seconds, ended_at, user_id FROM reading_sessions WHERE id = 'sess_remote_1'",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .unwrap();
    assert_eq!(seconds, 12 * 60);
    assert!(ended_at.is_none());
    assert_eq!(user_id, "u-remote");

    // Non-local book is FK-guarded out.
    let skipped = vec![RemoteReadingSessionRow {
        id: "sess_remote_2".to_string(),
        user_id: "u-remote".to_string(),
        book_id: "book-not-installed".to_string(),
        started_at: "2026-08-13T09:30:00Z".to_string(),
        duration_minutes: 5,
        date: "2026-08-13T00:00:00+00:00".to_string(),
        updated_at_epoch_millis: 1786615200001,
        start_percentage: None,
        end_percentage: None,
    }];
    let applied = repository.upsert_remote_reading_sessions(&skipped).unwrap();
    assert_eq!(applied, 0);

    let _ = fs::remove_file(db_path);
}

#[test]
fn upsert_remote_highlights_merges_through_facade_and_summary_round_trips() {
    let db_path = temp_db_path();
    let connection = open_and_migrate(&db_path).unwrap();
    let repository = LibraryRepository::new(connection);
    insert_test_book(&repository, "book-highlight-merge");

    // Valid EPUB highlight + FK guard now creates stub (fix for Odisea DHR-3) — unknown book also applied
    let valid = RemoteHighlightRow {
        id: "hl_remote_1".to_string(),
        user_id: "u-remote".to_string(),
        book_id: "book-highlight-merge".to_string(),
        cfi_range: Some("epubcfi(/6/2)".to_string()),
        text_content: "highlight text".to_string(),
        note: Some("a note".to_string()),
        color: "#FACC15".to_string(),
        page: Some(1),
        updated_at_epoch_millis: 1_700_000_000_000,
        deleted_at_epoch_millis: None,
    };
    let unknown = RemoteHighlightRow {
        id: "hl_remote_2".to_string(),
        user_id: "u-remote".to_string(),
        book_id: "book-not-installed".to_string(),
        cfi_range: Some("epubcfi(/6/2)".to_string()),
        text_content: "orphan".to_string(),
        note: None,
        color: "#FACC15".to_string(),
        page: Some(1),
        updated_at_epoch_millis: 1_700_000_000_001,
        deleted_at_epoch_millis: None,
    };

    let summary = repository.upsert_remote_highlights(&[valid, unknown]).unwrap();
    assert_eq!(summary.applied, 2);
    assert_eq!(summary.skipped_unknown_book, 0);
    assert_eq!(summary.skipped_invalid, 0);

    // Serde camelCase contract
    let json = serde_json::to_value(&summary).unwrap();
    assert!(json.get("applied").is_some());
    assert!(json.get("skippedUnknownBook").is_some());
    assert!(json.get("skippedInvalid").is_some());

    let (text, color, cfi, updated_at): (String, String, Option<String>, String) = repository
        .connection()
        .query_row(
            "SELECT text, color, cfi, updated_at FROM highlights WHERE id = 'hl_remote_1'",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
        )
        .unwrap();
    assert_eq!(text, "highlight text");
    assert_eq!(color, "#FACC15");
    assert_eq!(cfi.as_deref(), Some("epubcfi(/6/2)"));
    let stored_epoch =
        chrono::DateTime::parse_from_rfc3339(&updated_at).unwrap().timestamp_millis();
    assert_eq!(stored_epoch, 1_700_000_000_000);

    let _ = fs::remove_file(db_path);
}

#[test]
fn upsert_remote_highlights_summary_serde_contract() {
    let summary = nexo_desktop::models::UpsertRemoteSummary {
        applied: 2,
        skipped_unknown_book: 1,
        skipped_invalid: 3,
    };
    let json = serde_json::to_value(&summary).unwrap();
    assert_eq!(json.get("applied").unwrap().as_i64().unwrap(), 2);
    assert_eq!(json.get("skippedUnknownBook").unwrap().as_i64().unwrap(), 1);
    assert_eq!(json.get("skippedInvalid").unwrap().as_i64().unwrap(), 3);

    // Remote row camelCase contract
    let row = RemoteHighlightRow {
        id: "hl-1".to_string(),
        user_id: "u1".to_string(),
        book_id: "b1".to_string(),
        cfi_range: Some("epubcfi(/6/1)".to_string()),
        text_content: "x".to_string(),
        note: None,
        color: "#FACC15".to_string(),
        page: Some(1),
        updated_at_epoch_millis: 1000,
        deleted_at_epoch_millis: Some(2000),
    };
    let row_json = serde_json::to_value(&row).unwrap();
    assert!(row_json.get("cfiRange").is_some());
    assert!(row_json.get("textContent").is_some());
    assert!(row_json.get("updatedAtEpochMillis").is_some());
    assert!(row_json.get("deletedAtEpochMillis").is_some());
}
