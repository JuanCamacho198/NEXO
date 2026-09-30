package com.nexo.presentation.viewmodel

import android.content.Context
import com.nexo.R
import com.nexo.data.storage.CoverStorage
import com.nexo.domain.model.Book
import com.nexo.domain.repository.LibraryRepository
import com.nexo.presentation.UiEvent
import com.nexo.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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

    // ── Sanitization helpers (REQ-data-model-8) ────────────────────────

    @Test
    fun `parseChipList splits comma separated values and drops blanks`() {
        assertEquals(listOf("Fiction", "Adventure"), parseChipList("Fiction, Adventure"))
        assertEquals(emptyList<String>(), parseChipList(null))
        assertEquals(emptyList<String>(), parseChipList("  , ,  "))
        assertEquals(listOf("a", "b"), parseChipList("a,,b"))
    }

    @Test
    fun `sanitizeChipValue strips commas and trims`() {
        assertEquals("Fiction", sanitizeChipValue("  Fic,tion,  "))
        assertNull(sanitizeChipValue(" , , "))
        assertNull(sanitizeChipValue(""))
    }

    @Test
    fun `sanitizeChipList dedupes case-insensitively and caps at max`() {
        val input = listOf("Fantasy", " fantasy ", "FANTASY", "Adventure")
        assertEquals(listOf("Fantasy", "Adventure"), sanitizeChipList(input, max = 5))
        val capped = sanitizeChipList(listOf("a", "b", "c", "d", "e", "f"), max = 5)
        assertEquals(5, capped.size)
    }

    // ── Form seeding ───────────────────────────────────────────────────

    @Test
    fun `loads book and seeds form fields from comma separated metadata`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            coEvery { repository.observeBookById("book-1") } returns flowOf(sampleBook)

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
            coEvery { repository.observeBookById("book-1") } returns flowOf(sampleBook)

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
            coEvery { repository.observeBookById("book-1") } returns flowOf(sampleBook)

            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.onGenreAdd("fiction") // duplicate of seeded "Fiction"
            assertEquals(2, viewModel.uiState.value.genres.size)
        }

    @Test
    fun `addTag caps at 10`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            coEvery { repository.observeBookById("book-1") } returns flowOf(sampleBook)

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
            coEvery { repository.observeBookById("book-1") } returns flowOf(sampleBook)

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
            val context = mockk<Context>()
            coEvery { repository.observeBookById("book-1") } returns flowOf(sampleBook)
            coEvery { repository.updateBookMetadata(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(Unit)
            every { context.getString(R.string.library_snackbar_metadata_saved) } returns "Metadata updated"

            var saved = false
            val events = mutableListOf<UiEvent>()
            val viewModel =
                EditBookMetadataViewModel(
                    bookId = "book-1",
                    libraryRepository = repository,
                    coverStorage = mockk(),
                    appContext = context,
                    onSaved = { saved = true },
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
            assertTrue(events.firstOrNull() is UiEvent.ShowSnackbar)
            coVerify {
                repository.updateBookMetadata(
                    bookId = "book-1",
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
    fun `save failure emits error snackbar and does not call onSaved`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            val context = mockk<Context>()
            coEvery { repository.observeBookById("book-1") } returns flowOf(sampleBook)
            coEvery { repository.updateBookMetadata(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.failure(IllegalStateException("update failed"))
            every { context.getString(R.string.edit_metadata_save_failed) } returns "Could not save changes"

            var saved = false
            val events = mutableListOf<UiEvent>()
            val viewModel =
                EditBookMetadataViewModel(
                    bookId = "book-1",
                    libraryRepository = repository,
                    coverStorage = mockk(),
                    appContext = context,
                    onSaved = { saved = true },
                    mainDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiEvent.collect { events.add(it) }
            }
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()

            assertFalse(saved)
            assertTrue(events.firstOrNull() is UiEvent.ShowSnackbar)
        }

    @Test
    fun `save with new cover bytes saves cover and passes its path`() =
        runTest {
            val repository = mockk<LibraryRepository>()
            val context = mockk<Context>()
            val coverStorage = mockk<CoverStorage>()
            coEvery { repository.observeBookById("book-1") } returns flowOf(sampleBook)
            coEvery { repository.updateBookMetadata(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(Unit)
            coEvery { coverStorage.saveCover("book-1", any()) } returns Result.success("/covers/book-1.jpg")
            every { context.getString(R.string.library_snackbar_metadata_saved) } returns "Metadata updated"

            val viewModel =
                EditBookMetadataViewModel(
                    bookId = "book-1",
                    libraryRepository = repository,
                    coverStorage = coverStorage,
                    appContext = context,
                    onSaved = {},
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
            appContext = mockk(),
            onSaved = onSaved,
            mainDispatcher = UnconfinedTestDispatcher(),
        )
}
