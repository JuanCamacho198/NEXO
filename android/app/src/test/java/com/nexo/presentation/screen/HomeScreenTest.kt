package com.nexo.presentation.screen

import com.nexo.domain.model.Book
import com.nexo.presentation.feature.home.HomeSectionRenderState
import com.nexo.presentation.feature.home.homeSectionRenderState
import com.nexo.presentation.viewmodel.HomeUiState
import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for the HomeScreen composable's data contract.
 *
 * Compose rendering tests belong in androidTest/ with createComposeRule. Here we
 * test the ViewModel state that drives the screen — proving the data contract
 * for every section Home actually renders.
 *
 * Populated Home: 1. Header (collapsed hero: NEXO wordmark, `userName`/`avatarUrl`,
 * streak, search + notifications) · 2. Greeting (`userName`) ·
 * 3. ContinueReading (`currentBooks`) · 4. MyBookshelf (`recentBooks`) ·
 * 5. Bottom spacer (structural).
 * First-session Home: full hero + genres + sync banner, chosen by `isLibraryEmpty`.
 *
 * `TodaySummary` and `QuickAccess` are NOT Home sections: the summary moved to
 * Statistics (`StatisticsScreen`) and QuickAccess was removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun homeUiState_supportsAllHomeSections() =
        runTest {
            // Verifies the HomeUiState data class has the fields needed to render
            // every Home section:
            //
            // 1. Header — NEXO top bar; reads userName and avatarUrl
            // 2. Greeting — userName
            // 3. ContinueReading — currentBooks
            // 4. MyBookshelf — recentBooks
            // 5. Bottom spacer — structural (no data field needed)
            //
            // TodaySummary and QuickAccess were removed from Home; the daily
            // summary now lives on the Statistics screen.

            val state =
                HomeUiState(
                    userName = "TestUser",
                    avatarUrl = "https://example.com/avatar.png",
                    minutesReadToday = 42,
                    sessionsToday = 3,
                    dailyProgressPercent = 0.75f,
                    currentBooks = emptyList(),
                    recentBooks = emptyList(),
                    isLoading = false,
                )

            // Section 1: Header — identity shown by the top bar and the hero avatar
            assertEquals("TestUser", state.userName)
            assertEquals("https://example.com/avatar.png", state.avatarUrl)

            // Section 2: Greeting
            assertEquals("TestUser", state.userName)

            // Sections 3-4: ContinueReading and MyBookshelf
            assertTrue(state.currentBooks.isEmpty())
            assertTrue(state.recentBooks.isEmpty())
        }

    @Test
    fun homeUiState_supportsCurrentBooksForContinueReading() =
        runTest {
            val state =
                HomeUiState(
                    currentBooks =
                        listOf(
                            Book(
                                id = "book-1",
                                title = "Test Book",
                                author = "Test Author",
                                coverPath = null,
                                filePath = "/path/to/book.epub",
                                format = "epub",
                                updatedAtEpochMillis = 1000L,
                            ),
                        ),
                )

            assertEquals(1, state.currentBooks.size)
            assertEquals("Test Book", state.currentBooks[0].title)
            assertEquals("Test Author", state.currentBooks[0].author)
        }

    @Test
    fun homeUiState_supportsRecentBooksForMyBookshelf() =
        runTest {
            val books =
                listOf(
                    Book("b1", "Book 1", "Author 1", null, "/p1", "epub", totalPages = null, userRating = null, updatedAtEpochMillis = 1000L),
                    Book("b2", "Book 2", "Author 2", null, "/p2", "epub", totalPages = null, userRating = null, updatedAtEpochMillis = 2000L),
                )
            val state = HomeUiState(recentBooks = books)

            assertEquals(2, state.recentBooks.size)
            assertEquals("Book 1", state.recentBooks[0].title)
            assertEquals("Book 2", state.recentBooks[1].title)
        }

    @Test
    fun homeUiState_isLibraryEmpty_falseWhileLoading() =
        runTest {
            // Before the first Room emission the library is unknown, not empty —
            // the skeleton owns that window, so the empty layout must not show.
            val state = HomeUiState(isLoading = true, allBooks = emptyList())

            assertTrue("Loading is not the same as an empty library", !state.isLibraryEmpty)
        }

    @Test
    fun homeUiState_isLibraryEmpty_trueAfterLoadWithNoBooks() =
        runTest {
            val state = HomeUiState(isLoading = false, allBooks = emptyList())

            assertTrue(state.isLibraryEmpty)
        }

    @Test
    fun homeUiState_isLibraryEmpty_falseOnceAnyBookExists() =
        runTest {
            val state =
                HomeUiState(
                    isLoading = false,
                    allBooks =
                        listOf(
                            Book(
                                id = "book-1",
                                title = "Test Book",
                                author = "Test Author",
                                coverPath = null,
                                filePath = "/path/to/book.epub",
                                format = "epub",
                                updatedAtEpochMillis = 1000L,
                            ),
                        ),
                )

            assertTrue(!state.isLibraryEmpty)
        }

    @Test
    fun homeUiState_isLibraryEmpty_ignoresCurrentAndRecentBooks() =
        runTest {
            // The condition is the library itself (allBooks), not the carousel or
            // the shelf: a book present only in those derived lists still counts
            // as a populated library.
            val book =
                Book(
                    id = "book-1",
                    title = "Test Book",
                    author = "Test Author",
                    coverPath = null,
                    filePath = "/path/to/book.epub",
                    format = "epub",
                    updatedAtEpochMillis = 1000L,
                )
            val state = HomeUiState(isLoading = false, allBooks = emptyList(), currentBooks = listOf(book), recentBooks = listOf(book))

            assertTrue(state.isLibraryEmpty)
        }

    // ── Loading-skeleton gate (LS1-LS3) ────────────────────────────────

    @Test
    fun homeUiState_defaultsToLoadingWithEmptySections() =
        runTest {
            val state = HomeUiState()

            assertTrue("Home must start loading before the first Room emission", state.isLoading)
            assertTrue(state.currentBooks.isEmpty())
            assertTrue(state.recentBooks.isEmpty())
        }

    @Test
    fun homeUiState_afterFirstEmission_stopsLoadingAndAllowsEmptyStates() =
        runTest {
            val state = HomeUiState(isLoading = false)

            assertTrue(!state.isLoading)
            assertTrue(state.currentBooks.isEmpty())
            assertTrue(state.recentBooks.isEmpty())
        }

    @Test
    fun homeSectionRenderState_skeletonWhileLoadingAndUnpopulated() {
        assertEquals(
            HomeSectionRenderState.SKELETON,
            homeSectionRenderState(isLoading = true, isEmpty = true),
        )
    }

    @Test
    fun homeSectionRenderState_emptyStateOnlyAfterLoadingCompletes() {
        assertEquals(
            HomeSectionRenderState.EMPTY,
            homeSectionRenderState(isLoading = false, isEmpty = true),
        )
    }

    @Test
    fun homeSectionRenderState_contentWhenSectionIsPopulated() {
        assertEquals(
            HomeSectionRenderState.CONTENT,
            homeSectionRenderState(isLoading = true, isEmpty = false),
        )
        assertEquals(
            HomeSectionRenderState.CONTENT,
            homeSectionRenderState(isLoading = false, isEmpty = false),
        )
    }
}
