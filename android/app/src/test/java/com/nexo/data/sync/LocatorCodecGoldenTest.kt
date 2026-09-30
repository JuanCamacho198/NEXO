package com.nexo.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-language golden test — parity with desktop LocatorCodec.ts
 * Golden vectors are defined in desktop/src/test/unit/sync/LocatorCodec.golden.test.ts
 * This test MUST stay in sync with the TS golden. If you update one, update the other.
 *
 * Covers:
 * - readingOrder = spineHrefs (authoritative OPF spine order, linear=no filtered)
 * - normalizeHref parity (backslash -> forward slash)
 * - locatorFromCfi / locatorToJson / locatorFromJson round-trip
 */
class LocatorCodecGoldenTest {
    @Test
    fun normalizeHref_convertsBackslashes_parityWithTs() {
        assertEquals("a/b/c", LocatorCodec.normalizeHref("a\\b\\c"))
        assertEquals("OEBPS/Text/chapter1.xhtml", LocatorCodec.normalizeHref("OEBPS\\Text\\chapter1.xhtml"))
        assertEquals("already/correct", LocatorCodec.normalizeHref("already/correct"))
        // Golden vector 0
        assertEquals("OEBPS/Text/chapter1.xhtml", LocatorCodec.normalizeHref("OEBPS\\Text\\chapter1.xhtml"))
    }

    @Test
    fun parseSpineIndex_parityWithTs() {
        assertEquals(1, LocatorCodec.parseSpineIndex("epubcfi(/6/1!/4/2,/1:0,/1:5)"))
        assertEquals(24, LocatorCodec.parseSpineIndex("epubcfi(/6/24!/4/2,/1:0,/1:5)"))
        assertEquals(2, LocatorCodec.parseSpineIndex("epubcfi(/6/2!foo)"))
        assertNull(LocatorCodec.parseSpineIndex("invalid"))
        assertNull(LocatorCodec.parseSpineIndex(null))
    }

    @Test
    fun readingOrder_spineHrefs_locatorFromCfi_resolvesViaSpineIndex() {
        val readingOrder = listOf("OEBPS\\Text\\chap1.xhtml", "OEBPS/Text/chap2.xhtml", "OEBPS/Text/chap3.xhtml")
        val loc = LocatorCodec.locatorFromCfi(readingOrder, "epubcfi(/6/2!/4/2,/1:0,/1:5)", null)
        assertNotNull(loc)
        assertEquals("OEBPS/Text/chap2.xhtml", loc!!.href)
        // Backslash in readingOrder normalized
        val loc2 = LocatorCodec.locatorFromCfi(readingOrder, "epubcfi(/6/1!/4/2,/1:0,/1:5)", null)
        assertNotNull(loc2)
        assertEquals("OEBPS/Text/chap1.xhtml", loc2!!.href)
    }

    @Test
    fun locatorToJson_normalizesHrefBeforeSerialization() {
        val loc = LocatorCodec.locatorFromCfi(listOf("OEBPS\\Text\\chap1.xhtml", "b.html"), "epubcfi(/6/1!/4/2,/1:0,/1:5)", null)!!
        val json = LocatorCodec.locatorToJson(loc)
        assertTrue(!json.contains("\\"))
        val parsed = org.json.JSONObject(json)
        assertEquals("OEBPS/Text/chap1.xhtml", parsed.getString("href"))
    }

    @Test
    fun locatorFromJson_normalizesBackslashHref_golden() {
        val json = """{"href":"OEBPS\\Text\\chap1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5,"fragment":"epubcfi(/6/1!/4/2,/1:0,/1:5)"}}"""
        val loc = LocatorCodec.locatorFromJson(json)
        assertNotNull(loc)
        assertEquals("OEBPS/Text/chap1.xhtml", loc!!.href)
    }

    @Test
    fun normalizeLocatorJson_parity_backslashInHrefReplaced_cfiPreserved() {
        val json = """{"href":"OEBPS\\Text\\chap1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5,"fragment":"epubcfi(/6/1!/4/2,/1:0,/1:5)"}}"""
        val normalized = LocatorCodec.normalizeLocatorJson(json)
        assertNotNull(normalized)
        val parsed = org.json.JSONObject(normalized!!)
        assertEquals("OEBPS/Text/chap1.xhtml", parsed.getString("href"))
        val loc = parsed.getJSONObject("locations")
        assertEquals("epubcfi(/6/1!/4/2,/1:0,/1:5)", loc.getString("fragment"))
    }

    @Test
    fun roundTrip_spineHrefs_golden_HistoriaOffset2() {
        val spineHrefs =
            listOf(
                "OEBPS/Text/cover.xhtml",
                "OEBPS/Text/toc.xhtml",
                "OEBPS/Text/HM-colombia-1.html",
                "OEBPS/Text/HM-colombia-2.html",
                "OEBPS/Text/HM-colombia-3.html",
            )
        val cfiForToc0 = "epubcfi(/6/3!/4/2,/1:0,/1:5)"
        val loc = LocatorCodec.locatorFromCfi(spineHrefs, cfiForToc0, LocatorChapterMetric(chapterChars = 1000, charOffset = 250))
        assertNotNull(loc)
        assertEquals("OEBPS/Text/HM-colombia-1.html", loc!!.href)
        assertEquals(0.25, loc.locations.progression!!, 0.00001)
        assertEquals(cfiForToc0, loc.locations.fragment)
        val json = LocatorCodec.locatorToJson(loc)
        val restored = LocatorCodec.locatorFromJson(json)!!
        assertEquals(loc.href, restored.href)
        assertEquals(cfiForToc0, restored.locations.fragment)
    }

    @Test
    fun charOffsetToProgression_clamped() {
        assertEquals(0.25, LocatorCodec.charOffsetToProgression(250, 1000)!!, 0.00001)
        assertEquals(1.0, LocatorCodec.charOffsetToProgression(1500, 1000)!!, 0.00001)
        assertEquals(0.0, LocatorCodec.charOffsetToProgression(-5, 100)!!, 0.00001)
        assertNull(LocatorCodec.charOffsetToProgression(10, 0))
    }

    @Test
    fun deriveLocatorForChapter_parity() {
        val readingOrder = listOf("a.html", "b.html", "c.html")
        val loc = LocatorCodec.deriveLocatorForChapter(readingOrder, "b.html")
        assertNotNull(loc)
        assertEquals("b.html", loc!!.href)
        assertEquals(0.0, loc.locations.progression!!, 0.00001)
        assertNull(LocatorCodec.deriveLocatorForChapter(readingOrder, "missing.html"))
        // Backslash normalized
        val loc2 = LocatorCodec.deriveLocatorForChapter(listOf("OEBPS\\Text\\a.html", "b.html"), "OEBPS/Text/a.html")
        assertNotNull(loc2)
    }

    @Test
    fun cfiFromLocator_roundTrip() {
        val loc = LocatorCodec.locatorFromCfi(listOf("a.html", "b.html"), "epubcfi(/6/2!/4/2,/1:0,/1:5)", null)!!
        assertEquals("epubcfi(/6/2!/4/2,/1:0,/1:5)", LocatorCodec.cfiFromLocator(loc))
        val chapLoc = LocatorCodec.deriveLocatorForChapter(listOf("a.html", "b.html"), "a.html")!!
        assertNull(LocatorCodec.cfiFromLocator(chapLoc))
    }
}
