package com.nexo.presentation.viewmodel.library

import com.nexo.domain.model.Book
import com.nexo.domain.model.BookImportRequest
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.usecase.ImportEpubBookUseCase
import com.nexo.presentation.viewmodel.LibraryImportEvent
import com.nexo.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class BookImportStateHolderTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ── Default state ──────────────────────────────────────────────────

    @Test
    fun `default state is not importing`() {
        val scheduler = TestCoroutineScheduler()
        val (holder, _, _, _) = createHolder(scheduler)

        assertFalse(holder.state.value.isImporting)
    }

    // ── Import EPUB success ────────────────────────────────────────────

    @Test
    fun `importBookFromEpub sets isImporting then emits Success`() =
        runTest(StandardTestDispatcher()) {
            testScheduler.advanceUntilIdle() // let ViewModel init settle

            val mockUseCase = mockk<ImportEpubBookUseCase>()
            val mockRepository = mockk<LibraryRepository>(relaxed = true)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())

            coEvery {
                mockUseCase(any<BookImportRequest>(), any<suspend () -> InputStream?>())
            } returns
                Result.success(
                    Book(
                        id = "epub-1",
                        title = "Imported EPUB",
                        author = null,
                        coverPath = null,
                        filePath = "content://books/book.epub",
                        format = "epub",
                        updatedAtEpochMillis = 1L,
                    ),
                )

            var emittedEvent: LibraryImportEvent? = null
            val holder =
                BookImportStateHolder(
                    importEpubBookUseCase = mockUseCase,
                    libraryRepository = mockRepository,
                    scope = scope,
                    onImportEvent = { emittedEvent = it },
                    mainDispatcher = dispatcher,
                )

            holder.importBookFromEpub(
                sourcePath = "content://books/book.epub",
                fallbackTitle = "Imported EPUB",
                inputStreamProvider = { mockk() },
            )

            assertTrue("isImporting should be true immediately", holder.state.value.isImporting)
            advanceUntilIdle()
            assertFalse("isImporting should be false after completion", holder.state.value.isImporting)

            assertTrue("Should emit Success event", emittedEvent is LibraryImportEvent.Success)
            assertEquals("Imported EPUB", (emittedEvent as LibraryImportEvent.Success).title)
        }

    // ── Import EPUB failure ────────────────────────────────────────────

    @Test
    fun `importBookFromEpub emits Failure on error`() =
        runTest(StandardTestDispatcher()) {
            testScheduler.advanceUntilIdle()

            val mockUseCase = mockk<ImportEpubBookUseCase>()
            val mockRepository = mockk<LibraryRepository>(relaxed = true)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())

            coEvery {
                mockUseCase(any<BookImportRequest>(), any<suspend () -> InputStream?>())
            } returns Result.failure(IllegalStateException("bad epub"))

            var emittedEvent: LibraryImportEvent? = null
            val holder =
                BookImportStateHolder(
                    importEpubBookUseCase = mockUseCase,
                    libraryRepository = mockRepository,
                    scope = scope,
                    onImportEvent = { emittedEvent = it },
                    mainDispatcher = dispatcher,
                )

            holder.importBookFromEpub(
                sourcePath = "content://books/bad.epub",
                fallbackTitle = "Bad EPUB",
                inputStreamProvider = { null },
            )

            advanceUntilIdle()

            assertFalse("isImporting should be false after failure", holder.state.value.isImporting)
            assertTrue("Should emit Failure event", emittedEvent is LibraryImportEvent.Failure)
            assertNotNull((emittedEvent as LibraryImportEvent.Failure).message)
        }

    // ── Import PDF success ─────────────────────────────────────────────

    @Test
    fun `importPdfBook sets isImporting then emits Success`() =
        runTest(StandardTestDispatcher()) {
            testScheduler.advanceUntilIdle()

            val mockUseCase = mockk<ImportEpubBookUseCase>()
            val mockRepository = mockk<LibraryRepository>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())

            coEvery {
                mockRepository.importBookFromPdf(any<BookImportRequest>(), any<File>())
            } returns
                Result.success(
                    Book(
                        id = "pdf-1",
                        title = "Imported PDF",
                        author = null,
                        coverPath = null,
                        filePath = "content://books/book.pdf",
                        format = "pdf",
                        updatedAtEpochMillis = 1L,
                    ),
                )

            var emittedEvent: LibraryImportEvent? = null
            val holder =
                BookImportStateHolder(
                    importEpubBookUseCase = mockUseCase,
                    libraryRepository = mockRepository,
                    scope = scope,
                    onImportEvent = { emittedEvent = it },
                    mainDispatcher = dispatcher,
                )

            holder.importPdfBook(
                sourcePath = "content://books/book.pdf",
                fallbackTitle = "Imported PDF",
                pdfFile = File("/tmp/book.pdf"),
            )

            assertTrue("isImporting should be true immediately", holder.state.value.isImporting)
            advanceUntilIdle()
            assertFalse("isImporting should be false after completion", holder.state.value.isImporting)

            assertTrue("Should emit Success event", emittedEvent is LibraryImportEvent.Success)
            assertEquals("Imported PDF", (emittedEvent as LibraryImportEvent.Success).title)
        }

    // ── Import PDF failure ─────────────────────────────────────────────

    @Test
    fun `importPdfBook emits Failure on error`() =
        runTest(StandardTestDispatcher()) {
            testScheduler.advanceUntilIdle()

            val mockUseCase = mockk<ImportEpubBookUseCase>()
            val mockRepository = mockk<LibraryRepository>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + SupervisorJob())

            coEvery {
                mockRepository.importBookFromPdf(any<BookImportRequest>(), any<File>())
            } returns Result.failure(IllegalStateException("bad pdf"))

            var emittedEvent: LibraryImportEvent? = null
            val holder =
                BookImportStateHolder(
                    importEpubBookUseCase = mockUseCase,
                    libraryRepository = mockRepository,
                    scope = scope,
                    onImportEvent = { emittedEvent = it },
                    mainDispatcher = dispatcher,
                )

            holder.importPdfBook(
                sourcePath = "content://books/bad.pdf",
                fallbackTitle = "Bad PDF",
                pdfFile = File("/tmp/bad.pdf"),
            )

            advanceUntilIdle()

            assertFalse("isImporting should be false after failure", holder.state.value.isImporting)
            assertTrue("Should emit Failure event", emittedEvent is LibraryImportEvent.Failure)
            assertNotNull((emittedEvent as LibraryImportEvent.Failure).message)
        }

    // ── Import EPUB — use case throws (stuck-overlay hardening) ───────

    @Test
    fun `importBookFromEpub resets state to Idle when use case throws`() =
        runTest(StandardTestDispatcher()) {
            testScheduler.advanceUntilIdle()

            val mockUseCase = mockk<ImportEpubBookUseCase>()
            val mockRepository = mockk<LibraryRepository>(relaxed = true)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val thrown = mutableListOf<Throwable>()
            val scope =
                CoroutineScope(
                    dispatcher + SupervisorJob() +
                        CoroutineExceptionHandler { _, throwable -> thrown += throwable },
                )

            coEvery {
                mockUseCase(any<BookImportRequest>(), any<suspend () -> InputStream?>())
            } throws IllegalStateException("use case exploded")

            var emittedEvent: LibraryImportEvent? = null
            val holder =
                BookImportStateHolder(
                    importEpubBookUseCase = mockUseCase,
                    libraryRepository = mockRepository,
                    scope = scope,
                    onImportEvent = { emittedEvent = it },
                    mainDispatcher = dispatcher,
                )

            holder.importBookFromEpub(
                sourcePath = "content://books/boom.epub",
                fallbackTitle = "Boom EPUB",
                inputStreamProvider = { null },
            )

            assertTrue("isImporting should be true immediately", holder.state.value.isImporting)
            advanceUntilIdle()

            assertEquals("use case exception must be delivered", 1, thrown.size)
            assertTrue(thrown.single() is IllegalStateException)
            assertFalse("state must return to Idle even when the use case throws", holder.state.value.isImporting)
            assertEquals("no success/failure event may be emitted on a thrown use case", null, emittedEvent)
        }

    // ── Import PDF — repository throws (stuck-overlay hardening) ──────

    @Test
    fun `importPdfBook resets state to Idle when repository throws`() =
        runTest(StandardTestDispatcher()) {
            testScheduler.advanceUntilIdle()

            val mockUseCase = mockk<ImportEpubBookUseCase>()
            val mockRepository = mockk<LibraryRepository>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val thrown = mutableListOf<Throwable>()
            val scope =
                CoroutineScope(
                    dispatcher + SupervisorJob() +
                        CoroutineExceptionHandler { _, throwable -> thrown += throwable },
                )

            coEvery {
                mockRepository.importBookFromPdf(any<BookImportRequest>(), any<File>())
            } throws IllegalStateException("repository exploded")

            var emittedEvent: LibraryImportEvent? = null
            val holder =
                BookImportStateHolder(
                    importEpubBookUseCase = mockUseCase,
                    libraryRepository = mockRepository,
                    scope = scope,
                    onImportEvent = { emittedEvent = it },
                    mainDispatcher = dispatcher,
                )

            holder.importPdfBook(
                sourcePath = "content://books/boom.pdf",
                fallbackTitle = "Boom PDF",
                pdfFile = File("/tmp/boom.pdf"),
            )

            assertTrue("isImporting should be true immediately", holder.state.value.isImporting)
            advanceUntilIdle()

            assertEquals("repository exception must be delivered", 1, thrown.size)
            assertTrue(thrown.single() is IllegalStateException)
            assertFalse("state must return to Idle even when the repository throws", holder.state.value.isImporting)
            assertEquals("no success/failure event may be emitted on a thrown repository call", null, emittedEvent)
        }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun createHolder(scheduler: TestCoroutineScheduler): TestComponents {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val mockUseCase = mockk<ImportEpubBookUseCase>()
        val mockRepository = mockk<LibraryRepository>(relaxed = true)
        return TestComponents(
            holder =
                BookImportStateHolder(
                    importEpubBookUseCase = mockUseCase,
                    libraryRepository = mockRepository,
                    scope = scope,
                    onImportEvent = {},
                    mainDispatcher = dispatcher,
                ),
            mockUseCase = mockUseCase,
            mockRepository = mockRepository,
            scope = scope,
        )
    }

    private data class TestComponents(
        val holder: BookImportStateHolder,
        val mockUseCase: ImportEpubBookUseCase,
        val mockRepository: LibraryRepository,
        val scope: CoroutineScope,
    )
}
