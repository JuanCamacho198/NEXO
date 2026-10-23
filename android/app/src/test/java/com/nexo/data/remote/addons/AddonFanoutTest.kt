package com.nexo.data.remote.addons

import com.nexo.data.remote.addons.protocol.AddonClient
import com.nexo.data.remote.addons.protocol.AddonManifestParserV3
import com.nexo.data.remote.addons.protocol.AddonManifestV3
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.CompositeCatalogProvider
import com.nexo.data.remote.catalog.MAX_PAGE_SIZE
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.data.remote.catalog.addonSource
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val ADDON_A = "aaaa000000000001"
private const val ADDON_B = "bbbb000000000002"
private const val STREAM_ONLY = "cccc000000000003"

private const val CATALOG_RESOURCE = """["catalog",{"name":"meta","types":["book"],"idPrefixes":["gutenberg:","openlibrary:"]},{"name":"stream","types":["book"],"idPrefixes":["gutenberg:","openlibrary:"]}]"""

internal fun v3Manifest(
    id: String,
    name: String,
    catalogs: String = """[{"type":"book","id":"main","name":"Main","extra":[{"name":"search","isRequired":false},{"name":"skip","isRequired":false}]}]""",
    resources: String = CATALOG_RESOURCE,
): AddonManifestV3 =
    AddonManifestParserV3.loadManifestV3(
        """{"id":"$id","version":"3.0.0","name":"$name","types":["book"],"catalogs":$catalogs,"resources":$resources}"""
            .toByteArray(Charsets.UTF_8),
        "application/json",
    )

internal fun addonRow(
    id: String,
    name: String,
    addedAt: Long = 1L,
    manifest: AddonManifestV3 = v3Manifest(id, name),
    enabled: Boolean = true,
): InstalledAddonRow =
    InstalledAddonRow(
        id = id,
        url = "https://$id.example/manifest.json",
        manifest = manifest,
        enabled = enabled,
        addedAt = addedAt,
    )

/** Answers by exact URL; anything unscripted is a 404, which is never a search failure. */
internal class FanoutTransport(
    private val answers: Map<String, String> = emptyMap(),
    private val statuses: Map<String, Int> = emptyMap(),
) : AddonHttpTransport {
    val calls = mutableListOf<String>()

    override suspend fun fetch(url: String): AddonResource {
        calls += url
        val status = statuses[url]
        if (status != null) return AddonResource(status, "application/json", "{}".toByteArray(Charsets.UTF_8))
        val body = answers[url] ?: return AddonResource(404, "application/json", "{}".toByteArray(Charsets.UTF_8))
        return AddonResource(200, "application/json", body.toByteArray(Charsets.UTF_8))
    }
}

internal fun catalogEnvelope(vararg metas: Pair<String, String>): String =
    JSONObject()
        .put(
            "metas",
            JSONArray().apply {
                for ((id, name) in metas) {
                    put(JSONObject().put("id", id).put("type", "book").put("name", name))
                }
            },
        ).toString()

internal fun metaEnvelope(
    id: String,
    name: String,
    authors: List<String> = emptyList(),
    isbn13: String? = null,
): String {
    val meta =
        JSONObject().put("id", id).put("type", "book").put("name", name).put(
            "authors",
            JSONArray().apply { authors.forEach { put(it) } },
        )
    if (isbn13 != null) meta.put("isbn13", isbn13)
    return JSONObject().put("meta", meta).toString()
}

internal fun providerFor(
    rows: List<InstalledAddonRow>,
    transport: AddonHttpTransport,
    consent: AddonConsentStore = InMemoryAddonConsentStore(),
    isOwned: suspend (String) -> Boolean = { false },
): AddonCatalogProvider = AddonCatalogProvider(rows, AddonClient(AddonProtocolTransport(transport)), consent, isOwned)

