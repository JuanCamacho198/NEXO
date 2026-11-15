package com.nexo.presentation.viewmodel.reader.lifecycle

import android.util.Log
import com.nexo.presentation.viewmodel.reader.BookChapter
import org.readium.r2.shared.publication.Publication

/**
 * Builds chapter TOC from a Readium [Publication].
 *
 * Preserves verbatim logic from [ReaderLifecycleStateHolder]: recursive TOC walk,
 * href normalization, and spine index resolution.
 */
object TocBuilder {
    private const val TAG = "TocBuilder"

    fun buildChaptersFromPublication(publication: Publication): List<BookChapter> {
        if (publication.tableOfContents.isNotEmpty()) {
            val result = ArrayList<BookChapter>()
            for (link in publication.tableOfContents) {
                collectTocChapters(link, publication, 0, result)
            }
            return result
        }
        return publication.readingOrder.mapIndexed { readingIndex, link ->
            val href = link.href.toString()
            BookChapter(
                index = readingIndex,
                id = href,
                title = link.title?.takeIf { it.isNotBlank() } ?: "Chapter ${readingIndex + 1}",
                href = href,
                depth = 0,
            )
        }
    }

    fun collectTocChapters(
        link: org.readium.r2.shared.publication.Link,
        publication: Publication,
        depth: Int,
        out: MutableList<BookChapter>,
    ) {
        val href = link.href.toString()
        val title =
            link.title?.takeIf { it.isNotBlank() }
                ?: "Chapter ${out.size + 1}"
        val spineIndex = resolveSpineIndexForTocHref(href, link, publication, out.size)
        out.add(
            BookChapter(
                index = spineIndex.coerceAtLeast(0),
                id = href,
                title = title,
                href = href,
                depth = depth,
            ),
        )
        for (child in link.children) {
            collectTocChapters(child, publication, depth + 1, out)
        }
    }

    fun resolveSpineIndexForTocHref(
        href: String,
        link: org.readium.r2.shared.publication.Link,
        publication: Publication,
        fallbackIndex: Int,
    ): Int {
        // 1. Exact via Readium linkWithHref (handles './', encoding)
        try {
            publication.linkWithHref(link.href.resolve())?.let { resolved ->
                val idx = publication.readingOrder.indexOf(resolved)
                if (idx >= 0) return idx
            }
        } catch (_: Throwable) {
        }

        val normalizedHref = stripFragment(href).trim()
        val normalizedHrefLower = normalizeFile(href)
        val fileLower = filenameLower(href)

        // 2. Exact href match against readingOrder
        publication.readingOrder
            .indexOfFirst { it.href.toString() == href }
            .takeIf { it >= 0 }
            ?.let { return it }

        // 3. Normalized href exact (strip fragment/query)
        publication.readingOrder
            .indexOfFirst { stripFragment(it.href.toString()) == normalizedHref }
            .takeIf { it >= 0 }
            ?.let { return it }

        // 4. Filename lowercase fallback (handles Text/chapter.xhtml vs chapter.xhtml)
        if (fileLower.isNotBlank()) {
            publication.readingOrder
                .indexOfFirst { filenameLower(it.href.toString()) == fileLower }
                .takeIf { it >= 0 }
                ?.let { return it }
        }

        // 5. Case-insensitive normalized href
        publication.readingOrder
            .indexOfFirst { normalizeFile(it.href.toString()) == normalizedHrefLower }
            .takeIf { it >= 0 }
            ?.let { return it }

        Log.w(TAG, "collectTocChapters: no spine match for TOC href='$href' (file='$fileLower'), fallback to list position $fallbackIndex")
        return fallbackIndex
    }

    fun stripFragment(h: String): String = h.substringBefore('#').substringBefore('?')

    fun normalizeFile(h: String): String = stripFragment(h).trim().lowercase()

    fun filenameLower(h: String): String = stripFragment(h).substringAfterLast('/').trim().lowercase()
}
