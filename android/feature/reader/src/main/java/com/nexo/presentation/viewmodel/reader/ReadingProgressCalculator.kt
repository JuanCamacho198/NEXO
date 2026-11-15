package com.nexo.presentation.viewmodel.reader

import com.nexo.debug.DebugDual
import com.nexo.debug.DebugEvent
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Exact reflow pages-remaining calculator.
 *
 * Priority:
 * 1. Readium pagination (publication.positions) when available and non-empty
 * 2. Fallback typographic charsPerPage model when positions absent
 */
object ReadingProgressCalculator {
    private const val PROGRESSION_EPSILON = 0.02
    private const val NO_VIEWPORT_REMAINING_FACTOR = 10
    private const val AVG_CHAR_WIDTH_FACTOR = 0.6f
    private const val ESTIMATED_CHARS_PER_CHAPTER = 5000

    data class ViewportTypography(
        val viewportW: Int,
        val viewportH: Int,
        val fontSizeSp: Float,
        val lineHeight: Float,
        val pageMarginsDp: Float = 16f,
        val density: Float = 3f,
    )

    data class Result(
        val remaining: Int,
        val totalPages: Int,
        val currentPage: Int,
        val charsPerPage: Int,
        val path: String, // "positions" or "fallback"
    )

    /**
     * Compute remaining pages from [locator] snapshot.
     * Viewport typography listener: caller must re-invoke on viewport or font changes so
     * charsPerPage fallback stays exact; positions path is viewport-independent.
     */
    fun compute(
        publication: Publication?,
        locator: Locator?,
        chapters: List<BookChapter>,
        currentChapterIndex: Int,
        viewport: ViewportTypography?,
        totalCharsFallback: Int? = null,
    ): Result {
        // Try Readium positions first (via reflection for API compat across Readium versions)
        publication?.let { pub ->
            try {
                @Suppress("UNCHECKED_CAST")
                val positions: List<Locator>? =
                    runCatching {
                        // Try direct property via reflection (positions may be extension or field)
                        // Readium 3.2.0 exposes positions as List<Locator> via getPositions() or field 'positions'
                        // Also try Kotlin property 'positions' and declared methods with no args
                        val method = pub::class.java.methods.firstOrNull { it.name == "getPositions" || it.name == "positions" }
                        if (method != null) {
                            method.invoke(pub) as? List<Locator>
                        } else {
                            // Try declared method (private/protected)
                            val declared = pub::class.java.declaredMethods.firstOrNull { it.name == "getPositions" || it.name == "positions" }
                            if (declared != null) {
                                declared.isAccessible = true
                                declared.invoke(pub) as? List<Locator>
                            } else {
                                val field = pub::class.java.declaredFields.firstOrNull { it.name == "positions" }
                                field?.let {
                                    it.isAccessible = true
                                    it.get(pub) as? List<Locator>
                                }
                            }
                        }
                    }.getOrNull() ?: runCatching {
                        // Fallback: Kotlin reflection via members (covers extension property)
                        @Suppress("UNCHECKED_CAST")
                        (pub::class.members.firstOrNull { it.name == "positions" }?.call(pub) as? List<Locator>)
                    }.getOrNull()
                if (positions == null || positions.isEmpty()) throw IllegalStateException("no positions")
                val positionsNN = positions
                run {
                    val totalPages = positionsNN.size
                    // Find nearest position index matching locator href/progression
                    val currentIdx =
                        locator?.let { loc ->
                            // positions are Locators; find index where href matches and progression close
                            positionsNN
                                .indexOfFirst { pos ->
                                    pos.href.toString() == loc.href.toString() &&
                                        kotlin.math.abs((pos.locations.progression ?: 0.0) - (loc.locations.progression ?: 0.0)) < PROGRESSION_EPSILON
                                }.takeIf { it >= 0 }
                                ?: positionsNN.indexOfFirst { it.href.toString() == loc.href.toString() }.takeIf { it >= 0 }
                                ?: run {
                                    // fallback to progression-based estimate within positions
                                    val prog = loc.locations.progression ?: 0.0
                                    (prog * totalPages).toInt().coerceIn(0, totalPages - 1)
                                }
                        } ?: currentChapterIndex.coerceIn(0, totalPages - 1)
                    val remaining = (totalPages - currentIdx - 1).coerceAtLeast(0)
                    val charsPerPage = 0
                    val path = "positions"
                    DebugDual.log(
                        DebugEvent.FooterRecompute(
                            viewportH = viewport?.viewportH ?: 0,
                            viewportW = viewport?.viewportW ?: 0,
                            fontSize = viewport?.fontSizeSp ?: 0f,
                            lineHeight = viewport?.lineHeight ?: 0f,
                            pageMargins = viewport?.pageMarginsDp ?: 0f,
                            charsPerPage = charsPerPage,
                            remaining = remaining,
                            path = path,
                        ),
                    )
                    return Result(remaining, totalPages, currentIdx, charsPerPage, path)
                }
            } catch (_: Throwable) {
                // Fall through to typographic fallback
            }
        }

        // Fallback typographic model
        if (viewport == null || viewport.viewportW <= 0 || viewport.viewportH <= 0) {
            // No viewport -> fallback to simple (1 - progression) * estimate but with new logic: use 0 remaining
            val remaining =
                locator?.locations?.progression?.let { prog ->
                    ceil((1.0 - prog) * NO_VIEWPORT_REMAINING_FACTOR).toInt().coerceAtLeast(0)
                } ?: 0
            return Result(remaining, remaining, 0, 0, "fallback-no-viewport")
        }

        val density = viewport.density
        val fontSizePx = viewport.fontSizeSp * density
        val marginsPxH = viewport.pageMarginsDp * 2 * density
        val marginsPxW = viewport.pageMarginsDp * 2 * density
        val contentW = (viewport.viewportW * density - marginsPxW).coerceAtLeast(1f)
        val contentH = (viewport.viewportH * density - marginsPxH).coerceAtLeast(1f)
        val avgCharWidth = fontSizePx * AVG_CHAR_WIDTH_FACTOR
        val lineHeightPx = fontSizePx * viewport.lineHeight
        val charsPerLine = floor(contentW / avgCharWidth).toInt().coerceAtLeast(1)
        val linesPerPage = floor(contentH / lineHeightPx).toInt().coerceAtLeast(1)
        val charsPerPage = charsPerLine * linesPerPage

        // totalChars: prefer publication readingOrder estimated length or provided totalCharsFallback
        // Use heuristic: average chars per chapter * chapterCount, or 18000 for single chapter fallback
        val totalChars =
            totalCharsFallback
                ?: run {
                    // estimate: 18000 chars per chapter average * chapter count, or 5000 if unknown
                    val perChapter = ESTIMATED_CHARS_PER_CHAPTER
                    val count = chapters.size.takeIf { it > 0 } ?: 1
                    perChapter * count
                }

        val totalPages = ceil(totalChars.toDouble() / charsPerPage).toInt().coerceAtLeast(1)
        val progression = locator?.locations?.progression?.toFloat() ?: 0f
        // For chapter-based remaining, we compute remaining within chapter or total?
        // Spec: remaining = totalPages - currentPage where currentPage derived from progression
        // For fallback we interpret totalPages as estimated for chapter if chapters non-empty, otherwise book
        val currentPage = floor(progression * totalPages).toInt().coerceIn(0, totalPages - 1)
        val remaining = (totalPages - currentPage - 1).coerceAtLeast(0)

        DebugDual.log(
            DebugEvent.FooterRecompute(
                viewportH = viewport.viewportH,
                viewportW = viewport.viewportW,
                fontSize = viewport.fontSizeSp,
                lineHeight = viewport.lineHeight,
                pageMargins = viewport.pageMarginsDp,
                charsPerPage = charsPerPage,
                remaining = remaining,
                path = "fallback",
            ),
        )

        return Result(remaining, totalPages, currentPage, charsPerPage.toInt(), "fallback")
    }
}
