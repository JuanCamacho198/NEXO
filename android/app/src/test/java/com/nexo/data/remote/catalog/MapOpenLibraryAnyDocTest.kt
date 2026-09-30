package com.nexo.data.remote.catalog

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U1: permissive mapper keeps borrowable/in-copyright docs with identity
 * fields; strict mapper unchanged; old cache payloads still decode.
 */
class MapOpenLibraryAnyDocTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader
            ?.getResourceAsStream("catalog/$name")
            ?.bufferedReader()
            ?.readText()
            ?: error("missing test fixture catalog/$name")

    private val olDocs: List<OpenLibraryDoc> by lazy {
        json.decodeFromString<OpenLibrarySearchResponse>(fixture("openlibrary-search.json")).docs
    }

    @Test fun permissiveMapper_keepsBorrowableDocWithIdentity() {
        val doc = olDocs.first { it.title == "Borrow Restricted Title" }
        val book = mapOpenLibraryAnyDoc(doc)
        assertEquals("openlibrary:/works/OL99991W", book.id)
        assertEquals(BUILTIN_OPENLIBRARY, book.provider)
        assertEquals(listOf("Some Author"), book.authors)
        assertEquals("https://covers.openlibrary.org/b/id/1234567-M.jpg", book.coverUrl)
    }

    @Test fun permissiveMapper_carriesIsbnAndIaIdentityFields() {
        val doc =
            OpenLibraryDoc(
                key = "/works/OL99991W",
                title = "Borrow Restricted Title",
                authorName = listOf("Some Author"),
                coverId = 1234567,
                ebookAccess = "borrowable",
                isbn = listOf("9780141439518"),
                internetArchiveIds = listOf("prideandprejudice0000aust"),
            )
        assertEquals(listOf("9780141439518"), doc.isbn)
        assertEquals(listOf("prideandprejudice0000aust"), doc.internetArchiveIds)
        val book = mapOpenLibraryAnyDoc(doc)
        assertNotNull(book)
        assertEquals("openlibrary:/works/OL99991W", book.id)
        // Identity survives a doc JSON round-trip.
        val decoded = json.decodeFromString<OpenLibraryDoc>(json.encodeToString(doc))
        assertEquals(doc, decoded)
    }

    @Test fun permissiveMapper_decodesDocsCarryingIsbnAndIa() {
        val raw =
            """
            {
              "key": "/works/OL1W",
              "title": "T",
              "author_name": ["A"],
              "cover_i": 1,
              "ebook_access": "borrowable",
              "isbn": ["9780000000001"],
              "ia": ["someid"]
            }
            """.trimIndent()
        val doc = json.decodeFromString<OpenLibraryDoc>(raw)
        assertEquals(listOf("9780000000001"), doc.isbn)
        assertEquals(listOf("someid"), doc.internetArchiveIds)
        assertEquals("openlibrary:/works/OL1W", mapOpenLibraryAnyDoc(doc).id)
    }

    @Test fun strictMapper_stillDropsNonPublicDocs() {
        val borrowable = olDocs.first { it.title == "Borrow Restricted Title" }
        assertNull(mapOpenLibraryDoc(borrowable))
        val public = olDocs.first { it.title == "Pride and Prejudice" }
        assertEquals("openlibrary:/works/OL66554W", mapOpenLibraryDoc(public)?.id)
    }

    @Test fun legacyCachePayload_stillDecodes() {
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
        assertEquals(1, decoded.totalCount)
        assertTrue(
            decoded.results
                .single()
                .formats
                .isEmpty(),
        )
        assertNull(decoded.results.single().description)
    }
}
