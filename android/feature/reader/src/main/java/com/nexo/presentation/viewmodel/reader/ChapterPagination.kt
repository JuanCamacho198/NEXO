package com.nexo.presentation.viewmodel.reader

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Length of the text a reader actually sees in a chapter's XHTML: no `<head>`,
 * no scripts or styles, no tags, each entity one character, and whitespace runs
 * collapsed to a single space the way the browser renders them.
 *
 * The raw resource length (markup included) overstated a chapter by the weight
 * of its tags, which for a typical EPUB is a third or more of the file.
 */
internal fun visibleTextLength(html: String): Int =
    html
        .replace(HEAD_BLOCK, " ")
        .replace(SCRIPT_OR_STYLE_BLOCK, " ")
        .replace(TAG, " ")
        .replace(ENTITY, "x")
        .replace(WHITESPACE_RUN, " ")
        .trim()
        .length

private val HEAD_BLOCK = Regex("(?is)<head\\b.*?</head>")
private val SCRIPT_OR_STYLE_BLOCK = Regex("(?is)<(script|style)\\b.*?</\\1>")
private val TAG = Regex("(?s)<[^>]*>")
private val ENTITY = Regex("&#?[a-zA-Z0-9]+;")
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Visible text length per chapter href, filled off the main thread the first
 * time a chapter is shown. A chapter's length does not change while the book is
 * open, so one read per chapter serves every later page of it.
 */
internal object ChapterTextLengthCache {
    private val lengths = ConcurrentHashMap<String, Int>()
    private val readsInFlight = ConcurrentHashMap.newKeySet<String>()

    fun peek(href: String): Int? = lengths[href]

    /** True only for the first caller for [href]; the caller then performs the read. */
    fun claimRead(href: String): Boolean = readsInFlight.add(href)

    fun store(
        href: String,
        length: Int,
    ) {
        lengths[href] = length
        readsInFlight.remove(href)
    }

    fun release(href: String) {
        readsInFlight.remove(href)
    }

    fun clear() {
        lengths.clear()
        readsInFlight.clear()
    }
}

/**
 * Index of the chapter that contains [fraction] of the book, given each
 * chapter's start as a fraction of the book (`null` when unknown). The last
 * chapter whose start is at or before [fraction] wins; unknown starts are
 * skipped. Returns null when no start is known.
 */
internal fun chapterIndexAtFraction(
    starts: List<Float?>,
    fraction: Float,
): Int? {
    var found: Int? = null
    starts.forEachIndexed { index, start ->
        if (start != null && start <= fraction + FRACTION_EPSILON) found = index
    }
    return found ?: starts.indexOfFirst { it != null }.takeIf { it >= 0 }
}

private const val FRACTION_EPSILON = 1e-4f

/** Average glyph advance for running Latin text, in ems (spaces included). */
private const val AVG_CHAR_WIDTH_EM = 0.5f

/**
 * Share of a full page that holds text once paragraph breaks and short last
 * lines are accounted for: a page is never a solid block of characters.
 */
private const val PAGE_FILL_RATIO = 0.85f

/**
 * Pages left in the current chapter at the current typography.
 *
 * [chapterTextChars] is the chapter's visible text ([visibleTextLength]),
 * [progression] the position inside the chapter (0..1, Readium's
 * `locations.progression`), and [viewport] the reading area. The viewport is in
 * PIXELS (it comes from the layout's measured size), so only the margins and the
 * font size are converted with the density.
 *
 * Counts the pages after the current one, so the last page of a chapter reads 0.
 */
internal fun chapterPagesRemaining(
    chapterTextChars: Int,
    progression: Double,
    viewport: ReadingProgressCalculator.ViewportTypography,
): Int {
    if (chapterTextChars <= 0) return 0
    val density = viewport.density.coerceAtLeast(1f)
    val fontPx = viewport.fontSizeSp * density
    val marginsPx = viewport.pageMarginsDp * 2 * density
    val contentW = (viewport.viewportW - marginsPx).coerceAtLeast(1f)
    val contentH = (viewport.viewportH - marginsPx).coerceAtLeast(1f)
    val charsPerLine = floor(contentW / (fontPx * AVG_CHAR_WIDTH_EM)).coerceAtLeast(1f)
    val linesPerPage = floor(contentH / (fontPx * viewport.lineHeight)).coerceAtLeast(1f)
    val charsPerPage = (charsPerLine * linesPerPage * PAGE_FILL_RATIO).coerceAtLeast(1f)
    val remainingChars = chapterTextChars * (1.0 - progression.coerceIn(0.0, 1.0))
    return floor(remainingChars / charsPerPage).toInt().coerceAtLeast(0)
}

/**
 * A chapter title fit for one line of the reader footer, or null when what the
 * book supplied is not a title at all.
 *
 * EPUB titles arrive dirty: TOC leaders (`Capítulo 1 ........ 5`), trailing
 * ellipses and dot runs, collapsed whitespace, and sometimes the resource file
 * name standing in for a missing title (`chapter001.xhtml`). Dots runs and the
 * page number a leader points at are dropped; a bare file name yields null so
 * the footer can fall back to the book title instead of showing a path.
 */
fun cleanChapterTitle(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val collapsed = raw.replace(WHITESPACE_RUN, " ").trim()
    if (FILE_NAME.matches(collapsed)) return null
    val cleaned =
        collapsed
            .replace(LEADER_WITH_PAGE, "")
            .replace(DOT_RUN, " ")
            .replace(WHITESPACE_RUN, " ")
            .trim()
            .trimEnd('.', '·', '…', ' ', '-', '—', ':')
            .trim()
    return cleaned.ifBlank { null }
}

/** A dot or ellipsis run (two or more) and an optional page number after it: a TOC leader. */
private val LEADER_WITH_PAGE = Regex("""(?:\s*[.·…]{2,}\s*)+\d*\s*$""")
private val DOT_RUN = Regex("[.·…]{2,}")
private val FILE_NAME = Regex("""(?i)^[\w\-./]+\.(x?html?|xml)$""")
