package com.nexo.data.remote.addons

import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.entity.AddonEntity
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.data.remote.catalog.addonSource
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val VALID_MANIFEST_JSON =
    """
    {
      "id": "my-addon",
      "name": "My Addon",
      "version": "1.0.0",
      "catalogs": [{ "type": "book", "id": "main", "name": "Main catalog" }],
      "resources": ["catalog"]
    }
    """.trimIndent()

private fun manifestJson(
    id: String = "my-addon",
    name: String = "My Addon",
    version: String = "1.0.0",
): String =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .put("version", version)
        .put(
            "catalogs",
            JSONArray().put(JSONObject().put("type", "book").put("id", "main").put("name", "Main catalog")),
        ).put("resources", JSONArray().put("catalog"))
        .toString()

private fun resource(
    json: String? = null,
    name: String = "My Addon",
    version: String = "1.0.0",
    status: Int = 200,
    contentType: String? = "application/json",
) = AddonResource(
    status,
    contentType,
    (json ?: manifestJson(name = name, version = version)).toByteArray(Charsets.UTF_8),
)

/** Transport returning canned resources per call, in order (last one repeats). */
private class ScriptedAddonTransport(
    vararg resources: AddonResource,
) : AddonHttpTransport {
    private val queue = resources.toMutableList()
    var calls = 0
        private set

    override suspend fun fetch(url: String): AddonResource {
        calls += 1
        return if (queue.size > 1) queue.removeAt(0) else queue[0]
    }
}

/** In-memory mirror of the Room AddonDao UPSERT semantics (reinstall preserves enabled). */
private class FakeAddonDao : AddonDao {
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

private class FakeCatalogProvider(
    name: String,
    kind: CatalogSourceKind,
) : CatalogProvider {
    private val source = CatalogSourceInfo("fake:$name", name, kind)

    override fun listSources(): List<CatalogSourceInfo> = listOf(source)

    override suspend fun search(
        query: String,
        page: Int,
    ): PagedResult = PagedResult(emptyList(), null, 0)

    override suspend fun getDetails(id: String): Nothing = throw CatalogException(CatalogErrorCode.NOT_FOUND, id)

    override fun resolveDownloadUrl(
        formats: Map<String, String>,
        preferEpub: Boolean,
    ): String = ""
}

class AddonRegistryTest {
    private fun registry(
        dao: FakeAddonDao,
        vararg resources: AddonResource,
    ): AddonRegistry = AddonRegistry(dao, ScriptedAddonTransport(*resources))

    @Test
    fun `install stores validated manifest keyed by sha256 addonId`() =
        runTest {
            val dao = FakeAddonDao()
            val url = "https://example.com/manifest.json"
            val manifest = registry(dao, resource()).install(url)
            assertEquals("my-addon", manifest.id)
            assertEquals(1, dao.rows.size)
            assertEquals(AddonId.fromUrl(url), dao.rows[0].id)
            assertEquals(url, dao.rows[0].url)
            assertTrue(dao.rows[0].enabled)
            assertEquals("My Addon", JSONObject(dao.rows[0].manifestJson).getString("name"))
        }

    @Test
    fun `install rejects non-https urls before any network io`() =
        runTest {
            val dao = FakeAddonDao()
            val transport = ScriptedAddonTransport(resource())
            val reg = AddonRegistry(dao, transport)
            for (url in listOf("http://example.com/manifest.json", "ftp://example.com/manifest.json")) {
                try {
                    reg.install(url)
                    fail("expected HTTPS_REQUIRED for $url")
                } catch (err: AddonFetchException) {
                    assertEquals(AddonFetchErrorCode.HTTPS_REQUIRED, err.code)
                }
            }
            assertEquals(0, transport.calls)
            assertEquals(0, dao.rows.size)
        }

    @Test
    fun `reinstall is idempotent - manifest updated, enabled preserved`() =
        runTest {
            val dao = FakeAddonDao()
            val url = "https://example.com/manifest.json"
            val reg = registry(dao, resource(), resource(version = "2.0.0"))
            reg.install(url)
            val id = dao.rows[0].id
            reg.setEnabled(id, false)
            reg.install(url)
            assertEquals(1, dao.rows.size)
            assertEquals(id, dao.rows[0].id)
            assertEquals("2.0.0", JSONObject(dao.rows[0].manifestJson).getString("version"))
            assertFalse(dao.rows[0].enabled)
        }

