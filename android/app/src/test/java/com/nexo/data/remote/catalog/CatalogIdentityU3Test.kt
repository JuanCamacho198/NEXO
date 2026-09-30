package com.nexo.data.remote.catalog

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U3: additive identity fields default to null, survive a JSON round-trip,
 * are filled by the OL/Google mappers, merge across the Gutendex/OL dedupe
 * key, and never break old cached payloads.
 */
class CatalogIdentityU3Test {
    private val json = Json { ignoreUnknownKeys = true }

    private fun resFile(vararg names: String): java.io.File {
        val candidates = names.map { java.io.File(it) }
        return candidates.firstOrNull { it.exists() }
            ?: error("missing res file, tried: ${names.joinToString()}")
    }

    @Test fun identityFields_defaultToNull() {
        val book =
            CatalogBook(
                id = "gutendex:11",
                provider = BUILTIN_GUTENDEX,
                title = "Alice",
                authors = listOf("Lewis Carroll"),
                coverUrl = null,
                languages = listOf("en"),
                subjects = emptyList(),
                downloadUrl = null,
            )
        assertNull(book.isbn13)
        assertNull(book.isbn10)
        assertNull(book.openLibraryWorkId)
        assertNull(book.internetArchiveId)
        assertNull(book.googleBooksId)
    }

    @Test fun identityFields_surviveJsonRoundTrip() {
        val book =
            CatalogBook(
                id = "openlibrary:/works/OL66554W",
                provider = BUILTIN_OPENLIBRARY,
                title = "Pride and Prejudice",
                authors = listOf("Jane Austen"),
                coverUrl = null,
                languages = listOf("en"),
                subjects = emptyList(),
                downloadUrl = null,
                isbn13 = "9780141439518",
                isbn10 = "0141439513",
                openLibraryWorkId = "/works/OL66554W",
                internetArchiveId = "prideandprejudice0000aust",
                googleBooksId = null,
            )
        val decoded = json.decodeFromString<CatalogBook>(json.encodeToString(book))
        assertEquals(book, decoded)
    }

    @Test fun permissiveOlMapper_splitsIsbnAndCarriesWorkKeyAndIa() {
        val doc =
            OpenLibraryDoc(
                key = "/works/OL99991W",
                title = "Borrow Restricted Title",
                authorName = listOf("Some Author"),
                coverId = 1234567,
                ebookAccess = "borrowable",
                isbn = listOf("978-0-14-143951-8", "0-14-143951-3"),
                internetArchiveIds = listOf("prideandprejudice0000aust"),
            )
        val book = mapOpenLibraryAnyDoc(doc)
        assertEquals("9780141439518", book.isbn13)
        assertEquals("0141439513", book.isbn10)
        assertEquals("/works/OL99991W", book.openLibraryWorkId)
        assertEquals("prideandprejudice0000aust", book.internetArchiveId)
        assertNull(book.googleBooksId)
    }

    @Test fun strictOlMapper_carriesIdentityForPublicDocs() {
        val doc =
            OpenLibraryDoc(
                key = "/works/OL66554W",
                title = "Pride and Prejudice",
                authorName = listOf("Jane Austen"),
                ebookAccess = "public",
                isbn = listOf("9780141439518"),
                internetArchiveIds = listOf("prideandprejudice0000aust"),
            )
        val book = mapOpenLibraryDoc(doc)
        assertEquals("9780141439518", book?.isbn13)
        assertEquals("/works/OL66554W", book?.openLibraryWorkId)
        assertEquals("prideandprejudice0000aust", book?.internetArchiveId)
        assertNull(book?.googleBooksId)
    }

    @Test fun googleMapper_carriesVolumeIdAndIsbnPair() {
        val item =
            GoogleBooksVolumeItem(
                id = "abc123",
                volumeInfo =
                    GoogleBooksVolumeInfo(
                        title = "Dune",
                        authors = listOf("Frank Herbert"),
                        industryIdentifiers =
                            listOf(
                                mapOf("type" to "ISBN_13", "identifier" to "9780441172719"),
                                mapOf("type" to "ISBN_10", "identifier" to "0441172717"),
                            ),
                    ),
            )
        val book = mapGoogleBooksVolume(item)
        assertEquals("abc123", book?.googleBooksId)
        assertEquals("9780441172719", book?.isbn13)
        assertEquals("0441172717", book?.isbn10)
        assertNull(book?.openLibraryWorkId)
        assertNull(book?.internetArchiveId)
    }

    @Test fun gutendexMapper_leavesUnknownIdentityNull() {
        val book =
            mapGutendexBook(
                GutendexRecord(id = 1342, title = "Pride and Prejudice", copyright = false),
            )
        assertNull(book?.isbn13)
        assertNull(book?.isbn10)
        assertNull(book?.openLibraryWorkId)
        assertNull(book?.internetArchiveId)
        assertNull(book?.googleBooksId)
    }

    @Test fun merge_fillsIdentityGapsFromMatchedOlDoc() {
        val gutendexBook =
            CatalogBook(
                id = "gutendex:1342",
                provider = BUILTIN_GUTENDEX,
                title = "Pride and Prejudice",
                authors = listOf("Jane Austen"),
                coverUrl = null,
                languages = listOf("en"),
                subjects = emptyList(),
                downloadUrl = null,
            )
        val olBook =
            mapOpenLibraryAnyDoc(
                OpenLibraryDoc(
                    key = "/works/OL66554W",
                    title = "Pride and Prejudice",
                    authorName = listOf("Jane Austen"),
                    isbn = listOf("9780141439518"),
                    internetArchiveIds = listOf("prideandprejudice0000aust"),
                ),
            )
        val merged = mergeResults(listOf(gutendexBook), listOf(olBook)).single()
        assertEquals("gutendex:1342", merged.id)
        assertEquals("9780141439518", merged.isbn13)
        assertEquals("/works/OL66554W", merged.openLibraryWorkId)
        assertEquals("prideandprejudice0000aust", merged.internetArchiveId)
    }

    @Test fun legacyCachePayload_withoutIdentity_stillDecodesWithNulls() {
        val legacy =
            """
            {
              "results": [
                {
                  "id": "gutendex:11",
                  "provider": "builtin:gutendex",
                  "title": "Alice's Adventures in Wonderland",
                  "authors": ["Lewis Carroll"],
                  "coverUrl": null,
                  "languages": ["en"],
                  "subjects": ["Fantasy"],
                  "downloadUrl": null
                }
              ],
              "nextPage": null,
              "totalCount": 1
            }
            """.trimIndent()
        val decoded = json.decodeFromString<PagedResult>(legacy)
        val book = decoded.results.single()
        assertEquals(1, decoded.totalCount)
        assertNull(book.isbn13)
        assertNull(book.isbn10)
        assertNull(book.openLibraryWorkId)
        assertNull(book.internetArchiveId)
        assertNull(book.googleBooksId)
    }

    @Test fun accessGroupLabels_presentEnAndEs() {
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
        assertTrue(en.contains("name=\"access_group_free\""))
        assertTrue(en.contains("name=\"access_group_buy\""))
        assertTrue(en.contains("name=\"access_group_subscribe\""))
        assertTrue(es.contains("name=\"access_group_free\""))
        assertTrue(es.contains("name=\"access_group_buy\""))
        assertTrue(es.contains("name=\"access_group_subscribe\""))
        assertTrue(en.contains(">Free<"))
        assertTrue(en.contains(">Buy<"))
        assertTrue(en.contains(">Subscribe<"))
    }
}
