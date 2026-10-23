package com.nexo.data.remote.addons

import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.addonSource
import com.nexo.domain.access.AccessGroup
import com.nexo.domain.access.LegalAccess
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STREAM_ADDON_A = "dddd000000000004"
private const val STREAM_ADDON_B = "eeee000000000005"

private fun streamManifest(id: String) =
    v3Manifest(
        id,
        "Streams $id",
        catalogs = "[]",
        resources = """[{"name":"stream","types":["book"],"idPrefixes":["gutenberg:","openlibrary:"]}]""",
    )

private fun book(
    id: String = "gutenberg:1342",
    provider: String = addonSource(STREAM_ADDON_A),
): CatalogBook =
    CatalogBook(
        id = id,
        provider = provider,
        title = "Pride and Prejudice",
        authors = listOf("Jane Austen"),
        coverUrl = null,
        languages = emptyList(),
        subjects = emptyList(),
        downloadUrl = null,
    )

private fun streamsEnvelope(vararg streams: String): String = """{"streams":[${streams.joinToString(",")}]}"""

class AddonStreamResolveTest {
    @Test
    fun `an unconsented resolve returns an empty LegalAccess and never touches the network`() =
        runTest {
            val transport = FanoutTransport()
            val provider = providerFor(listOf(addonRow(STREAM_ADDON_A, "A", manifest = streamManifest(STREAM_ADDON_A))), transport)

            val access = provider.resolveAccess(book())

            assertEquals(LegalAccess("gutenberg:1342", false, null, emptyList()), access)
            assertTrue(transport.calls.isEmpty())
        }

    @Test
    fun `consent gates the stream resource only - catalog and meta answer without it`() =
        runTest {
            val transport =
                FanoutTransport(
                    mapOf(
                        "https://$STREAM_ADDON_A.example/catalog/book/main/search=dune.json" to
                            catalogEnvelope("gutenberg:1342" to "Pride and Prejudice"),
                        "https://$STREAM_ADDON_A.example/meta/book/gutenberg:1342.json" to
                            metaEnvelope("gutenberg:1342", "Pride and Prejudice"),
                    ),
                )
            val provider = providerFor(listOf(addonRow(STREAM_ADDON_A, "A")), transport)

            assertEquals(1, provider.search("dune", 1).results.size)
            assertEquals("Pride and Prejudice", provider.getDetails("gutenberg:1342").title)
            assertEquals(2, transport.calls.size)
            assertEquals(LegalAccess("gutenberg:1342", false, null, emptyList()), provider.resolveAccess(book()))
            assertEquals(2, transport.calls.size)
        }

    @Test
    fun `only the consented addon is queried for streams`() =
        runTest {
            val consent = InMemoryAddonConsentStore().apply { recordConsent(STREAM_ADDON_B) }
            val transport =
                FanoutTransport(
                    mapOf(
                        "https://$STREAM_ADDON_B.example/stream/book/gutenberg:1342.json" to
                            streamsEnvelope(
                                """{"name":"B","url":"https://cdn.example/b.epub","behaviorHints":{"accessType":"public-domain","format":"epub"}}""",
                            ),
                    ),
                )
            val provider =
                providerFor(
                    listOf(
                        addonRow(STREAM_ADDON_A, "A", addedAt = 1, manifest = streamManifest(STREAM_ADDON_A)),
                        addonRow(STREAM_ADDON_B, "B", addedAt = 2, manifest = streamManifest(STREAM_ADDON_B)),
                    ),
                    transport,
                    consent = consent,
                )

            val access = provider.resolveAccess(book())

            assertEquals(listOf("https://$STREAM_ADDON_B.example/stream/book/gutenberg:1342.json"), transport.calls)
            assertEquals(listOf("https://cdn.example/b.epub"), access.options.map { it.url })
            assertTrue(access.canDownloadInApp)
        }

    @Test
    fun `torrents are dropped and the deliverable stream is offered`() =
        runTest {
            val consent = InMemoryAddonConsentStore().apply { recordConsent(STREAM_ADDON_A) }
            val transport =
                FanoutTransport(
                    mapOf(
                        "https://$STREAM_ADDON_A.example/stream/book/gutenberg:1342.json" to
                            streamsEnvelope(
                                """{"name":"Torrent","infoHash":"abcdef","url":"https://cdn.example/a.epub"}""",
                                """{"name":"Magnet","sources":[{"type":"torrent","url":"magnet:?xt=urn:btih:abc"}]}""",
                                """{"name":"Needs consent to open","url":"https://cdn.example/book.epub","behaviorHints":{"accessType":"public-domain","format":"epub"}}""",
                            ),
                    ),
                )
            val provider = providerFor(listOf(addonRow(STREAM_ADDON_A, "A", manifest = streamManifest(STREAM_ADDON_A))), transport, consent = consent)

            val access = provider.resolveAccess(book())

            assertEquals(listOf("https://cdn.example/book.epub"), access.options.map { it.url })
            assertTrue(access.canDownloadInApp)
            assertEquals("https://cdn.example/book.epub", access.downloadUrl)
        }

