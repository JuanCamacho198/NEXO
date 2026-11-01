package com.nexo.presentation.feature.highlights.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightLocationResolverTest {
    // ── pdfPageNumber ────────────────────────────────────────────────

    @Test
    fun `pdfPageNumber parses a well-formed pdf marker`() {
        assertEquals(7, HighlightLocationResolver.pdfPageNumber("pdfpage:7"))
    }

    @Test
    fun `pdfPageNumber parses page zero`() {
        assertEquals(0, HighlightLocationResolver.pdfPageNumber("pdfpage:0"))
    }

    @Test
    fun `pdfPageNumber returns null for an epub cfi`() {
        assertNull(HighlightLocationResolver.pdfPageNumber("epubcfi(/6/4[chap1]!/4/2/1:10)"))
    }

    @Test
    fun `pdfPageNumber returns null for a non-numeric page`() {
        assertNull(HighlightLocationResolver.pdfPageNumber("pdfpage:abc"))
    }

    // ── readiumHref ──────────────────────────────────────────────────

    @Test
    fun `readiumHref extracts the resource href`() {
        assertEquals(
            "OEBPS/Text/sinopsis.xhtml",
            HighlightLocationResolver.readiumHref("readium:OEBPS/Text/sinopsis.xhtml"),
        )
    }

    @Test
    fun `readiumHref returns null for an empty marker`() {
        assertNull(HighlightLocationResolver.readiumHref("readium:"))
    }

    @Test
    fun `readiumHref returns null for an epub cfi`() {
        assertNull(HighlightLocationResolver.readiumHref("epubcfi(/6/4[chap1]!/4/2/1:10)"))
    }

    // ── epubSpineIndex ───────────────────────────────────────────────

    @Test
    fun `epubSpineIndex converts the 1-based cfi index to 0-based`() {
        assertEquals(3, HighlightLocationResolver.epubSpineIndex("epubcfi(/6/4[chap1]!/4/2/1:10)"))
    }

    @Test
    fun `epubSpineIndex decrements the first spine step`() {
        assertEquals(1, HighlightLocationResolver.epubSpineIndex("epubcfi(/6/2)!"))
    }

    @Test
    fun `epubSpineIndex returns null when the cfi has no spine step`() {
        assertNull(HighlightLocationResolver.epubSpineIndex("epubcfi(/4/2/1:10)"))
    }

    @Test
    fun `epubSpineIndex returns null for a pdf marker`() {
        assertNull(HighlightLocationResolver.epubSpineIndex("pdfpage:12"))
    }

    // ── chapterTitle (readium href) ──────────────────────────────────

    private val chapters =
        listOf(
            ChapterLocation(0, "Preface", "OEBPS/Text/preface.xhtml"),
            ChapterLocation(3, "Sinopsis", "OEBPS/Text/sinopsis.xhtml"),
            ChapterLocation(5, "The Funeral", "OEBPS/Text/chapter2.xhtml"),
        )

    @Test
    fun `chapterTitle resolves a readium href by full path`() {
        assertEquals(
            "Sinopsis",
            HighlightLocationResolver.chapterTitle("readium:OEBPS/Text/sinopsis.xhtml", chapters),
        )
    }

    @Test
    fun `chapterTitle resolves a readium href with doubled slashes`() {
        assertEquals(
            "Sinopsis",
            HighlightLocationResolver.chapterTitle("readium:OEBPS//Text//sinopsis.xhtml", chapters),
        )
    }

    @Test
    fun `chapterTitle resolves a readium href by filename when the path differs`() {
        assertEquals(
            "Sinopsis",
            HighlightLocationResolver.chapterTitle("readium:Text/sinopsis.xhtml", chapters),
        )
    }

    @Test
    fun `chapterTitle ignores a fragment on the readium href`() {
        assertEquals(
            "The Funeral",
            HighlightLocationResolver.chapterTitle("readium:OEBPS/Text/chapter2.xhtml#p3", chapters),
        )
    }

    @Test
    fun `chapterTitle returns null for an unknown readium href`() {
        assertNull(
            HighlightLocationResolver.chapterTitle("readium:OEBPS/Text/missing.xhtml", chapters),
        )
    }

    // ── chapterTitle (epub cfi) ──────────────────────────────────────

    @Test
    fun `chapterTitle resolves the matching chapter title from a cfi`() {
        assertEquals(
            "Sinopsis",
            HighlightLocationResolver.chapterTitle("epubcfi(/6/4[chap1]!/4/2/1:10)", chapters),
        )
    }

    @Test
    fun `chapterTitle returns null when no chapter matches the spine`() {
        assertNull(
            HighlightLocationResolver.chapterTitle("epubcfi(/6/99[chapX]!/4/2/1:10)", chapters),
        )
    }

    @Test
    fun `chapterTitle returns null for an unparseable position`() {
        assertNull(HighlightLocationResolver.chapterTitle("pdfpage:3", chapters))
    }

    @Test
    fun `chapterTitle returns null when the matched title is blank`() {
        assertNull(
            HighlightLocationResolver.chapterTitle(
                "epubcfi(/6/4[chap1]!/4/2/1:10)",
                listOf(ChapterLocation(3, "   ", "OEBPS/Text/sinopsis.xhtml")),
            ),
        )
    }

    @Test
    fun `chapterTitle prefers the first entry sharing a spine index`() {
        assertEquals(
            "Part I",
            HighlightLocationResolver.chapterTitle(
                "epubcfi(/6/4[chap1]!/4/2/1:10)",
                listOf(
                    ChapterLocation(3, "Part I", "a.xhtml"),
                    ChapterLocation(3, "Chapter 1", "b.xhtml"),
                ),
            ),
        )
    }

    // ── locatorProgression ───────────────────────────────────────────

    @Test
    fun `locatorProgression reads totalProgression from a locator`() {
        val json =
            """{"href":"OEBPS//Text//sinopsis.xhtml","type":"application//xhtml+xml",""" +
                """"locations":{"progression":0,"position":2,"totalProgression":0.011627906976744186}}"""
        assertEquals(0.011627906976744186, HighlightLocationResolver.locatorProgression(json)!!, 1e-12)
    }

    @Test
    fun `locatorProgression reads a bare-magnitude double`() {
        val json = """{"locations":{"totalProgression":0.5}}"""
        assertEquals(0.5, HighlightLocationResolver.locatorProgression(json)!!, 1e-12)
    }

    @Test
    fun `locatorProgression returns null when the locator is null`() {
        assertNull(HighlightLocationResolver.locatorProgression(null))
    }

    @Test
    fun `locatorProgression returns null when the locator is blank`() {
        assertNull(HighlightLocationResolver.locatorProgression("   "))
    }

    @Test
    fun `locatorProgression returns null when totalProgression is absent`() {
        assertNull(HighlightLocationResolver.locatorProgression("""{"locations":{"position":2}}"""))
    }

    @Test
    fun `locatorProgression returns null for malformed json`() {
        assertNull(HighlightLocationResolver.locatorProgression("{not json"))
    }

    @Test
    fun `locatorProgression returns null when the value is out of range`() {
        assertNull(HighlightLocationResolver.locatorProgression("""{"totalProgression":1.5}"""))
        assertNull(HighlightLocationResolver.locatorProgression("""{"totalProgression":-0.2}"""))
    }

    @Test
    fun `locatorProgression returns null when the value is not a number`() {
        assertNull(HighlightLocationResolver.locatorProgression("""{"totalProgression":"half"}"""))
    }

    // ── progressionLabel ─────────────────────────────────────────────

    @Test
    fun `progressionLabel rounds a fraction to a whole percent`() {
        assertEquals(46, HighlightLocationResolver.progressionLabel(0.46))
    }

    @Test
    fun `progressionLabel rounds half up`() {
        assertEquals(1, HighlightLocationResolver.progressionLabel(0.005))
    }

    @Test
    fun `progressionLabel maps the start to zero`() {
        assertEquals(0, HighlightLocationResolver.progressionLabel(0.0))
    }

    @Test
    fun `progressionLabel maps completion to one hundred`() {
        assertEquals(100, HighlightLocationResolver.progressionLabel(1.0))
    }

    @Test
    fun `progressionLabel returns null for a null fraction`() {
        assertNull(HighlightLocationResolver.progressionLabel(null))
    }

    @Test
    fun `progressionLabel returns null when out of range`() {
        assertNull(HighlightLocationResolver.progressionLabel(1.5))
        assertNull(HighlightLocationResolver.progressionLabel(-0.1))
    }
}
