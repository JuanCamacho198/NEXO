package com.nexo.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the crash-context model. No Android, no Compose — the sheet's
 * "what do I claim about this crash?" decision lives here so it is testable
 * without a device.
 */
class CrashContextTest {
    // ── CrashContext.of ─────────────────────────────────────────────────

    @Test
    fun `of with a bookId produces an InBook context`() {
        val context =
            CrashContext.of(
                bookId = "book-1",
                title = "El extranjero",
                chapterLabel = "Capítulo II",
                chapterIndex = 2,
                page = 30,
                coverPath = "/covers/1.jpg",
            )

        val book = context as CrashContext.InBook
        assertEquals("book-1", book.bookId)
        assertEquals("El extranjero", book.title)
        assertEquals("Capítulo II", book.chapterLabel)
        assertEquals(2, book.chapterIndex)
        assertEquals(30, book.page)
        assertEquals("/covers/1.jpg", book.coverPath)
    }

    @Test
    fun `of with a blank bookId produces an InScreen context`() {
        // The next-launch prompt passes every book field empty — the process
        // died before any reading session, so claiming a book would be a lie.
        val context =
            CrashContext.of(
                bookId = "",
                title = null,
                chapterLabel = null,
                chapterIndex = null,
                page = null,
                screenRoute = "settings",
            )

        val screen = context as CrashContext.InScreen
        assertEquals("settings", screen.screenRoute)
    }

    @Test
    fun `of with a null bookId does not invent a book even when title is present`() {
        val context =
            CrashContext.of(
                bookId = null,
                title = "La Odisea",
                chapterLabel = "Capítulo IX",
                chapterIndex = 9,
                page = 142,
            )

        assertTrue(context is CrashContext.InScreen)
    }

    // ── CrashContextViewModel.from ──────────────────────────────────────

    @Test
    fun `InBook view model exposes title, chapter line and cover`() {
        val vm =
            CrashContextViewModel.from(
                CrashContext.InBook(
                    bookId = "b1",
                    title = "El extranjero",
                    chapterLabel = "Capítulo II",
                    chapterIndex = 2,
                    page = 30,
                    coverPath = "/covers/1.jpg",
                ),
            )

        assertTrue(vm.isBook)
        assertEquals("El extranjero", vm.bookTitle)
        assertEquals("Capítulo II", vm.chapterLine?.chapterLabel)
        assertEquals(30, vm.chapterLine?.page)
        assertTrue(vm.hasCover)
        assertEquals("/covers/1.jpg", vm.coverPath)
    }

    @Test
    fun `InBook never claims a total page count`() {
        // Regression: the sheet used to render "página 142 de 318" where 318 was
        // a hardcoded literal. Only the current page is known on this path.
        val vm =
            CrashContextViewModel.from(
                CrashContext.InBook("b1", "El extranjero", "Capítulo II", 2, 30),
            )

        assertEquals(30, vm.chapterLine?.page)
        // The chapter line carries no total — there is no field to put one in.
        val rendered = "${vm.chapterLine?.chapterLabel}|${vm.chapterLine?.page}"
        assertFalse(rendered.contains("318"))
    }

    @Test
    fun `InBook with no cover path reports hasCover false`() {
        val vm = CrashContextViewModel.from(CrashContext.InBook("b1", "El extranjero", null, null, null, null))

        assertFalse(vm.hasCover)
        assertNull(vm.coverPath)
        // Title still shows even without a cover.
        assertEquals("El extranjero", vm.bookTitle)
    }

    @Test
    fun `InBook with blank cover path reports hasCover false`() {
        val vm = CrashContextViewModel.from(CrashContext.InBook("b1", "El extranjero", null, null, null, coverPath = "   "))

        assertFalse(vm.hasCover)
    }

    @Test
    fun `InBook with page zero or negative drops the page instead of showing it`() {
        val vm = CrashContextViewModel.from(CrashContext.InBook("b1", "El extranjero", "Capítulo II", 2, 0))

        assertNull(vm.chapterLine?.page)
        assertEquals("Capítulo II", vm.chapterLine?.chapterLabel)
    }

    @Test
    fun `InBook with no chapter and no page has a null chapter line`() {
        val vm = CrashContextViewModel.from(CrashContext.InBook("b1", "El extranjero", null, null, null))

        assertNull(vm.chapterLine)
    }

    @Test
    fun `InBook with blank title exposes a null book title`() {
        val vm = CrashContextViewModel.from(CrashContext.InBook("b1", "   ", "Capítulo II", 2, 30))

        assertNull(vm.bookTitle)
    }

    @Test
    fun `InScreen view model carries the route and no book data`() {
        val vm = CrashContextViewModel.from(CrashContext.InScreen("library"))

        assertFalse(vm.isBook)
        assertNull(vm.bookTitle)
        assertNull(vm.chapterLine)
        assertNull(vm.coverPath)
        assertFalse(vm.hasCover)
        assertEquals("library", vm.screenRoute)
    }

    @Test
    fun `InScreen with a blank route exposes a null screen route`() {
        val vm = CrashContextViewModel.from(CrashContext.InScreen("   "))

        assertNull(vm.screenRoute)
    }

    @Test
    fun `InScreen with no route exposes a null screen route`() {
        val vm = CrashContextViewModel.from(CrashContext.InScreen(null))

        assertNull(vm.screenRoute)
    }

    // ── toEntryFields (persisted shape) ─────────────────────────────────

    @Test
    fun `InBook maps onto the flat persisted fields`() {
        val fields =
            CrashContext
                .InBook("b1", "El extranjero", "Capítulo II", 2, 30)
                .toEntryFields()

        assertEquals("b1", fields.bookId)
        assertEquals(2, fields.chapterIndex)
        assertEquals(30, fields.page)
        assertEquals("El extranjero", fields.title)
        assertEquals("Capítulo II", fields.chapterLabel)
    }

    @Test
    fun `InScreen persists no book fields`() {
        val fields = CrashContext.InScreen("settings").toEntryFields()

        assertNull(fields.bookId)
        assertNull(fields.chapterIndex)
        assertNull(fields.page)
        assertNull(fields.title)
        assertNull(fields.chapterLabel)
    }

    @Test
    fun `InBook with a blank id persists a null bookId rather than an empty string`() {
        val fields = CrashContext.InBook("", null, null, null, null).toEntryFields()

        assertNull(fields.bookId)
    }

    // ── Persistence round-trip (invariant: old entries keep loading) ────

    @Test
    fun `FeedbackEntry JSON round-trip is unchanged by the new context model`() {
        val entry =
            FeedbackEvent.FeedbackEntry(
                eventId = "abc",
                text = "se cerró al pasar página",
                timestamp = 1_700_000_000_000L,
                bookId = "book-1",
                chapterIndex = 2,
                page = 30,
                title = "El extranjero",
                chapterLabel = "Capítulo II",
            )

        val restored = FeedbackEvent.jsonToEntry(FeedbackEvent.entryToJson(entry))

        assertEquals(entry, restored)
    }

    @Test
    fun `a non-book entry round-trips with null book fields`() {
        val entry =
            FeedbackEvent.FeedbackEntry(
                eventId = "abc",
                text = "",
                timestamp = 1L,
                bookId = null,
                chapterIndex = null,
                page = null,
                title = null,
                chapterLabel = null,
            )

        val restored = FeedbackEvent.jsonToEntry(FeedbackEvent.entryToJson(entry))

        assertEquals(entry, restored)
    }
}
