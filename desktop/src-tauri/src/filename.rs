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

/// Separator between the human-readable title and the identity-bearing book id
/// in a readable Drive object name (`Title -- <id>.epub`). It cannot appear in a
/// canonical stem (`[a-z0-9_-]` only) and survives Drive/NTFS intact.
pub const READABLE_NAME_SEPARATOR: &str = " -- ";

/// Max characters kept from the sanitized title, so the composed name stays short.
pub const MAX_TITLE_CHARS: usize = 80;

/// Sanitizes a book title into a filename-safe, human-readable segment: replaces
/// the NTFS/Drive reserved characters `<>:"/\|?*` and control characters with a
/// space, collapses whitespace runs, and trims leading/trailing dots and spaces.
/// Not lowercased and keeps Unicode letters — this segment is meant to be read.
/// Returns an empty string when nothing usable remains so the caller can fall
/// back to the id.
pub fn sanitize_title_for_filename(title: &str) -> String {
    let cleaned: String = title
        .nfkc()
        .map(|c| {
            let code = c as u32;
            if code < 0x20 || code == 0x7f || "<>:\"/\\|?*".contains(c) {
                ' '
            } else {
                c
            }
        })
        .collect();
    cleaned
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .trim_matches(|c| c == '.' || c == ' ')
        .chars()
        .take(MAX_TITLE_CHARS)
        .collect()
}

/// Readable Drive object name for a NEW upload:
/// `<sanitized title> -- <id>.<ext>`. The full id is retained after the
/// separator so identity is stable and concurrent same-name uploads cannot
/// collide. An empty title yields the id-only canonical name, so a nameless book
/// never produces a dangling separator. Existing id-only files are never renamed.
pub fn readable_drive_object_name(title: &str, book_id: &str, extension: Option<&str>) -> String {
    let safe_title = sanitize_title_for_filename(title);
    let id_part = canonical_stem(book_id);
    let ext = canonical_extension(extension);
    if safe_title.is_empty() {
        format!("{id_part}.{ext}")
    } else {
        format!("{safe_title}{READABLE_NAME_SEPARATOR}{id_part}.{ext}")
    }
}

