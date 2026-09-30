package com.nexo.data.remote.catalog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** Offline mirror of the desktop composite suite (same fixtures, fake sources). */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeCatalogProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader
            ?.getResourceAsStream("catalog/$name")
            ?.bufferedReader()
            ?.readText()
            ?: error("missing test fixture catalog/$name")

    private inner class FakeGutendex(
        var calls: Int = 0,
    ) : GutendexDataSource(
            FakeCatalogHttpTransport({ error("no I/O in composite test") }),
        ) {
        override suspend fun search(
            query: String,
            page: Int,
            pageSize: Int,
        ): CatalogSearchResult {
            calls += 1
            val data = json.decodeFromString<GutendexSearchResponse>(fixture("gutendex-search.json"))
            val books = data.results.mapNotNull(::mapGutendexBook)
            return CatalogSearchResult(books, data.count ?: books.size)
        }

        override suspend fun getById(numericId: Int): CatalogBook {
            calls += 1
            val data = json.decodeFromString<GutendexSearchResponse>(fixture("gutendex-search.json"))
            val record =
                data.results.firstOrNull { it.id == numericId }
                    ?: throw catalogError(CatalogErrorCode.NOT_FOUND, "unknown catalog id $numericId")
            return mapGutendexBook(record)
                ?: throw catalogError(CatalogErrorCode.NOT_FOUND, "gutendex book $numericId unavailable")
        }
    }

    private inner class FakeOpenLibrary(
        var calls: Int = 0,
    ) : OpenLibraryDataSource(
            FakeCatalogHttpTransport({ error("no I/O in composite test") }),
            RateLimiter(0L),
        ) {
        override suspend fun search(
            query: String,
            page: Int,
            pageSize: Int,
        ): CatalogSearchResult {
            calls += 1
            val data =
                json.decodeFromString<OpenLibrarySearchResponse>(fixture("openlibrary-search.json"))
            val books = data.docs.mapNotNull(::mapOpenLibraryDoc)
            return CatalogSearchResult(books, data.numFound ?: books.size)
        }
    }

    private fun provider(
        g: FakeGutendex,
        o: FakeOpenLibrary,
    ) = CompositeCatalogProvider(
        listOf(GutendexCatalogProvider(g), OpenLibraryCatalogProvider(o)),
    )

    @Test fun search_rejectsPageBelow1BeforeAnyIo() =
        runTest {
            val g = FakeGutendex()
            val o = FakeOpenLibrary()
            try {
                provider(g, o).search("x", 0)
                fail("expected CatalogException")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.INVALID_PAGE, err.code)
            }
            assertEquals(0, g.calls)
            assertEquals(0, o.calls)
        }

    @Test fun search_mergesWithGutendexAuthorityAndOlCoverFallback() =
        runTest {
            val g = FakeGutendex()
            val o = FakeOpenLibrary()
            val page = provider(g, o).search("pride", 1)
            assertEquals(3, page.totalCount)
            assertEquals(2, page.results.size)
            val pride = page.results.first { it.id == "gutendex:1342" }
            // U2: Gutendex cover is derived from the record id (Gutenberg cache),
            // so it wins over the OL cover fallback for this book.
            assertEquals("https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg", pride.coverUrl)
            assertFalse(page.results.any { it.title == "Borrow Restricted Title" })
            // Debounced fan-out hit both sources exactly once.
            assertEquals(1, g.calls)
            assertEquals(1, o.calls)
        }

    @Test fun getDetails_resolvesGutendexIdsAnd404sUnknownPrefixes() =
        runTest {
            val g = FakeGutendex()
            val o = FakeOpenLibrary()
            val catalog = provider(g, o)
            assertEquals("gutendex:1342", catalog.getDetails("gutendex:1342").id)
            for (id in listOf("openlibrary:/works/OL11W", "gutendex:99991", "gutendex:abc")) {
                try {
                    catalog.getDetails(id)
                    fail("expected CatalogException for $id")
                } catch (err: CatalogException) {
                    assertEquals(CatalogErrorCode.NOT_FOUND, err.code)
                }
            }
        }

    @Test fun resolveDownloadUrl_delegatesToPurePriorityFunction() =
        runTest {
            val catalog = provider(FakeGutendex(), FakeOpenLibrary())
            assertEquals(
                "https://example.com/b.txt",
                catalog.resolveDownloadUrl(mapOf("text/plain" to "https://example.com/b.txt"), true),
            )
        }
}
