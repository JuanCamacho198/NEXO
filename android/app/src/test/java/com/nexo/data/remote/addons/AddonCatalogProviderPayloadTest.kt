package com.nexo.data.remote.addons

import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.CompositeCatalogProvider
import com.nexo.data.remote.catalog.MAX_PAGE_SIZE
import com.nexo.data.remote.catalog.PagedResult
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

private const val ADDON_ID = "a1b2c3d4e5f60718"

private val SPACE_MANIFEST_JSON =
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

private fun spaceManifest(): AddonManifest = ManifestValidator.validate(SPACE_MANIFEST_JSON.toByteArray(), "application/json")

private fun bookJson(
    id: String = "book-1",
    title: String = "Dune",
): String =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("authors", JSONArray().put("Herbert"))
        .put("languages", JSONArray().put("en"))
        .put("subjects", JSONArray().put("sci-fi"))
        .toString()

private val SPACE_PAYLOAD: String =
    JSONObject()
        .put("results", JSONArray().put(JSONObject(bookJson())))
        .put("totalCount", 1)
        .toString()

/** Fake transport answering by URL host prefix, recording every call. */
private class RoutingTransport(
    private val routes: Map<String, Pair<Int, String>>,
    private val oversizeHosts: Set<String> = emptySet(),
) : AddonHttpTransport {
    val calls = mutableListOf<String>()

    override suspend fun fetch(url: String): AddonResource {
        calls.add(url)
        val host = Regex("https://([^/?]+)").find(url)?.groupValues?.get(1) ?: url
        if (host in oversizeHosts) {
            return AddonResource(200, "application/json", ByteArray(65 * 1024))
        }
        val (status, body) =
            routes[host]
                ?: throw IllegalStateException("unexpected addon fetch $url")
        return AddonResource(status, "application/json", body.toByteArray(Charsets.UTF_8))
    }
}

class AddonCatalogProviderPayloadTest {
    @Test
    fun `search renders the searchUrl template and maps payload books to addon source ids`() =
        runTest {
            val transport = RoutingTransport(mapOf("space.example" to (200 to SPACE_PAYLOAD)))
            val provider = AddonCatalogProvider(spaceManifest(), ADDON_ID, transport)
            val page = provider.search("dune mess iah", 1)
            assertEquals(listOf("https://space.example/search?q=dune%20mess%20iah&page=1"), transport.calls)
            assertEquals(1, page.results.size)
            assertEquals("addon:$ADDON_ID:book-1", page.results[0].id)
            assertEquals("addon:$ADDON_ID", page.results[0].provider)
            assertEquals("Dune", page.results[0].title)
            assertEquals(listOf("Herbert"), page.results[0].authors)
            assertEquals(null, page.results[0].coverUrl)
            assertEquals(1, page.totalCount)
            assertNull(page.nextPage)
        }

    @Test
    fun `clamps results to the shared page size and computes nextPage from totalCount`() =
        runTest {
            val many = JSONArray()
            for (i in 0 until 40) many.put(JSONObject().put("id", "b$i").put("title", "Book $i"))
            val payload = JSONObject().put("results", many).put("totalCount", 100).toString()
            val transport = RoutingTransport(mapOf("space.example" to (200 to payload)))
            val provider = AddonCatalogProvider(spaceManifest(), ADDON_ID, transport)
            val page = provider.search("x", 1)
            assertEquals(MAX_PAGE_SIZE, page.results.size)
            assertEquals(100, page.totalCount)
            assertEquals(2, page.nextPage)
        }

    @Test
    fun `order test R2-1 built-ins first, then addons A, B, C in install order`() =
        runTest {
            val hosts = listOf("a.example", "b.example", "c.example")
            val routes =
                hosts.associate {
                    val payload =
                        JSONObject()
                            .put("results", JSONArray().put(JSONObject().put("id", "only").put("title", "$it Book")))
                            .put("totalCount", 1)
                            .toString()
                    it to (200 to payload)
                }
            val transport = RoutingTransport(routes)
            val providers =
                hosts.mapIndexed { i, host ->
                    val addonId = AddonId.fromUrl("https://$host/m.json")
                    val manifestJson =
                        SPACE_MANIFEST_JSON
                            .replace("space.example", host)
                            .replace("space-books", "addon-$i")
                    AddonCatalogProvider(ManifestValidator.validate(manifestJson.toByteArray(), "application/json"), addonId, transport)
                }
            val builtin = FakeBuiltinProvider()
            val composite = CompositeCatalogProvider(listOf(builtin) + providers)
            val page = composite.search("dune", 1)
            assertEquals(
                providers.map { it.listSources()[0].sourceId },
                page.results.map { it.provider },
            )
            assertEquals(3, transport.calls.size)
        }

    @Test
    fun `getDetails routes to the owning addon and renders the detailsUrl R3-1`() =
        runTest {
            val transport = RoutingTransport(mapOf("space.example" to (200 to bookJson(id = "book-7", title = "Detail Book"))))
            val provider = AddonCatalogProvider(spaceManifest(), ADDON_ID, transport)
            val composite = CompositeCatalogProvider(listOf(provider))
            val detail = composite.getDetails("addon:$ADDON_ID:book-7")
            assertEquals("addon:$ADDON_ID:book-7", detail.id)
            assertEquals("addon:$ADDON_ID", detail.provider)
            assertEquals("Detail Book", detail.title)
            assertEquals(listOf("https://space.example/book/book-7"), transport.calls)
        }

