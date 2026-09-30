package com.nexo.data.remote.catalog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Offline suite for the featured/popular seam: closed sort literals, fan-out only
 * over opt-in providers, per-source caching under the new `f:v3:` namespace, and
 * the fail-closed defaults that make an unsupported rail auto-hide.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogFeaturedSeamTest {
    private fun book(
        id: String,
        title: String,
    ) = CatalogBook(
        id = id,
        provider = providerFor(id),
        title = title,
        authors = listOf("Author $id"),
        coverUrl = null,
        languages = listOf("en"),
        subjects = emptyList(),
        downloadUrl = null,
    )

    private fun providerFor(id: String): String = if (id.startsWith("openlibrary:")) BUILTIN_OPENLIBRARY else BUILTIN_GUTENDEX

    private class FakeSeamProvider(
        private val sourceId: String,
        private val featuredCapable: Boolean,
        private val featuredPage: (Int) -> PagedResult = { PagedResult(emptyList(), null, 0) },
    ) : CatalogProvider {
        val featuredCalls = mutableListOf<Pair<CatalogFeaturedSort, Int>>()
        var searchCalls = 0

        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult {
            searchCalls += 1
            return PagedResult(
                results =
                    listOf(
                        CatalogBook(
                            id = "gutendex:1",
                            provider = BUILTIN_GUTENDEX,
                            title = "Owned by $sourceId",
                            authors = listOf("A"),
                            coverUrl = null,
                            languages = listOf("en"),
                            subjects = emptyList(),
                            downloadUrl = null,
                        ),
                    ),
                nexoPage = null,
                totalCount = 1,
            )
        }

        override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "unused")

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = "https://example.com/book.txt"

        override fun listSources(): List<CatalogSourceInfo> = listOf(CatalogSourceInfo(sourceId, sourceId, CatalogSourceKind.BUILTIN))

        override suspend fun featured(
            sort: CatalogFeaturedSort,
            page: Int,
        ): PagedResult {
            featuredCalls.add(sort to page)
            return featuredPage(page)
        }

        override fun supportsFeatured(): Boolean = featuredCapable
    }

    @Test
    fun catalogProvider_defaultsFailClosed() =
        runTest {
            val provider =
                object : CatalogProvider {
                    override suspend fun search(
                        query: String,
                        page: Int,
                    ) = PagedResult(emptyList(), null, 0)

                    override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "unused")

                    override fun resolveDownloadUrl(
                        formats: Map<String, String>,
                        preferEpub: Boolean,
                    ) = "n/a"

                    override fun listSources(): List<CatalogSourceInfo> = emptyList()
                }
            assertFalse(provider.supportsFeatured())
            assertTrue(provider.featured(CatalogFeaturedSort.POPULAR, 1).results.isEmpty())
            assertTrue(provider.searchSource("builtin:gutendex", "pride", 1).results.isEmpty())
        }

    @Test
    fun featured_fansOutOnlyOverOptInProvidersAndMerges() =
        runTest {
            val capable =
                FakeSeamProvider(
                    sourceId = BUILTIN_GUTENDEX,
                    featuredCapable = true,
                    featuredPage = { PagedResult(listOf(book("gutendex:10", "Newest"), book("gutendex:11", "Second")), 2, 40) },
                )
            val notCapable = FakeSeamProvider(sourceId = BUILTIN_OPENLIBRARY, featuredCapable = false)
            val composite = CompositeCatalogProvider(listOf(capable, notCapable))

            val page = composite.featured(CatalogFeaturedSort.NEWEST, 1)

            assertEquals(listOf("gutendex:10", "gutendex:11"), page.results.map { it.id })
            assertEquals(40, page.totalCount)
            assertEquals(2, page.nexoPage)
            assertEquals(listOf(CatalogFeaturedSort.NEWEST to 1), capable.featuredCalls)
            assertTrue(notCapable.featuredCalls.isEmpty())
            assertTrue(composite.supportsFeatured())
        }

    @Test
    fun featured_triangulatesMergeOrderAcrossTwoCapableProviders() =
        runTest {
            val gutendex =
                FakeSeamProvider(BUILTIN_GUTENDEX, featuredCapable = true) {
                    PagedResult(listOf(book("gutendex:10", "Gutendex Newest")), 2, 40)
                }
            val openLibrary =
                FakeSeamProvider(BUILTIN_OPENLIBRARY, featuredCapable = true) {
                    PagedResult(listOf(book("openlibrary:/works/OL1W", "Open Library Newest")), null, 1)
                }

            val gutendexFirst =
                CompositeCatalogProvider(listOf(gutendex, openLibrary))
                    .featured(CatalogFeaturedSort.NEWEST, 1)
            assertEquals(
                listOf("gutendex:10", "openlibrary:/works/OL1W"),
                gutendexFirst.results.map { it.id },
            )
            // Left-fold merge takes the FIRST provider's counts and appends later books.
            assertEquals(40, gutendexFirst.totalCount)
            assertEquals(2, gutendexFirst.nexoPage)

            val openLibraryFirst =
                CompositeCatalogProvider(listOf(openLibrary, gutendex))
                    .featured(CatalogFeaturedSort.NEWEST, 1)
            assertEquals(
                listOf("openlibrary:/works/OL1W", "gutendex:10"),
                openLibraryFirst.results.map { it.id },
            )
            assertEquals(1, openLibraryFirst.totalCount)
            assertNull(openLibraryFirst.nexoPage)
        }

    @Test
    fun featured_returnsEmptyWhenNoProviderOptsIn() =
        runTest {
            val notCapable = FakeSeamProvider(sourceId = BUILTIN_OPENLIBRARY, featuredCapable = false)
            val composite = CompositeCatalogProvider(listOf(notCapable))

            val page = composite.featured(CatalogFeaturedSort.POPULAR, 1)

            assertTrue(page.results.isEmpty())
            assertEquals(0, page.totalCount)
            assertNull(page.nexoPage)
            assertFalse(composite.supportsFeatured())
        }

    @Test
    fun featured_rejectsPageBelow1BeforeAnyProviderCall() =
        runTest {
            val capable = FakeSeamProvider(sourceId = BUILTIN_GUTENDEX, featuredCapable = true)
            val composite = CompositeCatalogProvider(listOf(capable))
            try {
                composite.featured(CatalogFeaturedSort.POPULAR, 0)
                fail("expected CatalogException")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.INVALID_PAGE, err.code)
            }
            assertTrue(capable.featuredCalls.isEmpty())
        }

    @Test
    fun featured_cachesPerSourceUnderFeaturedNamespace() =
        runTest {
            val capable =
                FakeSeamProvider(
                    sourceId = BUILTIN_GUTENDEX,
                    featuredCapable = true,
                    featuredPage = { PagedResult(listOf(book("gutendex:10", "Newest")), null, 1) },
                )
            val cache = InMemoryDiscoverCache()
            val composite = CompositeCatalogProvider(listOf(capable), cache = cache)

            composite.featured(CatalogFeaturedSort.NEWEST, 1)
            composite.featured(CatalogFeaturedSort.NEWEST, 1)

            assertEquals(1, capable.featuredCalls.size)
            assertTrue(cache.get(featuredCacheKey(BUILTIN_GUTENDEX, CatalogFeaturedSort.NEWEST, 1), 0L) != null)
        }

    @Test
    fun searchSource_routesToOwningProviderOnly() =
        runTest {
            val gutendex = FakeSeamProvider(sourceId = BUILTIN_GUTENDEX, featuredCapable = true)
            val openLibrary = FakeSeamProvider(sourceId = BUILTIN_OPENLIBRARY, featuredCapable = false)
            val composite = CompositeCatalogProvider(listOf(gutendex, openLibrary))

            val page = composite.searchSource(BUILTIN_GUTENDEX, "pride", 1)

            assertEquals(1, page.results.size)
            assertTrue(
                page.results
                    .single()
                    .title
                    .contains(BUILTIN_GUTENDEX),
            )
            assertEquals(1, gutendex.searchCalls)
            assertEquals(0, openLibrary.searchCalls)
        }

    @Test
    fun searchSource_failsClosedForUnknownSource() =
        runTest {
            val gutendex = FakeSeamProvider(sourceId = BUILTIN_GUTENDEX, featuredCapable = true)
            val composite = CompositeCatalogProvider(listOf(gutendex))

            val page = composite.searchSource("addon:0123456789abcdef", "pride", 1)

            assertTrue(page.results.isEmpty())
            assertEquals(0, page.totalCount)
            assertNull(page.nexoPage)
            assertEquals(0, gutendex.searchCalls)
        }

    @Test
    fun featured_neverCachesEmptyRails() =
        runTest {
            val empty = FakeSeamProvider(sourceId = BUILTIN_GUTENDEX, featuredCapable = true)
            val store = RecordingStore()
            val composite = CompositeCatalogProvider(listOf(empty), cache = store)
            val page = composite.featured(CatalogFeaturedSort.POPULAR, 1)
            assertTrue(page.results.isEmpty())
            // Never-cache-empty: empty rails persist nothing under the featured namespace.
            assertTrue(store.puts.isEmpty())
        }

    @Test
    fun featured_writesRailsWith6hTtl() =
        runTest {
            val capable =
                FakeSeamProvider(
                    sourceId = BUILTIN_GUTENDEX,
                    featuredCapable = true,
                    featuredPage = { PagedResult(listOf(book("gutendex:10", "Newest")), null, 1) },
                )
            val store = RecordingStore()
            val composite = CompositeCatalogProvider(listOf(capable), cache = store)
            composite.featured(CatalogFeaturedSort.NEWEST, 1)
            assertEquals(1, store.puts.size)
            assertTrue(
                store.puts
                    .single()
                    .first
                    .startsWith("f:v3:"),
            )
            // Featured writer uses the 6h rail TTL, not the 24h search-page TTL.
            assertEquals(21_600L, store.puts.single().third)
            assertEquals(FEATURED_TTL_S, store.puts.single().third)
        }

    @Test
    fun featured_oneThrowingProviderStillRendersOtherRails() =
        runTest {
            val survivor =
                FakeSeamProvider(
                    sourceId = BUILTIN_GUTENDEX,
                    featuredCapable = true,
                    featuredPage = { PagedResult(listOf(book("gutendex:10", "Survivor")), null, 1) },
                )
            val throwing = ThrowingFeaturedProvider(sourceId = BUILTIN_OPENLIBRARY)
            val composite = CompositeCatalogProvider(listOf(survivor, throwing))
            val page = composite.featured(CatalogFeaturedSort.POPULAR, 1)
            assertEquals(listOf("gutendex:10"), page.results.map { it.id })
            assertEquals(1, page.totalCount)
        }

    @Test
    fun featured_throwingFirstProviderStillRendersSecondRail() =
        runTest {
            val throwing = ThrowingFeaturedProvider(sourceId = BUILTIN_GUTENDEX)
            val survivor =
                FakeSeamProvider(
                    sourceId = BUILTIN_OPENLIBRARY,
                    featuredCapable = true,
                    featuredPage = { PagedResult(listOf(book("openlibrary:/works/OL1W", "Survivor")), null, 1) },
                )
            val composite = CompositeCatalogProvider(listOf(throwing, survivor))
            val page = composite.featured(CatalogFeaturedSort.POPULAR, 1)
            assertEquals(listOf("openlibrary:/works/OL1W"), page.results.map { it.id })
        }

    /** Records cache writes while delegating to a real TTL store. */
    private class RecordingStore(
        private val delegate: DiscoverCacheStore = InMemoryDiscoverCache(),
    ) : DiscoverCacheStore {
        val puts = mutableListOf<Triple<String, String, Long>>()

        override suspend fun get(
            key: String,
            nowEpochSecs: Long,
        ): String? = delegate.get(key, nowEpochSecs)

        override suspend fun read(
            key: String,
            nowEpochSecs: Long,
        ): DiscoverCacheRead? = delegate.read(key, nowEpochSecs)

        override suspend fun put(
            key: String,
            payload: String,
            fetchedAtEpochSecs: Long,
            ttlSecs: Long,
        ) {
            puts.add(Triple(key, payload, ttlSecs))
            delegate.put(key, payload, fetchedAtEpochSecs, ttlSecs)
        }
    }

    private class ThrowingFeaturedProvider(
        private val sourceId: String,
    ) : CatalogProvider {
        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult = PagedResult(emptyList(), null, 0)

        override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "unused")

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = "https://example.com/book.txt"

        override fun listSources(): List<CatalogSourceInfo> = listOf(CatalogSourceInfo(sourceId, sourceId, CatalogSourceKind.BUILTIN))

        override suspend fun featured(
            sort: CatalogFeaturedSort,
            page: Int,
        ): PagedResult = throw catalogError(CatalogErrorCode.UPSTREAM_ERROR, "boom")

        override fun supportsFeatured(): Boolean = true
    }

    @Test
    fun searchSource_rejectsPageBelow1() =
        runTest {
            val gutendex = FakeSeamProvider(sourceId = BUILTIN_GUTENDEX, featuredCapable = true)
            val composite = CompositeCatalogProvider(listOf(gutendex))
            try {
                composite.searchSource(BUILTIN_GUTENDEX, "pride", 0)
                fail("expected CatalogException")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.INVALID_PAGE, err.code)
            }
            assertEquals(0, gutendex.searchCalls)
        }
}
