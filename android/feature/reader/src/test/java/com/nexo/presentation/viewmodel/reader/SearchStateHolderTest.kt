package com.nexo.presentation.viewmodel.reader

import android.util.Log
import com.nexo.domain.model.SearchResult
import com.nexo.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

@OptIn(ExperimentalCoroutinesApi::class)
class SearchStateHolderTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var navigatedLocator: Locator? = null
    private var goToChapterIndex: Int? = null
    private var goToPdfPageIndex: Int? = null

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Default state ──────────────────────────────────────────────────

    @Test
    fun `default state is all defaults`() {
        val holder = createHolder(TestCoroutineScheduler())

        val state = holder.state.value
        assertFalse(state.isSearchActive)
        assertEquals("", state.searchQuery)
        assertTrue(state.searchResults.isEmpty())
        assertFalse(state.isSearching)
    }

    // ── Toggle ─────────────────────────────────────────────────────────

    @Test
    fun `onToggleSearch flips isSearchActive and resets on second call`() =
        runTest {
            val holder = createHolder(testScheduler)

            assertFalse("Search should be inactive initially", holder.state.value.isSearchActive)

            holder.onToggleSearch()
            assertTrue("Search should be active after toggle", holder.state.value.isSearchActive)

            holder.onToggleSearch()
            assertFalse("Search should be inactive after second toggle", holder.state.value.isSearchActive)
        }

    // ── Clear ──────────────────────────────────────────────────────────

    @Test
    fun `onClearSearch resets query and results`() =
        runTest {
            val holder = createHolder(testScheduler)

            // Set up some search state first
            holder.onToggleSearch()
            holder.onSearchQuery("hello", null, null)
            testScheduler.advanceTimeBy(300)
            runCurrent()

            holder.onClearSearch()

            val state = holder.state.value
            assertEquals("Query should be empty", "", state.searchQuery)
            assertTrue("Results should be empty", state.searchResults.isEmpty())
            assertFalse("Should not be searching", state.isSearching)
        }

    // ── Dismiss ────────────────────────────────────────────────────────

    @Test
    fun `onDismissSearch deactivates search and resets all`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onToggleSearch()
            holder.onSearchQuery("hello", null, null)
            testScheduler.advanceTimeBy(300)
            runCurrent()
            assertTrue("Search should be active", holder.state.value.isSearchActive)

            holder.onDismissSearch()

            val state = holder.state.value
            assertFalse("Search should be inactive after dismiss", state.isSearchActive)
            assertEquals("Query should be empty", "", state.searchQuery)
            assertTrue("Results should be empty", state.searchResults.isEmpty())
            assertFalse("Should not be searching", state.isSearching)
        }

    // ── Search Query: Blank ────────────────────────────────────────────

    @Test
    fun `onSearchQuery with blank string cancels and clears`() =
        runTest {
            val holder = createHolder(testScheduler)

            // Start a search first
            holder.onSearchQuery("test", null, null)
            assertTrue("Should be searching", holder.state.value.isSearching)
            assertEquals("Query should be 'test'", "test", holder.state.value.searchQuery)

            // Now send blank — should cancel and clear
            holder.onSearchQuery("", null, null)

            val state = holder.state.value
            assertEquals("Query should be empty", "", state.searchQuery)
            assertTrue("Results should be empty", state.searchResults.isEmpty())
            assertFalse("Should not be searching", state.isSearching)
        }

    // ── Search Query: EPUB ─────────────────────────────────────────────

    @Test
    fun `onSearchQuery with EPUB book triggers search`() =
        runTest {
            val holder = createHolder(testScheduler)

            val mockPublication = mockk<Publication>(relaxed = true)
            val query = "MATCH"

            holder.onSearchQuery(query, mockPublication, "epub")

            // Immediately after call, isSearching should be true
            assertTrue("Should be searching immediately", holder.state.value.isSearching)
            assertEquals("Query should be set", query, holder.state.value.searchQuery)

            // Advance past the debounce (300ms)
            testScheduler.advanceTimeBy(300)
            runCurrent()

            // Note: searchReadiumPublication internally uses withContext(Dispatchers.IO),
            // which runs on the real IO dispatcher, not the test scheduler.
            // This test validates the debounce pipeline starts (isSearching flips to true).
            // Result parsing is tested via onPdfSearchResults tests.
        }

    @Test
    fun `onSearchQuery with null publication returns empty`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onSearchQuery("test", null, "epub")

            testScheduler.advanceTimeBy(300)
            runCurrent()

            val state = holder.state.value
            assertFalse("Should not be searching", state.isSearching)
            assertTrue("Results should be empty for null publication", state.searchResults.isEmpty())
        }

    @Test
    fun `onSearchQuery with PDF format returns empty`() =
        runTest {
            val holder = createHolder(testScheduler)

            val mockPublication = mockk<Publication>(relaxed = true)

            holder.onSearchQuery("test", mockPublication, "pdf")

            testScheduler.advanceTimeBy(300)
            runCurrent()

            val state = holder.state.value
            assertFalse("Should not be searching", state.isSearching)
            assertTrue("Results should be empty for PDF format (handled natively)", state.searchResults.isEmpty())
        }

    @Test
    fun `onSearchQuery with null book format returns empty`() =
        runTest {
            val holder = createHolder(testScheduler)

            val mockPublication = mockk<Publication>(relaxed = true)

            holder.onSearchQuery("test", mockPublication, null)

            testScheduler.advanceTimeBy(300)
            runCurrent()

            val state = holder.state.value
            assertFalse("Should not be searching", state.isSearching)
            assertTrue("Results should be empty when bookFormat is null", state.searchResults.isEmpty())
        }

    // ── PDF Search Results ─────────────────────────────────────────────

    @Test
    fun `onPdfSearchResults with valid JSON parses results correctly`() {
        val holder = createHolder(TestCoroutineScheduler())

        val json = """[
            {"pageIndex": 0, "pageLabel": "Page 1", "snippet": "first match"},
            {"pageIndex": 5, "pageLabel": "Page 6", "snippet": "second result"}
        ]"""

        holder.onPdfSearchResults(json)

        val state = holder.state.value
        assertEquals("Should have 2 results", 2, state.searchResults.size)
        assertFalse("Should not be searching", state.isSearching)

        assertEquals("first match", state.searchResults[0].text)
        assertEquals(0f, state.searchResults[0].page, 0f)
        assertEquals("Page 1", state.searchResults[0].chapterTitle)
        assertEquals("pdfpage:0", state.searchResults[0].cfi)

        assertEquals("second result", state.searchResults[1].text)
        assertEquals(5f, state.searchResults[1].page, 0f)
        assertEquals("Page 6", state.searchResults[1].chapterTitle)
        assertEquals("pdfpage:5", state.searchResults[1].cfi)
    }

    @Test
    fun `onPdfSearchResults with invalid JSON returns empty list`() {
        val holder = createHolder(TestCoroutineScheduler())

        holder.onPdfSearchResults("not valid json at all")

        val state = holder.state.value
        assertTrue("Results should be empty for invalid JSON", state.searchResults.isEmpty())
        assertFalse("Should not be searching", state.isSearching)
    }

    @Test
    fun `onPdfSearchResults with empty array returns empty list`() {
        val holder = createHolder(TestCoroutineScheduler())

        holder.onPdfSearchResults("[]")

        val state = holder.state.value
        assertTrue("Results should be empty", state.searchResults.isEmpty())
        assertFalse("Should not be searching", state.isSearching)
    }

    // ── Search Result Selected: PDF ────────────────────────────────────

    @Test
    fun `onSearchResultSelected with PDF calls onGoToPdfPage and dismisses`() =
        runTest {
            val holder = createHolder(testScheduler)

            // First toggle search so we can verify dismiss
            holder.onToggleSearch()
            assertTrue("Search should be active", holder.state.value.isSearchActive)

            val result =
                SearchResult(
                    text = "matched text",
                    offset = 0,
                    cfi = "pdfpage:7",
                )
            holder.onSearchResultSelected(result, null, "pdf", 0)

            assertEquals("Should navigate to PDF page 7", 7, goToPdfPageIndex)
            assertFalse("Search should be dismissed", holder.state.value.isSearchActive)
        }

    @Test
    fun `onSearchResultSelected with PDF and invalid cfi is no-op navigation and keeps search open`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onToggleSearch()
            assertTrue("Search should be active", holder.state.value.isSearchActive)

            val result =
                SearchResult(
                    text = "test",
                    offset = 0,
                    cfi = "no-pdfpage-prefix",
                )
            holder.onSearchResultSelected(result, null, "pdf", 0)

            // cfi "no-pdfpage-prefix" — removePrefix("pdfpage:") returns same string
            // toIntOrNull() returns null → early return, NO navigation, NO dismiss
            assertNull("Should not navigate with invalid cfi", goToPdfPageIndex)
            assertTrue(
                "Search should remain active — early return skips onDismissSearch()",
                holder.state.value.isSearchActive,
            )
        }

    // ── Search Result Selected: EPUB ───────────────────────────────────

    @Test
    fun `onSearchResultSelected with EPUB dismisses search without crashing`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onToggleSearch()
            assertTrue("Search should be active", holder.state.value.isSearchActive)

            val result =
                SearchResult(
                    text = "highlighted text",
                    offset = 100,
                    chapterIndex = 0,
                    chapterTitle = "Chapter 1",
                    cfi = "/0/4/100",
                )

            val mockLink =
                mockk<Link>(relaxed = true) {
                    every { title } returns "Chapter 1"
                }

            val mockPublication =
                mockk<Publication>(relaxed = true) {
                    every { readingOrder } returns listOf(mockLink)
                }

            // This exercises the EPUB navigation path without crashing.
            // Locator.fromJSON may return null with mock data (Readium validation),
            // so we verify the method doesn't crash and search is dismissed.
            holder.onSearchResultSelected(result, mockPublication, "epub", 0)

            assertFalse("Search should be dismissed", holder.state.value.isSearchActive)
        }

    @Test
    fun `onSearchResultSelected with EPUB and invalid chapter index does not navigate`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onToggleSearch()
            assertTrue("Search should be active", holder.state.value.isSearchActive)

            val result =
                SearchResult(
                    text = "test",
                    offset = 0,
                    chapterIndex = 99, // Out of bounds
                    chapterTitle = "Nowhere",
                    cfi = "/99/4/0",
                )

            val mockPublication =
                mockk<Publication>(relaxed = true) {
                    every { readingOrder } returns emptyList()
                }

            holder.onSearchResultSelected(result, mockPublication, "epub", 0)

            assertNull("Should NOT navigate with invalid chapter index", navigatedLocator)
            assertFalse("Search should still be dismissed", holder.state.value.isSearchActive)
        }

    @Test
    fun `onSearchResultSelected with EPUB and null publication does not navigate`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onToggleSearch()
            assertTrue("Search should be active", holder.state.value.isSearchActive)

            val result =
                SearchResult(
                    text = "test",
                    offset = 0,
                    chapterIndex = 0,
                    cfi = "/0/4/0",
                )

            holder.onSearchResultSelected(result, null, "epub", 0)

            assertNull("Should NOT navigate with null publication", navigatedLocator)
            assertFalse("Search should still be dismissed", holder.state.value.isSearchActive)
        }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun createHolder(scheduler: TestCoroutineScheduler): SearchStateHolder {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        navigatedLocator = null
        goToChapterIndex = null
        goToPdfPageIndex = null
        return SearchStateHolder(
            scope = scope,
            onNavigateToLocator = { navigatedLocator = it },
            onGoToChapter = { goToChapterIndex = it },
            onGoToPdfPage = { goToPdfPageIndex = it },
            mainDispatcher = dispatcher,
        )
    }
}