    @Test
    fun `malformed payloads reject the whole page with a stable error (no partial page)`() =
        runTest {
            val cases =
                listOf(
                    "not json at all",
                    "42",
                    "{}",
                    JSONObject().put("results", "nope").toString(),
                    JSONObject().put("results", JSONArray().put(JSONObject().put("title", "no id"))).toString(),
                    JSONObject()
                        .put("results", JSONArray().put(JSONObject(bookJson())).put(JSONObject().put("id", "y")))
                        .toString(),
                    JSONObject()
                        .put("results", JSONArray().put(JSONObject().put("id", "x").put("title", "ok").put("authors", "nope")))
                        .toString(),
                )
            for (bad in cases) {
                val transport = RoutingTransport(mapOf("space.example" to (200 to bad)))
                val provider = AddonCatalogProvider(spaceManifest(), ADDON_ID, transport)
                try {
                    provider.search("q", 1)
                    fail("expected UPSTREAM_ERROR")
                } catch (e: CatalogException) {
                    assertEquals(CatalogErrorCode.UPSTREAM_ERROR, e.code)
                }
                assertEquals(1, transport.calls.size)
            }
            val detailTransport = RoutingTransport(mapOf("space.example" to (200 to JSONObject().put("title", "no id").toString())))
            val provider = AddonCatalogProvider(spaceManifest(), ADDON_ID, detailTransport)
            try {
                provider.getDetails("addon:$ADDON_ID:x")
                fail("expected UPSTREAM_ERROR")
            } catch (e: CatalogException) {
                assertEquals(CatalogErrorCode.UPSTREAM_ERROR, e.code)
            }
        }

    @Test
    fun `enforces the payload size cap before parse`() =
        runTest {
            val transport = RoutingTransport(mapOf("space.example" to (200 to SPACE_PAYLOAD)), oversizeHosts = setOf("space.example"))
            val provider = AddonCatalogProvider(spaceManifest(), ADDON_ID, transport)
            try {
                provider.search("q", 1)
                fail("expected UPSTREAM_ERROR")
            } catch (e: CatalogException) {
                assertEquals(CatalogErrorCode.UPSTREAM_ERROR, e.code)
            }
        }

    @Test
    fun `maps non-2xx statuses to stable catalog codes after at most one retry`() =
        runTest {
            val rateLimited = RoutingTransport(mapOf("space.example" to (429 to "{}")))
            val provider = AddonCatalogProvider(spaceManifest(), ADDON_ID, rateLimited, retryDelayMs = 0)
            try {
                provider.search("q", 1)
                fail("expected RATE_LIMITED")
            } catch (e: CatalogException) {
                assertEquals(CatalogErrorCode.RATE_LIMITED, e.code)
            }
            assertEquals(2, rateLimited.calls.size)

            val notFound = RoutingTransport(mapOf("space.example" to (404 to "{}")))
            val provider404 = AddonCatalogProvider(spaceManifest(), ADDON_ID, notFound, retryDelayMs = 0)
            try {
                provider404.getDetails("addon:$ADDON_ID:x")
                fail("expected NOT_FOUND")
            } catch (e: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, e.code)
            }
            assertEquals(1, notFound.calls.size)

            val upstream = RoutingTransport(mapOf("space.example" to (500 to "{}")))
            val provider500 = AddonCatalogProvider(spaceManifest(), ADDON_ID, upstream, retryDelayMs = 0)
            try {
                provider500.search("q", 1)
                fail("expected UPSTREAM_ERROR")
            } catch (e: CatalogException) {
                assertEquals(CatalogErrorCode.UPSTREAM_ERROR, e.code)
            }
            assertEquals(2, upstream.calls.size)
        }

    @Test
    fun `endpoint-less manifests stay browse-only with zero IO`() =
        runTest {
            val transport = RoutingTransport(mapOf("space.example" to (200 to SPACE_PAYLOAD)))
            val bareJson =
                SPACE_MANIFEST_JSON
                    .replace(",\n  \"searchUrl\": \"https://space.example/search?q={query}&page={page}\"", "")
                    .replace(",\n  \"detailsUrl\": \"https://space.example/book/{bookId}\"", "")
            val manifest = ManifestValidator.validate(bareJson.toByteArray(), "application/json")
            assertNull(manifest.searchUrl)
            assertNull(manifest.detailsUrl)
            val provider = AddonCatalogProvider(manifest, ADDON_ID, transport)
            val page = provider.search("q", 1)
            assertEquals(0, page.results.size)
            assertEquals(0, page.totalCount)
            assertNull(page.nextPage)
            try {
                provider.getDetails("addon:$ADDON_ID:x")
                fail("expected NOT_FOUND")
            } catch (e: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, e.code)
            }
            assertEquals(0, transport.calls.size)
        }

    @Test
    fun `endpoints without transport fail fast at construction`() {
        try {
            AddonCatalogProvider(spaceManifest(), ADDON_ID, transport = null)
            fail("expected IllegalStateException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `manifest json encode roundtrip preserves endpoint templates`() {
        val manifest = spaceManifest()
        val encoded = AddonManifestJson.encode(manifest)
        val decoded = AddonManifestJson.decode(encoded)
        assertEquals(manifest.searchUrl, decoded.searchUrl)
        assertEquals(manifest.detailsUrl, decoded.detailsUrl)
    }
}

/** Minimal built-in double: one source, empty results, no I/O. */
private class FakeBuiltinProvider : com.nexo.data.remote.catalog.CatalogProvider {
    private val source =
        com.nexo.data.remote.catalog
            .CatalogSourceInfo("builtin:gutendex", "Gutendex", CatalogSourceKind.BUILTIN)

    override fun listSources() = listOf(source)

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
