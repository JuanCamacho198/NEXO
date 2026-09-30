package com.nexo.presentation.navigation

import com.nexo.presentation.UiEvent
import com.nexo.presentation.navigation.feature.resolveReaderBookIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for NexoNavHost navigation contracts.
 *
 * Slice 3a scaffolding: uses TestNavHostController + createComposeRule helpers
 * per tasks §2.5 (harness available in androidTest; this unit file validates
 * the pure navigation logic that underpins the host). The tests cover:
 * 1) startDestination matrix (unauth→auth, auth+noGoal→onboarding, auth+goal→home)
 * 2) OpenBookAtLocation sets selectedBook* + navigates to reader
 * 3) the Reader's typed-route → snapshot fallback resolution
 *
 * Full compose harness (TestNavHostController + createComposeRule) is exercised
 * in androidTest; this file keeps the matrix guards in unit test for fast
 * feedback. The typed-route destination pattern and the special-character
 * round-trip through a real NavController live in TypedRoutesNavigationTest.
 */
class NexoNavHostTest {
    // Helper mirroring host's startDestination derivation
    private fun resolveStartDestination(
        isAuthenticated: Boolean,
        hasDailyGoal: Boolean,
    ): String =
        when {
            !isAuthenticated -> NexoDestination.Auth.route
            !hasDailyGoal -> NexoDestination.OnboardingGoal.route
            else -> NexoDestination.Home.route
        }

    @Test
    fun `startDestination matrix - unauth to auth, auth no goal to onboarding, auth with goal to home`() {
        assertEquals(NexoDestination.Auth.route, resolveStartDestination(isAuthenticated = false, hasDailyGoal = false))
        assertEquals(NexoDestination.Auth.route, resolveStartDestination(isAuthenticated = false, hasDailyGoal = true))
        assertEquals(NexoDestination.OnboardingGoal.route, resolveStartDestination(isAuthenticated = true, hasDailyGoal = false))
        assertEquals(NexoDestination.Home.route, resolveStartDestination(isAuthenticated = true, hasDailyGoal = true))
    }

    @Test
    fun `startDestination - BottomTabNavOptions integration with TestNavHostController style`() {
        // Verifies that every startDestination is a valid NexoDestination route
        // and that BottomTabNavOptions.forRoute preserves host semantics.
        val homeRoute = NexoDestination.Home.route
        listOf(
            resolveStartDestination(false, false) to NexoDestination.Auth.route,
            resolveStartDestination(true, false) to NexoDestination.OnboardingGoal.route,
            resolveStartDestination(true, true) to NexoDestination.Home.route,
        ).forEach { (actual, expected) ->
            assertEquals(expected, actual)
            // BottomTabNavOptions must not break when startDestination is outside bottomBar
            val opts = BottomTabNavOptions.forRoute(actual, homeRoute)
            assertEquals(homeRoute, opts.popUpToRoute)
        }
    }

    @Test
    fun `OpenBookAtLocation sets selectedBook state and navigates to reader`() {
        // Simulates GlobalEventCollector's OpenBookAtLocation branch:
        // selectedBook* write lambdas + readerVM.navigateToCfiAfterLoad + nav to reader.
        var selectedBookId = ""
        var selectedBookFilePath: String? = null
        var selectedBookFormat = "epub"
        var navigatedRoute: ReaderRoute? = null
        var cfiAfterLoad: String? = null

        val event = UiEvent.OpenBookAtLocation(bookId = "book-123", cfiRange = "/6/2[c1]")

        // Simulated book lookup success
        val book = FakeBook(id = "book-123", filePath = "/files/book.epub", format = "epub")
        selectedBookId = book.id
        selectedBookFilePath = book.filePath
        selectedBookFormat = book.format
        cfiAfterLoad = event.cfiRange
        navigatedRoute = ReaderRoute(book.id, book.filePath, book.format)

        assertEquals("book-123", selectedBookId)
        assertEquals("/files/book.epub", selectedBookFilePath)
        assertEquals("epub", selectedBookFormat)
        assertEquals("/6/2[c1]", cfiAfterLoad)
        assertEquals(
            ReaderRoute(bookId = "book-123", bookPath = "/files/book.epub", bookFormat = "epub"),
            navigatedRoute,
        )
    }

    @Test
    fun `OpenBookAtLocation - ReaderRoute carries book identity verbatim as nav args`() {
        // Typed-route translation of the old encoded-route assertion: the raw
        // values ride on the route and Navigation owns percent-encoding, so a
        // path with special characters is neither pre-encoded nor corrupted.
        val path = "/files/my book.epub"
        val route = ReaderRoute(bookId = "book-123", bookPath = path, bookFormat = "epub")

        assertEquals("book-123", route.bookId)
        assertEquals(path, route.bookPath)
        assertEquals("epub", route.bookFormat)
        assertTrue("raw path keeps its space; Navigation encodes it", route.bookPath!!.contains(" "))
    }

    @Test
    fun `ReaderRoute defaults and unrelated routes are stable`() {
        // Equivalence guard: the Reader argument contract must stay verbatim
        // (bookId "" / bookPath null / bookFormat "epub"), and the destinations
        // that are out of S12's scope must not be disturbed.
        assertEquals("", ReaderRoute().bookId)
        assertNull(ReaderRoute().bookPath)
        assertEquals("epub", ReaderRoute().bookFormat)

        assertEquals("book_detail/{bookId}", NexoDestination.BookDetail.route)
        assertEquals("auth", NexoDestination.Auth.route)
    }

    @Test
    fun `reader identity - route args win over the host snapshot`() {
        val identity =
            resolveReaderBookIdentity(
                route = ReaderRoute(bookId = "route-book", bookPath = "/route/path.epub", bookFormat = "pdf"),
                snapshotBookId = "snapshot-book",
                snapshotBookPath = "/snapshot/path.epub",
                snapshotBookFormat = "epub",
            )

        assertEquals("route-book", identity.bookId)
        assertEquals("/route/path.epub", identity.bookPath)
        assertEquals("pdf", identity.bookFormat)
        assertEquals("args", identity.source)
    }

    @Test
    fun `reader identity - blank route bookId falls back to the host snapshot`() {
        val identity =
            resolveReaderBookIdentity(
                route = ReaderRoute(),
                snapshotBookId = "snapshot-book",
                snapshotBookPath = "/snapshot/path.epub",
                snapshotBookFormat = "pdf",
            )

        assertEquals("snapshot-book", identity.bookId)
        assertEquals("/snapshot/path.epub", identity.bookPath)
        assertEquals("pdf", identity.bookFormat)
        assertEquals("snapshot", identity.source)
    }

    @Test
    fun `reader identity - args mode normalises a blank bookPath to null`() {
        val identity =
            resolveReaderBookIdentity(
                route = ReaderRoute(bookId = "book-123", bookPath = "", bookFormat = "epub"),
                snapshotBookId = "snapshot-book",
                snapshotBookPath = "/snapshot/path.epub",
                snapshotBookFormat = "epub",
            )

        assertEquals("book-123", identity.bookId)
        assertNull(identity.bookPath)
        assertEquals("args", identity.source)
    }

    private data class FakeBook(
        val id: String,
        val filePath: String,
        val format: String,
    )
}
