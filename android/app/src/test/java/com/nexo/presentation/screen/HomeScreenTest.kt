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
 * Compose rendering tests (asserting all 7 sections render with visual elements)
 * belong in androidTest/ with createComposeRule. Here we test the ViewModel
 * state that drives the screen — proving the data contract for all 7 sections.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun homeUiState_supportsAllSevenSections() =
        runTest {
            // This test verifies the HomeUiState data class has ALL fields
            // needed to render the 7 sections of the Pencil design:
            //
            // 1. Header — static "NEXO" title (no data field needed)
            // 2. Greeting — userName
            // 3. TodaySummary — minutesReadToday, sessionsToday, dailyProgressPercent
            // 4. ContinueReading — currentBooks
            // 5. MyBookshelf — recentBooks
            // 6. QuickAccess — static icons (no data field needed)
            // 7. Bottom spacer — structural (no data field needed)

            val state =
                HomeUiState(
                    userName = "TestUser",
                    minutesReadToday = 42,
                    sessionsToday = 3,
                    dailyProgressPercent = 0.75f,
                    currentBooks = emptyList(),
                    recentBooks = emptyList(),
                    isLoading = false,
                )

            // Section 2: Greeting
            assertEquals("TestUser", state.userName)

            // Section 3: TodaySummary — 3 stat cards
            assertEquals(42, state.minutesReadToday)
            assertEquals(3, state.sessionsToday)
            assertEquals(0.75f, state.dailyProgressPercent, 0.001f)

            // Section 4: ContinueReading
            assertTrue(state.currentBooks.isEmpty())

            // Section 5: MyBookshelf
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
