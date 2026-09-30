package com.nexo.data.remote.addons

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Manifest validation parity suite. Vectors mirror
 * desktop/src/test/unit/services/addons/validate-manifest.test.ts and the
 * shared fixtures under src/test/resources/addons/fixtures (also copied to
 * desktop/test fixtures).
 */
class ManifestValidatorTest {
    private val validManifest =
        """
        {
          "id": "example-books",
          "name": "Example Books",
          "version": "1.0.0",
          "catalogs": [{ "type": "book-catalog", "id": "main", "name": "Example Catalog" }],
          "resources": ["search", "book-details"]
        }
        """.trimIndent()

    private fun bytes(json: String) = json.toByteArray(StandardCharsets.UTF_8)

    private fun assertCode(
        code: AddonFetchErrorCode,
        block: () -> Unit,
    ) {
        try {
            block()
            throw AssertionError("expected AddonFetchException with code $code")
        } catch (err: AddonFetchException) {
            assertEquals(code, err.code)
        }
    }

    @Test
    fun `error codes match the shared additive contract`() {
        assertEquals("HTTPS_REQUIRED", AddonFetchErrorCode.HTTPS_REQUIRED.name)
        assertEquals("TOO_LARGE", AddonFetchErrorCode.TOO_LARGE.name)
        assertEquals("BAD_CONTENT_TYPE", AddonFetchErrorCode.BAD_CONTENT_TYPE.name)
        assertEquals("INVALID_MANIFEST", AddonFetchErrorCode.INVALID_MANIFEST.name)
        assertEquals("NETWORK", AddonFetchErrorCode.NETWORK.name)
        // Full wire codes are ADDON_FETCH_-prefixed (parity with TS/Rust).
        assertEquals("ADDON_FETCH_HTTPS_REQUIRED", AddonFetchErrorCode.HTTPS_REQUIRED.wireCode)
        assertEquals("ADDON_FETCH_TOO_LARGE", AddonFetchErrorCode.TOO_LARGE.wireCode)
        assertEquals("ADDON_FETCH_BAD_CONTENT_TYPE", AddonFetchErrorCode.BAD_CONTENT_TYPE.wireCode)
        assertEquals("ADDON_FETCH_INVALID_MANIFEST", AddonFetchErrorCode.INVALID_MANIFEST.wireCode)
        assertEquals("ADDON_FETCH_NETWORK", AddonFetchErrorCode.NETWORK.wireCode)
    }

    @Test
    fun `accepts a valid manifest`() {
        val manifest = ManifestValidator.validate(bytes(validManifest), "application/json")
        assertEquals("example-books", manifest.id)
        assertEquals("Example Books", manifest.name)
        assertEquals("1.0.0", manifest.version)
        assertEquals(1, manifest.catalogs.size)
        assertEquals(listOf("search", "book-details"), manifest.resources)
    }

    @Test
    fun `rejects http url before any io`() {
        assertCode(AddonFetchErrorCode.HTTPS_REQUIRED) {
            ManifestValidator.assertHttpsInstallUrl("http://example.com/manifest.json")
        }
    }

    @Test
    fun `rejects non-http schemes before any io`() {
        for (url in listOf("ftp://example.com/m.json", "file:///etc/manifest.json", "javascript:alert(1)")) {
            assertCode(AddonFetchErrorCode.HTTPS_REQUIRED) {
                ManifestValidator.assertHttpsInstallUrl(url)
            }
        }
    }

    @Test
    fun `accepts https url`() {
        assertTrue(ManifestValidator.assertHttpsInstallUrl("https://example.com/manifest.json"))
    }

    @Test
    fun `rejects oversize before parse`() {
        assertCode(AddonFetchErrorCode.TOO_LARGE) {
            ManifestValidator.validate(ByteArray(ManifestValidator.MAX_MANIFEST_BYTES + 1), "application/json")
        }
    }

    @Test
    fun `accepts exactly 64kb`() {
        // Well-formed but truncated at exactly MAX bytes: must pass the size gate
        // (then fail shape, which proves the cap did not reject it).
        assertCode(AddonFetchErrorCode.INVALID_MANIFEST) {
            ManifestValidator.validate(ByteArray(ManifestValidator.MAX_MANIFEST_BYTES), "application/json")
        }
    }

    @Test
    fun `rejects html content type`() {
        assertCode(AddonFetchErrorCode.BAD_CONTENT_TYPE) {
            ManifestValidator.validate(bytes(validManifest), "text/html; charset=utf-8")
        }
    }

    @Test
    fun `rejects plain text content type`() {
        assertCode(AddonFetchErrorCode.BAD_CONTENT_TYPE) {
            ManifestValidator.validate(bytes(validManifest), "text/plain")
        }
    }

