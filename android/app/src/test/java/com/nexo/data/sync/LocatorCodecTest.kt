package com.nexo.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PR4 Android parity — LocatorCodec CFI-first derived page (desktop LocatorCodec.ts parity).
 *
 * Spec: page = LocatorCodec.fromCfi(cfiRange||cfiLocation) ?? 1, current_page deprecated.
 * Valid epubcfi(/6/N!) → 1, malformed/null → null (caller falls back to 1).
 */
class LocatorCodecTest {
    @Test
    fun parseSpineIndex_validCfi_returnsIndex() {
        assertEquals(4, LocatorCodec.parseSpineIndex("epubcfi(/6/4!/4/2)"))
        assertEquals(6, LocatorCodec.parseSpineIndex("epubcfi(/6/6!/4/10)"))
        assertEquals(1, LocatorCodec.parseSpineIndex("epubcfi(/6/1)"))
    }

    @Test
    fun parseSpineIndex_malformed_returnsNull() {
        assertNull(LocatorCodec.parseSpineIndex(null))
        assertNull(LocatorCodec.parseSpineIndex(""))
        assertNull(LocatorCodec.parseSpineIndex("not-a-cfi"))
        assertNull(LocatorCodec.parseSpineIndex("epubcfi(/6/0)"))
        assertNull(LocatorCodec.parseSpineIndex("epubcfi(/6/-1)"))
    }

    @Test
    fun fromCfi_valid_returns1() {
        assertEquals(1, LocatorCodec.fromCfi("epubcfi(/6/4!/4/2)"))
        assertEquals(1, LocatorCodec.fromCfi("epubcfi(/6/6!/4/2)"))
    }

    @Test
    fun fromCfi_invalid_returnsNull() {
        assertNull(LocatorCodec.fromCfi(null))
        assertNull(LocatorCodec.fromCfi(""))
        assertNull(LocatorCodec.fromCfi("bad"))
    }

    @Test
    fun derivedPage_aliasMatchesFromCfi() {
        assertEquals(LocatorCodec.fromCfi("epubcfi(/6/4!/4/2)"), LocatorCodec.derivePage("epubcfi(/6/4!/4/2)"))
        assertNull(LocatorCodec.derivePage(null))
    }

    @Test
    fun derivedPage_fallback_is1_viaElvis() {
        // Usage: val page = LocatorCodec.fromCfi(cfiRange ?: cfiLocation) ?: 1
        val cfi: String? = null
        val page = LocatorCodec.fromCfi(cfi) ?: 1
        assertEquals(1, page)

        val validCfi = "epubcfi(/6/4!/4/2)"
        val page2 = LocatorCodec.fromCfi(validCfi) ?: 1
        assertEquals(1, page2)
    }
}
