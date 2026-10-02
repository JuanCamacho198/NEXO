package com.nexo.data.sync

import java.text.Normalizer
import java.util.Locale

/**
 * Canonical Drive filename stem (FR-06). Constructible only through
 * [DriveFilename.canonical], which is the composition boundary the write guard
 * (WU5) consumes.
 */
@JvmInline
value class CanonicalName(
    val stem: String,
)

/**
 * The single canonical bookId-to-filename algorithm, byte-identical to the Rust
 * (`desktop/src-tauri/src/filename.rs`) and TypeScript
 * (`desktop/src/lib/shared/sync/driveFilename.ts`) implementations. All three
 * are pinned by the SAME shared contract,
 * `packages/drive-filename-fixtures/fixtures.json`.
 *
 * Rules, applied in order: NFKC-normalize, lowercase, keep `[a-z0-9_-]`, bound
 * to [MAX_BOOK_ID_CHARS], fall back to [FALLBACK_BOOK_ID] when empty or fully
 * filtered, then guard reserved Windows device names (`con` -> `con_`). The
 * lowercase step closes the NTFS case-insensitive collision class; dropping
 * (never replacing) illegal characters cannot inject a character that collides
 * with a genuine id character. The mapping is deterministic and idempotent.
 */
object DriveFilename {
    const val MAX_BOOK_ID_CHARS: Int = 120
    const val FALLBACK_BOOK_ID: String = "book"
    const val DEFAULT_EXTENSION: String = "epub"

    private const val MAX_EXT_CHARS: Int = 5

    private val RESERVED_DEVICE_NAMES: Set<String> =
        buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            for (index in 1..9) {
                add("COM$index")
                add("LPT$index")
            }
        }

    /** Canonical stem wrapped in the [CanonicalName] newtype. */
    fun canonical(rawBookId: String): CanonicalName = CanonicalName(canonicalStem(rawBookId))

    /** The canonical stem itself, without the newtype. */
    fun canonicalStem(rawBookId: String): String {
        val filtered =
            Normalizer
                .normalize(rawBookId, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .filter { it.isCanonicalCharacter() }
                .take(MAX_BOOK_ID_CHARS)
        val stem = filtered.ifEmpty { FALLBACK_BOOK_ID }
        return if (stem.uppercase(Locale.ROOT) in RESERVED_DEVICE_NAMES) "${stem}_" else stem
    }

    /**
     * Canonical Drive object name: the canonical stem plus the canonical
     * extension (`gutendex:2701`, `epub` -> `gutendex2701.epub`). Mirrors the
     * Rust `canonical_drive_object_name` and the TypeScript
     * `canonicalDriveObjectName`.
     */
    fun objectName(
        rawBookId: String,
        extension: String?,
    ): String = "${canonicalStem(rawBookId)}.${canonicalExtension(extension)}"

    /**
     * Drive object path `books/{canonical(userId)}/{stem}.{ext}`. Both the user
     * token and the extension go through the same canonical contract so no raw
     * segment can reach a Drive name.
     */
    fun objectPath(
        userId: String,
        book: CanonicalName,
        extension: String,
    ): String {
        val userToken = canonical(userId).stem
        return "books/$userToken/${book.stem}.${canonicalExtension(extension)}"
    }

    /** `[a-z0-9]{1,5}` extension, defaulting to [DEFAULT_EXTENSION]. */
    fun canonicalExtension(raw: String?): String {
        val filtered =
            raw.orEmpty().lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }
        return if (filtered.isEmpty() || filtered.length > MAX_EXT_CHARS) {
            DEFAULT_EXTENSION
        } else {
            filtered
        }
    }

    /**
     * Every stem a raw book id may have been stored under by a pre-WU4 client:
     * canonical, the desktop drop form (case-preserved and lowercased), the
     * Android `sanitizeIdToken` dash form, the Android `sanitize` underscore
     * form, and the raw unsanitized id (colon-bearing desktop names). The
     * reconciler matches against this set; it never deletes a match.
     */
    fun legacyForms(rawBookId: String): Set<String> =
        buildSet {
            add(canonicalStem(rawBookId))
            add(dropFilter(rawBookId))
            add(dropFilter(rawBookId).lowercase(Locale.ROOT))
            add(dashFilter(rawBookId))
            add(underscoreFilter(rawBookId))
            add(rawBookId)
        }.filterTo(mutableSetOf()) { it.isNotEmpty() }

    /**
     * Legacy `_state.json` names for a book id, including the raw colon-bearing
     * desktop form (`gutendex:2701_state.json`).
     */
    fun legacyStateNames(rawBookId: String): Set<String> =
        buildSet {
            add("${canonicalStem(rawBookId)}_state.json")
            add("${rawBookId}_state.json")
        }

    /** Parses a `books/{user}/{stem}.{ext}` path into its parts, or null. */
    fun parseDrivePath(path: String): ParsedDrivePath? {
        val segments = path.split('/')
        if (segments.size != 3 || segments.first() != "books") return null
        val fileName = segments.last()
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == fileName.lastIndex) return null
        val stem = fileName.substring(0, dotIndex)
        val extension = canonicalExtension(fileName.substring(dotIndex + 1))
        return ParsedDrivePath(stem = stem, extension = extension)
    }

    /** Parsed Drive path: the stored stem (canonical or legacy) plus extension. */
    data class ParsedDrivePath(
        val stem: String,
        val extension: String,
    )

    private fun dropFilter(raw: String): String =
        raw.filter {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        }

    private fun dashFilter(raw: String): String =
        raw
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "-")
            .trim('-')
            .ifBlank { "unknown" }

    private fun underscoreFilter(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun Char.isCanonicalCharacter(): Boolean = this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_'
}
