use chrono::DateTime;
use rusqlite::{params, OptionalExtension};

use crate::error::AppResult;
use crate::models::ReadingStatsSummaryDto;

use super::LibraryRepository;

/// Rows of one book closer than this (end of one to start of the next) are one
/// sitting. Android stores a row per minute of reading (`ReadingSessionRecorder`
/// flushes every 60 s), so counting rows reported minutes as "sessions".
pub(super) const SESSION_MERGE_GAP_SECONDS: i64 = 5 * 60;

pub(super) fn recompute_reading_stats_from_sessions(
    repo: &LibraryRepository,
    book_id: Option<&str>,
    user_id: &str,
) -> AppResult<ReadingStatsSummaryDto> {
    summarize_reading_sessions(repo, user_id, book_id, None)
}

/// Totals over the user's session rows, optionally for one book and/or a
/// `started_at` range (RFC3339 bounds, inclusive).
///
/// An empty owner is nobody's: rows with `user_id = ''` are legacy or
/// anonymous and never match, mirroring `get_today_minutes`.
pub(super) fn summarize_reading_sessions(
    repo: &LibraryRepository,
    user_id: &str,
    book_id: Option<&str>,
    range: Option<(&str, &str)>,
) -> AppResult<ReadingStatsSummaryDto> {
    let (from, to) = match range {
        Some((from, to)) => (Some(from), Some(to)),
        None => (None, None),
    };
    const FILTER: &str = "s.user_id = ?1
          AND (?2 IS NULL OR s.book_id = ?2)
          AND (?3 IS NULL OR s.started_at BETWEEN ?3 AND ?4)";

    let mut rows_stmt = repo.connection.prepare(&format!(
        "SELECT s.book_id, s.started_at, s.duration_seconds
         FROM reading_sessions s
         WHERE {FILTER}
         ORDER BY s.book_id, s.started_at"
    ))?;
    let rows = rows_stmt
        .query_map(params![user_id, book_id, from, to], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?, row.get::<_, i64>(2)?))
        })?
        .collect::<Result<Vec<_>, _>>()?;

    let total_seconds: i64 = rows.iter().map(|(_, _, seconds)| (*seconds).max(0)).sum();
    let total_sessions = count_sittings(&rows, SESSION_MERGE_GAP_SECONDS);

    // A book's progress is where it stands now (`reading_progress`), else the
    // furthest point a session recorded, else a PDF's page fraction. Android
    // rows carry no percentages, so reading them alone averaged to 0%. A book
    // with no known progress is left out of the average rather than counted
    // as 0.
    let mut progress_stmt = repo.connection.prepare(&format!(
        "SELECT COALESCE(
                    (SELECT rp.percentage FROM reading_progress rp
                     WHERE rp.book_id = x.book_id AND rp.deleted_at IS NULL
                     ORDER BY rp.updated_at DESC LIMIT 1),
                    x.session_progress,
                    (SELECT CASE WHEN b.total_pages > 0 AND b.current_page > 0
                                 THEN MIN(100.0, b.current_page * 100.0 / b.total_pages) END
                     FROM books b WHERE b.id = x.book_id)
                ) AS progress,
                (SELECT brs.status FROM book_reading_status brs WHERE brs.book_id = x.book_id)
         FROM (
            SELECT s.book_id, MAX(COALESCE(s.end_percentage, s.start_percentage)) AS session_progress
            FROM reading_sessions s
            WHERE {FILTER}
            GROUP BY s.book_id
         ) x"
    ))?;
    let books = progress_stmt
        .query_map(params![user_id, book_id, from, to], |row| {
            Ok((row.get::<_, Option<f64>>(0)?, row.get::<_, Option<String>>(1)?))
        })?
        .collect::<Result<Vec<_>, _>>()?;

    let books_started = books.len() as i64;
    let books_completed = books
        .iter()
        .filter(|(progress, status)| {
            progress.is_some_and(|p| p >= 100.0) || status.as_deref() == Some("completed")
        })
        .count() as i64;
    let known: Vec<f64> = books.iter().filter_map(|(progress, _)| *progress).collect();
    let avg_progress_percentage =
        if known.is_empty() { 0.0 } else { known.iter().sum::<f64>() / known.len() as f64 };

    Ok(ReadingStatsSummaryDto {
        total_minutes_read: ((total_seconds as f64) / 60.0).round() as i64,
        total_sessions,
        books_started,
        books_completed,
        avg_progress_percentage,
    })
}

