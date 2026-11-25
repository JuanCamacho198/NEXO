package com.nexo.presentation.screen.pdfjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun parsePdfJsMessage_selection_returnsTextLengthOnly() {
        assertEquals(
            PdfJsMessage.Selection(empty = false, pageNumber = 2, textLength = 5),
            parsePdfJsMessage("""{"type":"selection","pageNumber":2,"text":"hello"}"""),
        )
    }

    @Test
    fun parsePdfJsMessage_emptySelection_returnsEmptyFlag() {
        assertEquals(
            PdfJsMessage.Selection(empty = true, pageNumber = 0, textLength = 0),
            parsePdfJsMessage("""{"type":"selection","empty":true}"""),
        )
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