/// Recovers the identity-bearing id from a readable object name stem
/// (`Title -- <id>` -> `<id>`). Returns `None` when the stem carries no
/// separator. Splits on the LAST separator so a title containing ` -- ` resolves.
pub fn parse_readable_stem(stem: &str) -> Option<&str> {
    let idx = stem.rfind(READABLE_NAME_SEPARATOR)?;
    let id_part = &stem[idx + READABLE_NAME_SEPARATOR.len()..];
    if id_part.is_empty() {
        None
    } else {
        Some(id_part)
    }
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

    #[test]
    fn sanitize_title_for_filename_replaces_reserved_and_control_characters() {
        assert_eq!(sanitize_title_for_filename("a/b\\c:d*e?f\"g<h>i|j"), "a b c d e f g h i j");
        assert_eq!(sanitize_title_for_filename("  multiple   spaces  "), "multiple spaces");
        assert_eq!(sanitize_title_for_filename("line\nbreak\ttab"), "line break tab");
        assert_eq!(sanitize_title_for_filename("Moby Dick. "), "Moby Dick");
        assert_eq!(sanitize_title_for_filename("...title..."), "title");
        // Unicode letters, case, and accents are preserved: the name is read.
        assert_eq!(
            sanitize_title_for_filename("Prisioneros de la geografía"),
            "Prisioneros de la geografía"
        );
        assert_eq!(sanitize_title_for_filename("///"), "");
    }

    #[test]
    fn readable_drive_object_name_composes_title_separator_and_id() {
        let uuid = "5b4ba1c1-1b6f-4301-816a-40385be603fa";
        assert_eq!(
            readable_drive_object_name("Prisioneros de la geografía", uuid, Some("epub")),
            format!("Prisioneros de la geografía -- {uuid}.epub")
        );
        // Empty title falls back to the id-only canonical name (no dangling separator).
        assert_eq!(
            readable_drive_object_name("", uuid, Some("epub")),
            canonical_drive_object_name(uuid, Some("epub"))
        );
        // The extension is canonicalized.
        assert!(readable_drive_object_name("T", uuid, Some("PDF")).ends_with(".pdf"));
    }

    #[test]
    fn parse_readable_stem_recovers_the_id_after_the_last_separator() {
        let uuid = "5b4ba1c1-1b6f-4301-816a-40385be603fa";
        assert_eq!(parse_readable_stem(&format!("Title -- {uuid}")), Some(uuid));
        assert_eq!(parse_readable_stem(&format!("A -- B -- {uuid}")), Some(uuid));
        assert_eq!(parse_readable_stem(uuid), None);
        assert_eq!(parse_readable_stem("Title -- "), None);
    }

    /// The 80-char title bound counts Unicode code points (scalars), so a title
    /// whose 80th character is an astral one keeps the whole character instead of
    /// a lone surrogate. TypeScript/Kotlin count code points too, so all three
    /// platforms compose the SAME name for the same book.
    #[test]
    fn sanitize_title_bounds_on_code_points_not_utf16_units() {
        let title = format!("{}🎉tail", "a".repeat(79));
        let sanitized = sanitize_title_for_filename(&title);
        assert_eq!(sanitized.chars().count(), MAX_TITLE_CHARS);
        // The emoji sits exactly at the 80th code point and is kept whole.
        assert!(sanitized.ends_with('🎉'), "expected the astral char to survive: {sanitized:?}");
        // No lone surrogate can exist in a Rust String, so the composed name is
        // well-formed by construction; assert the byte-identity of the segment.
        assert_eq!(sanitized, format!("{}{}", "a".repeat(79), '🎉'));
    }

    /// The shared contract: this test and the Kotlin/TypeScript runners read the
    /// SAME `packages/drive-filename-fixtures/fixtures.json`. If any platform
    /// drifts, this test (or its sibling) fails on the platform that drifted.
    #[test]
    fn canonical_stem_matches_the_shared_fixtures_contract() {
        let contract = shared_fixture_contract();
        let canonical = contract["canonical"].as_array().expect("canonical is an array");
        assert!(canonical.len() >= 14, "the contract keeps at least 14 fixtures");

        for entry in canonical {
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

    /// The readable-name surface: `<sanitized title> -- <id>.<ext>` for a NEW
    /// upload. Rust, Kotlin and TypeScript compose the SAME name for the same
    /// title/bookId/extension or this test fails on the platform that drifted.
    #[test]
    fn readable_names_match_the_shared_fixtures_contract() {
        let contract = shared_fixture_contract();
        let readable = contract["readable"].as_array().expect("readable is an array");
        assert!(readable.len() >= 7, "the readable surface keeps at least 7 fixtures");

        for entry in readable {
            let title = entry["title"].as_str().expect("fixture title is a string");
            let book_id = entry["bookId"].as_str().expect("fixture bookId is a string");
            let extension = entry["extension"].as_str().expect("fixture extension is a string");
            let expected =
                entry["expectedName"].as_str().expect("fixture expectedName is a string");

            assert_eq!(
                readable_drive_object_name(title, book_id, Some(extension)),
                expected,
                "readable name for fixture title {title:?}"
            );
        }
    }

    /// The 80-character bound counts code points, not UTF-16 units, so the three
    /// platforms agree even when an astral character sits on the boundary.
    #[test]
    fn truncation_matches_the_shared_fixtures_contract() {
        let contract = shared_fixture_contract();
        let truncation = contract["truncation"].as_array().expect("truncation is an array");
        assert!(!truncation.is_empty(), "the truncation surface keeps at least 1 fixture");

        for entry in truncation {
            let title = entry["title"].as_str().expect("fixture title is a string");
            let expected =
                entry["expectedSanitized"].as_str().expect("fixture expectedSanitized is a string");

            assert_eq!(
                sanitize_title_for_filename(title),
                expected,
                "sanitized title for fixture {title:?}"
            );
        }
    }

    /// The shared fixture file, parsed once per test. The path is fixed relative
    /// to this source file so the contract travels with the code.
    fn shared_fixture_contract() -> serde_json::Value {
        let raw = include_str!("../../../packages/drive-filename-fixtures/fixtures.json");
        serde_json::from_str(raw).expect("fixtures.json must parse")
    }
}