/// Number of sittings in rows ordered by (book, start): a row opens a new one
/// when it is for another book or starts more than `gap_seconds` after the
/// previous row ended. A start that does not parse always opens a new one.
fn count_sittings(rows: &[(String, String, i64)], gap_seconds: i64) -> i64 {
    let mut sittings = 0;
    let mut previous: Option<(&str, Option<i64>)> = None;
    for (book, started_at, seconds) in rows {
        let start = DateTime::parse_from_rfc3339(started_at).ok().map(|t| t.timestamp());
        let continues = match (previous, start) {
            (Some((prev_book, Some(prev_end))), Some(start)) => {
                prev_book == book && start - prev_end <= gap_seconds
            }
            _ => false,
        };
        if !continues {
            sittings += 1;
        }
        let end = start.map(|t| t + (*seconds).max(0));
        let carried_end = match (previous, end) {
            (Some((prev_book, Some(prev_end))), Some(end)) if continues && prev_book == book => {
                Some(prev_end.max(end))
            }
            _ => end,
        };
        previous = Some((book.as_str(), carried_end));
    }
    sittings
}

pub(super) fn reading_stats_drift_over_threshold(
    repo: &LibraryRepository,
    book_id: Option<&str>,
    event_avg_progress: f64,
) -> AppResult<bool> {
    let baseline_progress: f64 = if let Some(id) = book_id {
        repo.connection
            .query_row(
                "SELECT COALESCE(percentage, 0.0)
             FROM reading_progress
             WHERE book_id = ?1 AND deleted_at IS NULL
             ORDER BY updated_at DESC
             LIMIT 1",
                params![id],
                |row| row.get(0),
            )
            .optional()?
            .unwrap_or(0.0)
    } else {
        repo.connection.query_row(
            "SELECT COALESCE(AVG(percentage), 0.0)
             FROM reading_progress
             WHERE deleted_at IS NULL",
            [],
            |row| row.get(0),
        )?
    };

    Ok((event_avg_progress - baseline_progress).abs() > 1.0)
}
#[cfg(test)]
mod tests {
    use crate::repository::tests::{insert_book, new_repository};
    use crate::repository::LibraryRepository;
    use chrono::Utc;
    use rusqlite::params;
    use uuid::Uuid;

    /// Seed one session row directly: the raw INSERT bypasses
    /// `save_reading_session` so historical timestamps are accepted.
    fn seed_session(
        repository: &LibraryRepository,
        user_id: &str,
        book_id: &str,
        duration_seconds: i64,
        end_percentage: f64,
    ) {
        seed_session_at(
            repository,
            user_id,
            book_id,
            &Utc::now().to_rfc3339(),
            duration_seconds,
            Some(end_percentage),
        );
    }

    fn seed_session_at(
        repository: &LibraryRepository,
        user_id: &str,
        book_id: &str,
        started_at: &str,
        duration_seconds: i64,
        end_percentage: Option<f64>,
    ) {
        repository
            .connection
            .execute(
                "INSERT INTO reading_sessions (id, book_id, user_id, started_at, ended_at, duration_seconds, start_percentage, end_percentage, created_at)
                 VALUES (?1, ?2, ?3, ?4, ?4, ?5, ?6, ?6, ?4)",
                params![
                    Uuid::new_v4().to_string(),
                    book_id,
                    user_id,
                    started_at,
                    duration_seconds,
                    end_percentage,
                ],
            )
            .unwrap();
    }

    fn seed_progress(repository: &LibraryRepository, book_id: &str, percentage: f64) {
        repository
            .connection
            .execute(
                "INSERT INTO reading_progress (id, book_id, cfi_location, percentage, updated_at, deleted_at, version)
                 VALUES (?1, ?2, 'loc', ?3, ?4, NULL, 1)",
                params![Uuid::new_v4().to_string(), book_id, percentage, Utc::now().to_rfc3339()],
            )
            .unwrap();
    }