    @Test
    fun `install rejects non-2xx status as network error`() =
        runTest {
            val dao = FakeAddonDao()
            try {
                registry(dao, resource(status = 404)).install("https://example.com/manifest.json")
                fail("expected NETWORK")
            } catch (err: AddonFetchException) {
                assertEquals(AddonFetchErrorCode.NETWORK, err.code)
            }
            assertEquals(0, dao.rows.size)
        }

    @Test
    fun `install rejects html content type`() =
        runTest {
            val dao = FakeAddonDao()
            try {
                registry(dao, resource(json = "<html>not a manifest</html>", contentType = "text/html"))
                    .install("https://example.com/manifest.json")
                fail("expected BAD_CONTENT_TYPE")
            } catch (err: AddonFetchException) {
                assertEquals(AddonFetchErrorCode.BAD_CONTENT_TYPE, err.code)
            }
            assertEquals(0, dao.rows.size)
        }

    @Test
    fun `install rejects oversized manifests before parse`() =
        runTest {
            val dao = FakeAddonDao()
            try {
                registry(dao, AddonResource(200, "application/json", ByteArray(65 * 1024)))
                    .install("https://example.com/manifest.json")
                fail("expected TOO_LARGE")
            } catch (err: AddonFetchException) {
                assertEquals(AddonFetchErrorCode.TOO_LARGE, err.code)
            }
            assertEquals(0, dao.rows.size)
        }

    @Test
    fun `install rejects manifest id colliding with built-in source name`() =
        runTest {
            val dao = FakeAddonDao()
            try {
                registry(dao, resource(json = manifestJson(id = "gutendex")))
                    .install("https://example.com/manifest.json")
                fail("expected INVALID_MANIFEST")
            } catch (err: AddonFetchException) {
                assertEquals(AddonFetchErrorCode.INVALID_MANIFEST, err.code)
            }
            assertEquals(0, dao.rows.size)
        }

    @Test
    fun `installManifest persists without refetching - single transport call`() =
        runTest {
            val dao = FakeAddonDao()
            val url = "https://example.com/manifest.json"
            val transport = ScriptedAddonTransport(resource())
            val reg = AddonRegistry(dao, transport)
            val manifest =
                ManifestValidator.validate(
                    manifestJson().toByteArray(Charsets.UTF_8),
                    "application/json",
                )
            reg.installManifest(url, manifest)
            assertEquals(0, transport.calls)
            assertEquals(1, dao.rows.size)
            assertEquals(AddonId.fromUrl(url), dao.rows[0].id)
            assertTrue(dao.rows[0].enabled)
        }

    @Test
    fun `installManifest preserves enabled and addedAt on reinstall like install`() =
        runTest {
            val dao = FakeAddonDao()
            val url = "https://example.com/manifest.json"
            val reg = registry(dao, resource())
            reg.install(url)
            val id = dao.rows[0].id
            val addedAt = dao.rows[0].addedAt
            reg.setEnabled(id, false)
            val updated =
                ManifestValidator.validate(
                    manifestJson(version = "2.0.0").toByteArray(Charsets.UTF_8),
                    "application/json",
                )
            reg.installManifest(url, updated)
            assertEquals(1, dao.rows.size)
            assertFalse(dao.rows[0].enabled)
            assertEquals(addedAt, dao.rows[0].addedAt)
            assertEquals("2.0.0", JSONObject(dao.rows[0].manifestJson).getString("version"))
        }

    @Test
    fun `installManifest rejects builtin id collision before dao write`() =
        runTest {
            val dao = FakeAddonDao()
            val reg = registry(dao, resource())
            val colliding =
                ManifestValidator.validate(
                    manifestJson(id = "gutendex").toByteArray(Charsets.UTF_8),
                    "application/json",
                )
            try {
                reg.installManifest("https://example.com/manifest.json", colliding)
                fail("expected INVALID_MANIFEST")
            } catch (err: AddonFetchException) {
                assertEquals(AddonFetchErrorCode.INVALID_MANIFEST, err.code)
            }
            assertEquals(0, dao.rows.size)
        }

    @Test
    fun `install delegates to fetchManifest plus installManifest with exactly one fetch`() =
        runTest {
            val dao = FakeAddonDao()
            val transport = ScriptedAddonTransport(resource())
            val reg = AddonRegistry(dao, transport)
            reg.install("https://example.com/manifest.json")
            assertEquals(1, transport.calls)
            assertEquals(1, dao.rows.size)
        }

    @Test
    fun `fetchManifest returns validated manifest without dao write`() =
        runTest {
            val dao = FakeAddonDao()
            val reg = registry(dao, resource(name = "Preview Addon"))
            val manifest = reg.fetchManifest("https://example.com/manifest.json")
            assertEquals("Preview Addon", manifest.name)
            assertEquals(0, dao.rows.size)
        }