/** Minimal built-in double: one source, empty pages, no I/O. */
internal class FanoutBuiltinProvider(
    sourceId: String = "builtin:gutendex",
    name: String = "Gutenberg",
) : CatalogProvider {
    private val source = CatalogSourceInfo(sourceId, name, CatalogSourceKind.BUILTIN)

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

class AddonFanoutTest {
    @Test
    fun `search unions every matching addon, dedupes by namespaced id and keeps install order`() =
        runTest {
            val transport =
                FanoutTransport(
                    mapOf(
                        "https://$ADDON_A.example/catalog/book/main/search=dune.json" to
                            catalogEnvelope("gutenberg:1342" to "Pride and Prejudice", "gutenberg:84" to "Frankenstein"),
                        "https://$ADDON_B.example/catalog/book/main/search=dune.json" to
                            catalogEnvelope("gutenberg:84" to "Frankenstein", "openlibrary:OL1W" to "Moby Dick"),
                    ),
                )
            val provider = providerFor(listOf(addonRow(ADDON_A, "A", addedAt = 1), addonRow(ADDON_B, "B", addedAt = 2)), transport)

            val page = provider.search("dune", 1)

            assertEquals(listOf("gutenberg:1342", "gutenberg:84", "openlibrary:OL1W"), page.results.map { it.id })
            assertEquals(
                listOf(addonSource(ADDON_A), addonSource(ADDON_A), addonSource(ADDON_B)),
                page.results.map { it.provider },
            )
            assertEquals(listOf("Pride and Prejudice", "Frankenstein", "Moby Dick"), page.results.map { it.title })
            assertEquals(3, page.totalCount)
            assertNull(page.nexoPage)
            assertEquals(2, transport.calls.size)
        }

    @Test
    fun `one failing addon never fails the search`() =
        runTest {
            val url = "https://$ADDON_A.example/catalog/book/main/search=dune.json"
            val transport =
                FanoutTransport(
                    answers = mapOf("https://$ADDON_B.example/catalog/book/main/search=dune.json" to catalogEnvelope("gutenberg:84" to "Frankenstein")),
                    statuses = mapOf(url to 500),
                )
            val provider = providerFor(listOf(addonRow(ADDON_A, "A", addedAt = 1), addonRow(ADDON_B, "B", addedAt = 2)), transport)

            val page = provider.search("dune", 1)

            assertEquals(listOf("gutenberg:84"), page.results.map { it.id })
            assertEquals(addonSource(ADDON_B), page.results[0].provider)
        }

    @Test
    fun `a page past the first one sends skip instead of a page number`() =
        runTest {
            val transport =
                FanoutTransport(
                    mapOf("https://$ADDON_A.example/catalog/book/main/search=dune&skip=$MAX_PAGE_SIZE.json" to catalogEnvelope()),
                )
            val provider = providerFor(listOf(addonRow(ADDON_A, "A")), transport)

            provider.search("dune", 2)

            assertEquals(listOf("https://$ADDON_A.example/catalog/book/main/search=dune&skip=$MAX_PAGE_SIZE.json"), transport.calls)
        }

    @Test
    fun `a truncated union reports the next page and clamps to the page size`() =
        runTest {
            val metas = (1..(MAX_PAGE_SIZE + 8)).map { "gutenberg:$it" to "Book $it" }.toTypedArray()
            val transport =
                FanoutTransport(
                    mapOf("https://$ADDON_A.example/catalog/book/main/search=dune.json" to catalogEnvelope(*metas)),
                )
            val provider = providerFor(listOf(addonRow(ADDON_A, "A")), transport)

            val page = provider.search("dune", 1)

            assertEquals(MAX_PAGE_SIZE, page.results.size)
            assertEquals(2, page.nexoPage)
        }

    @Test
    fun `getDetails answers a namespaced id through the meta resource`() =
        runTest {
            val transport =
                FanoutTransport(
                    mapOf(
                        "https://$ADDON_A.example/meta/book/gutenberg:1342.json" to
                            metaEnvelope("gutenberg:1342", "Pride and Prejudice", listOf("Jane Austen"), isbn13 = "9780141439518"),
                    ),
                )
            val provider = providerFor(listOf(addonRow(ADDON_A, "A")), transport)

            val book = provider.getDetails("gutenberg:1342")

            assertEquals("gutenberg:1342", book.id)
            assertEquals(addonSource(ADDON_A), book.provider)
            assertEquals("Pride and Prejudice", book.title)
            assertEquals(listOf("Jane Austen"), book.authors)
            assertEquals("9780141439518", book.isbn13)
            assertEquals(listOf("https://$ADDON_A.example/meta/book/gutenberg:1342.json"), transport.calls)
        }

    @Test
    fun `getDetails on an id no addon declares rejects NOT_FOUND with zero IO`() =
        runTest {
            val transport = FanoutTransport()
            val provider = providerFor(listOf(addonRow(ADDON_A, "A")), transport)

            try {
                provider.getDetails("isbn:9780141439518")
                fail("expected NOT_FOUND")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, err.code)
            }
            assertTrue(transport.calls.isEmpty())
        }

    @Test
    fun `the composite delegates a namespaced id to the addon fan-out`() =
        runTest {
            val transport =
                FanoutTransport(
                    mapOf("https://$ADDON_A.example/meta/book/gutenberg:1342.json" to metaEnvelope("gutenberg:1342", "Pride and Prejudice")),
                )
            val composite = CompositeCatalogProvider(listOf(FanoutBuiltinProvider(), providerFor(listOf(addonRow(ADDON_A, "A")), transport)))

            val book = composite.getDetails("gutenberg:1342")

            assertEquals("Pride and Prejudice", book.title)
            assertEquals(addonSource(ADDON_A), book.provider)
        }

    @Test
    fun `searchSource scopes the fan-out to a single addon and fails closed otherwise`() =
        runTest {
            val transport =
                FanoutTransport(
                    mapOf("https://$ADDON_B.example/catalog/book/main/search=dune.json" to catalogEnvelope("gutenberg:84" to "Frankenstein")),
                )
            val provider = providerFor(listOf(addonRow(ADDON_A, "A", addedAt = 1), addonRow(ADDON_B, "B", addedAt = 2)), transport)

            val page = provider.searchSource(addonSource(ADDON_B), "dune", 1)

            assertEquals(listOf("gutenberg:84"), page.results.map { it.id })
            assertEquals(listOf("https://$ADDON_B.example/catalog/book/main/search=dune.json"), transport.calls)

            val unknown = provider.searchSource(addonSource("ffff000000000009"), "dune", 1)
            assertEquals(0, unknown.results.size)
            assertEquals(1, transport.calls.size)
        }

    @Test
    fun `a stream-only addon contributes no search results and surfaces no error`() =
        runTest {
            val transport = FanoutTransport()
            val streamOnly =
                addonRow(
                    STREAM_ONLY,
                    "Streams Only",
                    manifest =
                        v3Manifest(
                            STREAM_ONLY,
                            "Streams Only",
                            catalogs = "[]",
                            resources = """[{"name":"stream","types":["book"],"idPrefixes":["gutenberg:"]}]""",
                        ),
                )
            val provider = providerFor(listOf(streamOnly), transport)

            val page = provider.search("dune", 1)

            assertEquals(0, page.results.size)
            assertEquals(0, page.totalCount)
            assertNull(page.nexoPage)
            assertTrue(transport.calls.isEmpty())
            assertEquals(listOf(addonSource(STREAM_ONLY)), provider.listSources().map { it.sourceId })

            val composite = CompositeCatalogProvider(listOf(provider))
            val throughComposite = composite.search("dune", 1)
            assertEquals(0, throughComposite.results.size)
            assertTrue(transport.calls.isEmpty())
        }

    @Test
    fun `a disabled addon is listed nowhere and answers nothing`() =
        runTest {
            val transport = FanoutTransport()
            val provider = providerFor(listOf(addonRow(ADDON_A, "A", enabled = false)), transport)

            assertTrue(provider.listSources().isEmpty())
            assertEquals(0, provider.search("dune", 1).results.size)
            assertTrue(transport.calls.isEmpty())
        }

    // -------------------------------------------------------------- legacy v2

    /** A stored v2 row, adapted on read exactly as the registry adapts one. */
    private fun legacyV2Row(id: String = ADDON_A): InstalledAddonRow =
        addonRow(
            id,
            "Space Books",
            manifest =
                AddonManifestParserV3.loadManifestV3(
                    """
                    {
                      "id": "space-books",
                      "name": "Space Books",
                      "version": "2.0.0",
                      "catalogs": [{ "type": "book-catalog", "id": "main", "name": "Main catalog" }],
                      "resources": ["search", "book-details", "resolve"],
                      "searchUrl": "https://space.example/search?q={query}",
                      "detailsUrl": "https://space.example/book/{bookId}",
                      "resolveUrl": "https://space.example/resolve/{bookId}",
                      "capabilities": ["resolve"]
                    }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                    "application/json",
                ),
        )

    private fun legacyV2CatalogPayload(): String =
        JSONObject()
            .put(
                "results",
                JSONArray()
                    .put(JSONObject().put("id", "1342").put("title", "Pride and Prejudice").put("authors", JSONArray().put("Jane Austen")))
                    .put(JSONObject().put("id", "84").put("title", "Frankenstein"))
                    .put(JSONObject().put("id", "11")),
            ).put("totalCount", 3)
            .toString()

    @Test
    fun `a legacy v2 row reads its catalog through the fan-out instead of being skipped`() =
        runTest {
            val transport =
                FanoutTransport(mapOf("https://space.example/search?q=dune" to legacyV2CatalogPayload()))
            val provider = providerFor(listOf(legacyV2Row()), transport)

            val page = provider.search("dune", 1)

            // Ids stay verbatim: the addon resolves its own ids through `{bookId}`.
            assertEquals(listOf("1342", "84"), page.results.map { it.id })
            assertEquals(listOf("Pride and Prejudice", "Frankenstein"), page.results.map { it.title })
            assertEquals(listOf("Jane Austen"), page.results[0].authors)
            // The entry with no title is not a book; totalCount is not carried.
            assertEquals(2, page.totalCount)
            assertNull(page.nexoPage)
            assertEquals(listOf("https://space.example/search?q=dune"), transport.calls)
        }

    @Test
    fun `a legacy v2 row answers meta and stream from its rewritten templates`() =
        runTest {
            val transport =
                FanoutTransport(
                    mapOf(
                        "https://space.example/book/1342" to
                            JSONObject()
                                .put("id", "1342")
                                .put("title", "Pride and Prejudice")
                                .put("authors", JSONArray().put("Jane Austen"))
                                .toString(),
                        "https://space.example/resolve/1342" to
                            JSONObject()
                                .put(
                                    "streams",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("url", "https://files.example/1342.epub")
                                            .put("behaviorHints", JSONObject().put("accessType", "public-domain")),
                                    ),
                                ).toString(),
                    ),
                )
            val consent = InMemoryAddonConsentStore().apply { recordConsent(ADDON_A) }
            val provider = providerFor(listOf(legacyV2Row()), transport, consent)

            val book = provider.getDetails("1342")
            assertEquals("Pride and Prejudice", book.title)
            // A v2 book payload's `downloadUrl` is not a meta field: files come from `stream`.
            assertNull(book.downloadUrl)

            val access = provider.resolveAccess(book)
            assertTrue(access.canDownloadInApp)
            assertEquals("https://files.example/1342.epub", access.downloadUrl)
            assertEquals(
                listOf("https://space.example/book/1342", "https://space.example/resolve/1342"),
                transport.calls,
            )
        }
}
