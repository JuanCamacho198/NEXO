package com.nexo.data.remote.addons

import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.entity.AddonEntity
import com.nexo.data.remote.catalog.CatalogBook
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
 * U4 regression suite: manifest v2 (optional/additive) + registry consent
 * gate + free-gate end to end. Strictly additive: v1 manifests validate and
 * behave byte-identically (defaults only, no new validation).
 */
private const val V2_ADDON_ID = "a1b2c3d4e5f60718"
private const val V2_RESOLVE_TEMPLATE =
    "https://resolve.example/r?isbn={isbn}&title={title}&author={author}&olid={openLibraryId}&gbid={googleBooksId}"

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

private fun resolveItemJson(
    accessType: String? = "free",
    license: String? = null,
    readUrl: String? = "https://read.example/b1",
    downloadUrl: String? = "https://dl.example/b1.epub",
): JSONObject =
    JSONObject()
        .put("id", "b1")
        .put("title", "Dune")
        .apply {
            if (accessType != null) put("accessType", accessType) else put("accessType", JSONObject.NULL)
            if (license != null) put("license", license)
            if (readUrl != null) put("readUrl", readUrl)
            if (downloadUrl != null) put("downloadUrl", downloadUrl)
        }

private fun resolvePayload(vararg items: JSONObject): String = JSONObject().put("results", JSONArray(items.toList())).put("totalCount", items.size).toString()

private fun book(publicDomain: Boolean?): CatalogBook =
    CatalogBook(
        id = "builtin:gutendex:123",
        provider = "builtin:gutendex",
        title = "The Odyssey",
        authors = listOf("Homer"),
        coverUrl = null,
        languages = listOf("en"),
        subjects = emptyList(),
        downloadUrl = null,
        isPublicDomain = publicDomain,
        isbn13 = "9780140449136",
        openLibraryWorkId = "/works/OL123W",
        googleBooksId = "abc123",
    )