    /// Android's recorder: one row per minute, no percentages.
    fn seed_minute_rows(
        repository: &LibraryRepository,
        user_id: &str,
        book_id: &str,
        first: chrono::DateTime<Utc>,
        minutes: i64,
    ) {
        for minute in 0..minutes {
            let started = (first + chrono::Duration::seconds(minute * 60)).to_rfc3339();
            seed_session_at(repository, user_id, book_id, &started, 60, None);
        }
    }

    #[test]
    fn per_minute_rows_count_as_one_sitting_each_time_the_reader_comes_back() {
        let repository = new_repository();
        insert_book(&repository, "book-android", "C:/library/a.epub");
        let morning = Utc::now() - chrono::Duration::hours(10);
        // 14 minutes in the morning, 5 more after a 4-minute pause (same
        // sitting), then 20 in the evening.
        seed_minute_rows(&repository, "u", "book-android", morning, 14);
        let after_pause = morning + chrono::Duration::minutes(14 + 4);
        seed_minute_rows(&repository, "u", "book-android", after_pause, 5);
        let evening = morning + chrono::Duration::hours(8);
        seed_minute_rows(&repository, "u", "book-android", evening, 20);

        let stats = repository.get_reading_stats(None, "u").unwrap();
        assert_eq!(stats.total_minutes_read, 39);
        assert_eq!(stats.total_sessions, 2);
    }

    #[test]
    fn sittings_on_different_books_are_counted_apart() {
        let repository = new_repository();
        insert_book(&repository, "book-a", "C:/library/a.epub");
        insert_book(&repository, "book-b", "C:/library/b.epub");
        let start = Utc::now() - chrono::Duration::hours(1);
        seed_minute_rows(&repository, "u", "book-a", start, 3);
        seed_minute_rows(&repository, "u", "book-b", start + chrono::Duration::minutes(3), 3);

        let stats = repository.get_reading_stats(None, "u").unwrap();
        assert_eq!(stats.total_sessions, 2);
        assert_eq!(stats.books_started, 2);
    }

    #[test]
    fn progress_comes_from_where_the_book_stands_when_sessions_carry_none() {
        let repository = new_repository();
        insert_book(&repository, "book-a", "C:/library/a.epub");
        insert_book(&repository, "book-b", "C:/library/b.epub");
        insert_book(&repository, "book-unknown", "C:/library/c.epub");
        let start = Utc::now() - chrono::Duration::hours(1);
        seed_minute_rows(&repository, "u", "book-a", start, 2);
        seed_minute_rows(&repository, "u", "book-b", start, 2);
        seed_minute_rows(&repository, "u", "book-unknown", start, 2);
        seed_progress(&repository, "book-a", 37.0);
        seed_progress(&repository, "book-b", 93.0);

        let from = (Utc::now() - chrono::Duration::days(1)).to_rfc3339();
        let to = (Utc::now() + chrono::Duration::days(1)).to_rfc3339();
        let range = repository.get_reading_stats_for_range(&from, &to, None, "u").unwrap();
        // The book with no known progress is left out, not averaged as 0%.
        assert!((range.avg_progress_percentage - 65.0).abs() < 1e-9);
        assert_eq!(range.books_started, 3);
        assert_eq!(range.books_completed, 0);

        let one = repository.get_reading_stats_for_range(&from, &to, Some("book-b"), "u").unwrap();
        assert!((one.avg_progress_percentage - 93.0).abs() < 1e-9);
    }

    #[test]
    fn a_book_marked_completed_counts_as_completed() {
        let repository = new_repository();
        insert_book(&repository, "book-done", "C:/library/d.epub");
        seed_minute_rows(&repository, "u", "book-done", Utc::now() - chrono::Duration::hours(1), 2);
        repository
            .connection
            .execute(
                "INSERT INTO book_reading_status (book_id, status) VALUES ('book-done', 'completed')",
                [],
            )
            .unwrap();

        let stats = repository.get_reading_stats(None, "u").unwrap();
        assert_eq!(stats.books_completed, 1);
    }

    #[test]
    fn count_sittings_treats_an_unparseable_start_as_its_own_sitting() {
        let rows = vec![
            ("b".to_string(), "2026-11-24T02:17:42.935+00:00".to_string(), 60),
            ("b".to_string(), "not a date".to_string(), 60),
            ("b".to_string(), "2026-11-24T02:18:43.022+00:00".to_string(), 60),
        ];
        assert_eq!(super::count_sittings(&rows, super::SESSION_MERGE_GAP_SECONDS), 3);
    }