    @Test
    fun `rejects null content type`() {
        assertCode(AddonFetchErrorCode.BAD_CONTENT_TYPE) {
            ManifestValidator.validate(bytes(validManifest), null)
        }
    }

    @Test
    fun `accepts json structured suffix content type`() {
        ManifestValidator.validate(bytes(validManifest), "application/manifest+json")
    }

    @Test
    fun `rejects malformed json`() {
        assertCode(AddonFetchErrorCode.INVALID_MANIFEST) {
            ManifestValidator.validate(bytes("{not json"), "application/json")
        }
    }

    @Test
    fun `rejects each missing required field`() {
        for (key in listOf("id", "name", "version", "catalogs", "resources")) {
            val obj = JSONObject(validManifest)
            obj.remove(key)
            assertCode(AddonFetchErrorCode.INVALID_MANIFEST) {
                ManifestValidator.validate(obj.toString().toByteArray(StandardCharsets.UTF_8), "application/json")
            }
        }
    }

    @Test
    fun `rejects wrong-typed fields`() {
        val cases =
            listOf(
                """{"id":42,"name":"n","version":"1","catalogs":[{"type":"t","id":"i","name":"n"}],"resources":["r"]}""",
                """{"id":"i","name":"","version":"1","catalogs":[{"type":"t","id":"i","name":"n"}],"resources":["r"]}""",
                """{"id":"i","name":"n","version":null,"catalogs":[{"type":"t","id":"i","name":"n"}],"resources":["r"]}""",
                """{"id":"i","name":"n","version":"1","catalogs":"main","resources":["r"]}""",
                """{"id":"i","name":"n","version":"1","catalogs":[],"resources":["r"]}""",
                """{"id":"i","name":"n","version":"1","catalogs":[{"type":"t","id":"i","name":"n"}],"resources":"search"}""",
            )
        for (json in cases) {
            assertCode(AddonFetchErrorCode.INVALID_MANIFEST) {
                ManifestValidator.validate(bytes(json), "application/json")
            }
        }
    }

    @Test
    fun `rejects catalog entries missing type id name`() {
        val cases =
            listOf(
                """[{"type":"t","id":"i"}]""",
                """[{"type":"t","name":"n"}]""",
                """[{"id":"i","name":"n"}]""",
                """[{"type":1,"id":"i","name":"n"}]""",
            )
        for (catalogs in cases) {
            val json = """{"id":"i","name":"n","version":"1","catalogs":$catalogs,"resources":["r"]}"""
            assertCode(AddonFetchErrorCode.INVALID_MANIFEST) {
                ManifestValidator.validate(bytes(json), "application/json")
            }
        }
    }

    @Test
    fun `ignores unknown fields`() {
        val json =
            """
            {
              "id": "example-books",
              "name": "Example Books",
              "version": "1.0.0",
              "catalogs": [{ "type": "book-catalog", "id": "main", "name": "Example Catalog", "extra": "ignored" }],
              "resources": ["search"],
              "futureTopLevelField": { "nested": true },
              "another": 7
            }
            """.trimIndent()
        val manifest = ManifestValidator.validate(bytes(json), "application/json")
        assertEquals("example-books", manifest.id)
    }

    @Test
    fun `shared fixtures produce the shared outcome`() {
        val names =
            listOf(
                "valid",
                "unknown-fields",
                "http-url",
                "oversize",
                "html-content-type",
                "missing-fields",
                "catalog-entry-missing-name",
            )
        for (name in names) {
            val fixtureJson =
                javaClass
                    .getResourceAsStream("/addons/fixtures/$name.json")
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.readText()
                    ?: throw AssertionError("missing fixture $name")
            val fixture = JSONObject(fixtureJson)
            val expect = fixture.getString("expect")
            try {
                when {
                    fixture.has("url") -> ManifestValidator.assertHttpsInstallUrl(fixture.getString("url"))
                    fixture.has("rawBytesLength") ->
                        ManifestValidator.validate(
                            ByteArray(fixture.getInt("rawBytesLength")),
                            fixture.optString("contentType", "application/json"),
                        )
                    else ->
                        ManifestValidator.validate(
                            fixture.getJSONObject("manifest").toString().toByteArray(StandardCharsets.UTF_8),
                            fixture.optString("contentType", "application/json"),
                        )
                }
                assertEquals("fixture $name should pass", "pass", expect)
            } catch (err: AddonFetchException) {
                assertEquals("fixture $name", fixture.getString("expectedCode"), err.code.wireCode)
            }
        }
    }
}
