package com.nexo.data.remote.catalog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Offline mirror of the desktop `catalog.test.ts` mapper suite (same fixtures). */
class CatalogMappersTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val gutendexRecords: List<GutendexRecord> by lazy {
        json.decodeFromString<GutendexSearchResponse>(fixture("gutendex-search.json")).results
    }

    private val olDocs: List<OpenLibraryDoc> by lazy {
        json.decodeFromString<OpenLibrarySearchResponse>(fixture("openlibrary-search.json")).docs
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            ?.getResourceAsStream("catalog/$name")
            ?.bufferedReader()
            ?.readText()
            ?: error("missing test fixture catalog/$name")

    // ── resolveDownloadUrl ──────────────────────────────────────────

    @Test fun resolveDownloadUrl_prefersEpubWhenRequested() {
        val formats =
            mapOf(
                "application/epub+zip" to "https://example.com/book.epub",
                "text/plain" to "https://example.com/book.txt",
            )
        assertEquals("https://example.com/book.epub", resolveDownloadUrl(formats, true))
    }

    @Test fun resolveDownloadUrl_fallsBackToTxtFirstWhenEpubNotPreferred() {
        val formats =
            mapOf(
                "application/epub+zip" to "https://example.com/book.epub",
                "text/plain" to "https://example.com/book.txt",
            )
        assertEquals("https://example.com/book.txt", resolveDownloadUrl(formats, false))
    }

    @Test fun resolveDownloadUrl_throwsUnavailableDownloadForEmptyFormats() {
        try {
            resolveDownloadUrl(emptyMap(), true)
            fail("expected CatalogException")
        } catch (err: CatalogException) {
            assertEquals(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, err.code)
        }
    }

    @Test fun resolveDownloadUrl_rejectsNonHttpsUrls() {
        try {
            resolveDownloadUrl(mapOf("text/plain" to "http://example.com/book.txt"), false)
            fail("expected CatalogException")
        } catch (err: CatalogException) {
            assertEquals(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, err.code)
        }
    }

    // ── PD predicates and mappers ───────────────────────────────────

    @Test fun gutendexMapper_excludesCopyrightedRecords() {
        assertFalse(isGutendexPublicDomain(gutendexRecords[1]))
        assertNull(mapGutendexBook(gutendexRecords[1]))
    }

    @Test fun gutendexMapper_mapsPublicDomainRecord() {
        val book = mapGutendexBook(gutendexRecords[0])
        assertEquals("gutendex:1342", book?.id)
        assertEquals("Pride and Prejudice", book?.title)
        assertEquals(listOf("Austen, Jane"), book?.authors)
    }

    @Test fun openLibraryMapper_excludesBorrowableDocs() {
        assertFalse(isOpenLibraryPublic(olDocs[1]))
        assertNull(mapOpenLibraryDoc(olDocs[1]))
    }

    @Test fun openLibraryMapper_mapsPublicDocWithCoverUrl() {
        val book = mapOpenLibraryDoc(olDocs[0])
        assertEquals("https://covers.openlibrary.org/b/id/6794977-M.jpg", book?.coverUrl)
    }

    @Test fun openLibraryMapper_mapsPublicDocWithoutCoverToNull() {
        assertNull(mapOpenLibraryDoc(olDocs[2])?.coverUrl)
    }

    // ── merge and pagination ────────────────────────────────────────

    @Test fun mergeResults_gutendexWinsAndOlFillsCoverGap() {
        val gBooks = gutendexRecords.mapNotNull(::mapGutendexBook)
        val oBooks = olDocs.mapNotNull(::mapOpenLibraryDoc)
        val merged = mergeResults(gBooks, oBooks)
        val pride = merged.first { it.id == "gutendex:1342" }
        assertEquals(BUILTIN_GUTENDEX, pride.provider)
        // U2: Gutendex derives its own cover from the record id, so the
        // Gutenberg URL wins and OL no longer fills the gap for this book.
        assertEquals("https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg", pride.coverUrl)
        // Borrowable OL doc dropped by the mapper, so it never reaches the merge.
        assertFalse(merged.any { it.title == "Borrow Restricted Title" })
    }

    @Test fun pagination_resolvesTotalCountWithGutendexAuthority() {
        assertEquals(1200, resolveTotalCount(1200, 42))
        assertEquals(42, resolveTotalCount(null, 42))
        assertEquals(2, computeNextPage(1, 24, 1200))
        assertNull(computeNextPage(50, 24, 1200))
    }

    @Test fun normalizeMatchKey_treatsAuthorOrderAsEqual() {
        val a = normalizeMatchKey("Pride and Prejudice", listOf("Austen, Jane"))
        val b = normalizeMatchKey("Pride and Prejudice", listOf("Jane Austen"))
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
    }

    // ── error codes ─────────────────────────────────────────────────

    @Test fun catalogError_keepsStableCodesAndRetryableFlags() {
        assertEquals(CatalogErrorCode.RATE_LIMITED, mapHttpStatusToCode(429))
        assertEquals(CatalogErrorCode.NOT_FOUND, mapHttpStatusToCode(404))
        assertEquals(CatalogErrorCode.UPSTREAM_ERROR, mapHttpStatusToCode(503))
        assertTrue(catalogError(CatalogErrorCode.RATE_LIMITED).retryable)
        assertTrue(catalogError(CatalogErrorCode.NETWORK_ERROR).retryable)
        assertFalse(catalogError(CatalogErrorCode.NOT_FOUND).retryable)
    }
}
