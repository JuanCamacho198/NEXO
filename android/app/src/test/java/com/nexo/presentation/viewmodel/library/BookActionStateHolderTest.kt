package com.nexo.presentation.viewmodel.library

import android.content.Context
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.domain.repository.LibraryRepository
import com.nexo.presentation.UiEvent
import com.nexo.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookActionStateHolderTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sampleBook =
        Book(
            id = "book-1",
            title = "Test Book",
            author = "Author",
            coverPath = null,
            filePath = "content://books/book.epub",
            format = "epub",
            updatedAtEpochMillis = 1L,
        )

    private val sampleBookPdf =
        Book(
            id = "book-pdf-1",
            title = "PDF Book",
            author = "Author",
            coverPath = null,
            filePath = "content://books/book.pdf",
            format = "pdf",
            updatedAtEpochMillis = 1L,
        )

    // ── Default state ──────────────────────────────────────────────────

    @Test
    fun `default state is all nulls`() {
        val scheduler = TestCoroutineScheduler()
        val (holder, _, _) = createHolder(scheduler)

        assertNull(holder.state.value.bookToDelete)
        assertNull(holder.state.value.bookToShare)
    }

    // ── Delete ─────────────────────────────────────────────────────────

    @Test
    fun `requestDeleteBook sets bookToDelete`() {
        val scheduler = TestCoroutineScheduler()
        val (holder, _, _) = createHolder(scheduler)

        holder.requestDeleteBook(sampleBook)
        assertNotNull(holder.state.value.bookToDelete)
        assertEquals(
            "book-1",
            holder.state.value.bookToDelete
                ?.id,
        )
    }

    @Test
    fun `dismissDeleteDialog clears bookToDelete`() {
        val scheduler = TestCoroutineScheduler()
        val (holder, _, _) = createHolder(scheduler)

        holder.requestDeleteBook(sampleBook)
        assertNotNull(holder.state.value.bookToDelete)

        holder.dismissDeleteDialog()
        assertNull(holder.state.value.bookToDelete)
    }

    @Test
    fun `confirmDeleteBook clears bookToDelete and emits snackbar on success`() =
        runTest {
            val scheduler = TestCoroutineScheduler()
            val dispatcher = UnconfinedTestDispatcher(scheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val mockRepository = mockk<LibraryRepository>()

            coEvery { mockRepository.deleteBook(any()) } returns Result.success(Unit)

            var emittedEvent: UiEvent? = null
            val holder =
                createActionHolder(
                    scope = scope,
                    dispatcher = dispatcher,
                    libraryRepository = mockRepository,
                    onUiEvent = { emittedEvent = it },
                )

            holder.requestDeleteBook(sampleBook)
            holder.confirmDeleteBook()
            advanceUntilIdle()

            assertNull("bookToDelete should be cleared", holder.state.value.bookToDelete)
            assertTrue("Should emit snackbar", emittedEvent is UiEvent.ShowSnackbar)
        }

    @Test
    fun `confirmDeleteBook emits error snackbar on failure`() =
        runTest {
            val scheduler = TestCoroutineScheduler()
            val dispatcher = UnconfinedTestDispatcher(scheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val mockRepository = mockk<LibraryRepository>()

            coEvery { mockRepository.deleteBook(any()) } returns Result.failure(IllegalStateException("delete failed"))

            var emittedEvent: UiEvent? = null
            val holder =
                createActionHolder(
                    scope = scope,
                    dispatcher = dispatcher,
                    libraryRepository = mockRepository,
                    onUiEvent = { emittedEvent = it },
                )

            holder.requestDeleteBook(sampleBook)
            holder.confirmDeleteBook()
            advanceUntilIdle()

            assertNull("bookToDelete should be cleared", holder.state.value.bookToDelete)
            assertTrue("Should emit snackbar even on failure", emittedEvent is UiEvent.ShowSnackbar)
        }

    @Test
    fun `confirmDeleteBook is no-op when bookToDelete is null`() =
        runTest {
            val scheduler = TestCoroutineScheduler()
            val dispatcher = UnconfinedTestDispatcher(scheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val mockRepository = mockk<LibraryRepository>()

            var emissionCount = 0
            val holder =
                createActionHolder(
                    scope = scope,
                    dispatcher = dispatcher,
                    libraryRepository = mockRepository,
                    onUiEvent = { emissionCount++ },
                )

            // No bookToDelete set
            holder.confirmDeleteBook()
            advanceUntilIdle()

            assertEquals("No event should be emitted", 0, emissionCount)
        }

    // ── Status changes ─────────────────────────────────────────────────

    @Test
    fun `onMenuMarkCompleted emits snackbar on success`() =
        runTest {
            val scheduler = TestCoroutineScheduler()
            val dispatcher = UnconfinedTestDispatcher(scheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val mockRepository = mockk<LibraryRepository>()
            val mockContext = mockk<Context>()

            coEvery { mockRepository.updateBookStatus(any(), any()) } returns Result.success(Unit)
            every { mockContext.getString(R.string.library_snackbar_marked_completed) } returns "Marked as completed"

            var emittedEvent: UiEvent? = null
            val holder =
                createActionHolder(
                    scope = scope,
                    dispatcher = dispatcher,
                    libraryRepository = mockRepository,
                    context = mockContext,
                    onUiEvent = { emittedEvent = it },
                )

            holder.onMenuMarkCompleted(sampleBook)
            advanceUntilIdle()

            assertTrue("Should emit snackbar", emittedEvent is UiEvent.ShowSnackbar)
            assertEquals("Marked as completed", (emittedEvent as UiEvent.ShowSnackbar).message)
        }

    @Test
    fun `onMenuMarkPlanToRead emits snackbar on success`() =
        runTest {
            val scheduler = TestCoroutineScheduler()
            val dispatcher = UnconfinedTestDispatcher(scheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val mockRepository = mockk<LibraryRepository>()
            val mockContext = mockk<Context>()

            coEvery { mockRepository.updateBookStatus(any(), any()) } returns Result.success(Unit)
            every { mockContext.getString(R.string.library_snackbar_marked_plan_to_read) } returns "Marked as plan to read"

            var emittedEvent: UiEvent? = null
            val holder =
                createActionHolder(
                    scope = scope,
                    dispatcher = dispatcher,
                    libraryRepository = mockRepository,
                    context = mockContext,
                    onUiEvent = { emittedEvent = it },
                )

            holder.onMenuMarkPlanToRead(sampleBook)
            advanceUntilIdle()

            assertTrue("Should emit snackbar", emittedEvent is UiEvent.ShowSnackbar)
            assertEquals("Marked as plan to read", (emittedEvent as UiEvent.ShowSnackbar).message)
        }

    // ── Share ──────────────────────────────────────────────────────────

    @Test
    fun `onMenuShare sets bookToShare then clears and emits ShareFile`() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val mockRepository = mockk<LibraryRepository>()

            var emittedEvent: UiEvent? = null
            val holder =
                createActionHolder(
                    scope = scope,
                    dispatcher = dispatcher,
                    libraryRepository = mockRepository,
                    onUiEvent = { emittedEvent = it },
                )

            holder.onMenuShare(sampleBook)

            // Immediately after call, bookToShare should be set
            assertNotNull("bookToShare should be set immediately", holder.state.value.bookToShare)
            assertEquals(
                "book-1",
                holder.state.value.bookToShare
                    ?.id,
            )

            advanceUntilIdle()

            // After advancement, bookToShare should be cleared and event emitted
            assertNull("bookToShare should be cleared after emit", holder.state.value.bookToShare)
            assertTrue("Should emit ShareFile event", emittedEvent is UiEvent.ShareFile)
            assertEquals("content://books/book.epub", (emittedEvent as UiEvent.ShareFile).filePath)
            assertEquals("application/epub+zip", (emittedEvent as UiEvent.ShareFile).mimeType)
        }

    @Test
    fun `onMenuShare with PDF uses correct mime type`() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val mockRepository = mockk<LibraryRepository>()

            var emittedEvent: UiEvent? = null
            val holder =
                createActionHolder(
                    scope = scope,
                    dispatcher = dispatcher,
                    libraryRepository = mockRepository,
                    onUiEvent = { emittedEvent = it },
                )

            holder.onMenuShare(sampleBookPdf)
            advanceUntilIdle()

            assertTrue("Should emit ShareFile event", emittedEvent is UiEvent.ShareFile)
            assertEquals("application/pdf", (emittedEvent as UiEvent.ShareFile).mimeType)
        }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun createHolder(scheduler: TestCoroutineScheduler): TestComponents {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val mockRepository = mockk<LibraryRepository>()
        val mockContext = mockk<Context>()

        return TestComponents(
            holder =
                BookActionStateHolder(
                    libraryRepository = mockRepository,
                    appContext = mockContext,
                    scope = scope,
                    onUiEvent = {},
                    mainDispatcher = dispatcher,
                ),
            mockRepository = mockRepository,
            mockContext = mockContext,
        )
    }

    private fun createActionHolder(
        scope: CoroutineScope,
        dispatcher: TestDispatcher,
        libraryRepository: LibraryRepository = mockk(),
        context: Context = mockk(),
        onUiEvent: (UiEvent) -> Unit,
    ): BookActionStateHolder =
        BookActionStateHolder(
            libraryRepository = libraryRepository,
            appContext = context,
            scope = scope,
            onUiEvent = onUiEvent,
            mainDispatcher = dispatcher,
        )

    private data class TestComponents(
        val holder: BookActionStateHolder,
        val mockRepository: LibraryRepository,
        val mockContext: Context,
    )
}
