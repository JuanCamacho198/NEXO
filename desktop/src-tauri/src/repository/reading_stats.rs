use rusqlite::{params, OptionalExtension};

use crate::error::AppResult;
use crate::models::ReadingStatsSummaryDto;

use super::LibraryRepository;

pub(super) fn recompute_reading_stats_from_sessions(
    repo: &LibraryRepository,
    book_id: Option<&str>,
    user_id: &str,
) -> AppResult<ReadingStatsSummaryDto> {
    // An empty owner is nobody's: rows with `user_id = ''` are legacy or
    // anonymous and must not leak into any account's totals. This mirrors
    // `get_today_minutes`, which already scopes on an exact `user_id`.
    if let Some(id) = book_id {
        let total_seconds: i64 = repo.connection.query_row(
            "SELECT COALESCE(SUM(duration_seconds), 0)
             FROM reading_sessions
             WHERE book_id = ?1 AND user_id = ?2",
            params![id, user_id],
            |row| row.get(0),
        )?;

        let total_sessions: i64 = repo.connection.query_row(
            "SELECT COUNT(*) FROM reading_sessions WHERE book_id = ?1 AND user_id = ?2",
            params![id, user_id],
            |row| row.get(0),
        )?;

        let max_progress: Option<f64> = repo.connection.query_row(
            "SELECT MAX(COALESCE(end_percentage, start_percentage))
             FROM reading_sessions
             WHERE book_id = ?1 AND user_id = ?2",
            params![id, user_id],
            |row| row.get(0),
        )?;

        let avg_progress = max_progress.unwrap_or(0.0);
        return Ok(ReadingStatsSummaryDto {
            total_minutes_read: ((total_seconds as f64) / 60.0).round() as i64,
            total_sessions,
            books_started: if total_sessions > 0 { 1 } else { 0 },
            books_completed: if avg_progress >= 100.0 { 1 } else { 0 },
            avg_progress_percentage: avg_progress,
        });
    }

    let total_seconds: i64 = repo.connection.query_row(
        "SELECT COALESCE(SUM(duration_seconds), 0) FROM reading_sessions WHERE user_id = ?1",
        params![user_id],
        |row| row.get(0),
    )?;
    let total_sessions: i64 = repo.connection.query_row(
        "SELECT COUNT(*) FROM reading_sessions WHERE user_id = ?1",
        params![user_id],
        |row| row.get(0),
    )?;
    let books_started: i64 = repo.connection.query_row(
        "SELECT COUNT(DISTINCT book_id) FROM reading_sessions WHERE user_id = ?1",
        params![user_id],
        |row| row.get(0),
    )?;
    let books_completed: i64 = repo.connection.query_row(
        "SELECT COUNT(*)
         FROM (
            SELECT book_id, MAX(COALESCE(end_percentage, start_percentage, 0)) AS max_progress
            FROM reading_sessions
            WHERE user_id = ?1
            GROUP BY book_id
         ) x
         WHERE x.max_progress >= 100.0",
        params![user_id],
        |row| row.get(0),
    )?;
    let avg_progress_percentage: f64 = repo.connection.query_row(
        "SELECT COALESCE(AVG(max_progress), 0.0)
         FROM (
            SELECT MAX(COALESCE(end_percentage, start_percentage, 0.0)) AS max_progress
            FROM reading_sessions
            WHERE user_id = ?1
            GROUP BY book_id
         )",
        params![user_id],
        |row| row.get(0),
    )?;

    Ok(ReadingStatsSummaryDto {
        total_minutes_read: ((total_seconds as f64) / 60.0).round() as i64,
        total_sessions,
        books_started,
        books_completed,
        avg_progress_percentage,
    })
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
        let now = Utc::now().to_rfc3339();
        repository
            .connection
            .execute(
                "INSERT INTO reading_sessions (id, book_id, user_id, started_at, ended_at, duration_seconds, start_percentage, end_percentage, created_at)
                 VALUES (?1, ?2, ?3, ?4, ?4, ?5, 0.0, ?6, ?4)",
                params![
                    Uuid::new_v4().to_string(),
                    book_id,
                    user_id,
                    now,
                    duration_seconds,
                    end_percentage,
                ],
            )
            .unwrap();
    }

    #[test]
    fn reading_stats_are_scoped_to_the_requesting_user() {
        let repository = new_repository();
        insert_book(&repository, "book-alice", "C:/library/book-alice.epub");
        insert_book(&repository, "book-bob", "C:/library/book-bob.epub");

        // alice: two sessions, 600s + 600s = 20 min, one book finished.
        seed_session(&repository, "alice", "book-alice", 600, 50.0);
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
