//! Single canonical bookId-to-filename algorithm (FR-06), shared by the
//! download temp path, every on-disk artifact derived from a catalog book id
//! (`books/` and `covers/`), and mirrored byte-for-byte by the Android and
//! TypeScript implementations so `INV-1` (same book id -> same name) holds.
//!
//! The canonical form is NFKC-normalize, lowercase, keep `[a-z0-9_-]`, bound to
//! [`MAX_BOOK_ID_CHARS`], fall back to [`FALLBACK_BOOK_ID`] for an
//! empty/fully-filtered input, then guard reserved Windows device names
//! (`con` -> `con_`). Lowercase is applied BEFORE the reserved-name guard, so
//! the guard compares lowercase stems (`CON` and `con` both map to `con_`).
//! Dropping (never replacing) illegal characters cannot inject a character that
//! collides with a genuine id character, and lowercase closes the NTFS
//! case-insensitive collision class.
//!
//! A catalog id such as `gutendex:2701` is not a valid Windows path segment:
//! `:` starts an NTFS alternate data stream, so `books/gutendex:2701.epub`
//! writes a stream and the following rename fails with `ERROR_INVALID_PARAMETER`
//! (os error 87). Every filename derived from a book id therefore goes through
//! [`canonical_stem`]. The algorithm is deterministic and idempotent:
//! re-canonicalizing an already-canonical stem is a no-op.
//!
//! The shared fixture contract lives in
//! `packages/drive-filename-fixtures/fixtures.json` and is consumed by the Rust,
//! Kotlin, and TypeScript test runners so a divergence fails a test instead of
//! staying latent.

use unicode_normalization::UnicodeNormalization;

