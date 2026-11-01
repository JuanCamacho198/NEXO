package com.nexo.presentation.feature.highlights.utils

/**
 * A chapter as the location resolver needs it: its reading-order index, its
 * display title, and its resource href.
 */
data class ChapterLocation(
    val index: Int,
    val title: String,
    val href: String,
)

/**
 * Derives a human-readable location label for a highlight.
 *
 * [Highlight][com.nexo.domain.model.Highlight] carries no chapter title, so it
 * must be recovered from the stored position. Three position formats exist in
 * the database, and all three are handled here:
 *
 * - `"pdfpage:<n>"` → the 1-based page number (caller adds 1).
 * - `"readium:<href>"` → the chapter whose href matches, by full path first and
 *   then by filename.
 * - EPUB CFI → the spine index via `epubcfi(/6/<spine>)`, matched against the
 *   chapter reading-order index.
 *
 * Returns `null` when no location can be derived, so callers render no row
 * instead of an empty or misleading one. Every derivation is pure, so the
 * mapping from a position to a display label is unit-testable without a
 * publication.
 */
object HighlightLocationResolver {
    private const val PDF_PAGE_PREFIX = "pdfpage:"
    private const val READIUM_PREFIX = "readium:"
    private val EPUB_SPINE_REGEX = Regex("/6/(\\d+)")
    private val TOTAL_PROGRESSION_REGEX = Regex("\"totalProgression\"\\s*:\\s*(-?[0-9.eE+]+)")

    /**
     * Extracts the 1-based PDF page number from a `"pdfpage:<n>"` marker.
     * Returns `null` for any other format.
     */
    fun pdfPageNumber(cfiRange: String): Int? =
        if (!cfiRange.startsWith(PDF_PAGE_PREFIX)) {
            null
        } else {
            cfiRange.removePrefix(PDF_PAGE_PREFIX).toIntOrNull()
        }

    /**
     * Extracts the resource href from a `"readium:<href>"` marker, or `null`
     * when the marker is absent or carries no href.
     */
    fun readiumHref(cfiRange: String): String? =
        if (!cfiRange.startsWith(READIUM_PREFIX)) {
            null
        } else {
            cfiRange.removePrefix(READIUM_PREFIX).trim().takeIf { it.isNotEmpty() }
        }

    /**
     * Extracts the 0-based spine index from an EPUB CFI such as
     * `epubcfi(/6/4[chap1]!/4[body]/2/1:10)`. The CFI's spine index is 1-based,
     * so it is decremented to match [ChapterLocation.index].
     *
     * Returns `null` when the CFI has no `/6/<n>` component.
     */
    fun epubSpineIndex(cfiRange: String): Int? =
        EPUB_SPINE_REGEX
            .find(cfiRange)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.minus(1)

    /** Returns the href with any fragment and query removed, lowercased. */
    private fun normalizeHref(href: String): String =
        href
            .substringBefore('#')
            .substringBefore('?')
            .replace("//", "/")
            .trim()
            .lowercase()

    /** Returns the last path segment of [href], lowercased. */
    private fun filenameOf(href: String): String =
        href
            .substringBefore('#')
            .substringBefore('?')
            .substringAfterLast('/')
            .trim()
            .lowercase()

    /**
     * Resolves a position to the title of the matching chapter in [chapters],
     * or `null` when the position is unparseable, the href is unknown, or no
     * chapter matches.
     *
     * A chapter matches by href when the normalized full paths are equal, and
     * otherwise when the filenames are equal. An EPUB CFI matches when the
     * chapter's [ChapterLocation.index] equals the CFI's spine index. Several
     * TOC entries can share a spine index (a part and its sub-chapters); the
     * first entry wins so the label stays stable.
     */
    fun chapterTitle(
        cfiRange: String,
        chapters: List<ChapterLocation>,
    ): String? {
        readiumHref(cfiRange)?.let { href ->
            val targetPath = normalizeHref(href)
            val targetFile = filenameOf(href)
            val match =
                chapters.firstOrNull { normalizeHref(it.href) == targetPath }
                    ?: chapters.firstOrNull { filenameOf(it.href) == targetFile && targetFile.isNotEmpty() }
            return match?.title?.takeIf { it.isNotBlank() }
        }

        val spineIndex = epubSpineIndex(cfiRange) ?: return null
        return chapters
            .firstOrNull { it.index == spineIndex }
            ?.title
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the overall book progression (0..1) from a persisted Readium
     * locator JSON such as
     * `{"href":"...","locations":{"totalProgression":0.0116,...}}`.
     *
     * Returns `null` when [locatorJson] is absent, malformed, or carries no
     * in-range `totalProgression`. The file is parsed with a narrow regex
     * rather than a JSON parser so this stays a pure JVM function with no
     * Android dependency, and it never needs the whole locator object.
     */
    fun locatorProgression(locatorJson: String?): Double? {
        if (locatorJson.isNullOrBlank()) return null
        val raw = TOTAL_PROGRESSION_REGEX.find(locatorJson)?.groupValues?.getOrNull(1) ?: return null
        val value = raw.toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && it in 0.0..1.0 }
    }

    /**
     * Formats a 0..1 [progression] as a whole-book percentage label, e.g.
     * `"46%"`. Returns `null` for a null/out-of-range value so callers skip
     * the row instead of rendering a bogus number.
     */
    fun progressionLabel(progression: Double?): Int? {
        if (progression == null || !progression.isFinite() || progression !in 0.0..1.0) return null
        return Math.round(progression * 100.0).toInt()
    }
}
