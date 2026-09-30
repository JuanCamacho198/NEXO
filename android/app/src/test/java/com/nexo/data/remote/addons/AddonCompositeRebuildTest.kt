package com.nexo.data.remote.addons

import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.entity.AddonEntity
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.CompositeCatalogProvider
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.data.remote.catalog.RebuildingCatalogProvider
import com.nexo.data.remote.catalog.addonSource
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val ENDPOINT_MANIFEST_JSON =
    """
{
  "id": "space-books",
  "name": "Space Books",
  "version": "1.0.0",
  "catalogs": [{ "type": "book", "id": "main", "name": "Main catalog" }],
  "resources": ["catalog"],
  "searchUrl": "https://space.example/search?q={query}&page={page}",
  "detailsUrl": "https://space.example/book/{bookId}"
}
    """.trimIndent()

private val INSTALL_URL = "https://space.example/manifest.json"

private val DETAILS_PAYLOAD: String =
    JSONObject().put("id", "book-1").put("title", "Space Book").toString()

private class FakeDao : AddonDao {
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

private class ScriptedTransport(
    vararg resources: AddonResource,
) : AddonHttpTransport {
    private val queue = resources.toMutableList()

    override suspend fun fetch(url: String): AddonResource = queue.removeAt(0)
}

private class PayloadTransport : AddonHttpTransport {
    val calls = mutableListOf<String>()

    override suspend fun fetch(url: String): AddonResource {
        calls.add(url)
        return AddonResource(200, "application/json", DETAILS_PAYLOAD.toByteArray(Charsets.UTF_8))
    }
}

private class FakeProvider(
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

class AddonCompositeRebuildTest {
    @Test
    fun `registry notifies change listeners on install, enable-disable and uninstall`() =
        runTest {
            val dao = FakeDao()
            val reg = AddonRegistry(dao, ScriptedTransport(AddonResource(200, "application/json", ENDPOINT_MANIFEST_JSON.toByteArray())))
            val versions = mutableListOf<Int>()
            reg.addOnChangedListener { versions.add(versions.size + 1) }

            reg.install(INSTALL_URL)
            val id = dao.rows[0].id
            reg.setEnabled(id, false)
            reg.setEnabled(id, true)
            reg.uninstall(id)

            assertEquals(listOf(1, 2, 3, 4), versions)
        }

    @Test
    fun `RebuildingCatalogProvider caches the delegate and rebuilds after invalidate`() =
        runTest {
            var builds = 0
            val live =
                RebuildingCatalogProvider {
                    builds += 1
                    FakeProvider("g", CatalogSourceKind.BUILTIN)
                }
            live.provider()
            live.provider()
            assertEquals(1, builds)
            live.invalidate()
            live.provider()
            assertEquals(2, builds)
        }

    @Test
    fun `registry mutations drive the live composite - install serves details, disable and uninstall stop contributing`() =
        runTest {
            val dao = FakeDao()
            val reg =
                AddonRegistry(
                    dao,
                    ScriptedTransport(AddonResource(200, "application/json", ENDPOINT_MANIFEST_JSON.toByteArray())),
                )
            val payloadTransport = PayloadTransport()
            val live =
                RebuildingCatalogProvider {
                    CompositeCatalogProvider(
                        catalogProvidersWithAddons(
                            builtIns = listOf(FakeProvider("g", CatalogSourceKind.BUILTIN), FakeProvider("ol", CatalogSourceKind.BUILTIN)),
                            curated = FakeProvider("curated", CatalogSourceKind.CURATED),
                            installedAddons = reg.listInstalled(),
                            addonTransport = payloadTransport,
                        ),
                    )
                }
            reg.addOnChangedListener { live.invalidate() }

            // Startup: zero addons.
            assertEquals(0, live.provider().listSources().count { it.kind == CatalogSourceKind.ADDON })

            reg.install(INSTALL_URL)
            val addonId = dao.rows[0].id
            val sources = live.provider().listSources().filter { it.kind == CatalogSourceKind.ADDON }
            assertEquals(listOf(addonSource(addonId)), sources.map { it.sourceId })
            val book = live.provider().getDetails("addon:$addonId:book-1")
            assertEquals("Space Book", book.title)
            assertEquals("addon:$addonId", book.provider)
            assertEquals(listOf("https://space.example/book/book-1"), payloadTransport.calls)

            // Disable: source unroutable, no new I/O.
            reg.setEnabled(addonId, false)
            assertEquals(0, live.provider().listSources().count { it.kind == CatalogSourceKind.ADDON })
            try {
                live.provider().getDetails("addon:$addonId:book-1")
                fail("expected NOT_FOUND")
            } catch (e: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, e.code)
            }
            assertEquals(1, payloadTransport.calls.size)

            // Re-enable then uninstall: same guarantee.
            reg.setEnabled(addonId, true)
            reg.uninstall(addonId)
            assertEquals(0, live.provider().listSources().count { it.kind == CatalogSourceKind.ADDON })
            try {
                live.provider().getDetails("addon:$addonId:book-1")
                fail("expected NOT_FOUND")
            } catch (e: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, e.code)
            }
            assertEquals(1, payloadTransport.calls.size)

            // Rows survive a fresh rebuild (restart parity).
            assertFalse(dao.rows.any { it.id == addonId })
            assertTrue(reg.listInstalled().isEmpty())
        }
}