    @Test
    fun `uninstall removes the row - other rows unaffected`() =
        runTest {
            val dao = FakeAddonDao()
            val reg = registry(dao, resource(name = "One"), resource(name = "Two"))
            reg.install("https://one.example/m.json")
            reg.install("https://two.example/m.json")
            val before = reg.listInstalled()
            assertEquals(2, before.size)
            reg.uninstall(before[0].id)
            val after = reg.listInstalled()
            assertEquals(1, after.size)
            assertFalse(after[0].id == before[0].id)
        }

    @Test
    fun `setEnabled persists the toggle`() =
        runTest {
            val dao = FakeAddonDao()
            val reg = registry(dao, resource())
            reg.install("https://example.com/manifest.json")
            val id = dao.rows[0].id
            reg.setEnabled(id, false)
            assertFalse(reg.listInstalled()[0].enabled)
            reg.setEnabled(id, true)
            assertTrue(reg.listInstalled()[0].enabled)
        }

    @Test
    fun `listInstalled returns rows in install order with parsed manifests`() =
        runTest {
            val dao = FakeAddonDao()
            val reg = registry(dao, resource(name = "One"), resource(name = "Two"))
            reg.install("https://one.example/m.json")
            reg.install("https://two.example/m.json")
            val rows = reg.listInstalled()
            assertTrue(rows[0].addedAt <= rows[1].addedAt)
            assertEquals("One", rows[0].manifest.name)
        }

    @Test
    fun `catalogProviders appends enabled addons in install order and excludes disabled`() =
        runTest {
            val dao = FakeAddonDao()
            val reg = registry(dao, resource(name = "One"), resource(name = "Two"))
            reg.install("https://one.example/m.json")
            reg.install("https://two.example/m.json")
            reg.setEnabled(dao.rows[0].id, false)

            val rows = reg.listInstalled()
            val providers =
                catalogProvidersWithAddons(
                    builtIns = listOf(FakeCatalogProvider("g", CatalogSourceKind.BUILTIN), FakeCatalogProvider("ol", CatalogSourceKind.BUILTIN)),
                    curated = FakeCatalogProvider("curated", CatalogSourceKind.CURATED),
                    installedAddons = rows,
                )
            val sources = providers.flatMap { it.listSources() }
            assertEquals(1, sources.count { it.kind == CatalogSourceKind.ADDON })
            assertEquals(addonSource(dao.rows[1].id), sources.last { it.kind == CatalogSourceKind.ADDON }.sourceId)
            // built-ins first, curated next, addons last
            assertEquals(CatalogSourceKind.BUILTIN, sources[0].kind)
            assertEquals(CatalogSourceKind.BUILTIN, sources[1].kind)
            assertEquals(CatalogSourceKind.CURATED, sources[2].kind)
            assertEquals(CatalogSourceKind.ADDON, sources[3].kind)
        }

    @Test
    fun `catalogProviders with zero addons matches the zero-addon parity set`() {
        val providers =
            catalogProvidersWithAddons(
                builtIns = listOf(FakeCatalogProvider("g", CatalogSourceKind.BUILTIN), FakeCatalogProvider("ol", CatalogSourceKind.BUILTIN)),
                curated = FakeCatalogProvider("curated", CatalogSourceKind.CURATED),
                installedAddons = emptyList(),
            )
        val kinds = providers.flatMap { it.listSources() }.map { it.kind }
        assertEquals(listOf(CatalogSourceKind.BUILTIN, CatalogSourceKind.BUILTIN, CatalogSourceKind.CURATED), kinds)
    }

    @Test
    fun `addon catalog provider is browse-only and exposes one addon source`() =
        runTest {
            val addonId = AddonId.fromUrl("https://example.com/manifest.json")
            val manifest = ManifestValidator.validate(VALID_MANIFEST_JSON.toByteArray(), "application/json")
            val provider = AddonCatalogProvider(manifest, addonId, FakeAddonHttpTransport())
            val sources = provider.listSources()
            assertEquals(1, sources.size)
            assertEquals(addonSource(addonId), sources[0].sourceId)
            assertEquals(CatalogSourceKind.ADDON, sources[0].kind)
            assertEquals("My Addon", sources[0].name)
            val page = provider.search("anything", 1)
            assertEquals(0, page.results.size)
            assertEquals(null, page.nexoPage)
            assertEquals(0, page.totalCount)
            try {
                provider.getDetails("addon:$addonId:some-book")
                fail("expected NOT_FOUND")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, err.code)
            }
        }
}
