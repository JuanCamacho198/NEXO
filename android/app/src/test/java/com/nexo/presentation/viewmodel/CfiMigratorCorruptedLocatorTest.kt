package com.nexo.presentation.viewmodel

import com.nexo.data.sync.LocatorCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-path repair (T3) at the seam `EpubBookLoader` uses to build the reader's initial locator.
 *
 * The loader calls `CfiMigrator.jsonToLocator(locatorJson)`, which is `normalizeLocatorJson` and
 * then Readium's `Locator.fromJSON`. Readium's parse cannot run on the JVM (see [jvmParseLimitation]
 * below), so this class pins the half that decides the outcome — the href the repaired JSON hands
 * to Readium — through the same codec entry point the loader uses.
 *
 * Every vector below is the row the pre-T2 writer persisted on a real device.
 */
class CfiMigratorCorruptedLocatorTest {
    /** The row the pre-T2 writer persisted on a real device, verbatim. */
    private val corruptedRow =
        """{"href":"OEBPS//supercomunicadores-5.xhtml","type":"application//xhtml+xml","locations":{"progression":0.133829,"fragment":"epubcfi(/6/11!/4/2,/1:0,/1:5)"}}"""

    /** The same row as T2's writer produces it. */
    private val cleanRow =
        """{"href":"OEBPS/supercomunicadores-5.xhtml","type":"application/xhtml+xml","locations":{"progression":0.133829,"fragment":"epubcfi(/6/11!/4/2,/1:0,/1:5)"}}"""

    /**
     * Readium's `Locator.fromJSON` resolves the href through `android.net.Uri.parse`, which is not
     * mocked in a JVM unit test, so *every* parse — clean or corrupted — yields null here. The
     * integration is therefore covered by the live pass and by [LocatorCodec]'s own tests, and this
     * class covers the JSON contract the loader depends on: what href the loader's parse sees.
     */
    @Test
    fun jvmParseLimitation_readiumParserIsNotRunnableOffDevice() {
        assertTrue(
            "Readium Locator parsing is expected to be unavailable on the JVM; " +
                "if this ever passes, promote this class to a full integration test",
            CfiMigrator.jsonToLocator(cleanRow) == null,
        )
    }

    /** The loader's first step hands Readium an href with a single separator, not `//`. */
    @Test
    fun loaderInput_corruptedRow_repairedBeforeReadiumParses() {
        val forReadium = LocatorCodec.normalizeLocatorJson(corruptedRow)
        assertNotNull(forReadium)
        val href = JSONObject(forReadium!!).getString("href")
        assertEquals("OEBPS/supercomunicadores-5.xhtml", href)
        assertTrue(!href.contains("//"))
    }

    /** Repairing the corrupted row and reading the clean row agree on the href — that is the heal. */
    @Test
    fun loaderInput_corruptedRow_resolvesLikeCleanRow() {
        val repairedHref =
            JSONObject(LocatorCodec.normalizeLocatorJson(corruptedRow)!!).getString("href")
        val cleanHref = JSONObject(LocatorCodec.normalizeLocatorJson(cleanRow)!!).getString("href")
        assertEquals(cleanHref, repairedHref)
    }

    /** Idempotent through the very entry point the loader uses. */
    @Test
    fun loaderInput_cleanAndRepairedRows_areStable() {
        assertEquals(cleanRow, LocatorCodec.normalizeLocatorJson(cleanRow))
        val once = LocatorCodec.normalizeLocatorJson(corruptedRow)!!
        assertEquals(once, LocatorCodec.normalizeLocatorJson(once))
    }

    /** An href with no `//` is untouched — the repair is not a general rewriter. */
    @Test
    fun loaderInput_cleanHref_untouched() {
        assertEquals(cleanRow, LocatorCodec.normalizeLocatorJson(cleanRow))
    }

    /**
     * The `type` field is not inert: Readium's `Locator.fromJSON` parses it as a `MediaType`, so a
     * doubled slash makes the whole parse fail. Both fields are therefore repaired on read.
     */
    @Test
    fun loaderInput_doubledMediaType_repaired() {
        val repaired = LocatorCodec.normalizeLocatorJson(corruptedRow)!!
        assertEquals("application/xhtml+xml", JSONObject(repaired).getString("type"))
    }

    /** The CFI carrying the reader's mid-chapter precision survives the repair untouched. */
    @Test
    fun loaderInput_cfiFragmentPreserved() {
        val repaired = LocatorCodec.normalizeLocatorJson(corruptedRow)!!
        val locations = JSONObject(repaired).getJSONObject("locations")
        assertEquals("epubcfi(/6/11!/4/2,/1:0,/1:5)", locations.getString("fragment"))
        assertEquals(0.133829, locations.getDouble("progression"), 0.000001)
    }

    /** `locatorToJson` must never write the corrupted shape back out. */
    @Test
    fun writerOutput_repairedLocator_serializesSingleSlashHref() {
        val repaired = LocatorCodec.normalizeLocatorJson(corruptedRow)!!
        val locator = LocatorCodec.locatorFromJson(repaired)
        assertNotNull(locator)
        assertEquals("OEBPS/supercomunicadores-5.xhtml", locator!!.href)
        assertEquals("OEBPS/supercomunicadores-5.xhtml", JSONObject(LocatorCodec.locatorToJson(locator)).getString("href"))
    }
}