/// Windows device names are rejected even when an extension follows the stem
/// (`con.epub` is not a legal path), so a stem matching one must be changed.
const RESERVED_DEVICE_NAMES: [&str; 22] = [
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
    "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

const MAX_EXT_CHARS: usize = 5;
const DEFAULT_EXT: &str = "epub";

/// Catalog ids are bounded so the composed Windows filename stays short.
pub(crate) const MAX_BOOK_ID_CHARS: usize = 120;
pub(crate) const FALLBACK_BOOK_ID: &str = "book";

/// NFKC-normalizes, lowercases, and keeps only `[a-z0-9_-]`, bounded in length.
/// A fully filtered-out or empty input falls back, so a segment can never be
/// empty or contain a separator. This is the one primitive shared by the book-id
/// algorithm and the generic transfer-id path in `commands::download`.
pub(crate) fn normalize_segment(raw: &str, max_chars: usize, fallback: &str) -> String {
    let filtered: String = raw
        .nfkc()
        .flat_map(char::to_lowercase)
        .filter(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == '-' || *c == '_')
        .take(max_chars)
        .collect();
    if filtered.is_empty() {
        fallback.to_string()
    } else {
        filtered
    }
}

/// Deterministic, Windows-safe filename stem for a book id. NFKC + lowercase
/// normalization plus character filtering drops `<>:"/\|?*`, control characters,
/// any trailing dot or space, and any remaining non-`[a-z0-9_-]` rune; a
/// reserved device name is suffixed so `con` never becomes the illegal
/// `con.epub`. The same id always yields the same stem (idempotent), so a retry
/// rewrites one path instead of accumulating files.
pub(crate) fn canonical_stem(raw: &str) -> String {
    let stem = normalize_segment(raw, MAX_BOOK_ID_CHARS, FALLBACK_BOOK_ID);
    if is_reserved_device_name(&stem) {
        format!("{stem}_")
    } else {
        stem
    }
}

/// Canonical Drive object name: the canonical stem plus the canonical extension
/// (`gutendex:2701`, `epub` -> `gutendex2701.epub`). This is the FR-06 contract
/// consumed by the shared fixtures; the extension only changes the suffix, so
/// the fixture list pins the stems.
pub fn canonical_drive_object_name(book_id: &str, extension: Option<&str>) -> String {
    let stem = canonical_stem(book_id);
    let ext = canonical_extension(extension);
    format!("{stem}.{ext}")
}

fn is_reserved_device_name(stem: &str) -> bool {
    let upper = stem.to_ascii_uppercase();
    RESERVED_DEVICE_NAMES.iter().any(|reserved| upper == *reserved)
}

/// `format` canonicalized to `[a-z0-9]{1,5}`, defaulting to `epub`. Anything
/// longer than five characters (or empty) is not a plausible extension and is
/// replaced.
pub(crate) fn canonical_extension(format: Option<&str>) -> String {
    let filtered: String = format
        .unwrap_or(DEFAULT_EXT)
        .to_ascii_lowercase()
        .chars()
        .filter(|c| c.is_ascii_lowercase() || c.is_ascii_digit())
        .collect();
    if filtered.is_empty() || filtered.len() > MAX_EXT_CHARS {
        DEFAULT_EXT.to_string()
    } else {
        filtered
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_stem_drops_every_windows_illegal_character_and_lowercases() {
        // The real catalog id that broke the download: the colon is dropped.
        assert_eq!(canonical_stem("gutendex:2701"), "gutendex2701");
        assert_eq!(canonical_stem("a<b>c:d\"e/f\\g|h?i*j"), "abcdefghij");
        // Control characters (including tab/newline) are filtered out too.
        assert_eq!(canonical_stem("line\nbreak\ttab"), "linebreaktab");
        // Lowercase normalization is the second canonical rule.
        assert_eq!(canonical_stem("openlibrary:/works/OL45804W"), "openlibraryworksol45804w");
        assert_eq!(canonical_stem("GuTeNdEx:2701"), "gutendex2701");
    }

    #[test]
    fn canonical_stem_applies_nfkc_before_filtering() {
        // Fullwidth compatibility characters normalize to their ASCII forms
        // instead of being dropped as non-alphanumeric.
        assert_eq!(canonical_stem("Ｈｅｌｌｏ:123"), "hello123");
    }

    #[test]
    fn canonical_stem_has_no_trailing_dot_or_space() {
        assert_eq!(canonical_stem("Moby Dick. "), "mobydick");
        assert_eq!(canonical_stem("trailing... "), "trailing");
        assert_eq!(canonical_stem("   "), "book");
    }

    #[test]
    fn canonical_stem_guards_reserved_device_names_even_with_an_extension() {
        for name in ["CON", "con", "PrN", "aux", "nul", "COM1", "com9", "LPT1", "lpt9"] {
            let expected = format!("{}_", name.to_ascii_lowercase());
            let stem = canonical_stem(name);
            assert_eq!(stem, expected, "reserved name {name}");
            // The composed filename with an extension is legal as well.
            assert_eq!(format!("{stem}.epub"), format!("{expected}.epub"));
        }
    }

    #[test]
    fn canonical_stem_falls_back_and_is_deterministic() {
        // Empty result guard: a fully filtered id cannot produce an empty stem.
        assert_eq!(canonical_stem(":::"), "book");
        assert_eq!(canonical_stem(""), "book");
        assert_eq!(canonical_stem("📚book🎉"), "book");

        // Determinism: the same id always maps to the same stem.
        let first = canonical_stem("gutendex:2701");
        let second = canonical_stem("gutendex:2701");
        assert_eq!(first, second);

        // Idempotence: re-canonicalizing the output is a no-op (reserved guard included).
        assert_eq!(canonical_stem(&first), first);
        assert_eq!(canonical_stem("CON"), "con_");
        assert_eq!(canonical_stem("con_"), "con_");
    }

    #[test]
    fn canonical_drive_object_name_composes_stem_and_extension() {
        assert_eq!(canonical_drive_object_name("gutendex:2701", Some("EPUB")), "gutendex2701.epub");
        assert_eq!(
            canonical_drive_object_name("openlibrary:/works/OL45804W", None),
            "openlibraryworksol45804w.epub"
        );
        assert_eq!(canonical_drive_object_name("", Some(".pdf")), "book.pdf");
    }

    /// The shared contract: this test and the Kotlin/TypeScript runners read the
    /// SAME `packages/drive-filename-fixtures/fixtures.json`. If any platform
    /// drifts, this test (or its sibling) fails on the platform that drifted.
    #[test]
    fn canonical_stem_matches_the_shared_fixtures_contract() {
        let raw = include_str!("../../../packages/drive-filename-fixtures/fixtures.json");
        let fixtures: Vec<serde_json::Value> =
            serde_json::from_str(raw).expect("fixtures.json must parse");

        assert!(fixtures.len() >= 14, "the contract keeps at least 14 fixtures");

        for entry in fixtures {
            let input = entry["input"].as_str().expect("fixture input is a string");
            let expected =
                entry["expectedStem"].as_str().expect("fixture expectedStem is a string");

            assert_eq!(
                canonical_stem(input),
                expected,
                "canonical stem for fixture input {input:?}"
            );
            // Every fixture output must itself be canonical (idempotence).
            assert_eq!(
                canonical_stem(expected),
                expected,
                "re-canonicalizing {expected:?} must be a no-op"
            );
        }
    }
}
