//! Tolerant TEXT reads.
//!
//! SQLite applies no UTF-8 validation on write (`Garbage-In, Garbage-Out`), so a
//! legacy row can hold bytes that are not valid UTF-8. When that happens SQLite
//! does not report the column as TEXT: it reports it as BLOB. `row.get::<_, String>`
//! then fails with `InvalidColumnType` — `Invalid column type Blob at index: 1`
//! — which names neither the column nor the value, and rejects the whole row.
//!
//! That is exactly how a book titled `Prisioneros de la geografía` holding
//! `0xED` where `0xC3 0xAD` belongs made the library fail to load.
//!
//! These helpers read by [`ValueRef`], fall back to bytes, and replace each
//! invalid byte with `U+FFFD` so one bad row cannot take down a query. A caller
//! that needs to *know* the value was damaged uses [`data_health`].

use rusqlite::types::ValueRef;
use rusqlite::Row;

/// Read a TEXT column, tolerating bytes that are not valid UTF-8.
///
/// Invalid bytes become `U+FFFD`. The result is always valid UTF-8, which is
/// what keeps a legacy row from failing every query that touches it.
pub fn text(row: &Row<'_>, idx: usize) -> rusqlite::Result<String> {
    Ok(match row.get_ref(idx)? {
        ValueRef::Text(bytes) => match std::str::from_utf8(bytes) {
            Ok(text) => text.to_string(),
            Err(_) => String::from_utf8_lossy(bytes).into_owned(),
        },
        ValueRef::Blob(bytes) => String::from_utf8_lossy(bytes).into_owned(),
        ValueRef::Null => String::new(),
        ValueRef::Integer(value) => value.to_string(),
        ValueRef::Real(value) => value.to_string(),
    })
}

/// Like [`text`], but `NULL` stays `None` instead of becoming an empty string.
pub fn text_opt(row: &Row<'_>, idx: usize) -> rusqlite::Result<Option<String>> {
    match row.get_ref(idx)? {
        ValueRef::Null => Ok(None),
        _ => Ok(Some(text(row, idx)?)),
    }
}

/// Whether a raw column value is TEXT that SQLite can decode.
///
/// `Ok(true)` is a healthy value, `Ok(false)` is a stored byte sequence that is
/// not valid UTF-8, and `None` means the value is absent or not textual.
pub fn data_health(value: &ValueRef<'_>) -> Option<bool> {
    match value {
        ValueRef::Text(bytes) => Some(std::str::from_utf8(bytes).is_ok()),
        // SQLite reports an undecodable TEXT value as a BLOB, so a BLOB in a
        // column the app writes as TEXT is itself the symptom.
        ValueRef::Blob(_) => Some(false),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;

    /// The live defect, byte for byte: `Prisioneros de la geografía` truncated
    /// to a bare `0xED` at index 25.
    const CORRUPT_TITLE: &[u8] = b"Prisioneros de la geograf\xeda";

    fn connection_with_corrupt_title() -> Connection {
        let connection = Connection::open_in_memory().unwrap();
        connection
            .execute_batch("CREATE TABLE books (id TEXT PRIMARY KEY, title TEXT NOT NULL);")
            .unwrap();
        connection
            .execute(
                "INSERT INTO books (id, title) VALUES (?1, ?2)",
                rusqlite::params!["5b4ba1c1-1b6f-4301-816a-40385be603fa", CORRUPT_TITLE],
            )
            .unwrap();
        connection
    }

    #[test]
    fn row_get_string_fails_on_the_stored_byte_sequence() {
        let connection = connection_with_corrupt_title();
        // The previous behaviour: the whole row is rejected with a message that
        // names only an index.
        let failure = connection
            .query_row("SELECT title FROM books", [], |row| row.get::<_, String>(0))
            .unwrap_err();
        assert!(
            matches!(failure, rusqlite::Error::InvalidColumnType(_, _, _)),
            "row.get::<String> must fail on the stored bytes, got {failure:?}"
        );
    }

    #[test]
    fn text_reads_the_row_and_preserves_what_can_be_decoded() {
        let connection = connection_with_corrupt_title();
        let title = connection
            .query_row("SELECT title FROM books", [], |row| text(row, 0))
            .expect("a damaged row must not fail the query");
        // The readable prefix survives; the stray byte becomes the replacement
        // character rather than aborting the read.
        assert_eq!(title, "Prisioneros de la geograf\u{FFFD}a");
        assert_eq!(title.chars().count(), 27, "the prefix and suffix are intact");
        assert_eq!(title.find('\u{FFFD}'), Some(25), "the damage is at the í");
    }

    #[test]
    fn text_preserves_well_formed_accents_unchanged() {
        let connection = Connection::open_in_memory().unwrap();
        connection.execute_batch("CREATE TABLE books (title TEXT NOT NULL);").unwrap();
        connection
            .execute("INSERT INTO books (title) VALUES (?1)", ["Prisioneros de la geografía"])
            .unwrap();
        let title =
            connection.query_row("SELECT title FROM books", [], |row| text(row, 0)).unwrap();
        assert_eq!(title, "Prisioneros de la geografía");
        assert!(!title.contains('\u{FFFD}'), "valid UTF-8 must pass through untouched");
    }

    #[test]
    fn text_opt_keeps_null_distinct_from_empty() {
        let connection = Connection::open_in_memory().unwrap();
        connection.execute_batch("CREATE TABLE books (title TEXT);").unwrap();
        connection.execute("INSERT INTO books (title) VALUES (NULL)", []).unwrap();
        let title =
            connection.query_row("SELECT title FROM books", [], |row| text_opt(row, 0)).unwrap();
        assert_eq!(title, None, "an absent value must stay absent");
    }

    #[test]
    fn data_health_flags_the_damaged_value_and_clears_a_good_one() {
        let connection = connection_with_corrupt_title();
        let health = connection
            .query_row("SELECT title FROM books", [], |row| Ok(data_health(&row.get_ref(0)?)))
            .unwrap();
        assert_eq!(health, Some(false), "the stored bytes are not valid UTF-8");

        connection.execute("UPDATE books SET title = ?1", ["Prisioneros de la geografía"]).unwrap();
        let health = connection
            .query_row("SELECT title FROM books", [], |row| Ok(data_health(&row.get_ref(0)?)))
            .unwrap();
        assert_eq!(health, Some(true), "a repaired value reports healthy");
    }
}
