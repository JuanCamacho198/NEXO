package com.nexo.presentation.viewmodel.reader

import android.app.Application
import android.util.Log
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.viewmodel.reader.lifecycle.pdfStartPageIndex
import com.nexo.testutil.MainDispatcherRule
import com.nexo.testutil.activeUserProviderFor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfPageReportTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val useCase = mockk<UpdateReadingProgressUseCase>(relaxed = true)
    private val navigations = mutableListOf<Int>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        coEvery { useCase(any(), any(), any(), any(), any()) } returns Unit
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `reported page sets zero-based page and persists pdfpage with locator position`() =
        runTest {
            val holder = openPdf(testScheduler, this, pages = 8)

            holder.onPdfPageReported(pageIndex = 4, totalPages = 8)
            advanceUntilIdle()

            assertEquals(4, holder.state.value.currentPdfPage)
            val json = slot<String?>()
            coVerify {
                useCase(
                    bookId = "book1",
                    userId = any(),
                    cfiLocation = "pdfpage:4",
                    percentage = 62.5f,
                    locatorJson = captureNullable(json),
                )
            }
            val locations = JSONObject(json.captured!!).getJSONObject("locations")
            assertEquals(5, locations.getInt("position"))
            assertEquals(0.5, locations.getDouble("totalProgression"), 1e-9)
        }

    @Test
    fun `reported page does not emit navigation`() =
        runTest {
            val holder = openPdf(testScheduler, this, pages = 8)

            holder.onPdfPageReported(pageIndex = 3, totalPages = 8)
            advanceUntilIdle()

            assertTrue(navigations.isEmpty())
        }

    @Test
    fun `reported page refreshes the page label`() =
        runTest {
            val holder = openPdf(testScheduler, this, pages = 8)

            holder.onPdfPageReported(pageIndex = 4, totalPages = 8)

            assertEquals("5 / 8", holder.state.value.progressLabel)
        }

    @Test
    fun `out of range or unchanged reports do not persist`() =
        runTest {
            val holder = openPdf(testScheduler, this, pages = 8)

            holder.onPdfPageReported(pageIndex = 8, totalPages = 8)
            holder.onPdfPageReported(pageIndex = -1, totalPages = 8)
            holder.onPdfPageReported(pageIndex = 0, totalPages = 8)
            advanceUntilIdle()

            coVerify(exactly = 0) { useCase(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `go to pdf page beyond the first emits a locator with that position`() =
        runTest {
            val holder = openPdf(testScheduler, this, pages = 8)

            holder.goToPdfPage(6)
            advanceUntilIdle()

            assertEquals(1, navigations.size)
            assertEquals(6, navigations.single())
            assertEquals(6, holder.state.value.currentPdfPage)
        }

    @Test
    fun `go to pdf page out of range emits nothing`() =
        runTest {
            val holder = openPdf(testScheduler, this, pages = 8)

            holder.goToPdfPage(8)
            advanceUntilIdle()

            assertTrue(navigations.isEmpty())
        }

    @Test
    fun `start page comes from pdfpage rows only`() {
        assertEquals(4, pdfStartPageIndex("pdfpage:4"))
        assertEquals(0, pdfStartPageIndex("readium:document.pdf"))
        assertEquals(0, pdfStartPageIndex("epubcfi(/6/4)"))
        assertEquals(0, pdfStartPageIndex("pdfpage:x"))
        assertEquals(0, pdfStartPageIndex("pdfpage:-3"))
        assertEquals(0, pdfStartPageIndex(null))
    }

    private fun openPdf(
        scheduler: TestCoroutineScheduler,
        scope: CoroutineScope,
        pages: Int,
    ): ReaderLifecycleStateHolder {
        val holder =
            ReaderLifecycleStateHolder(
                application = mockk<Application>(relaxed = true),
                readerRepository = mockk<ReaderRepository>(relaxed = true),
                updateReadingProgressUseCase = useCase,
                readingStatsRepository = mockk<ReadingStatsRepository>(relaxed = true),
                activeUser = activeUserProviderFor(),
                scope = scope,
                onChapterChanged = {},
                onErrorEvent = {},
                onNavigateToPdfPage = { navigations += it },
                mainDispatcher = StandardTestDispatcher(scheduler),
            )
        holder.loadBook("book1", "/path/book.pdf", "pdf")
        holder.onPdfDocumentLoaded(pages)
        return holder
    }
}
