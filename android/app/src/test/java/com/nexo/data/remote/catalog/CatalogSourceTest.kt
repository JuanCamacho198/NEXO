package com.nexo.data.remote.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Strict source-id parsing — mirrors desktop catalog-source.test.ts vectors. */
class CatalogSourceTest {
    private val addonId = "a1b2c3d4e5f60718"

    @Test
    fun `accepts the exact built-in ids`() {
        assertEquals(BUILTIN_GUTENDEX, parseCatalogSource("builtin:gutendex"))
        assertEquals(BUILTIN_OPENLIBRARY, parseCatalogSource("builtin:openlibrary"))
    }

    @Test
    fun `accepts addon sources with a 16-hex addonId`() {
        assertEquals("addon:$addonId", parseCatalogSource("addon:$addonId"))
        assertEquals("addon:$addonId", addonSource(addonId))
        assertEquals(addonId, addonSourceIdOf("addon:$addonId"))
    }

    @Test
    fun `addonSourceIdOf returns null for non-addon sources`() {
        assertNull(addonSourceIdOf(BUILTIN_GUTENDEX))
    }

    private fun expectRejected(raw: String) {
        try {
            parseCatalogSource(raw)
            fail("expected CatalogException for $raw")
        } catch (err: CatalogException) {
            assertEquals(CatalogErrorCode.NOT_FOUND, err.code)
        }
    }

    @Test
    fun `rejects bare names without a namespace prefix`() {
        expectRejected("gutendex")
        expectRejected("openlibrary")
    }

    @Test
    fun `rejects empty addon ids`() {
        expectRejected("addon:")
    }

    @Test
    fun `rejects unknown builtin ids`() {
        expectRejected("builtin:other")
    }

    @Test
    fun `rejects trailing junk and case drift on builtin ids`() {
        expectRejected("builtin:gutendex:x")
        expectRejected("builtin:gutendex ")
        expectRejected("builtin:GUTENDEX")
    }

    @Test
    fun `rejects malformed addon ids`() {
        expectRejected("addon:xyz")
        expectRejected("addon:ABCDEF0123456789")
        expectRejected("addon:${addonId}extra")
    }

    @Test
    fun `rejects unknown namespaces`() {
        expectRejected("curated:gutendex")
        expectRejected("")
    }
}
