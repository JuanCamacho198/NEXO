package com.nexo.presentation.feature.highlights

import com.nexo.domain.model.Highlight
import com.nexo.presentation.feature.highlights.utils.ChapterLocation
import com.nexo.presentation.feature.highlights.utils.HighlightLocationResolver

/** Highlights of one chapter, in reading order. [chapterTitle] is `null` for highlights with no known chapter. */
internal data class HighlightGroup(
    val chapterTitle: String?,
    val highlights: List<Highlight>,
)

/**
 * Orders [highlights] as they appear in the book and groups them by chapter.
 *
 * Position comes from the stored locator's whole-book progression; when a
 * highlight has none, its chapter's place in the table of contents stands in.
 * Highlights with no derivable position go last, keeping their incoming order
 * (the sort is stable). Groups appear in the order of their first highlight,
 * and the chapter-less group, if any, is always last.
 */
internal fun groupHighlightsByChapter(
    highlights: List<Highlight>,
    chapters: List<ChapterLocation>,
): List<HighlightGroup> {
    val located =
        highlights.map { highlight ->
            val title = HighlightLocationResolver.chapterTitle(highlight.cfiRange, chapters)
            val chapterOrder = title?.let { t -> chapters.indexOfFirst { it.title == t } }?.takeIf { it >= 0 }
            val position =
                HighlightLocationResolver.locatorProgression(highlight.locatorJson)
                    ?: chapterOrder?.let { it.toDouble() / chapters.size.coerceAtLeast(1) }
            Triple(highlight, title, position)
        }
    val ordered = located.sortedBy { (_, _, position) -> position ?: Double.MAX_VALUE }
    val (withChapter, withoutChapter) = ordered.partition { (_, title, _) -> title != null }
    val groups =
        withChapter
            .groupBy({ (_, title, _) -> title }, { (highlight, _, _) -> highlight })
            .map { (title, items) -> HighlightGroup(title, items) }
    return if (withoutChapter.isEmpty()) {
        groups
    } else {
        groups + HighlightGroup(null, withoutChapter.map { it.first })
    }
}