    #[test]
    fn reading_stats_are_scoped_to_the_requesting_user() {
        let repository = new_repository();
        insert_book(&repository, "book-alice", "C:/library/book-alice.epub");
        insert_book(&repository, "book-bob", "C:/library/book-bob.epub");

        // alice: two sittings hours apart, 600s + 600s = 20 min, one book finished.
        let earlier = (Utc::now() - chrono::Duration::hours(3)).to_rfc3339();
        seed_session_at(&repository, "alice", "book-alice", &earlier, 600, Some(50.0));
        seed_session(&repository, "alice", "book-alice", 600, 100.0);

        // bob: one session on a different book, 1800s = 30 min, unfinished.
        seed_session(&repository, "bob", "book-bob", 1800, 40.0);

        // A legacy/anonymous row: nobody's, counted by neither account.
        seed_session(&repository, "", "book-alice", 3600, 100.0);

        let alice = repository.get_reading_stats(None, "alice").unwrap();
        assert_eq!(alice.total_minutes_read, 20);
        assert_eq!(alice.total_sessions, 2);
        assert_eq!(alice.books_started, 1);
        assert_eq!(alice.books_completed, 1);
        assert!((alice.avg_progress_percentage - 100.0).abs() < f64::EPSILON);

        let bob = repository.get_reading_stats(None, "bob").unwrap();
        assert_eq!(bob.total_minutes_read, 30);
        assert_eq!(bob.total_sessions, 1);
        assert_eq!(bob.books_started, 1);
        assert_eq!(bob.books_completed, 0);
        assert!((bob.avg_progress_percentage - 40.0).abs() < f64::EPSILON);

        // The empty owner matches neither account, and the empty owner itself
        // sees nothing.
        let nobody = repository.get_reading_stats(None, "").unwrap();
        assert_eq!(nobody.total_minutes_read, 0);
        assert_eq!(nobody.total_sessions, 0);
        assert_eq!(nobody.books_started, 0);
        assert_eq!(nobody.books_completed, 0);
        assert_eq!(nobody.avg_progress_percentage, 0.0);
    }

    #[test]
    fn reading_stats_for_range_are_scoped_to_the_requesting_user() {
        let repository = new_repository();
        insert_book(&repository, "book-alice", "C:/library/book-alice.epub");
        insert_book(&repository, "book-bob", "C:/library/book-bob.epub");

        seed_session(&repository, "alice", "book-alice", 600, 100.0);
        seed_session(&repository, "bob", "book-bob", 1200, 100.0);
        seed_session(&repository, "", "book-alice", 1800, 100.0);

        let now = Utc::now();
        let from = (now - chrono::Duration::days(1)).to_rfc3339();
        let to = (now + chrono::Duration::days(1)).to_rfc3339();

        let alice = repository.get_reading_stats_for_range(&from, &to, None, "alice").unwrap();
        assert_eq!(alice.total_minutes_read, 10);
        assert_eq!(alice.total_sessions, 1);

        let bob = repository.get_reading_stats_for_range(&from, &to, None, "bob").unwrap();
        assert_eq!(bob.total_minutes_read, 20);
        assert_eq!(bob.total_sessions, 1);

        let nobody = repository.get_reading_stats_for_range(&from, &to, None, "").unwrap();
        assert_eq!(nobody.total_minutes_read, 0);
        assert_eq!(nobody.total_sessions, 0);
    }

    #[test]
    fn stats_drift_threshold_respects_one_percent_tolerance() {
        let repository = new_repository();
        insert_book(&repository, "book-stats", "C:/library/book-stats.epub");

        repository
            .connection
            .execute(
                "INSERT INTO reading_progress (id, book_id, cfi_location, percentage, updated_at, deleted_at, version)
                 VALUES (?1, ?2, 'loc', ?3, ?4, NULL, 1)",
                params![Uuid::new_v4().to_string(), "book-stats", 10.0_f64, Utc::now().to_rfc3339()],
            )
            .unwrap();

        assert!(!repository.reading_stats_drift_over_threshold(Some("book-stats"), 10.5).unwrap());
        assert!(repository.reading_stats_drift_over_threshold(Some("book-stats"), 12.5).unwrap());
    }
}
