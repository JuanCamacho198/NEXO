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

    /**
     * The exact shape the device produces: the platform `org.json` serializer escapes `/` as `\/`
     * (libcore JSONStringer.string escapes '"', '\\' and '/'). The old code tested the RAW TEXT for
     * a backslash, so this input fell through to a blanket `replace("\\", "/")` and gained a DOUBLE
     * slash in href AND type. Regression vector for the corruption that shipped.
     */
    @Test
    fun normalizeLocatorJson_slashEscapedHrefAndType_notMangled() {
        val json =
            """{"href":"OEBPS\/supercomunicadores-5.xhtml","type":"application\/xhtml+xml","locations":{"progression":0.133829,"fragment":"epubcfi(/6/11!/4/2,/1:0,/1:5)"}}"""
        val normalized = LocatorCodec.normalizeLocatorJson(json)
        assertNotNull(normalized)
        assertEquals(json, normalized)
        assertTrue(!normalized!!.contains("//"))
        val parsed = org.json.JSONObject(normalized)
        assertEquals("OEBPS/supercomunicadores-5.xhtml", parsed.getString("href"))
        assertEquals("application/xhtml+xml", parsed.getString("type"))
    }

    /** A normal href passes through untouched — parity with desktop's `return json` branch. */
    @Test
    fun normalizeLocatorJson_cleanHref_untouched() {
        val json = """{"href":"OEBPS/chapter1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}"""
        assertEquals(json, LocatorCodec.normalizeLocatorJson(json))
    }

    /** A genuine Windows-path backslash in the href is still normalised; `type` is not rewritten. */
    @Test
    fun normalizeLocatorJson_realBackslashHref_normalized_typePreserved() {
        val json = """{"href":"OEBPS\\Text\\chapter1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}"""
        val normalized = LocatorCodec.normalizeLocatorJson(json)
        assertNotNull(normalized)
        val parsed = org.json.JSONObject(normalized!!)
        assertEquals("OEBPS/Text/chapter1.xhtml", parsed.getString("href"))
        assertEquals("application/xhtml+xml", parsed.getString("type"))
    }

    /**
     * Genuinely unparseable input takes the fallback, which rewrites ONLY the href value and leaves
     * `type` alone (the old blanket replace corrupted `type` too). The fallback operates on the raw
     * JSON text, so it replaces each backslash character — identical to desktop's regex branch.
     * The `type` value carries a backslash so a blanket replace cannot pass this vector.
     */
    @Test
    fun normalizeLocatorJson_malformedJson_rewritesHrefValueOnly() {
        val malformed =
            """{"href":"OEBPS\Text\chapter1.xhtml","type":"application\xhtml+xml","unterminated"""
        val normalized = LocatorCodec.normalizeLocatorJson(malformed)
        assertNotNull(normalized)
        assertTrue(normalized!!.contains(""""href":"OEBPS/Text/chapter1.xhtml""""))
        assertTrue(normalized.contains(""""type":"application\xhtml+xml""""))
        assertTrue(!normalized.contains("application/xhtml+xml"))
    }

    /** Round-trip through the live serializer must never gain a double slash (acceptance #7). */
    @Test
    fun locatorToJson_locatorFromJson_roundTrip_noDoubleSlash() {
        val json =
            LocatorCodec.locatorToJson(
                CanonicalLocator(
                    href = "OEBPS/supercomunicadores-5.xhtml",
                    type = "application/xhtml+xml",
                    locations = LocatorLocations(progression = 0.133829, fragment = "epubcfi(/6/11!/4/2,/1:0,/1:5)"),
                ),
            )
        assertEquals(
            json,
            LocatorCodec.normalizeLocatorJson(json),
        )
        val restored = LocatorCodec.locatorFromJson(json)!!
        assertEquals("OEBPS/supercomunicadores-5.xhtml", restored.href)
        assertEquals("application/xhtml+xml", restored.type)
        assertEquals(0.133829, restored.locations.progression!!, 0.000001)
    }

    /**
     * The `"href"` value of a parsed locator, with the platform serializer's `\/` escaping
     * undone, so a round-trip can be compared against the stored text.
     */
    private fun hrefTextOf(json: String): String =
        org.json
            .JSONObject(json)
            .getString("href")
            .replace("\\/", "/")

    /**
     * Read-path repair (T3): the double-slash shape the pre-T2 writer persisted is repaired when a
     * locator is READ, and resolves to the same href a clean locator would. This is the vector
     * that a bulk UPDATE would have had to guess at; here it is exact and idempotent.
     */
    @Test
    fun locatorFromJson_alreadyCorruptedDoubleSlash_repairsHrefOnRead() {
        val corrupted =
            """{"href":"OEBPS//supercomunicadores-5.xhtml","type":"application//xhtml+xml","locations":{"progression":0.133829}}"""
        val loc = LocatorCodec.locatorFromJson(corrupted)
        assertNotNull(loc)
        assertEquals("OEBPS/supercomunicadores-5.xhtml", loc!!.href)
        assertEquals(0.133829, loc.locations.progression!!, 0.000001)
    }

    /**
     * The repaired locator is what gets serialised back, whatever escaping `org.json` applies on
     * the way out, so a healed row round-trips to the single-slash form.
     */
    @Test
    fun normalizeHref_doubleSlash_repairedRegardlessOfJsonEscaping() {
        val corrupted =
            """{"href":"OEBPS\/supercomunicadores-5.xhtml","type":"application\/xhtml+xml","locations":{"progression":0.133829}}"""
        val repaired = LocatorCodec.normalizeLocatorJson(corrupted)!!
        assertEquals("OEBPS/supercomunicadores-5.xhtml", hrefTextOf(repaired))
        assertTrue(!hrefTextOf(repaired).contains("//"))
    }

    /** Idempotent: repairing an already-clean locator changes nothing, byte for byte. */
    @Test
    fun normalizeLocatorJson_cleanLocator_unchangedByRepair() {
        val clean =
            """{"href":"OEBPS/supercomunicadores-5.xhtml","type":"application/xhtml+xml","locations":{"progression":0.133829,"fragment":"epubcfi(/6/11!/4/2,/1:0,/1:5)"}}"""
        assertEquals(clean, LocatorCodec.normalizeLocatorJson(clean))
    }

    /** The `type` field is a media type, not a path: the repair collapses its doubled slash too. */
    @Test
    fun normalizeLocatorJson_doubleSlashHref_typeAlsoRepaired() {
        // `type` is NOT inert. Readium's Locator.fromJSON parses it as a MediaType and returns
        // null for the whole locator when it is malformed (measured on device: the same JSON with
        // `application/xhtml+xml` parses; with `application//xhtml+xml` it does not). Repairing
        // only the href would therefore leave the reader landing on the cover.
        val corrupted =
            """{"href":"OEBPS//supercomunicadores-5.xhtml","type":"application//xhtml+xml","locations":{"progression":0.133829}}"""
        val repaired = LocatorCodec.normalizeLocatorJson(corrupted)!!
        assertEquals("application/xhtml+xml", org.json.JSONObject(repaired).getString("type"))
        assertEquals("OEBPS/supercomunicadores-5.xhtml", org.json.JSONObject(repaired).getString("href"))
    }

    /** A legitimate href-suffix value (`chapter.xhtml#a`) is not broken by the repair. */
    @Test
    fun normalizeLocatorJson_hrefWithFragmentAndQuery_survivesRepair() {
        val json =
            """{"href":"OEBPS//Text/chap1.xhtml#note-2?page=1","type":"application/xhtml+xml","locations":{"progression":0.5}}"""
        val repaired = LocatorCodec.normalizeLocatorJson(json)!!
        assertEquals("OEBPS/Text/chap1.xhtml#note-2?page=1", org.json.JSONObject(repaired).getString("href"))
    }

    /**
     * The `locations.fragment` CFI carries no href separators — `epubcfi(/6/11!/4/2,/1:0,/1:5)`
     * must come out of the repair untouched, exactly as the writer stored it.
     */
    @Test
    fun normalizeLocatorJson_corruptedHref_cfiFragmentPreserved() {
        val corrupted =
            """{"href":"OEBPS//supercomunicadores-5.xhtml","type":"application//xhtml+xml","locations":{"progression":0.133829,"fragment":"epubcfi(/6/11!/4/2,/1:0,/1:5)"}}"""
        val repaired = LocatorCodec.normalizeLocatorJson(corrupted)!!
        val locations = org.json.JSONObject(repaired).getJSONObject("locations")
        assertEquals("epubcfi(/6/11!/4/2,/1:0,/1:5)", locations.getString("fragment"))
        assertEquals(0.133829, locations.getDouble("progression"), 0.000001)
    }
}
