package com.nexo.presentation.viewmodel

import com.nexo.data.storage.CoverStorage
import com.nexo.domain.model.Book
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.event.UiEvent
import com.nexo.testutil.MainDispatcherRule
import com.nexo.testutil.TEST_USER_ID
import com.nexo.testutil.activeUserProviderFor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The snackbar copy is passed in as plain strings and the tests assert the exact
 * text: the emitted message is the ViewModel's observable behavior, and it must
 * not change as the ViewModel moves off `Context`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditBookMetadataViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sampleBook =
        Book(
            id = "book-1",
            title = "Test Book",
            author = "Author",
            coverPath = null,
            filePath = "/books/book.epub",
            format = "epub",
            updatedAtEpochMillis = 1L,
            genre = "Fiction, Adventure",
            language = "en",
            publisher = "Publisher X",
            tags = "favorites, read-later",
            publishedDate = "1937-09-21",
        )

    // ── Form seeding ───────────────────────────────────────────────────

    @Test
    fun `loads book and seeds form fields from comma separated metadata`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            coEvery { repository.observeBookById("book-1", TEST_USER_ID) } returns flowOf(sampleBook)

            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals("Test Book", state.title)
            assertEquals("Author", state.author)
            assertEquals(listOf("Fiction", "Adventure"), state.genres)
            assertEquals(listOf("favorites", "read-later"), state.tags)
            assertEquals("en", state.language)
            assertEquals("Publisher X", state.publisher)
            assertEquals("1937-09-21", state.publishedDate)
        }

    // ── Genre / tag editing ────────────────────────────────────────────

    @Test
    fun `addGenre sanitizes values and caps at 5`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            coEvery { repository.observeBookById("book-1", TEST_USER_ID) } returns flowOf(sampleBook)

            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.onGenreAdd(" Sci-fi,")
            viewModel.onGenreAdd("Drama")
            viewModel.onGenreAdd("Mystery")
            viewModel.onGenreAdd("History")
            viewModel.onGenreAdd("Poetry")
            // Over the cap — ignored
            viewModel.onGenreAdd("Romance")

            assertEquals(5, viewModel.uiState.value.genres.size)
            assertTrue(
                viewModel.uiState.value.genres
                    .contains("Sci-fi"),
            )
            assertFalse(
                viewModel.uiState.value.genres
                    .contains("Romance"),
            )
        }

    @Test
    fun `addGenre ignores duplicates case-insensitively`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            coEvery { repository.observeBookById("book-1", TEST_USER_ID) } returns flowOf(sampleBook)

            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.onGenreAdd("fiction") // duplicate of seeded "Fiction"
            assertEquals(2, viewModel.uiState.value.genres.size)
        }

    @Test
    fun `addTag caps at 10`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            coEvery { repository.observeBookById("book-1", TEST_USER_ID) } returns flowOf(sampleBook)

            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            for (i in 1..12) viewModel.onTagAdd("tag-$i")

            assertEquals(MAX_TAGS, viewModel.uiState.value.tags.size)
            assertTrue(
                viewModel.uiState.value.tags
                    .contains("tag-8"),
            )
            assertFalse(
                viewModel.uiState.value.tags
                    .contains("tag-11"),
            )
        }

    @Test
    fun `removeGenre removes matching value case-insensitively`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            coEvery { repository.observeBookById("book-1", TEST_USER_ID) } returns flowOf(sampleBook)

            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.onGenreRemove("adventure")
            assertEquals(listOf("Fiction"), viewModel.uiState.value.genres)
        }

    // ── Save ───────────────────────────────────────────────────────────

    @Test
    fun `save updates metadata with joined values and calls onSaved`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            coEvery { repository.observeBookById("book-1", TEST_USER_ID) } returns flowOf(sampleBook)
            coEvery { repository.updateBookMetadata(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(Unit)

            var saved = false
            val events = mutableListOf<UiEvent>()
            val viewModel =
                EditBookMetadataViewModel(
                    bookId = "book-1",
                    libraryRepository = repository,
                    coverStorage = mockk(),
                    savedMessage = "Metadata updated",
                    saveFailedMessage = "Could not save changes",
                    onSaved = { saved = true },
                    activeUser = activeUserProviderFor(),
                    mainDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiEvent.collect { events.add(it) }
            }
            advanceUntilIdle()

            viewModel.onTitleChange("New Title")
            viewModel.save()
            advanceUntilIdle()

            assertTrue(saved)
            val snackbar = events.firstOrNull() as UiEvent.ShowSnackbar
            assertEquals("Metadata updated", snackbar.message)
            coVerify {
                repository.updateBookMetadata(
                    bookId = "book-1",
                    userId = TEST_USER_ID,
                    title = "New Title",
                    author = "Author",
                    description = null,
                    coverPath = null,
                    genre = "Fiction, Adventure",
                    language = "en",
                    publisher = "Publisher X",
                    tags = "favorites, read-later",
                    publishedDate = "1937-09-21",
                )
            }
        }

    @Test
    fun `save failure emits the resource text when the error has no message`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            coEvery { repository.observeBookById("book-1", TEST_USER_ID) } returns flowOf(sampleBook)
            coEvery { repository.updateBookMetadata(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.failure(IllegalStateException())

            var saved = false
            val events = mutableListOf<UiEvent>()
            val viewModel =
                EditBookMetadataViewModel(
                    bookId = "book-1",
                    libraryRepository = repository,
                    coverStorage = mockk(),
                    savedMessage = "Metadata updated",
                    saveFailedMessage = "Could not save changes",
                    onSaved = { saved = true },
                    activeUser = activeUserProviderFor(),
                    mainDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiEvent.collect { events.add(it) }
            }
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()

            assertFalse(saved)
            val snackbar = events.firstOrNull() as UiEvent.ShowSnackbar
            assertEquals("Could not save changes", snackbar.message)
        }

    @Test
    fun `save failure keeps the error message when it has one`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            coEvery { repository.observeBookById("book-1", TEST_USER_ID) } returns flowOf(sampleBook)
            coEvery { repository.updateBookMetadata(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.failure(IllegalStateException("disk full"))

            val events = mutableListOf<UiEvent>()
            val viewModel =
                EditBookMetadataViewModel(
                    bookId = "book-1",
                    libraryRepository = repository,
                    coverStorage = mockk(),
                    savedMessage = "Metadata updated",
                    saveFailedMessage = "Could not save changes",
                    onSaved = {},
                    activeUser = activeUserProviderFor(),
                    mainDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiEvent.collect { events.add(it) }
            }
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()

            val snackbar = events.firstOrNull() as UiEvent.ShowSnackbar
            assertEquals("disk full", snackbar.message)
        }

    @Test
    fun `save with new cover bytes saves cover and passes its path`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            val coverStorage = mockk<CoverStorage>()
            coEvery { repository.observeBookById("book-1", TEST_USER_ID) } returns flowOf(sampleBook)
            coEvery { repository.updateBookMetadata(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(Unit)
            coEvery { coverStorage.saveCover("book-1", any()) } returns Result.success("/covers/book-1.jpg")

            val viewModel =
                EditBookMetadataViewModel(
                    bookId = "book-1",
                    libraryRepository = repository,
                    coverStorage = coverStorage,
                    savedMessage = "Metadata updated",
                    saveFailedMessage = "Could not save changes",
                    onSaved = {},
                    activeUser = activeUserProviderFor(),
                    mainDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            advanceUntilIdle()

            viewModel.onCoverSelected(null, ByteArray(4))
            viewModel.save()
            advanceUntilIdle()

            coVerify { coverStorage.saveCover("book-1", any()) }
            coVerify {
                repository.updateBookMetadata(
                    bookId = "book-1",
                    userId = TEST_USER_ID,
                    title = "Test Book",
                    author = "Author",
                    description = null,
                    coverPath = "/covers/book-1.jpg",
                    genre = "Fiction, Adventure",
                    language = "en",
                    publisher = "Publisher X",
                    tags = "favorites, read-later",
                    publishedDate = "1937-09-21",
                )
            }
        }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun createViewModel(
        repository: LibraryRepository,
        onSaved: () -> Unit = {},
    ): EditBookMetadataViewModel =
        EditBookMetadataViewModel(
            bookId = "book-1",
            libraryRepository = repository,
            coverStorage = mockk(),
            savedMessage = "Metadata updated",
            saveFailedMessage = "Could not save changes",
            onSaved = onSaved,
            activeUser = activeUserProviderFor(),
            mainDispatcher = UnconfinedTestDispatcher(),
        )
}
