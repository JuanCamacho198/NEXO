package com.nexo.data.remote.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Endpoint-template parsing parity with desktop validate-manifest.test.ts. */
class ManifestEndpointTest {
    private fun manifest(vararg pairs: Pair<String, Any?>): ByteArray {
        val obj =
            org.json
                .JSONObject()
                .put("id", "example-books")
                .put("name", "Example Books")
                .put("version", "1.0.0")
                .put(
                    "catalogs",
                    org.json.JSONArray().put(
                        org.json
                            .JSONObject()
                            .put("type", "book")
                            .put("id", "main")
                            .put("name", "Main catalog"),
                    ),
                ).put("resources", org.json.JSONArray().put("catalog"))
        for ((key, value) in pairs) {
            if (value != null) obj.put(key, value)
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `parses https searchUrl and detailsUrl templates when present`() {
        val parsed =
            ManifestValidator.validate(
                manifest(
                    "searchUrl" to "https://example.com/search?q={query}&page={page}",
                    "detailsUrl" to "https://example.com/book/{bookId}",
                ),
                "application/json",
            )
        assertEquals("https://example.com/search?q={query}&page={page}", parsed.searchUrl)
        assertEquals("https://example.com/book/{bookId}", parsed.detailsUrl)
    }

    @Test
    fun `leaves absent endpoint fields null`() {
        val parsed = ManifestValidator.validate(manifest(), "application/json")
        assertNull(parsed.searchUrl)
        assertNull(parsed.detailsUrl)
    }

    @Test
    fun `rejects non-https endpoint templates`() {
        for (bad in listOf("http://example.com/s", "ftp://example.com/s", "not a url")) {
            try {
                ManifestValidator.validate(manifest("searchUrl" to bad), "application/json")
                fail("expected INVALID_MANIFEST for $bad")
            } catch (err: AddonFetchException) {
                assertEquals(AddonFetchErrorCode.INVALID_MANIFEST, err.code)
            }
        }
    }

    @Test
    fun `rejects oversized endpoint templates`() {
        val bad = "https://example.com/" + "a".repeat(2048)
        try {
            ManifestValidator.validate(manifest("detailsUrl" to bad), "application/json")
            fail("expected INVALID_MANIFEST")
        } catch (err: AddonFetchException) {
            assertEquals(AddonFetchErrorCode.INVALID_MANIFEST, err.code)
        }
    }

    @Test
    fun `unknown top-level fields are still ignored alongside endpoints`() {
        val parsed =
            ManifestValidator.validate(
                manifest("searchUrl" to "https://example.com/s?q={query}", "futureField" to mapOf("nested" to true)),
                "application/json",
            )
        assertEquals("https://example.com/s?q={query}", parsed.searchUrl)
        assertTrue(parsed.catalogs.isNotEmpty())
    }
}
