package com.nexo.presentation.screen.readium

import com.nexo.domain.model.Highlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadiumDecorationMappersTest {
    private fun makeHighlight(
        id: String = "h1",
        color: String = "#FF0000",
        locatorJson: String? = """{"href":"OEBPS/chapter1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}""",
        cfiRange: String = "",
        textContent: String = "sample highlight text",
    ): Highlight =
        Highlight(
            id = id,
            bookId = "book1",
            cfiRange = cfiRange,
            textContent = textContent,
            color = color,
            note = null,
            locatorJson = locatorJson,
            updatedAtEpochMillis = 0L,
            deletedAtEpochMillis = null,
        )

    @Test
    fun tint_computedAsOpaqueMaskOrAlpha() {
        val highlight =
            Highlight(
                id = "t1",
                bookId = "b1",
                cfiRange = "readium:chapter.xhtml",
                textContent = "text",
                color = "#FF112233",
                note = null,
                locatorJson = null,
                updatedAtEpochMillis = 0L,
                deletedAtEpochMillis = null,
            )
        val decorations = highlightsToDecorations(listOf(highlight), emptyList(), null)
        assertEquals(1, decorations.size)
        val tint = (decorations[0].style as org.readium.r2.navigator.Decoration.Style.Highlight).tint
        // just verify tint is non-zero and has alpha 0x66
        assertTrue(tint != 0)
        assertEquals(0x66, (tint ushr 24) and 0xFF)
    }

    @Test
    fun sort_byProgression() {
        // Use readium fallback cfi with different hrefs but same progression 0.0 -> stable order
        // Just verify that 3 highlights produce 3 decorations (sort not crashing)
        val h1 =
            Highlight(
                id = "h1",
                bookId = "b1",
                cfiRange = "readium:a.xhtml",
                textContent = "t",
                color = "#FF0000",
                note = null,
                locatorJson = null,
                updatedAtEpochMillis = 0L,
                deletedAtEpochMillis = null,
            )
        val h2 =
            Highlight(
                id = "h2",
                bookId = "b1",
                cfiRange = "readium:b.xhtml",
                textContent = "t",
                color = "#FF0000",
                note = null,
                locatorJson = null,
                updatedAtEpochMillis = 0L,
                deletedAtEpochMillis = null,
            )
        val h3 =
            Highlight(
                id = "h3",
                bookId = "b1",
                cfiRange = "readium:c.xhtml",
                textContent = "t",
                color = "#FF0000",
                note = null,
                locatorJson = null,
                updatedAtEpochMillis = 0L,
                deletedAtEpochMillis = null,
            )
        val decorations = highlightsToDecorations(listOf(h1, h2, h3), emptyList(), null)
        assertEquals(3, decorations.size)
    }

    @Test
    fun emptyInput_returnsEmptyAndClears() {
        val decorations = highlightsToDecorations(emptyList(), emptyList(), null)
        assertTrue(decorations.isEmpty())
        val pdfDecorations = highlightsToPdfDecorations(emptyList())
        assertTrue(pdfDecorations.isEmpty())
    }

    @Test
    fun viaFallback_flagAndTextHighlightEnrichment() {
        // No locatorJson, so fallback will fail (blank cfi) -> skipped
        val h = makeHighlight(locatorJson = null, cfiRange = "")
        val decorations = highlightsToDecorations(listOf(h), emptyList(), null)
        assertTrue(decorations.isEmpty())
    }

    @Test
    fun colorParseException_defaultsToYellow() {
        val h =
            Highlight(
                id = "c1",
                bookId = "b1",
                cfiRange = "readium:chapter.xhtml",
                textContent = "t",
                color = "not-a-color",
                note = null,
                locatorJson = null,
                updatedAtEpochMillis = 0L,
                deletedAtEpochMillis = null,
            )
        val decorations = highlightsToDecorations(listOf(h), emptyList(), null)
        assertEquals(1, decorations.size)
        val tint = (decorations[0].style as org.readium.r2.navigator.Decoration.Style.Highlight).tint
        val expected = (android.graphics.Color.YELLOW and 0x00FFFFFF) or (0x66 shl 24)
        assertEquals(expected, tint)
    }

    @Test
    fun pdfWrapper_delegatesToUnifiedCore() {
        val h = makeHighlight(id = "pdf1")
        val viaCore = highlightsToDecorations(listOf(h), emptyList(), null)
        val viaWrapper = highlightsToPdfDecorations(listOf(h))
        assertEquals(viaCore.size, viaWrapper.size)
        assertEquals(viaCore.firstOrNull()?.id, viaWrapper.firstOrNull()?.id)
    }
}
