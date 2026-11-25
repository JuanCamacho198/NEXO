package com.nexo.presentation.screen.reader

import com.nexo.domain.model.Bookmark
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where the reader is, reduced to what a bookmark can be compared against. */
data class ReadingSpot(
    val href: String? = null,
    val position: Int? = null,
    val totalProgression: Double? = null,
    val pdfPage: Int? = null,
)

/**
 * Answers "is this page bookmarked?" and orders bookmarks as they appear in the
 * book. Pure and JSON-regex based (like `HighlightLocationResolver`) so it is
 * unit-testable without a publication or Android's JSON parser.
 *
 * Two spots are the same page when they share a resource and Readium position
 * (a position is a fixed slice of text, roughly a page). Without positions, a
 * whole-book progression within [SAME_SPOT_PROGRESSION] stands in.
 */
internal object BookmarkSpots {
    private const val PDF_PREFIX = "pdfpage:"
    private const val READIUM_PREFIX = "readium:"
    private const val SAME_SPOT_PROGRESSION = 0.002

    private val POSITION = Regex(""""position"\s*:\s*(\d+)""")
    private val TOTAL_PROGRESSION = Regex(""""totalProgression"\s*:\s*(-?[0-9.]+(?:[eE][-+]?\d+)?)""")
    private val HREF = Regex(""""href"\s*:\s*"((?:\\.|[^"\\])*)"""")

    fun spotOf(bookmark: Bookmark): ReadingSpot {
        if (bookmark.cfiLocation.startsWith(PDF_PREFIX)) {
            return ReadingSpot(pdfPage = bookmark.cfiLocation.removePrefix(PDF_PREFIX).toIntOrNull())
        }
        val json = bookmark.locatorJson.orEmpty()
        val href =
            HREF
                .find(json)
                ?.groupValues
                ?.get(1)
                ?.replace("\\/", "/")
                ?: bookmark.cfiLocation.takeIf { it.startsWith(READIUM_PREFIX) }?.removePrefix(READIUM_PREFIX)
        return ReadingSpot(
            href = href,
            position =
                POSITION
                    .find(json)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull(),
            totalProgression =
                TOTAL_PROGRESSION
                    .find(json)
                    ?.groupValues
                    ?.get(1)
                    ?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it in 0.0..1.0 },
        )
    }

    fun isSameSpot(
        a: ReadingSpot,
        b: ReadingSpot,
    ): Boolean {
        if (a.pdfPage != null || b.pdfPage != null) return a.pdfPage != null && a.pdfPage == b.pdfPage
        if (a.href != null && b.href != null && normalize(a.href) != normalize(b.href)) return false
        if (a.position != null && b.position != null) return a.position == b.position
        val pa = a.totalProgression ?: return false
        val pb = b.totalProgression ?: return false
        return abs(pa - pb) < SAME_SPOT_PROGRESSION
    }

    /** The bookmark on the page the reader is looking at, if any. */
    fun bookmarkAt(
        bookmarks: List<Bookmark>,
        current: ReadingSpot?,
    ): Bookmark? = current?.let { spot -> bookmarks.firstOrNull { isSameSpot(spotOf(it), spot) } }

    /** Book order: PDF page, else whole-book progression; unknown positions last, newest first among them. */
    fun inBookOrder(bookmarks: List<Bookmark>): List<Bookmark> =
        bookmarks.sortedWith(
            compareBy<Bookmark> { spotOf(it).pdfPage?.toDouble() ?: spotOf(it).totalProgression ?: Double.MAX_VALUE }
                .thenByDescending { it.updatedAtEpochMillis },
        )

    /** Whole-book percentage; `null` when unknown (PDF bookmarks show their page instead). */
    fun progressPercent(bookmark: Bookmark): Int? = spotOf(bookmark).totalProgression?.let { (it * 100).roundToInt() }

    private fun normalize(href: String): String =
        href
            .substringBefore('#')
            .trimStart('/')
            .lowercase()
}
