package com.nexo.presentation.viewmodel.reader

import android.app.Application
import android.util.Log
import com.nexo.domain.model.DailyReadingActivity
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsData
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.testutil.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderLifecycleStateHolderTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    // ── Book Loading ─────────────────────────────────────────────────

    @Test
    fun `loadBook sets isLoading to true initially`() =
        runTest {
            val holder = createHolder(testScheduler, this)

            holder.loadBook("book1", "/path/to/book.epub", "epub")

            val state = holder.state.value
            assertTrue("loadBook should set isLoading=true", state.isLoading)
            assertEquals("book1", state.selectedBookId)
            assertEquals("/path/to/book.epub", state.bookFilePath)
            assertEquals("epub", state.bookFormat)
        }

    // ── PDF Document ─────────────────────────────────────────────────

    @Test
    fun `onPdfDocumentLoaded updates totalPdfPages`() =
        runTest {
            val holder = createHolder(testScheduler, this)

            holder.onPdfDocumentLoaded(42)

            assertEquals("Should set totalPdfPages=42", 42, holder.state.value.totalPdfPages)
        }

    // ── Chapter Navigation ───────────────────────────────────────────

    @Test
    fun `goToNextChapter increments if not last`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            val chapters =
                listOf(
                    BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"),
                    BookChapter(1, "ch2", "Chapter 2", "ch2.xhtml"),
                )
            holder.setChaptersForTest(chapters)

            holder.goToNextChapter()

            assertEquals("Should advance to chapter index 1", 1, holder.state.value.currentChapterIndex)
        }

    @Test
    fun `goToNextChapter does nothing if already at last chapter`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            val chapters =
                listOf(
                    BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"),
                )
            holder.setChaptersForTest(chapters)

            holder.goToNextChapter()

            assertEquals("Should remain at chapter index 0", 0, holder.state.value.currentChapterIndex)
        }

    @Test
    fun `goToPreviousChapter decrements if not first`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            val chapters =
                listOf(
                    BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"),
                    BookChapter(1, "ch2", "Chapter 2", "ch2.xhtml"),
                )
            holder.setChaptersForTest(chapters)
            holder.goToNextChapter()
            assertEquals("Should be at chapter 1 after goToNextChapter", 1, holder.state.value.currentChapterIndex)

            holder.goToPreviousChapter()

            assertEquals("Should go back to chapter 0", 0, holder.state.value.currentChapterIndex)
        }

    @Test
    fun `goToPreviousChapter does nothing if already at first chapter`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            val chapters =
                listOf(
                    BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"),
                    BookChapter(1, "ch2", "Chapter 2", "ch2.xhtml"),
                )
            holder.setChaptersForTest(chapters)

            holder.goToPreviousChapter()

            assertEquals("Should remain at chapter index 0", 0, holder.state.value.currentChapterIndex)
        }

    // ── PDF Page Navigation ──────────────────────────────────────────

    @Test
    fun `goToPdfPage with valid index updates page`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            holder.onPdfDocumentLoaded(10)

            holder.goToPdfPage(5)

            assertEquals("Should set currentPdfPage=5", 5, holder.state.value.currentPdfPage)
        }

    @Test
    fun `goToPdfPage with invalid index is no-op`() =
        runTest {
            val holder = createHolder(testScheduler, this)

            holder.goToPdfPage(5)

            assertEquals("Should remain at page 0", 0, holder.state.value.currentPdfPage)
        }

    @Test
    fun `goToPdfPage with negative index is no-op`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            holder.onPdfDocumentLoaded(10)

            holder.goToPdfPage(-1)

            assertEquals("Should remain at page 0", 0, holder.state.value.currentPdfPage)
        }

    @Test
    fun `goToPdfPage with last valid index succeeds`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            holder.onPdfDocumentLoaded(10)

            holder.goToPdfPage(9)

            assertEquals("Should set currentPdfPage=9 (last page)", 9, holder.state.value.currentPdfPage)
        }

    @Test
    fun `goToPdfPage with out-of-range index is no-op`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            holder.onPdfDocumentLoaded(10)

            holder.goToPdfPage(10)

            assertEquals("Should remain at page 0", 0, holder.state.value.currentPdfPage)
        }

    // ── Progress Drag ─────────────────────────────────────────────────

    @Test
    fun `onProgressChange with PDF calculates page index`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            // Need selectedBookId for onProgressChange to proceed
            holder.loadBook("test-book", "/path/file.pdf", "pdf")
            holder.onPdfDocumentLoaded(10)

            holder.onProgressChange(50f)

            assertEquals("Should calculate page index 5 at 50%", 5, holder.state.value.currentPdfPage)
        }

    @Test
    fun `onProgressChange at 0 percent goes to page 0`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            holder.loadBook("test-book", "/path/file.pdf", "pdf")
            holder.onPdfDocumentLoaded(10)
            // Start from page 1 to verify page 0 is reached
            holder.goToPdfPage(1)

            holder.onProgressChange(0f)

            assertEquals("Should go to page 0", 0, holder.state.value.currentPdfPage)
        }

    @Test
    fun `onProgressChange at 100 percent goes to last page`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            holder.loadBook("test-book", "/path/file.pdf", "pdf")
            holder.onPdfDocumentLoaded(10)

            holder.onProgressChange(100f)

            assertEquals("Should go to page 9 (last)", 9, holder.state.value.currentPdfPage)
        }

    @Test
    fun `onProgressChange clamps percent to 0-100 range`() =
        runTest {
            val holder = createHolder(testScheduler, this)
            holder.loadBook("test-book", "/path/file.pdf", "pdf")
            holder.onPdfDocumentLoaded(10)

            holder.onProgressChange(-10f)

            assertTrue("Progress should be clamped to 0", holder.state.value.progressPercent >= 0f)
        }

    // ── Reading Time ─────────────────────────────────────────────────

    @Test
    fun `onReaderOpened and onReaderPaused flushes reading time`() =
        runTest {
            val readingStatsRepo = mockk<ReadingStatsRepository>(relaxed = true)
            val holder =
                ReaderLifecycleStateHolder(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = mockk<ReaderRepository>(relaxed = true),
                    updateReadingProgressUseCase = mockk<UpdateReadingProgressUseCase>(relaxed = true),
                    readingStatsRepository = readingStatsRepo,
                    scope = this,
                    onChapterChanged = {},
                    onErrorEvent = {},
                    mainDispatcher = StandardTestDispatcher(testScheduler),
                )
            holder.loadBook("test-book", "/path/file.pdf", "pdf")

            holder.onReaderOpened()
            holder.onReaderPaused()
            testScheduler.advanceUntilIdle()

            coVerify { readingStatsRepo.updateReadingTime("test-book", 1) }
        }

    @Test
    fun `onReaderOpened is idempotent`() =
        runTest {
            val readingStatsRepo = mockk<ReadingStatsRepository>(relaxed = true)
            val holder =
                ReaderLifecycleStateHolder(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = mockk<ReaderRepository>(relaxed = true),
                    updateReadingProgressUseCase = mockk<UpdateReadingProgressUseCase>(relaxed = true),
                    readingStatsRepository = readingStatsRepo,
                    scope = this,
                    onChapterChanged = {},
                    onErrorEvent = {},
                    mainDispatcher = StandardTestDispatcher(testScheduler),
                )
            holder.loadBook("test-book", "/path/file.pdf", "pdf")

            holder.onReaderOpened()
            holder.onReaderOpened() // second call should be no-op

            holder.onReaderPaused()
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) { readingStatsRepo.updateReadingTime("test-book", 1) }
        }

    // ── Reading Session Recording (REQ-reading-sessions-sync-1, SCEN-sync-1/2) ──

    @Test
    fun `flushReadingTime records a reading session with the active user`() =
        runTest {
            val readingStatsRepo = FakeCountingReadingStatsRepository()
            val holder =
                ReaderLifecycleStateHolder(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = mockk<ReaderRepository>(relaxed = true),
                    updateReadingProgressUseCase = mockk<UpdateReadingProgressUseCase>(relaxed = true),
                    readingStatsRepository = readingStatsRepo,
                    scope = this,
                    onChapterChanged = {},
                    onErrorEvent = {},
                    mainDispatcher = StandardTestDispatcher(testScheduler),
                )
            holder.setActiveUserId("user-42")
            holder.loadBook("test-book", "/path/file.pdf", "pdf")

            holder.onReaderOpened()
            holder.onReaderPaused()
            testScheduler.advanceUntilIdle()

            assertEquals(1, readingStatsRepo.recordedSessions.size)
            val session = readingStatsRepo.recordedSessions.single()
            assertEquals("test-book", session.bookId)
            assertEquals("user-42", session.userId)
            assertEquals(1, session.durationMinutes)
            assertTrue("startTimeEpochMillis must be captured before the interval reset", session.startTimeEpochMillis > 0L)
        }

    @Test
    fun `flushReadingTime records blank user session when no active user`() =
        runTest {
            val readingStatsRepo = FakeCountingReadingStatsRepository()
            val holder =
                ReaderLifecycleStateHolder(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = mockk<ReaderRepository>(relaxed = true),
                    updateReadingProgressUseCase = mockk<UpdateReadingProgressUseCase>(relaxed = true),
                    readingStatsRepository = readingStatsRepo,
                    scope = this,
                    onChapterChanged = {},
                    onErrorEvent = {},
                    mainDispatcher = StandardTestDispatcher(testScheduler),
                )
            holder.loadBook("test-book", "/path/file.pdf", "pdf")

            holder.onReaderOpened()
            holder.onReaderPaused()
            testScheduler.advanceUntilIdle()

            assertEquals(1, readingStatsRepo.recordedSessions.size)
            assertEquals("", readingStatsRepo.recordedSessions.single().userId)
        }

    // ── Chapter callback ─────────────────────────────────────────────

    @Test
    fun `goToNextChapter triggers onChapterChanged callback`() =
        runTest {
            var callbackCount = 0
            val holder =
                ReaderLifecycleStateHolder(
                    application = mockk<Application>(relaxed = true),
                    readerRepository = mockk<ReaderRepository>(relaxed = true),
                    updateReadingProgressUseCase = mockk<UpdateReadingProgressUseCase>(relaxed = true),
                    readingStatsRepository = mockk<ReadingStatsRepository>(relaxed = true),
                    scope = this,
                    onChapterChanged = { callbackCount++ },
                    onErrorEvent = {},
                    mainDispatcher = StandardTestDispatcher(testScheduler),
                )
            val chapters =
                listOf(
                    BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"),
                    BookChapter(1, "ch2", "Chapter 2", "ch2.xhtml"),
                )
            holder.setChaptersForTest(chapters)

            holder.goToNextChapter()

            assertEquals("onChapterChanged should be called", 1, callbackCount)
        }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun createHolder(
        scheduler: TestCoroutineScheduler,
        scope: CoroutineScope,
    ): ReaderLifecycleStateHolder {
        val dispatcher = StandardTestDispatcher(scheduler)
        return ReaderLifecycleStateHolder(
            application = mockk<Application>(relaxed = true),
            readerRepository = mockk<ReaderRepository>(relaxed = true),
            updateReadingProgressUseCase = mockk<UpdateReadingProgressUseCase>(relaxed = true),
            readingStatsRepository = mockk<ReadingStatsRepository>(relaxed = true),
            scope = scope,
            onChapterChanged = {},
            onErrorEvent = {},
            mainDispatcher = dispatcher,
        )
    }

    /**
     * Counting fake for the session-recording path (REQ-reading-sessions-sync-1):
     * records [ReadingStatsRepository.recordReadingSession] invocations without
     * touching a real DB.
     */
    private class FakeCountingReadingStatsRepository : ReadingStatsRepository {
        data class RecordedSession(
            val bookId: String,
            val startTimeEpochMillis: Long,
            val durationMinutes: Int,
            val userId: String,
        )

        val recordedSessions = mutableListOf<RecordedSession>()

        override fun observeStats(bookId: String): Flow<ReadingStatsData?> = MutableStateFlow(null)

        override fun observeTotalTime(): Flow<Long> = MutableStateFlow(0L)

        override fun observeBookStats(): Flow<List<ReadingStatsData>> = MutableStateFlow(emptyList())

        override suspend fun getDailyActivity(userId: String?): List<DailyReadingActivity> = emptyList()

        override suspend fun updateReadingTime(
            bookId: String,
            additionalMinutes: Long,
        ) = Unit

        override suspend fun deleteStats(bookId: String) = Unit

        override suspend fun recordReadingSession(
            bookId: String,
            startTimeEpochMillis: Long,
            durationMinutes: Int,
            userId: String,
        ) {
            recordedSessions.add(
                RecordedSession(bookId, startTimeEpochMillis, durationMinutes, userId),
            )
        }
    }
}