/** Recording transport: canned JSON body, every fetched URL captured. */
private class RecordingAddonTransport(
    private val body: String,
) : AddonHttpTransport {
    val urls = mutableListOf<String>()

    override suspend fun fetch(url: String): AddonResource {
        urls.add(url)
        return AddonResource(200, "application/json", body.toByteArray(StandardCharsets.UTF_8))
    }
}

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
    fun `v1 manifest validates with v2 defaults and behaves byte-identically`() =
        runTest {
            val manifest = ManifestValidator.validate(bytes(V1_MANIFEST_JSON), "application/json")
            assertNull(manifest.searchUrl)
            assertNull(manifest.detailsUrl)
            assertNull(manifest.resolveUrl)
            assertEquals(emptyList<String>(), manifest.capabilities)
            // Endpoint-less v1 provider: stable empty page, NOT_FOUND details, zero I/O.
            val transport = RecordingAddonTransport(resolvePayload(resolveItemJson()))
            val provider = AddonCatalogProvider(manifest, V2_ADDON_ID, transport)
            val page = provider.search("anything", 1)
            assertEquals(0, page.results.size)
            assertEquals(0, page.totalCount)
            assertNull(page.nexoPage)
            assertEquals(0, transport.urls.size)
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
    fun `no consent means empty resolve with zero network io`() =
        runTest {
            val transport = RecordingAddonTransport(resolvePayload(resolveItemJson()))
            val provider = AddonCatalogProvider(v2Manifest(), V2_ADDON_ID, transport)
            val access = provider.resolveAccess(book(publicDomain = true))
            assertFalse(access.canDownloadInApp)
            assertNull(access.downloadUrl)
            assertEquals(0, access.options.size)
            assertEquals(0, transport.urls.size)
        }

    @Test
    fun `consent without resolveUrl stays empty with zero network io`() =
        runTest {
            val consent = InMemoryAddonConsentStore()
            consent.recordConsent(V2_ADDON_ID)
            val v1 = ManifestValidator.validate(bytes(V1_MANIFEST_JSON), "application/json")
            val transport = RecordingAddonTransport(resolvePayload(resolveItemJson()))
            val provider = AddonCatalogProvider(v1, V2_ADDON_ID, transport, consent = consent)
            val access = provider.resolveAccess(book(publicDomain = true))
            assertFalse(access.canDownloadInApp)
            assertNull(access.downloadUrl)
            assertEquals(0, access.options.size)
            assertEquals(0, transport.urls.size)
        }

    @Test
    fun `consent resolves the url template from book identity`() =
        runTest {
            val consent = InMemoryAddonConsentStore()
            consent.recordConsent(V2_ADDON_ID)
            val transport = RecordingAddonTransport(resolvePayload(resolveItemJson()))
            val provider = AddonCatalogProvider(v2Manifest(), V2_ADDON_ID, transport, consent = consent)
            provider.resolveAccess(book(publicDomain = true))
            assertEquals(1, transport.urls.size)
            assertEquals(
                "https://resolve.example/r?isbn=9780140449136&title=The%20Odyssey" +
                    "&author=Homer&olid=%2Fworks%2FOL123W&gbid=abc123",
                transport.urls[0],
            )
        }

    @Test
    fun `free item plus public domain book flows to in-app download`() =
        runTest {
            val consent = InMemoryAddonConsentStore()
            consent.recordConsent(V2_ADDON_ID)
            val transport = RecordingAddonTransport(resolvePayload(resolveItemJson()))
            val provider = AddonCatalogProvider(v2Manifest(), V2_ADDON_ID, transport, consent = consent)
            val access = provider.resolveAccess(book(publicDomain = true))
            assertTrue(access.canDownloadInApp)
            assertEquals("https://dl.example/b1.epub", access.downloadUrl)
            assertEquals(1, access.options.size)
            assertEquals("https://read.example/b1", access.options[0].url)
            assertFalse(access.options[0].opensInApp)
        }

    @Test
    fun `free item on a non-public-domain book never downloads in-app`() =
        runTest {
            val consent = InMemoryAddonConsentStore()
            consent.recordConsent(V2_ADDON_ID)
            val transport = RecordingAddonTransport(resolvePayload(resolveItemJson()))
            val provider = AddonCatalogProvider(v2Manifest(), V2_ADDON_ID, transport, consent = consent)
            for (pd in listOf(false, null)) {
                val access = provider.resolveAccess(book(publicDomain = pd))
                assertFalse(access.canDownloadInApp)
                assertNull(access.downloadUrl)
                // External-open link still resolves.
                assertEquals(1, access.options.size)
            }
        }

    @Test
    fun `non-free items resolve to external-open links only`() =
        runTest {
            val consent = InMemoryAddonConsentStore()
            consent.recordConsent(V2_ADDON_ID)
            val transport =
                RecordingAddonTransport(
                    resolvePayload(
                        resolveItemJson(
                            accessType = "buy",
                            readUrl = "https://shop.example/b1",
                            downloadUrl = "https://dl.example/b1.epub",
                        ),
                    ),
                )
            val provider = AddonCatalogProvider(v2Manifest(), V2_ADDON_ID, transport, consent = consent)
            val access = provider.resolveAccess(book(publicDomain = true))
            assertFalse(access.canDownloadInApp)
            assertNull(access.downloadUrl)
            assertEquals(1, access.options.size)
            assertEquals("https://shop.example/b1", access.options[0].url)
            assertFalse(access.options[0].opensInApp)
        }

    @Test
    fun `license-cleared item may download in-app for public domain books`() =
        runTest {
            val consent = InMemoryAddonConsentStore()
            consent.recordConsent(V2_ADDON_ID)
            val transport =
                RecordingAddonTransport(
                    resolvePayload(resolveItemJson(accessType = "buy", license = "public-domain")),
                )
            val provider = AddonCatalogProvider(v2Manifest(), V2_ADDON_ID, transport, consent = consent)
            val access = provider.resolveAccess(book(publicDomain = true))
            assertTrue(access.canDownloadInApp)
            assertEquals("https://dl.example/b1.epub", access.downloadUrl)
        }

    @Test
    fun `unknown accessType is never downloadable`() =
        runTest {
            val consent = InMemoryAddonConsentStore()
            consent.recordConsent(V2_ADDON_ID)
            val transport = RecordingAddonTransport(resolvePayload(resolveItemJson(accessType = "rental")))
            val provider = AddonCatalogProvider(v2Manifest(), V2_ADDON_ID, transport, consent = consent)
            val access = provider.resolveAccess(book(publicDomain = true))
            assertFalse(access.canDownloadInApp)
            assertNull(access.downloadUrl)
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

    @Test
    fun `manifest json roundtrip preserves v2 fields and decodes v1 caches`() {
        val manifest = v2Manifest()
        val decoded = AddonManifestJson.decode(AddonManifestJson.encode(manifest))
        assertEquals(manifest.resolveUrl, decoded.resolveUrl)
        assertEquals(manifest.capabilities, decoded.capabilities)
        // Old cached rows without v2 keys keep decoding (additive defaults).
        val legacy = AddonManifestJson.decode(V1_MANIFEST_JSON)
        assertNull(legacy.resolveUrl)
        assertEquals(emptyList<String>(), legacy.capabilities)
    }
}
