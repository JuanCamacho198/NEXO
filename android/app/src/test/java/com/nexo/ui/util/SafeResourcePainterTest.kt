package com.nexo.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * FIX 2 regression: the shared guard behind [safePainterResource] must turn the
 * Compose unsupported-resource [IllegalArgumentException] ("Only VectorDrawables
 * and rasterized asset types are supported ex. PNG, JPG, WEBP") into a graceful
 * placeholder, record the offending source, and never mask any other failure.
 */
class SafeResourcePainterTest {
    @Test
    fun `unsupported drawable degrades to fallback and reports the source`() {
        var reported: IllegalArgumentException? = null

        val resolved =
            resolvePainterOrFallback(
                loader = {
                    throw IllegalArgumentException(
                        "Only VectorDrawables and rasterized asset types are supported ex. PNG, JPG, WEBP",
                    )
                },
                fallback = { "fallback" },
                onUnsupported = { reported = it },
            )

        assertEquals("fallback", resolved)
        assertNotNull("The unsupported asset must be reported", reported)
    }

    @Test
    fun `supported drawable returns the loaded painter without fallback`() {
        var reported = false

        val resolved =
            resolvePainterOrFallback(
                loader = { "real" },
                fallback = { "fallback" },
                onUnsupported = { reported = true },
            )

        assertEquals("real", resolved)
        assertFalse("A successful load must not report an unsupported asset", reported)
    }

    @Test
    fun `non-IllegalArgumentException still propagates`() {
        try {
            resolvePainterOrFallback(
                loader = { throw IllegalStateException("boom") },
                fallback = { "fallback" },
                onUnsupported = { },
            )
            fail("A genuine failure must not be swallowed by the guard")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
