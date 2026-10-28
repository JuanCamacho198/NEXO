package com.nexo.data.remote.catalog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Offline mirror of the desktop datasource suite (same fixtures, fake transport). */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogDataSourcesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader
            ?.getResourceAsStream("catalog/$name")
            ?.bufferedReader()
            ?.readText()
            ?: error("missing test fixture catalog/$name")

    private fun ok(body: String) = CatalogHttpResponse(200, body)

    // ── Gutendex ────────────────────────────────────────────────

    @Test fun gutendexFeatured_emitsOnlyVerifiedSortLiterals() =
        runTest {
            val transport = FakeCatalogHttpTransport({ ok(fixture("gutendex-search.json")) })
            val ds = GutendexDataSource(transport)
            val popular = ds.featured(CatalogFeaturedSort.POPULAR, 1)
            val newest = ds.featured(CatalogFeaturedSort.NEWEST, 2)
            assertEquals(
                "$GUTENDEX_BASE_URL/books/?sort=popular&page=1",
                transport.requestedUrls[0],
            )
            assertEquals(
                "$GUTENDEX_BASE_URL/books/?sort=descending&page=2",
                transport.requestedUrls[1],
            )
            assertEquals(3, popular.totalCount)
            assertEquals(listOf("gutendex:1342", "gutendex:11"), newest.books.map { it.id })
        }

    @Test fun gutendexSearch_queriesHostAndDropsInCopyrightRecords() =
        runTest {
            val transport = FakeCatalogHttpTransport({ ok(fixture("gutendex-search.json")) })
            val ds = GutendexDataSource(transport)
            val result = ds.search("pride", 1)
            assertTrue(transport.requestedUrls.single().startsWith("$GUTENDEX_BASE_URL/books/"))
            assertTrue(transport.requestedUrls.single().contains("search=pride"))
            assertEquals(3, result.totalCount)
            assertEquals(listOf("gutendex:1342", "gutendex:11"), result.books.map { it.id })
        }

    /**
     * The regression this rail shipped with: a topic shelf asked for
     * `?search=science fiction` (6 books upstream) instead of
     * `?topic=science fiction` (3 530). Asserted on the built URL, not the
     * result, so a fallback back to free-text search fails here.
     *
     * The term is form-encoded (`+` for the space), the same encoding the
     * existing `search` door emits; Gutendex accepts both on a query string.
     */
    @Test fun gutendexTopic_issuesTopicParameterNeverFreeTextSearch() =
        runTest {
            val transport = FakeCatalogHttpTransport({ ok(fixture("gutendex-search.json")) })
            val ds = GutendexDataSource(transport)

            ds.topic("science fiction", 1)

            val url = transport.requestedUrls.single()
            assertEquals("$GUTENDEX_BASE_URL/books/?topic=science+fiction&page=1", url)
            // Explicit negatives: neither the free-text door nor a sort literal.
            assertFalse(url.contains("search="))
            assertFalse(url.contains("sort="))
        }

    @Test fun gutendexTopic_encodesTheTermAndCarriesThePage() =
        runTest {
            val transport = FakeCatalogHttpTransport({ ok(fixture("gutendex-search.json")) })
            val ds = GutendexDataSource(transport)

            ds.topic("science fiction", 3)

            assertEquals(
                "$GUTENDEX_BASE_URL/books/?topic=science+fiction&page=3",
                transport.requestedUrls.single(),
            )
        }

    @Test fun gutendexTopic_mapsThePageLikeAnyOtherCatalogPage() =
        runTest {
            val transport = FakeCatalogHttpTransport({ ok(fixture("gutendex-search.json")) })
            val result = GutendexDataSource(transport).topic("adventure", 1)
            // The fixture's `count` is the authority, and in-copyright rows still drop.
            assertEquals(3, result.totalCount)
            assertEquals(listOf("gutendex:1342", "gutendex:11"), result.books.map { it.id })
        }

    @Test fun gutendexTopic_propagatesNetworkErrorUntouched() =
        runTest {
            val transport =
                FakeCatalogHttpTransport({
                    throw catalogError(CatalogErrorCode.NETWORK_ERROR, "catalog request failed")
                })
            try {
                GutendexDataSource(transport).topic("adventure", 1)
                fail("expected CatalogException")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.NETWORK_ERROR, err.code)
            }
        }

    @Test fun gutendexSearch_retriesOnceAfter429ThenSucceeds() =
        runTest {
            var calls = 0
            val transport =
                FakeCatalogHttpTransport({
                    calls += 1
                    if (calls == 1) CatalogHttpResponse(429, "{}") else ok(fixture("gutendex-search.json"))
                })
            val result = GutendexDataSource(transport).search("pride", 1)
            assertEquals(2, calls)
            assertEquals(2, result.books.size)
        }

    @Test fun gutendexSearch_maps404ToNotFound() =
        runTest {
            val transport = FakeCatalogHttpTransport({ CatalogHttpResponse(404, "{}") })
            try {
                GutendexDataSource(transport).search("pride", 1)
                fail("expected CatalogException")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, err.code)
            }
        }

    @Test fun gutendexSearch_propagatesNetworkErrorUntouched() =
        runTest {
            // The Ktor transport wraps raw I/O failures as NETWORK_ERROR;
            // the datasource must let typed errors bubble without rewrapping.
            val transport =
                FakeCatalogHttpTransport({
                    throw catalogError(CatalogErrorCode.NETWORK_ERROR, "catalog request failed")
                })
            try {
                GutendexDataSource(transport).search("pride", 1)
                fail("expected CatalogException")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.NETWORK_ERROR, err.code)
                assertTrue(err.retryable)
            }
        }

    @Test fun gutendexGetById_resolvesKnownBookAnd404sUnknown() =
        runTest {
            val record =
                json
                    .decodeFromString<GutendexSearchResponse>(fixture("gutendex-search.json"))
                    .results
                    .first()
            val recordJson = Json.encodeToString(GutendexRecord.serializer(), record)
            val transport =
                FakeCatalogHttpTransport({ url ->
                    if (url.endsWith("/books/1342/")) ok(recordJson) else CatalogHttpResponse(404, "{}")
                })
            val ds = GutendexDataSource(transport)
            assertEquals("gutendex:1342", ds.getById(1342).id)
            try {
                ds.getById(99991)
                fail("expected CatalogException")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, err.code)
            }
        }

    // ── Open Library ────────────────────────────────────────────

    @Test fun openLibrarySearch_dropsBorrowableDocs() =
        runTest {
            val transport = FakeCatalogHttpTransport({ ok(fixture("openlibrary-search.json")) })
            val ds = OpenLibraryDataSource(transport, RateLimiter(0L))
            val result = ds.search("pride", 1)
            assertTrue(transport.requestedUrls.single().startsWith("$OPEN_LIBRARY_BASE_URL/search.json"))
            assertEquals(3, result.totalCount)
            assertEquals(
                listOf("openlibrary:/works/OL66554W", "openlibrary:/works/OL11W"),
                result.books.map { it.id },
            )
        }
}