    @Test
    fun `options follow the product order and a duplicate link is offered once`() =
        runTest {
            val consent = InMemoryAddonConsentStore().apply { recordConsent(STREAM_ADDON_A) }
            val transport =
                FanoutTransport(
                    mapOf(
                        "https://$STREAM_ADDON_A.example/stream/book/gutenberg:1342.json" to
                            streamsEnvelope(
                                """{"name":"Buy","externalUrl":"https://store.example/x","behaviorHints":{"accessType":"purchase"}}""",
                                """{"name":"Borrow","externalUrl":"https://lend.example/y","behaviorHints":{"accessType":"borrow","format":"pdf"}}""",
                                """{"name":"PD","url":"https://cdn.example/z.epub","behaviorHints":{"accessType":"public-domain","format":"epub"}}""",
                                """{"name":"PD again","url":"https://cdn.example/z.epub","behaviorHints":{"accessType":"public-domain","format":"epub"}}""",
                            ),
                    ),
                )
            val provider = providerFor(listOf(addonRow(STREAM_ADDON_A, "A", manifest = streamManifest(STREAM_ADDON_A))), transport, consent = consent)

            val access = provider.resolveAccess(book())

            assertEquals(
                listOf("https://cdn.example/z.epub", "https://lend.example/y", "https://store.example/x"),
                access.options.map { it.url },
            )
            assertEquals(listOf(AccessGroup.FREE, AccessGroup.SUBSCRIBE, AccessGroup.BUY), access.options.map { it.group })
            assertFalse(access.options.any { it.opensInApp })
        }

    @Test
    fun `an item already on the shelf keeps its options and is never offered for download`() =
        runTest {
            val consent = InMemoryAddonConsentStore().apply { recordConsent(STREAM_ADDON_A) }
            val transport =
                FanoutTransport(
                    mapOf(
                        "https://$STREAM_ADDON_A.example/stream/book/gutenberg:1342.json" to
                            streamsEnvelope(
                                """{"name":"PD","url":"https://cdn.example/z.epub","behaviorHints":{"accessType":"public-domain","format":"epub"}}""",
                            ),
                    ),
                )
            val looked = mutableListOf<String>()
            val provider =
                providerFor(
                    listOf(addonRow(STREAM_ADDON_A, "A", manifest = streamManifest(STREAM_ADDON_A))),
                    transport,
                    consent = consent,
                    isOwned = { id ->
                        looked += id
                        true
                    },
                )

            val access = provider.resolveAccess(book())

            assertEquals(listOf("gutenberg:1342"), looked)
            assertTrue(access.options.single().owned)
            assertFalse(access.canDownloadInApp)
            assertNull(access.downloadUrl)
        }

    @Test
    fun `an id the addon does not prefix-match resolves to nothing with zero IO`() =
        runTest {
            val consent = InMemoryAddonConsentStore().apply { recordConsent(STREAM_ADDON_A) }
            val transport = FanoutTransport()
            val provider = providerFor(listOf(addonRow(STREAM_ADDON_A, "A", manifest = streamManifest(STREAM_ADDON_A))), transport, consent = consent)

            val access = provider.resolveAccess(book(id = "isbn:9780141439518"))

            assertEquals(LegalAccess("isbn:9780141439518", false, null, emptyList()), access)
            assertTrue(transport.calls.isEmpty())
        }

    @Test
    fun `a failed stream fetch stays an empty resolution, never an error`() =
        runTest {
            val consent = InMemoryAddonConsentStore().apply { recordConsent(STREAM_ADDON_A) }
            val url = "https://$STREAM_ADDON_A.example/stream/book/gutenberg:1342.json"
            val transport = FanoutTransport(statuses = mapOf(url to 500))
            val provider = providerFor(listOf(addonRow(STREAM_ADDON_A, "A", manifest = streamManifest(STREAM_ADDON_A))), transport, consent = consent)

            val access = provider.resolveAccess(book())

            assertEquals(LegalAccess("gutenberg:1342", false, null, emptyList()), access)
            assertEquals(listOf(url), transport.calls)
        }
}
