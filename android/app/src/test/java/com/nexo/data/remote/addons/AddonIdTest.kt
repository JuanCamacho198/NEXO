package com.nexo.data.remote.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform parity vectors for addonId = first 16 hex chars of sha256(url).
 * Desktop counterpart: desktop/src/test/unit/services/addons/addon-id.test.ts.
 */
class AddonIdTest {
    @Test
    fun `returns first 16 hex chars of sha256 for parity vectors`() {
        val vectors =
            listOf(
                "https://example.com/manifest.json" to "1eb50a3f96621a44",
                "HTTPS://EXAMPLE.COM/MANIFEST.JSON" to "a462b1f139b195fa",
                "https://example.com/manifest.json?v=2" to "f80b43dc80cace9a",
            )
        for ((url, expected) in vectors) {
            assertEquals("addonId($url)", expected, AddonId.fromUrl(url))
        }
    }

    @Test
    fun `returns 16-char lowercase hex for arbitrary urls`() {
        val id = AddonId.fromUrl("https://example.com/a/b?x=1#frag")
        assertTrue(id.matches(Regex("^[0-9a-f]{16}$")))
    }
}
