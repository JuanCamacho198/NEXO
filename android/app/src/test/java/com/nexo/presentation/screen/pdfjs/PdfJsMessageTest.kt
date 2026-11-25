package com.nexo.presentation.screen.pdfjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
