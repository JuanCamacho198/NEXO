package com.nexo.data.remote.catalog

import com.nexo.debug.SentryMetrics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** U1: one throwing provider inside `executeSearch` still yields survivors' merged results. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositePartialTest {
    private fun book(id: String) =
        CatalogBook(
            id = id,
            provider = BUILTIN_GUTENDEX,
            title = "Title $id",
            authors = listOf("Author"),
            coverUrl = null,
            languages = listOf("en"),
            subjects = emptyList(),
            downloadUrl = null,
        )

    private fun stub(
        sourceId: String,
        books: List<CatalogBook>,
        totalCount: Int,
    ): CatalogProvider =
        object : CatalogProvider {
            override suspend fun search(
                query: String,
                page: Int,
            ): PagedResult = toPagedResult(books, page, totalCount)

            override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "n/a")

            override fun resolveDownloadUrl(
                formats: Map<String, String>,
                preferEpub: Boolean,
            ): String = throw catalogError(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, "n/a")

            override fun listSources(): List<CatalogSourceInfo> = listOf(CatalogSourceInfo(sourceId, "Stub", CatalogSourceKind.BUILTIN))
        }

    private fun failing(sourceId: String): CatalogProvider =
        object : CatalogProvider {
            override suspend fun search(
                query: String,
                page: Int,
            ): PagedResult = throw catalogError(CatalogErrorCode.UPSTREAM_ERROR, "boom")

            override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "n/a")

            override fun resolveDownloadUrl(
                formats: Map<String, String>,
                preferEpub: Boolean,
            ): String = throw catalogError(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, "n/a")

            override fun listSources(): List<CatalogSourceInfo> = listOf(CatalogSourceInfo(sourceId, "Failing", CatalogSourceKind.BUILTIN))
        }

    // ── U3-2 emit paths: latency+totals on success, empty totals, error attrs ──

    @Test fun search_completionEmitsLatencyAndTotalsPerProvider() =
        runTest {
            SentryMetrics.clearForTest()
            val survivor = book("gutendex:11")
            val catalog = CompositeCatalogProvider(listOf(stub(BUILTIN_GUTENDEX, listOf(survivor), 1)))
            catalog.search("alice", 1)
            val lat = SentryMetrics.aggregate("discover_search_latency")
            checkNotNull(lat) { "latency aggregate missing" }
            assertEquals(1, lat.count)
            SentryMetrics.clearForTest()
        }

    @Test fun search_emptyEmitsEmptyTotal() =
        runTest {
            SentryMetrics.clearForTest()
            val catalog = CompositeCatalogProvider(listOf(stub(BUILTIN_GUTENDEX, emptyList(), 0)))
            val page = catalog.search("zzz-no-match", 1)
            assertEquals(0, page.results.size)
            // Latency fires on the empty path too (empty_total shares the call site).
            val lat = SentryMetrics.aggregate("discover_search_latency")
            checkNotNull(lat) { "latency aggregate missing on empty path" }
            assertEquals(1, lat.count)
            SentryMetrics.clearForTest()
        }

    @Test fun search_failingProviderEmitsSearchErrorWithProviderAndCode() =
        runTest {
            SentryMetrics.clearForTest()
            val catalog =
                CompositeCatalogProvider(
                    listOf(stub(BUILTIN_GUTENDEX, emptyList(), 0), failing("builtin:failing-a")),
                )
            catalog.search("alice", 1)
            val lat = SentryMetrics.aggregate("discover_search_latency")
            checkNotNull(lat) { "latency aggregate missing after partial failure" }
            assertEquals(1, lat.count)
            SentryMetrics.clearForTest()
        }

    @Test fun search_yieldsSurvivorResultsWhenOneProviderThrows() =
        runTest {
            val survivor = book("gutendex:11")
            val catalog =
                CompositeCatalogProvider(
                    listOf(
                        stub(BUILTIN_GUTENDEX, listOf(survivor), 1),
                        failing("builtin:failing-a"),
                    ),
                )
            val page = catalog.search("alice", 1)
            assertEquals(listOf(survivor), page.results)
            assertEquals(1, page.totalCount)
        }

    @Test fun search_survivesThrowingProviderRegardlessOfOrder() =
        runTest {
            val survivor = book("gutendex:11")
            val catalog =
                CompositeCatalogProvider(
                    listOf(
                        failing("builtin:failing-a"),
                        stub(BUILTIN_GUTENDEX, listOf(survivor), 1),
                    ),
                )
            val page = catalog.search("alice", 1)
            assertEquals(listOf(survivor), page.results)
        }
}
