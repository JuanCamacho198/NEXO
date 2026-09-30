package com.nexo.data.remote.catalog

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `description` / `isPublicDomain` / `formats` fields are additive and
 * defaulted, so payloads cached before they existed must keep decoding (no Room
 * migration) and payloads carrying them must round-trip byte-for-byte.
 */
class CatalogBookLenientDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes legacy payload without new fields using defaults`() {
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
              "nexoPage": null,
              "totalCount": 1
            }
            """.trimIndent()

        val decoded = json.decodeFromString<PagedResult>(legacy)

        val book = decoded.results.single()
        assertNull(book.description)
        assertNull(book.isPublicDomain)
        assertTrue(book.formats.isEmpty())
    }

    @Test
    fun `round-trips payload carrying the new fields`() {
        val book =
            CatalogBook(
                id = "gutendex:11",
                provider = "builtin:gutendex",
                title = "Alice's Adventures in Wonderland",
                authors = listOf("Lewis Carroll"),
                coverUrl = null,
                languages = listOf("en"),
                subjects = listOf("Fantasy"),
                downloadUrl = "https://www.gutenberg.org/ebooks/11.epub.noimages",
                description = "A curious tale.",
                isPublicDomain = true,
                formats =
                    mapOf(
                        "application/epub+zip" to "https://www.gutenberg.org/ebooks/11.epub.noimages",
                    ),
            )
        val page = PagedResult(results = listOf(book), nexoPage = null, totalCount = 1)

        val decoded = json.decodeFromString<PagedResult>(json.encodeToString(page))

        assertEquals(page, decoded)
    }

    @Test
    fun `mapGutendexBook forwards summaries copyright and formats`() {
        val record =
            GutendexRecord(
                id = 11,
                title = "Alice's Adventures in Wonderland",
                copyright = false,
                summaries = listOf("Down the rabbit hole.", "A trial in Wonderland."),
                formats =
                    mapOf(
                        "application/epub+zip" to "https://www.gutenberg.org/ebooks/11.epub.noimages",
                        "text/html" to "https://www.gutenberg.org/ebooks/11.html.images",
                    ),
            )

        val book = mapGutendexBook(record)

        assertEquals("Down the rabbit hole.\n\nA trial in Wonderland.", book?.description)
        assertEquals(true, book?.isPublicDomain)
        assertEquals(record.formats, book?.formats)
        assertEquals("https://www.gutenberg.org/ebooks/11.epub.noimages", book?.downloadUrl)
    }
}
