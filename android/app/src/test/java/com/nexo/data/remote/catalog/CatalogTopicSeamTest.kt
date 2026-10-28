package com.nexo.data.remote.catalog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Offline suite for the `?topic=` seam: opt-in fan-out over providers that declare
 * [CatalogProvider.supportsTopic], per-source caching under its own `t:` namespace
 * (a topic page and a free-text search page are different upstream answers and must
 * never share a key), and a fail-closed default for every provider that does not opt in.
 *
 * Mirrors the desktop `CatalogTopicSeamTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogTopicSeamTest {
    private fun book(
        id: String,
        title: String,
    ) = CatalogBook(
        id = id,
        provider = BUILTIN_GUTENDEX,
        title = title,
        authors = listOf("Author $id"),
        coverUrl = null,
        languages = listOf("en"),
        subjects = emptyList(),
        downloadUrl = null,
    )

    private class FakeTopicProvider(
        private val sourceId: String,
        private val topicCapable: Boolean,
        private val topicPage: (String, Int) -> PagedResult = { _, _ -> PagedResult(emptyList(), null, 0) },
        private val searchPage: PagedResult = PagedResult(emptyList(), null, 0),
    ) : CatalogProvider {
        val topicCalls = mutableListOf<Pair<String, Int>>()
        val searchCalls = mutableListOf<Pair<String, Int>>()

        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult {
            searchCalls.add(query to page)
            return searchPage
        }

        override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "unused")

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = "n/a"

        override fun listSources(): List<CatalogSourceInfo> = listOf(CatalogSourceInfo(sourceId, sourceId, CatalogSourceKind.BUILTIN))

        override suspend fun topic(
            term: String,
            page: Int,
        ): PagedResult {
            topicCalls.add(term to page)
            return topicPage(term, page)
        }

        override fun supportsTopic(): Boolean = topicCapable
    }

    @Test
    fun catalogProvider_defaultsFailClosedForTopic() =
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
            assertFalse(provider.supportsTopic())
            assertTrue(provider.topic("adventure", 1).results.isEmpty())
        }

    @Test
    fun topic_fansOutOnlyOverOptInProviders() =
        runTest {
            val capable =
                FakeTopicProvider(
                    sourceId = BUILTIN_GUTENDEX,
                    topicCapable = true,
                    topicPage = { term, _ ->
                        PagedResult(listOf(book("gutendex:1", "About $term")), null, 3_530)
                    },
                )
            val refuses = FakeTopicProvider(sourceId = BUILTIN_OPENLIBRARY, topicCapable = false)
            val composite = CompositeCatalogProvider(listOf(capable, refuses))

            val page = composite.topic("science fiction", 1)

            assertEquals(listOf("science fiction" to 1), capable.topicCalls)
            assertTrue(refuses.topicCalls.isEmpty())
            // The topic total is the authority, not the search total.
            assertEquals(3_530, page.totalCount)
            assertTrue(composite.supportsTopic())
        }

    @Test
    fun topic_rejectsPageBelow1BeforeAnyProviderCall() =
        runTest {
            val capable = FakeTopicProvider(sourceId = BUILTIN_GUTENDEX, topicCapable = true)
            val composite = CompositeCatalogProvider(listOf(capable))
            try {
                composite.topic("adventure", 0)
                fail("expected INVALID_PAGE")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.INVALID_PAGE, err.code)
            }
            assertTrue(capable.topicCalls.isEmpty())
        }

    /**
     * The cache is what makes this bug able to come back silently: a topic page
     * stored under the search namespace would be served to a search (and vice
     * versa). The two namespaces are asserted disjoint, in both directions.
     */
    @Test
    fun topic_neverSharesACacheKeyWithSearch() =
        runTest {
            val provider =
                FakeTopicProvider(
                    sourceId = BUILTIN_GUTENDEX,
                    topicCapable = true,
                    topicPage = { term, _ -> PagedResult(listOf(book("gutendex:1", "Topic $term")), null, 3_530) },
                    searchPage = PagedResult(listOf(book("gutendex:2", "Search")), null, 6),
                )
            val cache = InMemoryDiscoverCache()
            val composite = CompositeCatalogProvider(listOf(provider), cache = cache)

            // `topic` read-through-caches under `t:`; the free-text `search` caches
            // under `p:`. Same words, two doors, two rows — never one row served to
            // both, which is exactly how a topic page could be handed to a search.
            composite.topic("science fiction", 1)
            composite.search("science fiction", 1)

            assertEquals(listOf("science fiction" to 1), provider.topicCalls)
            assertEquals(listOf("science fiction" to 1), provider.searchCalls)
            val topicRow = cache.get(topicCacheKey(BUILTIN_GUTENDEX, "science fiction", 1), 0L)
            val searchRow = cache.get(pageCacheKey(BUILTIN_GUTENDEX, "science fiction", 1), 0L)
            assertNotNull(topicRow)
            assertNotNull(searchRow)
            assertTrue(topicRow!!.contains("Topic science fiction"))
            assertTrue(searchRow!!.contains("Search"))
            assertFalse(topicRow.contains("Search"))
            assertFalse(searchRow.contains("Topic science fiction"))
        }

    @Test
    fun topic_cachesPerSourceUnderTheTopicNamespace() =
        runTest {
            val provider =
                FakeTopicProvider(
                    sourceId = BUILTIN_GUTENDEX,
                    topicCapable = true,
                    topicPage = { _, _ -> PagedResult(listOf(book("gutendex:1", "Cached")), null, 1) },
                )
            val cache = InMemoryDiscoverCache()
            val composite = CompositeCatalogProvider(listOf(provider), cache = cache)

            composite.topic("adventure", 1)
            composite.topic("adventure", 1)

            // One upstream request, one cached row, under `t:` and not `p:`.
            assertEquals(1, provider.topicCalls.size)
            assertNotNull(cache.get(topicCacheKey(BUILTIN_GUTENDEX, "adventure", 1), 0L))
            assertNull(cache.get(pageCacheKey(BUILTIN_GUTENDEX, "adventure", 1), 0L))
        }

    @Test
    fun topic_neverCachesAnEmptyPage() =
        runTest {
            val provider =
                FakeTopicProvider(
                    sourceId = BUILTIN_GUTENDEX,
                    topicCapable = true,
                    topicPage = { _, _ -> PagedResult(emptyList(), null, 0) },
                )
            val cache = InMemoryDiscoverCache()
            val composite = CompositeCatalogProvider(listOf(provider), cache = cache)

            val page = composite.topic("horror", 1)

            assertTrue(page.results.isEmpty())
            assertNull(cache.get(topicCacheKey(BUILTIN_GUTENDEX, "horror", 1), 0L))
        }

    @Test
    fun topic_oneThrowingSourceDoesNotDropTheAnsweringSibling() =
        runTest {
            val throws =
                FakeTopicProvider(
                    sourceId = BUILTIN_GUTENDEX,
                    topicCapable = true,
                    topicPage = { _, _ -> throw catalogError(CatalogErrorCode.NETWORK_ERROR, "socket closed") },
                )
            val answers =
                FakeTopicProvider(
                    sourceId = BUILTIN_OPENLIBRARY,
                    topicCapable = true,
                    topicPage = { _, _ -> PagedResult(listOf(book("gutendex:9", "Survivor")), null, 9) },
                )
            val composite = CompositeCatalogProvider(listOf(throws, answers))

            val page = composite.topic("adventure", 1)

            assertEquals(listOf("Survivor"), page.results.map { it.title })
        }
}
