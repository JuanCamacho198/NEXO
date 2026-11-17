package com.nexo.presentation.screen.readium

import com.nexo.domain.model.Highlight
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.resource.Resource
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The chapter length that feeds a precise-CFI highlight is a full EPUB
 * resource read. It must never happen on the thread that maps highlights:
 * that caller is a Compose `LaunchedEffect`, i.e. the main thread.
 *
 * Two behaviours are proven here:
 * 1. the caller does not wait for the read (no blocking read on its thread), and
 * 2. the read still happens, on a worker thread, at most once per [Link], so
 *    the length stays correct instead of silently degrading to the estimate.
 */
@RunWith(RobolectricTestRunner::class)
class ReadiumChapterCharsOffThreadTest {
    private class SlowPublication(
        private val chapters: Map<String, String>,
        private val gate: CountDownLatch,
    ) {
        val readThreads = CopyOnWriteArrayList<String>()
        var readCount = 0
            private set

        private val publicationMock: Publication = mockk(relaxed = true)

        fun asPublication(): Publication = publicationMock

        fun readCountNow(): Int = readCount

        fun snapshotThreads(): List<String> = readThreads.toList()

        init {
            every { publicationMock.get(any<Link>()) } answers {
                val link = firstArg<Link>()
                val body = chapters[link.href.toString()] ?: ""
                val resource: Resource = recordingResource(body)
                resource
            }
        }

        private fun recordingResource(body: String): Resource {
            val resource: Resource = mockk(relaxed = true)
            coEvery { resource.read() } coAnswers {
                readThreads += Thread.currentThread().name
                readCount += 1
                gate.await()
                Try.success(body.toByteArray())
            }
            return resource
        }
    }

    private fun link(href: String): Link = Link(href = Url(href)!!, mediaType = null)

    private fun cfiHighlight(
        id: String,
        cfi: String,
    ): Highlight =
        Highlight(
            id = id,
            bookId = "book",
            cfiRange = cfi,
            textContent = "",
            note = null,
            color = "#FFAA00",
            updatedAtEpochMillis = 0L,
            deletedAtEpochMillis = null,
        )

    @Test
    fun highlightsToDecorations_doesNotBlockCallerOnChapterRead() {
        ChapterCharsCache.clear()
        val gate = CountDownLatch(1)
        val publication = SlowPublication(mapOf("chap1.xhtml" to "x".repeat(8000)), gate)
        val readingOrder = listOf(link("chap1.xhtml"))
        val highlights =
            listOf(
                cfiHighlight("h1", "epubcfi(/6/1!/4/2,/1:100,/1:120)"),
                cfiHighlight("h2", "epubcfi(/6/1!/4/2,/1:200,/1:220)"),
            )

        val caller = AtomicReference<Thread>()
        val decorations = AtomicReference<List<Any>>()

        val worker =
            thread(name = "decor-caller") {
                caller.set(Thread.currentThread())
                decorations.set(
                    highlightsToDecorations(highlights, readingOrder, publication.asPublication()),
                )
            }

        awaitReadStart(publication)

        // The caller must finish while the resource read is still gated.
        worker.join(MAX_WAIT_MS)
        assertEquals(
            "highlightsToDecorations blocked its caller on a chapter read",
            false,
            worker.isAlive,
        )
        assertEquals("the first pass cannot have a measurement yet", 2, decorations.get().size)
        assertEquals(10000, ChapterCharsCache.lengthOrFallback("chap1.xhtml"))

        // The read is deferred, not dropped: release it and it lands in the cache.
        gate.countDown()
        awaitRead(publication)
        awaitLength("chap1.xhtml", 8000)

        assertEquals(
            "chapter read must not run on the caller thread (${caller.get()?.name})",
            emptyList<String>(),
            publication.readThreads.filter { it == caller.get()?.name },
        )
        assertEquals("one read per distinct chapter", 1, publication.readCount)
    }

    @Test
    fun highlightsToDecorations_chapterLengthStaysExactPerChapter() {
        ChapterCharsCache.clear()
        val gate = CountDownLatch(0)
        val long = "y".repeat(12_345)
        val short = "z".repeat(4_000)
        val publication = SlowPublication(mapOf("long.xhtml" to long, "short.xhtml" to short), gate)
        val readingOrder = listOf(link("long.xhtml"), link("short.xhtml"))

        val decorations =
            highlightsToDecorations(listOf(cfiHighlight("h1", "epubcfi(/6/1!/4/2,/1:11,/1:20)")), readingOrder, publication.asPublication())
        assertEquals(1, decorations.size)

        awaitLength("long.xhtml", 12_345)
        // Second pass, as a reader produces once the highlight changes or the
        // decoration retry loop runs: now the measurement is available.
        val first = decorationsOf(readingOrder, publication, "epubcfi(/6/1!/4/2,/1:1234,/1:1240)")
        assertEquals(1234.0 / 12_345.0, first, PROGRESSION_EPSILON)

        // A chapter is only read once a highlight lands in it: this pass is the
        // first for chapter 2, so it starts the read and estimates.
        val secondEstimated = decorationsOf(readingOrder, publication, "epubcfi(/6/2!/4/2,/1:2000,/1:2010)")
        assertEquals(2000.0 / ESTIMATED_CHAPTER_CHARS_FALLBACK, secondEstimated, PROGRESSION_EPSILON)

        awaitLength("short.xhtml", 4_000)
        val second = decorationsOf(readingOrder, publication, "epubcfi(/6/2!/4/2,/1:2000,/1:2010)")
        assertEquals(2000.0 / 4_000.0, second, PROGRESSION_EPSILON)
    }

    private fun decorationsOf(
        readingOrder: List<Link>,
        publication: SlowPublication,
        cfi: String,
    ): Double {
        val decorations =
            highlightsToDecorations(
                listOf(cfiHighlight("h-probe", cfi)),
                readingOrder,
                publication.asPublication(),
            )
        assertEquals(1, decorations.size)
        return decorations[0].locator.locations.progression!!
    }

    private fun awaitLength(
        href: String,
        expected: Int,
    ) {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ChapterCharsCache.peek(href) == expected) return
            Thread.sleep(5)
        }
        throw AssertionError("$href length still ${ChapterCharsCache.peek(href)}, expected $expected")
    }

    private fun awaitReadStart(publication: SlowPublication) {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (publication.readCountNow() >= 1) return
            Thread.sleep(5)
        }
        throw AssertionError("the chapter read never started")
    }

    private fun awaitRead(
        publication: SlowPublication,
        expectedReads: Int = 1,
    ) {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (publication.readCountNow() >= expectedReads) return
            Thread.sleep(5)
        }
        throw AssertionError("chapter read never happened (readCount=${publication.readCountNow()})")
    }

    private companion object {
        const val MAX_WAIT_MS = 3_000L
        const val PROGRESSION_EPSILON = 1e-9
    }
}
