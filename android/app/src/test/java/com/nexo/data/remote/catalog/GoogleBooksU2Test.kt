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
 * U2: keyed Google Books + Gutenberg covers.
 *
 * - Absent/blank key ⇒ provider omitted from the DI list (fail-closed).
 * - Present key + fake transport ⇒ mapping works and the composite's U1
 *   failure isolation covers the new provider.
 * - `google_books_attribution` string present (EN + ES voseo).
 * - Gutenberg cover derivation + initial-letter fallback contract.
 * - Strict existing mappers unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoogleBooksU2Test {
    private fun googleBooksPayload(): String =
        """
        {
          "totalItems": 2,
          "items": [
            {
              "id": "abc123",
              "volumeInfo": {
                "title": "Pride and Prejudice",
                "authors": ["Jane Austen"],
                "description": "A classic.",
                "language": "en",
                "categories": ["Fiction"],
                "imageLinks": { "thumbnail": "http://books.google.com/books/content?id=abc123&printsec=frontcover&img=1&zoom=1" }
              }
            },
            {
              "id": "no-cover-1",
              "volumeInfo": {
                "title": "Coverless Essay",
                "authors": ["Anon"],
                "language": "es"
              }
            }
          ]
        }
        """.trimIndent()

    private fun failingProvider(sourceId: String): CatalogProvider =
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

    // ── key wiring, fail-closed ─────────────────────────────────────

    @Test fun `absent key omits provider from DI list`() {
        assertNull(googleBooksProviderOrNull(FakeCatalogHttpTransport({ error("no I/O") }), ""))
        assertNull(googleBooksProviderOrNull(FakeCatalogHttpTransport({ error("no I/O") }), "   "))
    }

    @Test fun `present key includes provider with googlebooks source`() {
        val provider =
            googleBooksProviderOrNull(
                FakeCatalogHttpTransport({ error("no I/O") }),
                "  test-key-123  ",
            )
        assertNotNull(provider)
        assertEquals(
            listOf(CatalogSourceInfo(BUILTIN_GOOGLEBOOKS, "Google Books", CatalogSourceKind.BUILTIN)),
            provider!!.listSources(),
        )
        // Closed built-in registry accepts the new source.
        assertEquals(BUILTIN_GOOGLEBOOKS, parseCatalogSource("builtin:googlebooks"))
    }

    // ── present-key path with fake transport ────────────────────────

    @Test fun `present key search maps volumes through fake transport`() =
        runTest {
            val transport = FakeCatalogHttpTransport({ CatalogHttpResponse(200, googleBooksPayload()) })
            val ds = GoogleBooksDataSource(transport, apiKey = "k")
            val result = ds.search("pride prejudice", 1)
            assertEquals(2, result.totalCount)
            assertEquals(2, result.books.size)
            val first = result.books.first { it.id == "googlebooks:abc123" }
            assertEquals("Pride and Prejudice", first.title)
            assertEquals(listOf("Jane Austen"), first.authors)
            assertEquals(BUILTIN_GOOGLEBOOKS, first.provider)
            // http thumbnail normalized to https.
            assertTrue(first.coverUrl!!.startsWith("https://"))
            assertEquals(listOf("en"), first.languages)
            assertEquals(listOf("Fiction"), first.subjects)
            assertNull(first.downloadUrl)
            // Volume without imageLinks maps with null cover (UI letter fallback).
            val coverless = result.books.first { it.id == "googlebooks:no-cover-1" }
            assertNull(coverless.coverUrl)
        }

    @Test fun `isbn query uses isbn operator and title-author passes through`() =
        runTest {
            val transport = FakeCatalogHttpTransport({ CatalogHttpResponse(200, googleBooksPayload()) })
            val ds = GoogleBooksDataSource(transport, apiKey = "k")
            ds.search("9780140449136", 1)
            assertTrue(transport.requestedUrls.single().contains("q=isbn%3A9780140449136"))
            transport.requestedUrls.clear()
            ds.search("Jane Austen Pride", 1)
            assertTrue(transport.requestedUrls.single().contains("q=Jane+Austen+Pride"))
        }

    @Test fun `composite failure isolation covers google provider`() =
        runTest {
            val survivor =
                CatalogBook(
                    id = "gutendex:11",
                    provider = BUILTIN_GUTENDEX,
                    title = "Survivor",
                    authors = listOf("Author"),
                    coverUrl = null,
                    languages = listOf("en"),
                    subjects = emptyList(),
                    downloadUrl = null,
                )
            val gutendexStub =
                object : CatalogProvider {
                    override suspend fun search(
                        query: String,
                        page: Int,
                    ): PagedResult = toPagedResult(listOf(survivor), page, 1)

                    override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "n/a")

                    override fun resolveDownloadUrl(
                        formats: Map<String, String>,
                        preferEpub: Boolean,
                    ): String = throw catalogError(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, "n/a")

                    override fun listSources(): List<CatalogSourceInfo> = listOf(CatalogSourceInfo(BUILTIN_GUTENDEX, "Gutendex", CatalogSourceKind.BUILTIN))
                }
            val catalog =
                CompositeCatalogProvider(
                    listOf(gutendexStub, failingProvider(BUILTIN_GOOGLEBOOKS)),
                )
            val page = catalog.search("alice", 1)
            assertEquals(listOf(survivor), page.results)
        }

    @Test fun `google provider detail routing rejects malformed ids`() =
        runTest {
            val ds = GoogleBooksDataSource(FakeCatalogHttpTransport({ error("no I/O") }), apiKey = "k")
            val provider = GoogleBooksCatalogProvider(ds)
            for (id in listOf("gutendex:11", "googlebooks:", "googlebooks:a/b", "googlebooks:a b")) {
                try {
                    provider.getDetails(id)
                    fail("expected NOT_FOUND for $id")
                } catch (err: CatalogException) {
                    assertEquals(CatalogErrorCode.NOT_FOUND, err.code)
                }
            }
        }

    // ── attribution string present ──────────────────────────────────

    private fun resFile(vararg names: String): java.io.File {
        val candidates = names.map { java.io.File(it) }
        return candidates.firstOrNull { it.exists() }
            ?: error("missing res file, tried: ${names.joinToString()}")
    }

    @Test fun `google books attribution string present EN and ES`() {
        val en =
            resFile(
                "src/main/res/values/strings.xml",
                "app/src/main/res/values/strings.xml",
            ).readText()
        val es =
            resFile(
                "src/main/res/values-es/strings.xml",
                "app/src/main/res/values-es/strings.xml",
            ).readText()
        assertTrue(en.contains("name=\"google_books_attribution\""))
        assertTrue(en.contains("Powered by Google"))
        assertTrue(es.contains("name=\"google_books_attribution\""))
        assertTrue(es.contains("Con tecnolog") || es.contains("tecnolog"))
        assertTrue(en.contains("name=\"discover_google_books_disabled\""))
        assertTrue(es.contains("name=\"discover_google_books_disabled\""))
    }

    @Test fun `network allowlist and key wiring present`() {
        val nsc =
            resFile(
                "src/main/res/xml/network_security_config.xml",
                "app/src/main/res/xml/network_security_config.xml",
            ).readText()
        assertTrue(nsc.contains("books.googleapis.com"))
        val gradle = resFile("build.gradle.kts", "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("GOOGLE_BOOKS_KEY"))
        val networkModule =
            resFile(
                "src/main/java/com/nexo/di/modules/NetworkModule.kt",
                "app/src/main/java/com/nexo/di/modules/NetworkModule.kt",
            ).readText()
        assertTrue(networkModule.contains("googleBooksProviderOrNull"))
        assertTrue(networkModule.contains("BuildConfig.GOOGLE_BOOKS_KEY"))
    }

    // ── Gutenberg cover derivation + fallback ───────────────────────

    @Test fun `gutenberg cover derived from record id`() {
        assertEquals(
            "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
            gutenbergCoverUrl(1342),
        )
        assertNull(gutenbergCoverUrl(0))
        val book = mapGutendexBook(GutendexRecord(id = 1342, title = "Pride", copyright = false))
        assertEquals(
            "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
            book?.coverUrl,
        )
    }

    @Test fun `merge still fills cover gap when gutenberg cover missing`() {
        val gutendexBook =
            CatalogBook(
                id = "gutendex:0",
                provider = BUILTIN_GUTENDEX,
                title = "Shared Title",
                authors = listOf("Jane Austen"),
                coverUrl = null,
                languages = listOf("en"),
                subjects = emptyList(),
                downloadUrl = null,
            )
        val olBook =
            CatalogBook(
                id = "openlibrary:/works/OL1W",
                provider = BUILTIN_OPENLIBRARY,
                title = "Shared Title",
                authors = listOf("Jane Austen"),
                coverUrl = "https://covers.openlibrary.org/b/id/1-M.jpg",
                languages = listOf("en"),
                subjects = emptyList(),
                downloadUrl = null,
            )
        val merged = mergeResults(listOf(gutendexBook), listOf(olBook))
        assertEquals("https://covers.openlibrary.org/b/id/1-M.jpg", merged.first().coverUrl)
    }

    // ── strict existing mappers unchanged ───────────────────────────

    @Test fun `strict openlibrary mapper still drops borrowable and maps public cover`() {
        val borrowable =
            OpenLibraryDoc(
                key = "/works/OL2W",
                title = "Borrow Restricted Title",
                ebookAccess = "borrowable",
            )
        assertNull(mapOpenLibraryDoc(borrowable))
        val pub =
            OpenLibraryDoc(
                key = "/works/OL1W",
                title = "Public",
                ebookAccess = "public",
                coverId = 123,
            )
        assertEquals("https://covers.openlibrary.org/b/id/123-M.jpg", mapOpenLibraryDoc(pub)?.coverUrl)
    }

    @Test fun `strict gutendex mapper still excludes copyrighted and rejects http downloads`() {
        assertNull(mapGutendexBook(GutendexRecord(id = 5, title = "C", copyright = true)))
        try {
            resolveDownloadUrl(mapOf("text/plain" to "http://example.com/b.txt"), true)
            fail("expected UNAVAILABLE_DOWNLOAD")
        } catch (err: CatalogException) {
            assertEquals(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, err.code)
        }
        assertFalse(isOpenLibraryPublic(OpenLibraryDoc(key = "k", title = "t", ebookAccess = "borrowable")))
    }
}
