package com.nexo.presentation.screen.pdfjs

import com.nexo.presentation.viewmodel.reader.PdfRect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfJsMessageTest {
    @Test
    fun parsePdfJsMessage_loaded_returnsLoadedWithPages() {
        assertEquals(PdfJsMessage.Loaded(8), parsePdfJsMessage("""{"type":"loaded","numPages":8}"""))
    }

    @Test
    fun parsePdfJsMessage_page_returnsPageAndTotal() {
        assertEquals(PdfJsMessage.Page(3, 8), parsePdfJsMessage("""{"type":"page","page":3,"total":8}"""))
    }

    @Test
    fun parsePdfJsMessage_selection_returnsWholePayload() {
        val raw =
            """
            {"type":"selection","empty":false,"pageNumber":4,"text":"hello",
             "rects":[{"x":10,"y":20,"width":30,"height":12},{"x":10,"y":34,"width":18,"height":12}],
             "bounds":{"left":100,"top":200,"right":400.5,"bottom":280.25},"scale":1.6}
            """.trimIndent()
        assertEquals(
            PdfJsMessage.Selection(
                empty = false,
                pageNumber = 4,
                text = "hello",
                rects = listOf(PdfRect(10f, 20f, 30f, 12f), PdfRect(10f, 34f, 18f, 12f)),
                bounds = PdfJsBounds(100f, 200f, 400.5f, 280.25f),
                scale = 1.6f,
            ),
            parsePdfJsMessage(raw),
        )
    }

    @Test
    fun parsePdfJsMessage_emptySelection_hasNoPageAndNoText() {
        val selection = parsePdfJsMessage("""{"type":"selection","empty":true}""") as PdfJsMessage.Selection
        assertTrue(selection.empty)
        // Never a fabricated page 0: an empty selection has no page at all.
        assertNull(selection.pageNumber)
        assertEquals("", selection.text)
        assertEquals(emptyList<PdfRect>(), selection.rects)
        assertNull(selection.bounds)
        assertEquals(1f, selection.scale, 0f)
    }

    @Test
    fun parsePdfJsMessage_selectionWithBlankText_isEmpty() {
        val selection =
            parsePdfJsMessage("""{"type":"selection","empty":false,"pageNumber":2,"text":"  \n "}""")
                as PdfJsMessage.Selection
        assertTrue(selection.empty)
        assertEquals(2, selection.pageNumber)
    }

    @Test
    fun parsePdfJsMessage_selectionWithoutBounds_keepsTextAndRects() {
        val selection =
            parsePdfJsMessage(
                """{"type":"selection","empty":false,"pageNumber":2,"text":"hi","rects":[{"x":1,"y":2,"width":3,"height":4}]}""",
            ) as PdfJsMessage.Selection
        assertEquals(false, selection.empty)
        assertEquals(2, selection.pageNumber)
        assertEquals(listOf(PdfRect(1f, 2f, 3f, 4f)), selection.rects)
        assertNull(selection.bounds)
    }

    @Test
    fun parsePdfJsMessage_selectionWithMalformedRect_dropsOnlyThatRect() {
        val selection =
            parsePdfJsMessage(
                """
                {"type":"selection","empty":false,"pageNumber":1,"text":"hi",
                 "rects":[
                   {"x":1,"y":2,"width":3,"height":4},
                   {"x":5,"y":6,"width":"wide","height":8},
                   {"x":9},
                   7,
                   null,
                   {"x":10,"y":20,"width":0,"height":12}
                 ]}
                """.trimIndent(),
            ) as PdfJsMessage.Selection
        assertEquals("only the well-formed rect survives", listOf(PdfRect(1f, 2f, 3f, 4f)), selection.rects)
    }

    @Test
    fun parsePdfJsMessage_selectionWithWrongTypes_degradesWithoutThrowing() {
        val selection =
            parsePdfJsMessage(
                """{"type":"selection","empty":false,"pageNumber":3,"text":42,"rects":"nope","bounds":[],"scale":"big"}""",
            ) as PdfJsMessage.Selection
        // A non-string text means there is nothing to select.
        assertTrue(selection.empty)
        assertEquals("", selection.text)
        assertEquals(emptyList<PdfRect>(), selection.rects)
        assertNull(selection.bounds)
        assertEquals(1f, selection.scale, 0f)
        assertEquals(3, selection.pageNumber)
    }

    @Test
    fun parsePdfJsMessage_selectionWithInvertedBounds_normalizesIt() {
        val selection =
            parsePdfJsMessage(
                """{"type":"selection","empty":false,"pageNumber":1,"text":"hi","bounds":{"left":100,"top":280,"right":400,"bottom":200}}""",
            ) as PdfJsMessage.Selection
        assertEquals(PdfJsBounds(100f, 200f, 400f, 280f), selection.bounds)
    }

    @Test
    fun parsePdfJsMessage_error_returnsMessage() {
        assertEquals(PdfJsMessage.Error("boom"), parsePdfJsMessage("""{"type":"error","message":"boom"}"""))
    }

    @Test
    fun parsePdfJsMessage_malformedJson_returnsNull() {
        assertNull(parsePdfJsMessage("{not json"))
    }

    @Test
    fun parsePdfJsMessage_unknownTypeOrNull_returnsNull() {
        assertNull(parsePdfJsMessage("""{"type":"zzz"}"""))
        assertNull(parsePdfJsMessage(null))
    }

    @Test
    fun pdfPageFromLocator_usesPosition() {
        assertEquals(7, pdfPageFromLocator(locator("""{"href":"a.pdf","type":"application/pdf","locations":{"position":7}}""")))
    }

    @Test
    fun pdfPageFromLocator_fallsBackToPageFragment() {
        assertEquals(
            3,
            pdfPageFromLocator(locator("""{"href":"a.pdf","type":"application/pdf","locations":{"fragments":["page=3"]}}""")),
        )
    }

    @Test
    fun pdfPageFromLocator_withoutPageInfo_returnsNull() {
        assertNull(pdfPageFromLocator(locator("""{"href":"a.pdf","type":"application/pdf"}""")))
        assertNull(pdfPageFromLocator(locator("""{"href":"a.pdf","type":"application/pdf","locations":{"position":0}}""")))
    }

    private fun locator(json: String) = Locator.fromJSON(JSONObject(json))!!
}
