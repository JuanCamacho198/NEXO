package com.nexo.data.remote.addons

import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.entity.AddonEntity
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * U4 regression suite: the v2 manifest shape (optional/additive) and the
 * consent gate. A v2 row is adapted on read and served by the v3 fan-out, so
 * this suite covers the manifest vocabulary and consent bookkeeping only; the
 * payload reading is covered where the shaper lives.
 */
private const val V2_ADDON_ID = "a1b2c3d4e5f60718"
private const val V2_RESOLVE_TEMPLATE = "https://resolve.example/r?bookId={bookId}"

private val V1_MANIFEST_JSON =
    """
    {
      "id": "example-books",
      "name": "Example Books",
      "version": "1.0.0",
      "catalogs": [{ "type": "book-catalog", "id": "main", "name": "Example Catalog" }],
      "resources": ["search", "book-details"]
    }
    """.trimIndent()

private fun v2ManifestJson(): String =
    JSONObject()
        .put("id", "resolve-books")
        .put("name", "Resolve Books")
        .put("version", "1.0.0")
        .put(
            "catalogs",
            JSONArray().put(JSONObject().put("type", "book").put("id", "main").put("name", "Main")),
        ).put("resources", JSONArray().put("catalog"))
        .put("resolveUrl", V2_RESOLVE_TEMPLATE)
        .put("capabilities", JSONArray().put("resolve"))
        .toString()

private fun v2Manifest(): AddonManifest = ManifestValidator.validate(v2ManifestJson().toByteArray(StandardCharsets.UTF_8), "application/json")

private class V2FakeAddonDao : AddonDao {
    val rows = mutableListOf<AddonEntity>()

    override suspend fun getAll(): List<AddonEntity> = rows.toList()

    override suspend fun getById(id: String): AddonEntity? = rows.firstOrNull { it.id == id }

    override suspend fun getByUrl(url: String): AddonEntity? = rows.firstOrNull { it.url == url }

    override suspend fun upsert(addon: AddonEntity) {
        val i = rows.indexOfFirst { it.id == addon.id }
        if (i >= 0) rows[i] = addon else rows.add(addon)
    }

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].copy(enabled = enabled)
    }

    override suspend fun delete(id: String) {
        rows.removeAll { it.id == id }
    }
}

class AddonManifestV2ConsentTest {
    private fun bytes(json: String) = json.toByteArray(StandardCharsets.UTF_8)

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected INVALID_MANIFEST")
        } catch (err: AddonFetchException) {
            assertEquals(AddonFetchErrorCode.INVALID_MANIFEST, err.code)
        }
    }

    @Test
    fun `v1 manifest validates with v2 defaults`() {
        val manifest = ManifestValidator.validate(bytes(V1_MANIFEST_JSON), "application/json")
        assertNull(manifest.searchUrl)
        assertNull(manifest.detailsUrl)
        assertNull(manifest.resolveUrl)
        assertEquals(emptyList<String>(), manifest.capabilities)
    }

    @Test
    fun `v2 optional fields parse when present`() {
        val manifest = v2Manifest()
        assertEquals(V2_RESOLVE_TEMPLATE, manifest.resolveUrl)
        assertEquals(listOf("resolve"), manifest.capabilities)
    }

    @Test
    fun `v2 validation is conditional - absent fields are skipped`() {
        // v1 shape (no v2 keys at all) still validates: absent ⇒ skipped.
        ManifestValidator.validate(bytes(V1_MANIFEST_JSON), "application/json")
        // Explicit JSON nulls are treated as absent, not as errors.
        val nulled =
            JSONObject(v2ManifestJson())
                .put("resolveUrl", JSONObject.NULL)
                .put("capabilities", JSONObject.NULL)
                .toString()
        val manifest = ManifestValidator.validate(bytes(nulled), "application/json")
        assertNull(manifest.resolveUrl)
        assertEquals(emptyList<String>(), manifest.capabilities)
    }

    @Test
    fun `v2 validation rejects present-but-malformed fields`() {
        val base = JSONObject(v2ManifestJson())
        assertInvalid {
            ManifestValidator.validate(
                bytes(base.put("resolveUrl", "http://resolve.example/r").toString()),
                "application/json",
            )
        }
        assertInvalid {
            ManifestValidator.validate(
                bytes(JSONObject(v2ManifestJson()).put("capabilities", "resolve").toString()),
                "application/json",
            )
        }
        assertInvalid {
            ManifestValidator.validate(
                bytes(
                    JSONObject(v2ManifestJson())
                        .put("capabilities", JSONArray().put(""))
                        .toString(),
                ),
                "application/json",
            )
        }
    }

    @Test
    fun `accessType parsing fails closed on unknown values`() {
        assertEquals(AddonAccessType.FREE, ManifestValidator.parseAccessType("free"))
        assertEquals(AddonAccessType.BUY, ManifestValidator.parseAccessType("buy"))
        assertEquals(AddonAccessType.SUBSCRIBE, ManifestValidator.parseAccessType("subscribe"))
        assertNull(ManifestValidator.parseAccessType("rental"))
        assertNull(ManifestValidator.parseAccessType(null))
        assertNull(ManifestValidator.parseAccessType(""))
    }

    @Test
    fun `consent is recorded once per addon`() {
        val consent = InMemoryAddonConsentStore()
        assertFalse(consent.hasConsent(V2_ADDON_ID))
        consent.recordConsent(V2_ADDON_ID)
        consent.recordConsent(V2_ADDON_ID)
        assertTrue(consent.hasConsent(V2_ADDON_ID))
        consent.revokeConsent(V2_ADDON_ID)
        assertFalse(consent.hasConsent(V2_ADDON_ID))
    }

    @Test
    fun `registry records and gates consent per addon`() =
        runTest {
            val dao = V2FakeAddonDao()
            val registry = AddonRegistry(dao, FakeAddonHttpTransport())
            assertFalse(registry.hasAddonConsent(V2_ADDON_ID))
            registry.recordAddonConsent(V2_ADDON_ID)
            registry.recordAddonConsent(V2_ADDON_ID)
            assertTrue(registry.hasAddonConsent(V2_ADDON_ID))
            registry.revokeAddonConsent(V2_ADDON_ID)
            assertFalse(registry.hasAddonConsent(V2_ADDON_ID))
        }
}
