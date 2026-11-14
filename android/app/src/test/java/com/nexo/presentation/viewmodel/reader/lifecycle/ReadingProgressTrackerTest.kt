package com.nexo.presentation.viewmodel.reader.lifecycle

import android.app.Application
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.viewmodel.reader.BookChapter
import com.nexo.presentation.viewmodel.reader.ReaderLifecycleState
import com.nexo.testutil.TEST_USER_ID
import com.nexo.testutil.activeUserProviderFor
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

/**
 * The reading position must SURVIVE a Continue tap.
 *
 * Two defects used to destroy it together:
 *
 * 1. `onReadiumLocatorChanged` writes `readium:<href>`, but
 *    [ReadingProgressTracker.restoreProgressForBook] only branched on `pdfpage:`
 *    and `epubcfi(`, so a row this app wrote was never recognised as
 *    restorable.
 * 2. The navigator's FIRST `currentLocator` emission arrives before the saved
 *    locator is applied, and `onReadiumLocatorChanged` persisted it
 *    unconditionally — replacing a 13% row with the cover at 0%.
 *
 * The second defect is why the bug was DESTRUCTIVE rather than merely annoying,
 * so both directions are covered: a not-yet-restored emission must not be
 * persisted, and a genuine page-one position still must be.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingProgressTrackerTest {
    @Test
    fun `readium prefixed row restores its chapter`() =
        runTest {
            val tracker = createTracker(progress = progressRow("readium:$MID_CHAPTER_HREF"))
            tracker.restoreProgressForBook(BOOK_ID)
            testScheduler.advanceUntilIdle()

            assertEquals(
                "A row written by onReadiumLocatorChanged must restore its chapter",
                MID_CHAPTER_INDEX,
                tracker.state.currentChapterIndex,
            )
        }

    @Test
    fun `readium prefixed row restores when only the filename matches`() =
        runTest {
            val tracker = createTracker(progress = progressRow("readium:../OEBPS/${MID_CHAPTER_HREF.substringAfterLast('/')}"))
            tracker.restoreProgressForBook(BOOK_ID)
            testScheduler.advanceUntilIdle()

            assertEquals(
                "A href differing only by path prefix must still resolve",
                MID_CHAPTER_INDEX,
                tracker.state.currentChapterIndex,
            )
        }

    @Test
    fun `unresolvable readium row never moves the reader`() =
        runTest {
            val tracker = createTracker(progress = progressRow("readium:OEBPS/not-a-chapter.xhtml"))
            tracker.restoreProgressForBook(BOOK_ID)
            testScheduler.advanceUntilIdle()

            assertEquals(
                "An href matching no chapter must not guess one",
                0,
                tracker.state.currentChapterIndex,
            )
        }

    @Test
    fun `epubcfi and pdfpage rows keep working`() =
        runTest {
            val tracker = createTracker(progress = progressRow("epubcfi(/6/${MID_CHAPTER_INDEX + 1})"))
            tracker.restoreProgressForBook(BOOK_ID)
            testScheduler.advanceUntilIdle()

            assertEquals(
                "The pre-existing epubcfi branch must not regress",
                MID_CHAPTER_INDEX,
                tracker.state.currentChapterIndex,
            )
        }

    @Test
    fun `first emission before the restore is not persisted`() =
        runTest {
            val updateProgress = mockk<UpdateReadingProgressUseCase>(relaxed = true)
            val tracker =
                createTracker(
                    updateProgress = updateProgress,
                    progress = progressRow("readium:$MID_CHAPTER_HREF"),
                )
            tracker.restoreProgressForBook(BOOK_ID)
            testScheduler.advanceUntilIdle()

            // The navigator's first emission: the cover, before the restored
            // locator has been applied. Persisting it destroys the row.
            tracker.onReadiumLocatorChanged(coverLocator())
            testScheduler.advanceUntilIdle()

            assertFalse("The cover emission must not open the gate", tracker.state.progressPersistenceArmed)
            coVerify(exactly = 0) { updateProgress(any(), any(), any(), any(), any()) }
            assertEquals(
                "The saved row is left untouched",
                READING_PROGRESS_PERCENT,
                tracker.state.readingProgress?.percentage,
            )
        }

    @Test
    fun `emission matching the saved position opens the gate and is persisted`() =
        runTest {
            val updateProgress = mockk<UpdateReadingProgressUseCase>(relaxed = true)
            val tracker =
                createTracker(
                    updateProgress = updateProgress,
                    progress = progressRow("readium:$MID_CHAPTER_HREF"),
                )
            tracker.restoreProgressForBook(BOOK_ID)
            testScheduler.advanceUntilIdle()

            tracker.onReadiumLocatorChanged(midBookLocator())
            testScheduler.advanceUntilIdle()

            assertTrue("The restore landing opens the gate", tracker.state.progressPersistenceArmed)
            coVerify(exactly = 1) {
                updateProgress(
                    bookId = BOOK_ID,
                    userId = TEST_USER_ID,
                    cfiLocation = "readium:$MID_CHAPTER_HREF",
                    percentage = READING_PROGRESS_PERCENT,
                    locatorJson = any(),
                )
            }
        }

    @Test
    fun `reader movement after the restore is persisted`() =
        runTest {
            val updateProgress = mockk<UpdateReadingProgressUseCase>(relaxed = true)
            val tracker = createTracker(updateProgress = updateProgress, progress = progressRow("readium:$MID_CHAPTER_HREF"))
            tracker.restoreProgressForBook(BOOK_ID)
            testScheduler.advanceUntilIdle()

            // The reader chose a new chapter: a deliberate position, not the
            // navigator's pre-restore resting place.
            tracker.onReaderMoved()
            tracker.onReadiumLocatorChanged(otherChapterLocator())
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) {
                updateProgress(
                    bookId = BOOK_ID,
                    userId = TEST_USER_ID,
                    cfiLocation = "readium:${OTHER_CHAPTER_HREF}",
                    percentage = any(),
                    locatorJson = any(),
                )
            }
        }

    @Test
    fun `first emission is not persisted even when the async progress slice is still empty`() =
        runTest {
            val updateProgress = mockk<UpdateReadingProgressUseCase>(relaxed = true)
            val repository =
                mockk<ReaderRepository> {
                    // Room has not delivered yet — the live failure: the gate must
                    // not depend on this slice.
                    every { observeProgress(BOOK_ID, any()) } returns MutableStateFlow(null)
                }
            val state = MutableStateFlow(ReaderLifecycleState(selectedBookId = BOOK_ID, chapters = chapters()))
            val tracker =
                ReadingProgressTracker(
                    application = mockk<Application>(relaxed = true),
                    state = state,
                    scope = TestScope(StandardTestDispatcher(testScheduler)),
                    mainDispatcher = StandardTestDispatcher(testScheduler),
                    readerRepository = repository,
                    activeUser = activeUserProviderFor(),
                    updateReadingProgressUseCase = updateProgress,
                )
            tracker.openOnSavedPosition(progressRow("readium:$MID_CHAPTER_HREF"))

            tracker.onReadiumLocatorChanged(coverLocator())
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { updateProgress(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a genuine page-one position with no saved row is persisted immediately`() =
        runTest {
            val updateProgress = mockk<UpdateReadingProgressUseCase>(relaxed = true)
            val tracker = createTracker(updateProgress = updateProgress, progress = null)
            tracker.restoreProgressForBook(BOOK_ID)
            testScheduler.advanceUntilIdle()

            // No saved position to protect: a reader opening page one for the
            // first time must still be recorded there.
            tracker.onReadiumLocatorChanged(coverLocator())
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) {
                updateProgress(
                    bookId = BOOK_ID,
                    userId = TEST_USER_ID,
                    cfiLocation = "readium:$COVER_HREF",
                    percentage = 0f,
                    locatorJson = any(),
                )
            }
        }

    @Test
    fun `the display is updated before the persistence gate`() =
        runTest {
            val tracker = createTracker(progress = progressRow("readium:$MID_CHAPTER_HREF"))
            tracker.restoreProgressForBook(BOOK_ID)
            testScheduler.advanceUntilIdle()

            tracker.onReadiumLocatorChanged(midBookLocator())
            testScheduler.advanceUntilIdle()

            assertEquals(
                "The gate must not freeze the reader's progress display",
                READING_PROGRESS_PERCENT,
                tracker.state.progressPercent,
            )
        }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun TestScope.createTracker(
        updateProgress: UpdateReadingProgressUseCase = mockk<UpdateReadingProgressUseCase>(relaxed = true),
        progress: ReadingProgress? = null,
    ): TestableReadingProgressTracker {
        val repository =
            mockk<ReaderRepository> {
                every { observeProgress(BOOK_ID, any()) } returns flowOf(progress)
            }
        val state = MutableStateFlow(ReaderLifecycleState(selectedBookId = BOOK_ID, chapters = chapters()))
        val tracker =
            ReadingProgressTracker(
                application = mockk<Application>(relaxed = true),
                state = state,
                scope = TestScope(StandardTestDispatcher(testScheduler)),
                mainDispatcher = StandardTestDispatcher(testScheduler),
                readerRepository = repository,
                activeUser = activeUserProviderFor(),
                updateReadingProgressUseCase = updateProgress,
            )
        // The loader hands over the row it read synchronously, before the
        // navigator can emit anything.
        tracker.openOnSavedPosition(progress)
        return TestableReadingProgressTracker(tracker, state)
    }

    /** Test-only surface exposing the tracker's otherwise write-only state. */
    private class TestableReadingProgressTracker(
        private val tracker: ReadingProgressTracker,
        private val stateFlow: MutableStateFlow<ReaderLifecycleState>,
    ) {
        val state: ReaderLifecycleState get() = stateFlow.value

        fun restoreProgressForBook(bookId: String) = tracker.restoreProgressForBook(bookId)

        fun onReadiumLocatorChanged(locator: Locator) = tracker.onReadiumLocatorChanged(locator)

        /** The reader navigated deliberately — see `ReadingProgressTracker.onReaderMoved`. */
        fun onReaderMoved() = tracker.onReaderMoved()
    }

    private fun chapters() =
        listOf(
            BookChapter(0, "cover", "Cover", COVER_HREF),
            BookChapter(1, "ch1", "Chapter 1", OTHER_CHAPTER_HREF),
            BookChapter(2, "ch2", "Chapter 2", "OEBPS/supercomunicadores-2.xhtml"),
            BookChapter(3, "ch3", "Chapter 3", "OEBPS/supercomunicadores-3.xhtml"),
            BookChapter(MID_CHAPTER_INDEX, "ch4", "Chapter 4", MID_CHAPTER_HREF),
        )

    private fun progressRow(cfiLocation: String) =
        ReadingProgress(
            id = "progress-$BOOK_ID",
            bookId = BOOK_ID,
            cfiLocation = cfiLocation,
            percentage = READING_PROGRESS_PERCENT,
            updatedAtEpochMillis = 1L,
        )

    private fun coverLocator() = locator(COVER_HREF, totalProgression = 0.0)

    private fun midBookLocator() = locator(MID_CHAPTER_HREF, totalProgression = READING_PROGRESS_PERCENT / 100.0)

    private fun otherChapterLocator() = locator(OTHER_CHAPTER_HREF, totalProgression = 0.5)

    private fun locator(
        href: String,
        totalProgression: Double,
    ): Locator {
        val locator = mockk<Locator>(relaxed = true)
        val url = mockk<Url>(relaxed = true)
        every { url.toString() } returns href
        every { locator.href } returns url
        val locations = mockk<Locator.Locations>(relaxed = true)
        every { locations.totalProgression } returns totalProgression
        every { locator.locations } returns locations
        return locator
    }

    private companion object {
        const val BOOK_ID = "book-1"
        const val COVER_HREF = "OEBPS/cover.xhtml"
        const val MID_CHAPTER_HREF = "OEBPS/supercomunicadores-5.xhtml"
        const val OTHER_CHAPTER_HREF = "OEBPS/chapter1.xhtml"
        const val MID_CHAPTER_INDEX = 4
        const val READING_PROGRESS_PERCENT = 13.3829f
    }
}
