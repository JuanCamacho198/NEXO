package com.nexo.presentation.screen.readium

import kotlinx.coroutines.runBlocking
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.resource.Resource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal const val ESTIMATED_CHAPTER_CHARS_FALLBACK = 10000

/**
 * Exact chapter text length per spine href, read off the caller's thread.
 *
 * A spine item is a whole chapter, so its length costs a file and zip read that
 * must not happen on the thread mapping highlights (a Compose `LaunchedEffect`).
 * The first pass sees the estimate and starts the read; every later pass sees
 * the real length, because the length belongs to the chapter, not to a highlight:
 * all highlights in one chapter share the same chapterChars denominator.
 */
internal object ChapterCharsCache {
    private val lengths = ConcurrentHashMap<String, Int>()
    private val readsInFlight = ConcurrentHashMap.newKeySet<String>()

    private val readers: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nexo-chapter-chars").apply { isDaemon = true }
        }
    }

    fun peek(href: String): Int? = lengths[href]

    fun store(
        href: String,
        chars: Int,
    ) {
        lengths[href] = chars
    }

    /** True only for the first caller for [href] while its read is pending. */
    fun startRead(href: String): Boolean = readsInFlight.add(href)

    fun finishRead(href: String) {
        readsInFlight.remove(href)
    }

    fun clear() {
        lengths.clear()
        readsInFlight.clear()
    }

    /** Test seam: the measured length, or the estimate when nothing was read yet. */
    fun lengthOrFallback(href: String): Int = lengths[href] ?: ESTIMATED_CHAPTER_CHARS_FALLBACK

    fun startBackgroundRead(
        href: String,
        loadResource: () -> Resource?,
    ) {
        if (!startRead(href)) return
        readers.execute {
            try {
                // loadResource may itself be I/O, so it runs here, not on the caller.
                store(href, readChapterLength(loadResource))
            } finally {
                finishRead(href)
            }
        }
    }

    /** Blocks the calling thread on the read; only for callers already off the main thread. */
    fun blockingRead(loadResource: () -> Resource?): Int = readChapterLength(loadResource)

    private fun readChapterLength(loadResource: () -> Resource?): Int =
        runCatching {
            val bytes = loadResource()?.let { runBlocking { it.read() } }?.getOrNull()
            val decodedLength =
                bytes?.let {
                    try {
                        it.decodeToString().length
                    } catch (_: Throwable) {
                        it.size
                    }
                } ?: ESTIMATED_CHAPTER_CHARS_FALLBACK
            decodedLength.takeIf { it > 0 } ?: ESTIMATED_CHAPTER_CHARS_FALLBACK
        }.getOrDefault(ESTIMATED_CHAPTER_CHARS_FALLBACK)
}

/**
 * Length of [link]'s chapter: the measured value when known, otherwise the
 * estimate while the read is started in the background.
 *
 * Never blocks the caller, so a mapper on the main thread stays a mapper.
 */
internal fun chapterCharCount(
    link: Link,
    publication: Publication,
): Int {
    val href = link.href.toString()
    ChapterCharsCache.peek(href)?.let { return it }
    ChapterCharsCache.startBackgroundRead(href) { publication.get(link) }
    return ESTIMATED_CHAPTER_CHARS_FALLBACK
}
