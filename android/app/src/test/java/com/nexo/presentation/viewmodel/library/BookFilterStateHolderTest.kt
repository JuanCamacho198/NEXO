package com.nexo.presentation.viewmodel.library

import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookFilterStateHolderTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ── Default state ──────────────────────────────────────────────────

    @Test
    fun `default state matches LibraryUiState defaults`() {
        val holder = createHolder(TestCoroutineScheduler())

        val state = holder.state.value
        assertEquals("all", state.statusFilter)
        assertEquals("date_added", state.sortBy)
        assertTrue(state.isGridView)
        assertEquals("", state.searchQuery)
        assertEquals("", state.debouncedSearchQuery)
        assertFalse(state.showSearch)
        assertFalse(state.showFilterSheet)
        assertEquals("all", state.filterFormat)
    }

    // ── Filter ─────────────────────────────────────────────────────────

    @Test
    fun `onStatusFilterChanged updates filter`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onStatusFilterChanged("reading")
            assertEquals("reading", holder.state.value.statusFilter)

            holder.onStatusFilterChanged("completed")
            assertEquals("completed", holder.state.value.statusFilter)
        }

    // ── Sort ───────────────────────────────────────────────────────────

    @Test
    fun `onSortByChanged updates sort order`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onSortByChanged("title")
            assertEquals("title", holder.state.value.sortBy)

            holder.onSortByChanged("author")
            assertEquals("author", holder.state.value.sortBy)
        }

    // ── View toggle ────────────────────────────────────────────────────

    @Test
    fun `onToggleView flips isGridView`() =
        runTest {
            val holder = createHolder(testScheduler)

            assertTrue("Should start in grid mode", holder.state.value.isGridView)

            holder.onToggleView()
            assertFalse("Should switch to list mode", holder.state.value.isGridView)

            holder.onToggleView()
            assertTrue("Should switch back to grid mode", holder.state.value.isGridView)
        }

    // ── Search toggle ──────────────────────────────────────────────────

    @Test
    fun `onToggleSearch flips showSearch and resets queries`() =
        runTest {
            val holder = createHolder(testScheduler)

            assertFalse(holder.state.value.showSearch)

            holder.onToggleSearch()
            assertTrue(holder.state.value.showSearch)

            // Set some search state
            holder.onSearchQueryChanged("test")
            holder.onToggleSearch()
            assertFalse("Search should be hidden", holder.state.value.showSearch)
            assertEquals("Search query should be reset", "", holder.state.value.searchQuery)
            assertEquals("Debounced query should be reset", "", holder.state.value.debouncedSearchQuery)
        }

    // ── Search debounce ────────────────────────────────────────────────

    @Test
    fun `onSearchQueryChanged updates searchQuery immediately`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onSearchQueryChanged("hello")
            assertEquals("hello", holder.state.value.searchQuery)
        }

    @Test
    fun `search debounce fires after 300ms`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onSearchQueryChanged("test")

            // Immediately: searchQuery set, debouncedSearchQuery still ""
            assertEquals("test", holder.state.value.searchQuery)
            assertEquals("", holder.state.value.debouncedSearchQuery)

            // Advance time by 300ms and run pending coroutines
            testScheduler.advanceTimeBy(300)
            runCurrent()

            // Now debouncedSearchQuery should be set
            assertEquals("test", holder.state.value.debouncedSearchQuery)
        }

    @Test
    fun `search debounce does not fire before 300ms`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onSearchQueryChanged("test")

            // Advance only 200ms — debounce should NOT have fired
            testScheduler.advanceTimeBy(200)
            runCurrent()

            assertEquals("", holder.state.value.debouncedSearchQuery)
        }

    @Test
    fun `search cancel on blank query clears immediately`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onSearchQueryChanged("test")
            testScheduler.advanceTimeBy(100)

            // Now send blank — should cancel pending debounce and clear immediately
            holder.onSearchQueryChanged("")

            assertEquals("searchQuery should be blank", "", holder.state.value.searchQuery)
            assertEquals("debouncedSearchQuery should be blank", "", holder.state.value.debouncedSearchQuery)

            // Advance past 300ms to verify no late emission
            testScheduler.advanceTimeBy(300)
            runCurrent()

            assertEquals("Should still be blank after full debounce window", "", holder.state.value.debouncedSearchQuery)
        }

    @Test
    fun `subsequent search cancels previous debounce`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onSearchQueryChanged("first")
            testScheduler.advanceTimeBy(100)

            holder.onSearchQueryChanged("second")
            testScheduler.advanceTimeBy(100)

            // "first" should NOT have fired (cancelled), and "second" should NOT have fired yet
            assertEquals("", holder.state.value.debouncedSearchQuery)

            // Advance remaining 200ms for "second"
            testScheduler.advanceTimeBy(200)
            runCurrent()

            assertEquals("second", holder.state.value.debouncedSearchQuery)
        }

    // ── Filter sheet ───────────────────────────────────────────────────

    @Test
    fun `onToggleFilterSheet flips showFilterSheet`() =
        runTest {
            val holder = createHolder(testScheduler)

            assertFalse(holder.state.value.showFilterSheet)

            holder.onToggleFilterSheet()
            assertTrue(holder.state.value.showFilterSheet)

            holder.onToggleFilterSheet()
            assertFalse(holder.state.value.showFilterSheet)
        }

    @Test
    fun `onFilterFormatChanged sets format and dismisses sheet`() =
        runTest {
            val holder = createHolder(testScheduler)

            holder.onToggleFilterSheet()
            assertTrue("Filter sheet should be shown", holder.state.value.showFilterSheet)

            holder.onFilterFormatChanged("pdf")
            assertEquals("pdf", holder.state.value.filterFormat)
            assertFalse("Filter sheet should be dismissed", holder.state.value.showFilterSheet)
        }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun createHolder(scheduler: TestCoroutineScheduler): BookFilterStateHolder {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        return BookFilterStateHolder(
            scope = scope,
            mainDispatcher = dispatcher,
        )
    }
}
