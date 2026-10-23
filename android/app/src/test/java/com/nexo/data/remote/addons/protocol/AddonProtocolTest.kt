package com.nexo.data.remote.addons.protocol

import com.nexo.data.remote.addons.AddonCatalogEntry
import com.nexo.data.remote.addons.AddonFetchErrorCode
import com.nexo.data.remote.addons.AddonFetchException
import com.nexo.data.remote.addons.AddonManifest
import com.nexo.data.remote.addons.ManifestValidator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android parity suite for the v3 addon protocol. One test per behavior of
 * `packages/addon-protocol/test` suite; the TS sources are the spec.
 */
class AddonProtocolTest {
    // ------------------------------------------------------------------ helpers

    private class Clock(
        private var current: Long = 1_000_000L,
    ) {
        fun now(): Long = current

        fun advance(ms: Long) {
            current += ms
        }
    }

    private fun json(text: String): Any? = JSONTokener(text).nextValue()

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun codeOf(block: () -> Unit): AddonFetchErrorCode {
        try {
            block()
        } catch (error: AddonFetchException) {
            return error.code
        }
        throw AssertionError("expected AddonFetchException")
    }

    private fun parseManifest(
        text: String,
        contentType: String? = JSON_CONTENT_TYPE,
    ): AddonManifestV3 = AddonManifestParserV3.parseManifestV3(bytes(text), contentType)

    private fun manifestObject(): JSONObject =
        JSONTokener(
            """
            {
              "id": "org.example.gutendex",
              "version": "3.0.0",
              "name": "Gutendex",
              "description": "Project Gutenberg catalog",
              "types": ["book"],
              "catalogs": [
                { "type": "book", "id": "popular", "name": "Popular",
                  "extra": [{ "name": "search", "isRequired": false },
                            { "name": "skip", "isRequired": false }] }
              ],
              "resources": ["catalog", { "name": "meta", "types": ["book"] }]
            }
            """.trimIndent(),
        ).nextValue() as JSONObject

    private fun manifest(
        types: List<String> = listOf("book"),
        catalogs: List<AddonCatalogEntryV3> = emptyList(),
        resources: List<AddonResourceV3> = listOf(AddonResourceV3("catalog")),
        idPrefixes: List<String>? = null,
        nexo: AddonNexo? = null,
    ): AddonManifestV3 =
        AddonManifestV3(
            id = "org.example.gutendex",
            version = "3.0.0",
            name = "Gutendex",
            types = types,
            catalogs = catalogs,
            resources = resources,
            idPrefixes = idPrefixes,
            nexo = nexo,
        )

    /** An addon that answers all three resources, as an installed addon does. */
    private fun fullManifest(): AddonManifestV3 =
        AddonManifestV3(
            id = "org.example.catalog",
            version = "3.0.0",
            name = "Catalog",
            types = listOf("book"),
            catalogs = listOf(AddonCatalogEntryV3("book", "popular", "Popular")),
            resources =
                listOf(
                    AddonResourceV3("catalog"),
                    AddonResourceV3("meta"),
                    AddonResourceV3("stream"),
                ),
        )

    private fun addon(
        id: String,
        addedAt: Long = 1L,
        enabled: Boolean = true,
        manifest: AddonManifestV3 = fullManifest(),
    ): AddonDescriptorV3 =
        AddonDescriptorV3(
            id = id,
            installUrl = "https://$id.test/manifest.json",
            addedAt = addedAt,
            enabled = enabled,
            manifest = manifest,
        )

    private fun stream(
        source: StreamSource = StreamSource.DOWNLOAD,
        url: String = "https://example.org/book.epub",
        accessType: StreamAccessType = StreamAccessType.UNKNOWN,
        format: StreamFormat = StreamFormat.EPUB,
        addonId: String? = null,
        name: String? = null,
        title: String? = null,
        addedAt: Long? = null,
    ): BookStream = BookStream(source, url, accessType, format, addonId, name, title, addedAt)

    private val malformedBodies: List<Any?> =
        listOf(
            null,
            json("\"nope\""),
            json("42"),
            json("true"),
            json("[]"),
            json("{}"),
            json("""{ "metas": {} }"""),
            json("""{ "metas": "nope" }"""),
            json("""{ "streams": {} }"""),
            json("""{ "streams": "nope" }"""),
        )

    private val catalogRaw =
        """
        {
          "metas": [
            {
              "id": "gutenberg:1342",
              "type": "book",
              "name": "Pride and Prejudice",
              "poster": "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
              "description": "Austen's most famous novel.",
              "releaseInfo": "1813",
              "authors": ["Jane Austen"],
              "languages": ["en"],
              "subjects": ["Courtship -- Fiction"]
            },
            {
              "id": "isbn:9780141439518",
              "type": "book",
              "name": "Pride and Prejudice (Penguin Classics)",
              "authors": [{ "name": "Jane Austen" }],
              "languages": ["en"]
            },
            {
              "id": "gutenberg:84",
              "name": "Frankenstein; Or, The Modern Prometheus"
            },
            { "type": "book", "name": "A meta without an id" },
            { "id": "gutenberg:98", "type": "book" }
          ]
        }
        """.trimIndent()

    private val gutendexBooks =
        """
        {
          "count": 3,
          "next": "https://gutendex.com/books?page=2",
          "previous": null,
          "results": [
            {
              "id": 1342,
              "title": "Pride and Prejudice",
              "authors": [{ "name": "Austen, Jane", "birth_year": 1775, "death_year": 1817 }],
              "summaries": [],
              "subjects": ["Courtship -- Fiction", "England -- Fiction"],
              "bookshelves": ["Best Books Ever Listings"],
              "languages": ["en"],
              "copyright": false,
              "media_type": "Text",
              "formats": {
                "text/html": "https://www.gutenberg.org/ebooks/1342.html.images",
                "image/jpeg": "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
                "application/epub+zip": "https://www.gutenberg.org/ebooks/1342.epub3.images"
              },
              "download_count": 72341
            },
            {
              "id": 84,
              "title": "Frankenstein; Or, The Modern Prometheus",
              "authors": [{ "name": "Shelley, Mary Wollstonecraft", "birth_year": 1797, "death_year": 1851 }],
              "summaries": [],
              "subjects": ["Science fiction"],
              "bookshelves": ["Gothic Fiction"],
              "languages": ["en"],
              "copyright": false,
              "media_type": "Text",
              "formats": {
                "image/jpeg": "https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg"
              },
              "download_count": 60000
            },
            { "id": 999, "authors": [] }
          ]
        }
        """.trimIndent()

    private val openlibrarySearch =
        """
        {
          "numFound": 2,
          "start": 0,
          "docs": [
            {
              "key": "/works/OL66554W",
              "title": "Pride and prejudice",
              "author_name": ["Jane Austen"],
              "first_publish_year": 1813,
              "language": ["eng"],
              "subject": ["Courtship", "Fiction"],
              "isbn": ["0141439513", "9780141439518"],
              "cover_i": 8732464
            },
            { "key": "/works/OL1W", "author_name": ["Anonymous"] }
          ]
        }
        """.trimIndent()

    private val openlibraryWork =
        """
        {
          "key": "/works/OL66554W",
          "title": "Pride and prejudice",
          "description": { "type": "/type/text", "value": "A novel of manners." },
          "first_publish_date": "1813",
          "subjects": ["Courtship -- Fiction"],
          "covers": [8732464]
        }
        """.trimIndent()

    // ---------------------------------------------------------------- constants

    @Test
    fun `constants freeze the protocol values`() {
        assertEquals("book", AddonProtocolConstants.TYPE_BOOK)
        assertEquals(listOf("audiobook", "comic"), AddonProtocolConstants.RESERVED_TYPES)
        assertEquals(
            listOf(
                "gutenberg:",
                "isbn:",
                "openlibrary:",
                "googlebooks:",
                "archive:",
                "standardebooks:",
                "librivox:",
            ),
            AddonProtocolConstants.ID_PREFIXES,
        )
        assertEquals(listOf("search", "skip", "genre", "lang"), AddonProtocolConstants.EXTRA_NAMES)
        assertEquals(
            listOf("raw", "gutendex", "openlibrary", "legacy-v2"),
            AddonProtocolConstants.SHAPES,
        )
        assertEquals(8 * 1024, AddonProtocolConstants.MAX_NEXO_CHARS)
    }

    // ----------------------------------------------------------------- manifest

    @Test
    fun `parseManifestV3 parses a book manifest with mixed resource forms`() {
        val source = manifestObject()
        source.put(
            "resources",
            JSONArray()
                .put("catalog")
                .put("meta")
                .put(
                    JSONObject()
                        .put("name", "stream")
                        .put("types", JSONArray().put("book"))
                        .put("idPrefixes", JSONArray().put("gutenberg:").put("isbn:")),
                ),
        )

        val parsed = parseManifest(source.toString())

        assertEquals("org.example.gutendex", parsed.id)
        assertEquals("3.0.0", parsed.version)
        assertEquals(listOf("book"), parsed.types)
        assertEquals(AddonResourceV3("catalog"), parsed.resources[0])
        assertEquals(AddonResourceV3("meta"), parsed.resources[1])
        assertEquals(
            AddonResourceV3("stream", listOf("book"), listOf("gutenberg:", "isbn:")),
            parsed.resources[2],
        )
        assertEquals(
            listOf(AddonExtraArg("search", false), AddonExtraArg("skip", false)),
            parsed.catalogs[0].extra,
        )
        assertTrue(AddonManifestParserV3.isManifestV3(source))
        assertFalse(AddonManifestParserV3.isManifestV3(json("""{ "types": ["book"] }""")))
    }

    @Test
    fun `parseManifestV3 accepts an empty catalogs array`() {
        val source = manifestObject()
        source.put("catalogs", JSONArray())
        source.put("resources", JSONArray().put("stream").put(JSONObject().put("name", "meta").put("types", JSONArray().put("book"))))

        val parsed = parseManifest(source.toString())

        assertEquals(emptyList<AddonCatalogEntryV3>(), parsed.catalogs)
        assertEquals(2, parsed.resources.size)
    }

    @Test
    fun `parseManifestV3 preserves the nexo object verbatim`() {
        val nexo =
            JSONObject()
                .put("homepage", "https://example.org/gutendex")
                .put("license", "CC0-1.0")
                .put(
                    "templates",
                    JSONObject()
                        .put("catalog", "https://example.org/catalog?q={search}&skip={skip}")
                        .put("meta", "https://example.org/meta/{id}"),
                ).put("shape", "raw")
        val source = manifestObject().put("nexo", nexo)

        val parsed = parseManifest(source.toString())

        assertEquals("https://example.org/gutendex", parsed.nexo?.homepage)
        assertEquals("CC0-1.0", parsed.nexo?.license)
        assertEquals(AddonShape.RAW, parsed.nexo?.shape)
        assertEquals(
            mapOf(
                "catalog" to "https://example.org/catalog?q={search}&skip={skip}",
                "meta" to "https://example.org/meta/{id}",
            ),
            parsed.nexo?.templates,
        )
    }

    @Test
    fun `parseManifestV3 rejects a nexo object over 8 KiB`() {
        val templates = JSONObject()
        val filler = "a".repeat(2000)
        for (index in 0 until 6) {
            templates.put("resource$index", "https://example.org/$filler")
        }

        val code = codeOf { parseManifest(manifestObject().put("nexo", JSONObject().put("templates", templates)).toString()) }

        assertEquals(8 * 1024, AddonProtocolConstants.MAX_NEXO_CHARS)
        assertEquals(AddonFetchErrorCode.INVALID_MANIFEST, code)
    }

    @Test
    fun `parseManifestV3 rejects a nexo shape that no reader in this build can serve`() {
        // Both were once declared. Nothing parses them, so selecting either would
        // install an addon that contributes nothing: the wire name is now unknown.
        for (shape in listOf("standard-ebooks", "librivox")) {
            val source = manifestObject().put("nexo", JSONObject().put("shape", shape))

            assertEquals(
                AddonFetchErrorCode.INVALID_MANIFEST,
                codeOf { parseManifest(source.toString()) },
            )
        }
    }

    @Test
    fun `parseManifestV3 rejects a manifest with no resources`() {
        val missing = manifestObject().apply { remove("resources") }

        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf { parseManifest(missing.toString()) },
        )
        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf { parseManifest(manifestObject().put("resources", JSONArray()).toString()) },
        )
    }

    @Test
    fun `parseManifestV3 rejects a non-https template`() {
        val source =
            manifestObject()
                .put(
                    "nexo",
                    JSONObject().put(
                        "templates",
                        JSONObject().put("catalog", "http://example.org/catalog?q={search}"),
                    ),
                )

        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf { parseManifest(source.toString()) },
        )
    }

    @Test
    fun `parseManifestV3 rejects a reserved type`() {
        val source = manifestObject().put("types", JSONArray().put("book").put("audiobook"))

        assertTrue(AddonProtocolConstants.RESERVED_TYPES.contains("audiobook"))
        assertEquals(
            AddonFetchErrorCode.UNSUPPORTED_TYPE,
            codeOf { parseManifest(source.toString()) },
        )
    }

    @Test
    fun `parseManifestV3 drops a non-book catalog entry without failing`() {
        val parsed =
            parseManifest(
                manifestObject()
                    .put(
                        "catalogs",
                        JSONArray()
                            .put(JSONObject().put("type", "movie").put("id", "films").put("name", "Films"))
                            .put(JSONObject().put("type", "book").put("id", "popular").put("name", "Popular")),
                    ).toString(),
            )

        assertEquals(1, parsed.catalogs.size)
        assertEquals("popular", parsed.catalogs[0].id)

        val onlyForeign =
            parseManifest(
                manifestObject()
                    .put("catalogs", JSONArray().put(JSONObject().put("type", "series").put("id", "s").put("name", "S")))
                    .toString(),
            )
        assertEquals(emptyList<AddonCatalogEntryV3>(), onlyForeign.catalogs)
    }

    @Test
    fun `parseManifestV3 ignores unknown top-level fields`() {
        val parsed =
            parseManifest(
                manifestObject().put("foo", 1).put("extraTopLevel", JSONObject().put("bar", true)).toString(),
            )

        assertEquals("org.example.gutendex", parsed.id)
        assertEquals(
            "org.example.gutendex",
            AddonManifestParserV3.parseManifestV3(bytes(manifestObject().put("foo", 1).toString()), JSON_CONTENT_TYPE).id,
        )
    }

    @Test
    fun `parseManifestV3 rejects a body over MAX_MANIFEST_BYTES`() {
        val oversized = ByteArray(ManifestValidator.MAX_MANIFEST_BYTES + 1) { 0x20 }

        val code = codeOf { AddonManifestParserV3.parseManifestV3(oversized, JSON_CONTENT_TYPE) }

        assertEquals(64 * 1024, ManifestValidator.MAX_MANIFEST_BYTES)
        assertEquals(AddonFetchErrorCode.TOO_LARGE, code)
    }

    @Test
    fun `parseManifestV3 rejects a non-JSON content type and invalid JSON`() {
        assertEquals(
            AddonFetchErrorCode.BAD_CONTENT_TYPE,
            codeOf { parseManifest(manifestObject().toString(), "text/html") },
        )
        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf { parseManifest("{ not json") },
        )
    }

    @Test
    fun `parseManifestV3 drops unknown extra names and rejects a non-boolean isRequired`() {
        val parsed =
            parseManifest(
                manifestObject()
                    .put(
                        "catalogs",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "book")
                                .put("id", "popular")
                                .put("name", "Popular")
                                .put(
                                    "extra",
                                    JSONArray()
                                        .put(JSONObject().put("name", "search"))
                                        .put(JSONObject().put("name", "publisher"))
                                        .put(JSONObject().put("name", "genre")),
                                ),
                        ),
                    ).toString(),
            )

        assertEquals(
            listOf(AddonExtraArg("search"), AddonExtraArg("genre")),
            parsed.catalogs[0].extra,
        )
        assertNull(
            parsed.catalogs[0]
                .extra
                ?.get(0)
                ?.isRequired,
        )

        val bad =
            manifestObject().put(
                "catalogs",
                JSONArray().put(
                    JSONObject()
                        .put("type", "book")
                        .put("id", "popular")
                        .put("name", "Popular")
                        .put("extra", JSONArray().put(JSONObject().put("name", "search").put("isRequired", "yes"))),
                ),
            )
        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf { parseManifest(bad.toString()) },
        )
    }

    @Test
    fun `parseManifestV3 accepts a known nexo shape and rejects an unknown one`() {
        val known = parseManifest(manifestObject().put("nexo", JSONObject().put("shape", "legacy-v2")).toString())

        assertEquals(AddonShape.LEGACY_V2, known.nexo?.shape)
        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf { parseManifest(manifestObject().put("nexo", JSONObject().put("shape", "nope")).toString()) },
        )
    }

    // ---------------------------------------------------------------- templates

    @Test
    fun `resolveAddonBase strips a trailing manifest json and trailing slashes`() {
        assertEquals("https://a.test", AddonTemplates.resolveAddonBase("https://a.test/manifest.json"))
        assertEquals("https://a.test/addons/one", AddonTemplates.resolveAddonBase("https://a.test/addons/one/"))
        assertEquals(
            "https://a.test/addons/one",
            AddonTemplates.resolveAddonBase("https://a.test/addons/one/manifest.json/"),
        )
    }

    @Test
    fun `encodeSegment keeps the colon readable and encodes spaces as %20`() {
        assertEquals("isbn:9780141439518", AddonTemplates.encodeSegment("isbn:9780141439518"))
        assertEquals("pride%20and%20prejudice", AddonTemplates.encodeSegment("pride and prejudice"))
        assertEquals("a%2Fb", AddonTemplates.encodeSegment("a/b"))
        assertEquals("a%26b%3Dc", AddonTemplates.encodeSegment("a&b=c"))
    }

    @Test
    fun `encodeExtra keeps insertion order, skips nullish and empty values, and never emits plus`() {
        assertEquals(
            "search=pride%20and%20prejudice&skip=100",
            AddonTemplates.encodeExtra(
                linkedMapOf("search" to "pride and prejudice", "skip" to 100, "genre" to "", "lang" to null),
            ),
        )
        assertEquals("lang=en", AddonTemplates.encodeExtra(linkedMapOf<String, Any?>("genre" to null, "lang" to "en")))
        assertEquals("", AddonTemplates.encodeExtra(emptyMap()))
        assertEquals("", AddonTemplates.encodeExtra(null))
    }

    @Test
    fun `encodeExtra round-trips through decodeExtra`() {
        assertEquals(
            mapOf("search" to "pride and prejudice", "skip" to "100"),
            AddonTemplates.decodeExtra("search=pride%20and%20prejudice&skip=100"),
        )
        assertEquals(emptyMap<String, String>(), AddonTemplates.decodeExtra(""))
    }

    @Test
    fun `buildRoutedUrl builds the routed forms`() {
        assertEquals(
            "https://a.test/catalog/book/popular.json",
            AddonTemplates.buildRoutedUrl(INSTALL_URL, ResourceRequest("catalog", "book", "popular")),
        )
        assertEquals(
            "https://a.test/catalog/book/popular/search=pride%20and%20prejudice&skip=100.json",
            AddonTemplates.buildRoutedUrl(
                INSTALL_URL,
                ResourceRequest("catalog", "book", "popular", linkedMapOf("search" to "pride and prejudice", "skip" to 100)),
            ),
        )
        assertEquals(
            "https://a.test/stream/book/isbn:9780141439518.json",
            AddonTemplates.buildRoutedUrl(INSTALL_URL, ResourceRequest("stream", "book", "isbn:9780141439518")),
        )
    }

    @Test
    fun `buildRoutedUrl rejects a slash in a segment and an install URL with no base`() {
        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf { AddonTemplates.buildRoutedUrl(INSTALL_URL, ResourceRequest("meta", "book", "a/b")) },
        )
        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf { AddonTemplates.buildRoutedUrl(INSTALL_URL, ResourceRequest("meta", "book/x", "a")) },
        )
        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf { AddonTemplates.buildRoutedUrl("/", ResourceRequest("meta", "book", "a")) },
        )
    }

    @Test
    fun `fillTemplate fills a template with encoded id and query`() {
        assertEquals(
            "https://g.test/books?q=pride%20%26%20prejudice&page=2",
            AddonTemplates.fillTemplate(
                "https://g.test/books?q={search}&page={skip}",
                ResourceRequest("catalog", "book", "popular", linkedMapOf("search" to "pride & prejudice", "skip" to 2)),
            ),
        )
        assertEquals(
            "https://g.test/books/isbn:9780141439518",
            AddonTemplates.fillTemplate(
                "https://g.test/books/{id}",
                ResourceRequest("meta", "book", "isbn:9780141439518"),
            ),
        )
        assertEquals(
            "https://g.test/books/isbn:9780141439518/formats",
            AddonTemplates.fillTemplate(
                "https://g.test/books/{bookId}/formats",
                ResourceRequest("stream", "book", "isbn:9780141439518"),
            ),
        )
    }

    @Test
    fun `fillTemplate reads query and page as legacy aliases and empties omitted arguments`() {
        assertEquals(
            "https://g.test/books?q=a%20b&page=5",
            AddonTemplates.fillTemplate(
                "https://g.test/books?q={query}&page={page}",
                ResourceRequest("catalog", "book", "popular", linkedMapOf("search" to "a b", "skip" to 5)),
            ),
        )
        assertEquals(
            "https://g.test/books?q=",
            AddonTemplates.fillTemplate("https://g.test/books?q={search}", ResourceRequest("catalog", "book", "popular")),
        )
    }

    @Test
    fun `fillTemplate rejects an unknown placeholder`() {
        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf {
                AddonTemplates.fillTemplate(
                    "https://g.test/books?p={publisher}",
                    ResourceRequest("meta", "book", "a"),
                )
            },
        )
    }

    @Test
    fun `buildRequestUrl prefers a template over the routed form`() {
        assertEquals(
            "https://routed.test/meta/book/isbn:9780141439518.json",
            AddonTemplates.buildRequestUrl(
                addon("routed", manifest = manifest()),
                ResourceRequest("meta", "book", "isbn:9780141439518"),
            ),
        )

        val templated =
            manifest(
                nexo =
                    AddonNexo(
                        templates =
                            linkedMapOf(
                                "meta" to "https://gutendex.test/books/{id}",
                                "catalog" to "https://gutendex.test/books?q={search}",
                            ),
                    ),
            )
        val addon = addon("templated", manifest = templated)

        assertEquals(
            "https://gutendex.test/books/12",
            AddonTemplates.buildRequestUrl(addon, ResourceRequest("meta", "book", "12")),
        )
        assertEquals(
            "https://gutendex.test/books?q=dickens",
            AddonTemplates.buildRequestUrl(
                addon,
                ResourceRequest("catalog", "book", "popular", linkedMapOf("search" to "dickens")),
            ),
        )
        assertEquals(
            "https://templated.test/stream/book/12.json",
            AddonTemplates.buildRequestUrl(addon, ResourceRequest("stream", "book", "12")),
        )
    }

    // ------------------------------------------------------------------- legacy

    @Test
    fun `adaptLegacyManifest adapts a v2 search details resolve addon to templates`() {
        val v3 =
            AddonLegacyAdapter.adaptLegacyManifest(
                AddonManifest(
                    id = "gutendex",
                    name = "Gutendex",
                    version = "1.0.0",
                    catalogs = listOf(AddonCatalogEntry("book-catalog", "gutendex", "Project Gutenberg")),
                    resources = listOf("search", "book-details"),
                    searchUrl = "https://gutendex.com/books?q={query}&page={page}",
                    detailsUrl = "https://gutendex.com/books/{bookId}",
                    resolveUrl = "https://gutendex.com/books/{bookId}/formats",
                ),
            )

        assertEquals("gutendex", v3.id)
        assertEquals("Gutendex", v3.name)
        assertEquals("1.0.0", v3.version)
        assertEquals(listOf("book"), v3.types)
        assertEquals(AddonShape.LEGACY_V2, v3.nexo?.shape)
        assertEquals(
            mapOf(
                "catalog" to "https://gutendex.com/books?q={search}&page={skip}",
                "meta" to "https://gutendex.com/books/{id}",
                "stream" to "https://gutendex.com/books/{id}/formats",
            ),
            v3.nexo?.templates,
        )
        assertEquals(
            listOf(
                AddonCatalogEntryV3(
                    type = "book",
                    id = "gutendex",
                    name = "Project Gutenberg",
                    extra = listOf(AddonExtraArg("search"), AddonExtraArg("skip")),
                ),
            ),
            v3.catalogs,
        )
        assertEquals(
            listOf(
                AddonResourceV3("catalog"),
                AddonResourceV3("meta", listOf("book")),
                AddonResourceV3("stream", listOf("book")),
            ),
            v3.resources,
        )
    }

    @Test
    fun `adaptLegacyManifest maps capabilities resolve to a stream resource`() {
        val v3 =
            AddonLegacyAdapter.adaptLegacyManifest(
                AddonManifest(
                    id = "openlibrary",
                    name = "Open Library",
                    version = "1.0.0",
                    catalogs = listOf(AddonCatalogEntry("book-catalog", "openlibrary", "Open Library")),
                    resources = listOf("search"),
                    capabilities = listOf("resolve"),
                ),
            )

        assertEquals(
            listOf(AddonResourceV3("catalog"), AddonResourceV3("stream", listOf("book"))),
            v3.resources,
        )
        assertTrue(v3.nexo?.templates?.isEmpty() == true)
        assertNull(v3.catalogs[0].extra)
    }

    @Test
    fun `adaptLegacyManifest drops catalog extras when there is no searchUrl`() {
        val v3 =
            AddonLegacyAdapter.adaptLegacyManifest(
                AddonManifest(
                    id = "librivox",
                    name = "LibriVox",
                    version = "1.0.0",
                    catalogs = listOf(AddonCatalogEntry("book-catalog", "librivox", "LibriVox")),
                    resources = listOf("search"),
                    detailsUrl = "https://librivox.test/books/{bookId}",
                ),
            )

        assertEquals(
            listOf(AddonResourceV3("catalog"), AddonResourceV3("meta", listOf("book"))),
            v3.resources,
        )
        assertEquals(mapOf("meta" to "https://librivox.test/books/{id}"), v3.nexo?.templates)
    }

    @Test
    fun `loadManifestV3 picks v3 for a manifest with types`() {
        val v3 =
            AddonManifestParserV3.loadManifestV3(
                bytes(
                    """
                    {
                      "id": "org.example.tpb",
                      "version": "1.0.0",
                      "name": "TPB",
                      "types": ["movie"],
                      "catalogs": [{ "type": "movie", "id": "top", "name": "Top" }],
                      "resources": ["catalog", "meta", "stream"]
                    }
                    """.trimIndent(),
                ),
                JSON_CONTENT_TYPE,
            )

        assertEquals(listOf("movie"), v3.types)
        assertEquals(emptyList<AddonCatalogEntryV3>(), v3.catalogs)
        assertNull(v3.nexo)
    }

    @Test
    fun `loadManifestV3 adapts a stored v2 manifest`() {
        val v3 =
            AddonManifestParserV3.loadManifestV3(
                bytes(
                    """
                    {
                      "id": "gutendex",
                      "name": "Gutendex",
                      "version": "1.0.0",
                      "catalogs": [
                        { "type": "book-catalog", "id": "gutendex", "name": "Project Gutenberg (via Gutendex)" }
                      ],
                      "resources": ["search", "book-details"]
                    }
                    """.trimIndent(),
                ),
                JSON_CONTENT_TYPE,
            )

        assertEquals(listOf("book"), v3.types)
        assertEquals(
            listOf(AddonCatalogEntryV3("book", "gutendex", "Project Gutenberg (via Gutendex)")),
            v3.catalogs,
        )
        assertEquals(
            listOf(AddonResourceV3("catalog"), AddonResourceV3("meta", listOf("book"))),
            v3.resources,
        )
        assertEquals(AddonShape.LEGACY_V2, v3.nexo?.shape)
    }

    @Test
    fun `loadManifestV3 rejects a non-JSON content type and invalid JSON`() {
        val v2 =
            """
            {
              "id": "gutendex",
              "name": "Gutendex",
              "version": "1.0.0",
              "catalogs": [{ "type": "book-catalog", "id": "gutendex", "name": "Project Gutenberg" }],
              "resources": ["search"]
            }
            """.trimIndent()

        assertEquals(
            AddonFetchErrorCode.BAD_CONTENT_TYPE,
            codeOf { AddonManifestParserV3.loadManifestV3(bytes(v2), "text/html") },
        )
        assertEquals(
            AddonFetchErrorCode.INVALID_MANIFEST,
            codeOf { AddonManifestParserV3.loadManifestV3(bytes("{ not json"), JSON_CONTENT_TYPE) },
        )
    }

    // ------------------------------------------------------------------- router

    @Test
    fun `declaresResource matches every type for a string-form resource`() {
        val declared = manifest(resources = listOf(AddonResourceV3("meta")))

        assertTrue(AddonResourceRouter.declaresResource(declared, "meta", "book", "isbn:9780141439518"))
        assertTrue(AddonResourceRouter.declaresResource(declared, "meta", "movie", "isbn:9780141439518"))
        assertTrue(AddonResourceRouter.declaresResource(declared, "meta", "series", "x"))
    }

    @Test
    fun `declaresResource filters by types on an object-form resource`() {
        val declared = manifest(resources = listOf(AddonResourceV3("meta", listOf("movie"))))

        assertTrue(AddonResourceRouter.declaresResource(declared, "meta", "movie", "x"))
        assertFalse(AddonResourceRouter.declaresResource(declared, "meta", "book", "x"))
        assertFalse(AddonResourceRouter.declaresResource(declared, "stream", "movie", "x"))
    }

    @Test
    fun `declaresResource filters meta and stream by idPrefixes`() {
        val declared =
            manifest(
                resources =
                    listOf(
                        AddonResourceV3("meta", listOf("book"), listOf("isbn:")),
                        AddonResourceV3("stream", listOf("book"), listOf("gutenberg:", "isbn:")),
                    ),
            )

        assertTrue(AddonResourceRouter.declaresResource(declared, "meta", "book", "isbn:9780141439518"))
        assertFalse(AddonResourceRouter.declaresResource(declared, "meta", "book", "gutenberg:1342"))
        assertTrue(AddonResourceRouter.declaresResource(declared, "stream", "book", "gutenberg:1342"))
        assertFalse(AddonResourceRouter.declaresResource(declared, "stream", "book", "archive:pride"))

        val inherited =
            manifest(
                resources = listOf(AddonResourceV3("stream", listOf("book"))),
                idPrefixes = listOf("isbn:"),
            )
        assertTrue(AddonResourceRouter.declaresResource(inherited, "stream", "book", "isbn:9780141439518"))
        assertFalse(AddonResourceRouter.declaresResource(inherited, "stream", "book", "gutenberg:1342"))

        val unfiltered = manifest(resources = listOf(AddonResourceV3("stream", listOf("book"))))
        assertTrue(AddonResourceRouter.declaresResource(unfiltered, "stream", "book", "archive:pride"))

        val empty = manifest(resources = listOf(AddonResourceV3("stream", listOf("book"), emptyList())))
        assertTrue(AddonResourceRouter.declaresResource(empty, "stream", "book", "archive:pride"))
    }

    @Test
    fun `declaresResource never filters catalog by idPrefixes`() {
        val declared =
            manifest(
                resources = listOf(AddonResourceV3("catalog", null, listOf("isbn:"))),
                idPrefixes = listOf("isbn:"),
            )

        assertTrue(AddonResourceRouter.declaresResource(declared, "catalog", "book", "popular"))
        assertTrue(AddonResourceRouter.declaresResource(declared, "catalog", "book", "isbn:9780141439518"))
        assertEquals(
            1,
            AddonResourceRouter
                .selectAddons(
                    listOf(addon("a", manifest = declared)),
                    AddonRouteRequest("catalog", "book", "recent"),
                ).size,
        )
    }

    @Test
    fun `declaresResource routes a templated addon without a resources match`() {
        val declared =
            manifest(
                resources = listOf(AddonResourceV3("meta", listOf("movie"))),
                nexo = AddonNexo(templates = mapOf("stream" to "https://a.test/stream/{id}")),
            )

        assertFalse(declared.resources.any { it.name == "stream" })
        assertTrue(AddonResourceRouter.declaresResource(declared, "stream", "book", "gutenberg:1342"))
        assertEquals(
            1,
            AddonResourceRouter
                .selectAddons(
                    listOf(addon("a", manifest = declared)),
                    AddonRouteRequest("stream", "book", "gutenberg:1342"),
                ).size,
        )
        assertFalse(AddonResourceRouter.declaresResource(declared, "meta", "book", "gutenberg:1342"))
    }

    @Test
    fun `selectAddons excludes disabled addons and orders by addedAt then id`() {
        val declared = manifest(resources = listOf(AddonResourceV3("meta")))
        val addons =
            listOf(
                addon("enabled", addedAt = 1, manifest = declared),
                addon("disabled", addedAt = 2, enabled = false, manifest = declared),
            )

        assertEquals(
            listOf("enabled"),
            AddonResourceRouter.selectAddons(addons, AddonRouteRequest("meta", "book", "x")).map { it.id },
        )

        val ordered =
            listOf(
                addon("late", addedAt = 30, manifest = declared),
                addon("zulu", addedAt = 10, manifest = declared),
                addon("alpha", addedAt = 10, manifest = declared),
                addon("middle", addedAt = 20, manifest = declared),
                addon("wrong-resource", manifest = manifest(resources = listOf(AddonResourceV3("stream")))),
            )
        val selected = AddonResourceRouter.selectAddons(ordered, AddonRouteRequest("meta", "book", "x"))

        assertEquals(listOf("alpha", "zulu", "middle", "late"), selected.map { it.id })

        val shuffled = listOf(ordered[2], ordered[4], ordered[1], ordered[3], ordered[0])
        assertEquals(
            selected,
            AddonResourceRouter.selectAddons(shuffled, AddonRouteRequest("meta", "book", "x")),
        )
        assertNotSame(ordered, selected)
    }

    @Test
    fun `declaredExtras returns only the catalog names`() {
        val declared =
            manifest(
                catalogs =
                    listOf(
                        AddonCatalogEntryV3(
                            "book",
                            "popular",
                            "Popular",
                            listOf(AddonExtraArg("search"), AddonExtraArg("skip", false)),
                        ),
                        AddonCatalogEntryV3("book", "recent", "Recent", listOf(AddonExtraArg("lang"))),
                        AddonCatalogEntryV3("book", "bare", "Bare"),
                    ),
            )

        assertEquals(listOf("search", "skip"), AddonResourceRouter.declaredExtras(declared, "popular"))
        assertEquals(listOf("lang"), AddonResourceRouter.declaredExtras(declared, "recent"))
        assertEquals(emptyList<String>(), AddonResourceRouter.declaredExtras(declared, "bare"))
        assertEquals(emptyList<String>(), AddonResourceRouter.declaredExtras(declared, "missing"))
    }

    // ----------------------------------------------------------------- payloads

    @Test
    fun `parseCatalogPayload parses a metas envelope and coerces missing author arrays`() {
        val parsed = AddonPayloads.parseCatalogPayload(json(catalogRaw), AddonShape.RAW)

        assertEquals(2, parsed.skipped)
        assertEquals(3, parsed.metas.size)
        assertEquals(
            MetaPreview(
                id = "gutenberg:1342",
                type = "book",
                name = "Pride and Prejudice",
                poster = "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
                description = "Austen's most famous novel.",
                releaseInfo = "1813",
                authors = listOf("Jane Austen"),
                languages = listOf("en"),
                subjects = listOf("Courtship -- Fiction"),
            ),
            parsed.metas[0],
        )
        assertEquals(
            MetaPreview(
                id = "isbn:9780141439518",
                type = "book",
                name = "Pride and Prejudice (Penguin Classics)",
                authors = listOf("Jane Austen"),
                languages = listOf("en"),
                isbn13 = "9780141439518",
            ),
            parsed.metas[1],
        )
        assertEquals(
            MetaPreview(
                id = "gutenberg:84",
                type = "book",
                name = "Frankenstein; Or, The Modern Prometheus",
            ),
            parsed.metas[2],
        )
    }

    @Test
    fun `parseCatalogPayload caps metas at 200 and counts the overflow as skipped`() {
        val entries = JSONArray()
        for (index in 0 until 250) {
            entries.put(JSONObject().put("id", "gutenberg:$index").put("type", "book").put("name", "Book $index"))
        }

        val parsed = AddonPayloads.parseCatalogPayload(JSONObject().put("metas", entries), AddonShape.RAW)

        assertEquals(200, parsed.metas.size)
        assertEquals(50, parsed.skipped)
        assertEquals("gutenberg:199", parsed.metas[199].id)
    }

    @Test
    fun `parseCatalogPayload truncates a long string at 2048 chars and skips a non-13-digit isbn`() {
        val parsed =
            AddonPayloads.parseCatalogPayload(
                json("""{ "metas": [{ "id": "gutenberg:1", "name": "Long", "description": "${"x".repeat(3000)}" }] }"""),
                AddonShape.RAW,
            )

        assertEquals(0, parsed.skipped)
        assertEquals(2048, parsed.metas[0].description?.length)
        assertEquals("Long", parsed.metas[0].name)

        val tenDigits =
            AddonPayloads.parseCatalogPayload(
                json("""{ "metas": [{ "id": "isbn:0141439513", "name": "Ten digits" }] }"""),
                AddonShape.RAW,
            )
        assertNull(tenDigits.metas[0].isbn13)
    }

    @Test
    fun `parseCatalogPayload returns an empty result for a malformed envelope`() {
        for (body in malformedBodies) {
            assertEquals(CatalogPayload(emptyList(), 1), AddonPayloads.parseCatalogPayload(body, AddonShape.RAW))
        }
    }

    @Test
    fun `parseCatalogPayload reads a gutendex native payload through its shape`() {
        val parsed = AddonPayloads.parseCatalogPayload(json(gutendexBooks), AddonShape.GUTENDEX)

        assertEquals(1, parsed.skipped)
        assertEquals(2, parsed.metas.size)
        assertEquals(
            MetaPreview(
                id = "gutenberg:1342",
                type = "book",
                name = "Pride and Prejudice",
                poster = "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
                authors = listOf("Austen, Jane"),
                languages = listOf("en"),
                subjects = listOf("Courtship -- Fiction", "England -- Fiction"),
            ),
            parsed.metas[0],
        )
    }

    @Test
    fun `parseCatalogPayload reads an openlibrary search payload through its shape`() {
        val parsed = AddonPayloads.parseCatalogPayload(json(openlibrarySearch), AddonShape.OPENLIBRARY)

        assertEquals(1, parsed.skipped)
        assertEquals(1, parsed.metas.size)
        assertEquals(
            MetaPreview(
                id = "openlibrary:OL66554W",
                type = "book",
                name = "Pride and prejudice",
                poster = "https://covers.openlibrary.org/b/id/8732464-M.jpg",
                releaseInfo = "1813",
                authors = listOf("Jane Austen"),
                languages = listOf("eng"),
                subjects = listOf("Courtship", "Fiction"),
                isbn13 = "9780141439518",
            ),
            parsed.metas[0],
        )
    }

    @Test
    fun `parseCatalogPayload contributes nothing for an unimplemented shape`() {
        assertEquals(
            CatalogPayload(emptyList(), 0),
            AddonPayloads.parseCatalogPayload(json(catalogRaw), AddonShape.LEGACY_V2),
        )
    }

    @Test
    fun `parseCatalogPayload never throws for any body and shape combination`() {
        for (shape in AddonShape.entries) {
            for (body in malformedBodies) {
                AddonPayloads.parseCatalogPayload(body, shape)
            }
        }
    }

    @Test
    fun `parseMetaPayload reads a single meta from the envelope`() {
        val body =
            json(
                """
                {
                  "meta": {
                    "id": "openlibrary:OL66554W",
                    "type": "book",
                    "name": "Pride and Prejudice",
                    "poster": "https://covers.openlibrary.org/b/id/8732464-M.jpg",
                    "description": "A novel of manners.",
                    "releaseInfo": "1813",
                    "authors": ["Jane Austen"],
                    "languages": ["en"],
                    "subjects": ["Courtship -- Fiction"],
                    "isbn13": "9780141439518"
                  }
                }
                """.trimIndent(),
            )

        assertEquals(
            MetaPreview(
                id = "openlibrary:OL66554W",
                type = "book",
                name = "Pride and Prejudice",
                poster = "https://covers.openlibrary.org/b/id/8732464-M.jpg",
                description = "A novel of manners.",
                releaseInfo = "1813",
                authors = listOf("Jane Austen"),
                languages = listOf("en"),
                subjects = listOf("Courtship -- Fiction"),
                isbn13 = "9780141439518",
            ),
            AddonPayloads.parseMetaPayload(body, AddonShape.RAW),
        )
    }

    @Test
    fun `parseMetaPayload returns null for a malformed or unreadable envelope`() {
        for (body in malformedBodies) {
            assertNull(AddonPayloads.parseMetaPayload(body, AddonShape.RAW))
        }
        assertNull(AddonPayloads.parseMetaPayload(json("""{ "meta": { "type": "book", "name": "No id" } }"""), AddonShape.RAW))
        assertNull(AddonPayloads.parseMetaPayload(json("""{ "meta": { "id": "gutenberg:1", "type": "book" } }"""), AddonShape.RAW))
        assertNull(AddonPayloads.parseMetaPayload(json("""{ "meta": null }"""), AddonShape.RAW))
    }

    @Test
    fun `parseMetaPayload reads a gutendex book and an openlibrary work through their shapes`() {
        val books = json(gutendexBooks) as JSONObject
        val results = books.getJSONArray("results")

        assertEquals("gutenberg:84", AddonPayloads.parseMetaPayload(results.get(1), AddonShape.GUTENDEX)?.id)
        assertNull(AddonPayloads.parseMetaPayload(results.get(2), AddonShape.GUTENDEX))

        val work = AddonPayloads.parseMetaPayload(json(openlibraryWork), AddonShape.OPENLIBRARY)
        assertEquals("openlibrary:OL66554W", work?.id)
        assertEquals("A novel of manners.", work?.description)
        assertEquals("1813", work?.releaseInfo)
        assertEquals("https://covers.openlibrary.org/b/id/8732464-M.jpg", work?.poster)
    }

    @Test
    fun `parseStreamPayload maps url streams to download and externalUrl streams to external`() {
        val parsed =
            AddonPayloads.parseStreamPayload(
                json(
                    """
                    {
                      "streams": [
                        {
                          "name": "Standard Ebooks",
                          "title": "EPUB · Public domain",
                          "url": "https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice/downloads/jane-austen_pride-and-prejudice.epub",
                          "behaviorHints": { "accessType": "public-domain", "format": "epub" }
                        },
                        {
                          "name": "Open Library",
                          "title": "Borrow for 14 days",
                          "externalUrl": "https://openlibrary.org/borrow/ia/prideandprejudice00aust",
                          "behaviorHints": { "accessType": "borrow" }
                        },
                        {
                          "name": "Archive scan",
                          "url": "https://archive.org/download/prideandprejudice/prideandprejudice.pdf?token=abc",
                          "behaviorHints": {}
                        },
                        { "name": "A stream that links nowhere", "title": "Broken" }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(1, parsed.skipped)
        assertEquals(3, parsed.streams.size)
        assertEquals(
            BookStream(
                source = StreamSource.DOWNLOAD,
                url = "https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice/downloads/jane-austen_pride-and-prejudice.epub",
                accessType = StreamAccessType.PUBLIC_DOMAIN,
                format = StreamFormat.EPUB,
                name = "Standard Ebooks",
                title = "EPUB · Public domain",
            ),
            parsed.streams[0],
        )
        assertEquals(
            BookStream(
                source = StreamSource.EXTERNAL,
                url = "https://openlibrary.org/borrow/ia/prideandprejudice00aust",
                accessType = StreamAccessType.BORROW,
                format = StreamFormat.EPUB,
                name = "Open Library",
                title = "Borrow for 14 days",
            ),
            parsed.streams[1],
        )
        assertEquals(
            BookStream(
                source = StreamSource.DOWNLOAD,
                url = "https://archive.org/download/prideandprejudice/prideandprejudice.pdf?token=abc",
                accessType = StreamAccessType.UNKNOWN,
                format = StreamFormat.PDF,
                name = "Archive scan",
            ),
            parsed.streams[2],
        )
    }

    @Test
    fun `parseStreamPayload drops every infoHash stream and counts it as skipped`() {
        val parsed =
            AddonPayloads.parseStreamPayload(
                json(
                    """
                    {
                      "streams": [
                        { "url": "https://example.org/book.epub" },
                        { "url": "https://example.org/a.epub", "infoHash": "aaa" },
                        { "url": "https://example.org/b.epub", "infoHash": "bbb", "sources": ["tracker:udp://x"] },
                        { "url": "https://example.org/c.epub", "infoHash": null }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(1, parsed.streams.size)
        assertEquals("https://example.org/book.epub", parsed.streams[0].url)
        assertEquals(3, parsed.skipped)
    }

    @Test
    fun `parseStreamPayload drops a bittorrent hint and a torrent source`() {
        val hints =
            AddonPayloads.parseStreamPayload(
                json(
                    """
                    {
                      "streams": [
                        { "url": "https://example.org/a.epub", "behaviorHints": { "bittorrent": { "infoHash": "aaa" } } },
                        { "url": "https://example.org/b.epub", "behaviorHints": { "accessType": "borrow" } }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(1, hints.streams.size)
        assertEquals(1, hints.skipped)

        val sources =
            AddonPayloads.parseStreamPayload(
                json(
                    """
                    {
                      "streams": [
                        { "url": "https://example.org/a.epub", "sources": [{ "type": "torrent" }] },
                        { "url": "https://example.org/b.epub", "sources": [{ "type": "TORRENT" }] },
                        { "url": "https://example.org/c.epub", "sources": [{ "type": "web" }] }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(listOf("https://example.org/c.epub"), sources.streams.map { it.url })
        assertEquals(2, sources.skipped)
    }

    @Test
    fun `parseStreamPayload drops a non http link even when it is the only link present`() {
        val parsed =
            AddonPayloads.parseStreamPayload(
                json(
                    """
                    {
                      "streams": [
                        { "url": "magnet:?xt=urn:btih:8f2a1c4b" },
                        { "externalUrl": "magnet:?xt=urn:btih:8f2a1c4b" },
                        { "url": "ftp://example.org/book.epub" }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(emptyList<BookStream>(), parsed.streams)
        assertEquals(3, parsed.skipped)
    }

    @Test
    fun `parseStreamPayload infers format from the url extension and defaults to epub`() {
        val parsed =
            AddonPayloads.parseStreamPayload(
                json(
                    """
                    {
                      "streams": [
                        { "url": "https://example.org/book.pdf" },
                        { "url": "https://example.org/book.PDF?download=1#page=2" },
                        { "url": "https://example.org/book.epub" },
                        { "externalUrl": "https://openlibrary.org/borrow/ia/somebook" },
                        { "url": "https://example.org/book.mobi", "behaviorHints": { "format": "pdf" } }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(
            listOf(
                StreamFormat.PDF,
                StreamFormat.PDF,
                StreamFormat.EPUB,
                StreamFormat.EPUB,
                StreamFormat.PDF,
            ),
            parsed.streams.map { it.format },
        )
    }

    @Test
    fun `parseStreamPayload reads accessType from behaviorHints and defaults to unknown`() {
        val parsed =
            AddonPayloads.parseStreamPayload(
                json(
                    """
                    {
                      "streams": [
                        { "url": "https://example.org/a.epub", "behaviorHints": { "accessType": "public-domain" } },
                        { "url": "https://example.org/b.epub", "behaviorHints": { "accessType": "borrow" } },
                        { "url": "https://example.org/c.epub", "behaviorHints": { "accessType": "purchase" } },
                        { "url": "https://example.org/d.epub", "behaviorHints": { "accessType": "rented" } },
                        { "url": "https://example.org/e.epub" }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(
            listOf(
                StreamAccessType.PUBLIC_DOMAIN,
                StreamAccessType.BORROW,
                StreamAccessType.PURCHASE,
                StreamAccessType.UNKNOWN,
                StreamAccessType.UNKNOWN,
            ),
            parsed.streams.map { it.accessType },
        )
    }

    @Test
    fun `parseStreamPayload caps streams at 100 and rejects a malformed envelope`() {
        val entries = JSONArray()
        for (index in 0 until 150) {
            entries.put(JSONObject().put("url", "https://example.org/$index.epub"))
        }

        val capped = AddonPayloads.parseStreamPayload(JSONObject().put("streams", entries))

        assertEquals(100, capped.streams.size)
        assertEquals(50, capped.skipped)

        for (body in malformedBodies) {
            assertEquals(StreamPayload(emptyList(), 1), AddonPayloads.parseStreamPayload(body))
        }
    }

    @Test
    fun `parseStreamPayload gives a TPB-shaped addon zero options and zero errors`() {
        val parsed =
            AddonPayloads.parseStreamPayload(
                json(
                    """
                    {
                      "streams": [
                        { "name": "ThePirateBay+", "title": "2160p", "infoHash": "DEADBEEFDEADBEEFDEADBEEFDEADBEEF", "sources": [{ "type": "torrent" }] },
                        { "name": "ThePirateBay+", "title": "1080p", "infoHash": "DEADBEEFDEADBEEFDEADBEEFDEADBEEF" },
                        { "name": "ThePirateBay+", "title": "720p", "infoHash": null }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(emptyList<BookStream>(), parsed.streams)
        assertEquals(3, parsed.skipped)
    }

    @Test
    fun `assertDeliverableStream accepts a plain download or external link`() {
        assertEquals(
            BookStream(
                source = StreamSource.DOWNLOAD,
                url = "https://example.org/book.epub",
                accessType = StreamAccessType.UNKNOWN,
                format = StreamFormat.EPUB,
            ),
            AddonDeliveryGuard.assertDeliverableStream(json("""{ "url": "https://example.org/book.epub" }""")),
        )
        assertEquals(
            BookStream(
                source = StreamSource.EXTERNAL,
                url = "https://example.org/borrow/1",
                accessType = StreamAccessType.UNKNOWN,
                format = StreamFormat.EPUB,
            ),
            AddonDeliveryGuard.assertDeliverableStream(json("""{ "externalUrl": "https://example.org/borrow/1" }""")),
        )
    }

    @Test
    fun `assertDeliverableStream rejects every torrent identity and unusable link`() {
        val rejected =
            listOf<Any?>(
                null,
                "https://example.org/book.epub",
                json("[]"),
                json("{}"),
                json("""{ "name": "No link" }"""),
                json("""{ "url": "" }"""),
                json("""{ "url": 42 }"""),
                json("""{ "infoHash": "aaa" }"""),
                json("""{ "infoHash": "aaa", "url": "https://example.org/book.epub" }"""),
                json("""{ "infoHash": null, "url": "https://example.org/book.epub" }"""),
                json("""{ "url": "https://example.org/book.epub", "behaviorHints": { "bittorrent": {} } }"""),
                json("""{ "url": "https://example.org/book.epub", "behaviorHints": { "bittorrent": null } }"""),
                json("""{ "url": "https://example.org/book.epub", "sources": [{ "type": "torrent" }] }"""),
                json("""{ "url": "https://example.org/book.epub", "sources": [{ "type": " Torrent " }] }"""),
                json("""{ "url": "magnet:?xt=urn:btih:aaa" }"""),
                json("""{ "externalUrl": "magnet:?xt=urn:btih:aaa" }"""),
                json("""{ "url": "data:text/plain,hello" }"""),
            )

        for (raw in rejected) {
            assertNull(AddonDeliveryGuard.assertDeliverableStream(raw))
        }
    }

    @Test
    fun `assertDeliverableStream lets a valid stream through when behaviorHints carry unrelated keys`() {
        assertEquals(
            BookStream(
                source = StreamSource.DOWNLOAD,
                url = "https://example.org/book.epub",
                accessType = StreamAccessType.UNKNOWN,
                format = StreamFormat.EPUB,
            ),
            AddonDeliveryGuard.assertDeliverableStream(
                json("""{ "url": "https://example.org/book.epub", "behaviorHints": { "bingeGroup": "x", "proxyHeaders": {} } }"""),
            ),
        )
    }

    @Test
    fun `orderStreams orders options public-domain first, then epub, then addon order`() {
        val publicEpubLate = stream(accessType = StreamAccessType.PUBLIC_DOMAIN, addonId = "zz", addedAt = 200)
        val publicEpubEarly = stream(accessType = StreamAccessType.PUBLIC_DOMAIN, addonId = "mm", addedAt = 100)
        val publicPdf = stream(accessType = StreamAccessType.PUBLIC_DOMAIN, format = StreamFormat.PDF, addonId = "cc", addedAt = 3)
        val borrow = stream(accessType = StreamAccessType.BORROW, addonId = "bb", addedAt = 2)
        val purchase = stream(accessType = StreamAccessType.PURCHASE, addonId = "aa", addedAt = 1)
        val unknown = stream(accessType = StreamAccessType.UNKNOWN, addonId = "dd", addedAt = 4)

        val ordered =
            AddonPayloads.orderStreams(
                listOf(unknown, purchase, publicPdf, borrow, publicEpubLate, publicEpubEarly),
            )

        assertEquals(
            listOf(
                "public-domain/epub/mm",
                "public-domain/epub/zz",
                "public-domain/pdf/cc",
                "borrow/epub/bb",
                "purchase/epub/aa",
                "unknown/epub/dd",
            ),
            ordered.map { "${it.accessType.wire}/${it.format.wire}/${it.addonId}" },
        )
    }

    @Test
    fun `orderStreams breaks the addon order tie with the addon id`() {
        val ordered =
            AddonPayloads.orderStreams(
                listOf(
                    stream(accessType = StreamAccessType.PUBLIC_DOMAIN, addonId = "b-addon", addedAt = 10),
                    stream(accessType = StreamAccessType.PUBLIC_DOMAIN, addonId = "a-addon", addedAt = 10),
                ),
            )

        assertEquals(listOf("a-addon", "b-addon"), ordered.map { it.addonId })
    }

    @Test
    fun `orderStreams is stable and leaves the input untouched`() {
        val first = stream(accessType = StreamAccessType.BORROW, addonId = "same")
        val second = stream(accessType = StreamAccessType.BORROW, addonId = "same")
        val input = listOf(first, second)

        val ordered = AddonPayloads.orderStreams(input)

        assertNotSame(input, ordered)
        assertSame(first, ordered[0])
        assertSame(second, ordered[1])
        assertEquals(input, ordered)
    }

    @Test
    fun `shape adapters count native entries they cannot read`() {
        assertEquals(
            CatalogPayload(emptyList(), 1),
            AddonPayloads.parseCatalogPayload(null, AddonShape.GUTENDEX),
        )
        assertEquals(
            CatalogPayload(emptyList(), 1),
            AddonPayloads.parseCatalogPayload(json("""{ "docs": "nope" }"""), AddonShape.OPENLIBRARY),
        )
        assertNull(AddonPayloads.parseMetaPayload(json("{}"), AddonShape.GUTENDEX))
        assertNull(
            AddonPayloads.parseMetaPayload(json("""{ "key": "/works/OL1W" }"""), AddonShape.OPENLIBRARY),
        )
    }

    // -------------------------------------------------------------------- cache

    @Test
    fun `TtlCache returns null for a key it never stored`() {
        val cache = TtlCache(now = Clock()::now)

        assertNull(cache.get("missing"))
    }

    @Test
    fun `TtlCache expires an entry against the injected clock and treats the boundary as expired`() {
        val time = Clock()
        val cache = TtlCache(now = time::now)
        cache.set("k", "value", 300_000)

        time.advance(299_999)
        assertEquals("value", cache.get("k"))

        time.advance(1)
        assertNull(cache.get("k"))
    }

    @Test
    fun `TtlCache re-sets a key with a fresh value and a fresh ttl`() {
        val time = Clock()
        val cache = TtlCache(now = time::now)
        cache.set("k", "first", 1_000)
        cache.set("k", "second", 1_000)
        assertEquals("second", cache.get("k"))

        time.advance(999)
        assertEquals("second", cache.get("k"))
        time.advance(1)
        assertNull(cache.get("k"))
    }

    @Test
    fun `TtlCache evicts the least recently used entry at maxEntries`() {
        val cache = TtlCache(now = Clock()::now, maxEntries = 2)
        cache.set("a", "one", 60_000)
        cache.set("b", "two", 60_000)

        assertEquals("one", cache.get("a"))
        cache.set("c", "three", 60_000)

        assertEquals("one", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("three", cache.get("c"))
    }

    @Test
    fun `TtlCache evicts an expired entry before a live one`() {
        val time = Clock()
        val cache = TtlCache(now = time::now, maxEntries = 2)
        cache.set("short", "gone-soon", 10)
        cache.set("long", "stays", 60_000)

        time.advance(10)
        cache.set("new", "fresh", 60_000)

        assertEquals("stays", cache.get("long"))
        assertEquals("fresh", cache.get("new"))
    }

    @Test
    fun `TtlCache drops every entry on clear`() {
        val cache = TtlCache(now = Clock()::now)
        cache.set("a", "one", 60_000)
        cache.clear()

        assertNull(cache.get("a"))
    }

    // ---------------------------------------------------------- circuit breaker

    @Test
    fun `CircuitBreaker stays closed below the threshold and opens after three failures`() {
        val closed = CircuitBreaker(now = Clock()::now)
        closed.recordFailure("a")
        closed.recordFailure("a")
        assertTrue(closed.canRequest("a"))

        val open = CircuitBreaker(now = Clock()::now)
        open.recordFailure("a")
        open.recordFailure("a")
        open.recordFailure("a")
        assertFalse(open.canRequest("a"))
    }

    @Test
    fun `CircuitBreaker resets the failure count on success`() {
        val breaker = CircuitBreaker(now = Clock()::now)
        breaker.recordFailure("a")
        breaker.recordFailure("a")
        breaker.recordSuccess("a")
        breaker.recordFailure("a")
        breaker.recordFailure("a")

        assertTrue(breaker.canRequest("a"))
    }

    @Test
    fun `CircuitBreaker half-opens after the cooldown and grants exactly one probe`() {
        val time = Clock()
        val breaker = CircuitBreaker(now = time::now)
        repeat(3) { breaker.recordFailure("a") }

        time.advance(299_999)
        assertFalse(breaker.canRequest("a"))

        time.advance(1)
        assertTrue(breaker.canRequest("a"))
        assertFalse(breaker.canRequest("a"))

        breaker.recordSuccess("a")
        assertTrue(breaker.canRequest("a"))
    }

    @Test
    fun `CircuitBreaker reopens for a full cooldown when the probe fails`() {
        val time = Clock()
        val breaker = CircuitBreaker(now = time::now)
        repeat(3) { breaker.recordFailure("a") }

        time.advance(300_000)
        assertTrue(breaker.canRequest("a"))
        breaker.recordFailure("a")

        time.advance(299_999)
        assertFalse(breaker.canRequest("a"))
        time.advance(1)
        assertTrue(breaker.canRequest("a"))
    }

    @Test
    fun `CircuitBreaker tracks each addon independently and honors custom options`() {
        val breaker = CircuitBreaker(now = Clock()::now)
        repeat(3) { breaker.recordFailure("a") }
        assertFalse(breaker.canRequest("a"))
        assertTrue(breaker.canRequest("b"))

        val time = Clock()
        val custom = CircuitBreaker(threshold = 1, openMs = 5_000, now = time::now)
        custom.recordFailure("a")
        assertFalse(custom.canRequest("a"))
        time.advance(5_000)
        assertTrue(custom.canRequest("a"))
    }

    // ------------------------------------------------------------------- client

    @Test
    fun `createAddonClient serves a repeated catalog request from cache`() {
        val (transport, urls) = spy { CATALOG_BODY_RESPONSE }
        val client = AddonClient(transport = transport, now = Clock()::now)

        val first = runBlocking { client.searchCatalog(listOf(addon("one", 1)), CatalogRequest("popular")) }
        val second = runBlocking { client.searchCatalog(listOf(addon("one", 1)), CatalogRequest("popular")) }

        assertEquals(1, urls.size)
        assertEquals(first.items, second.items)
    }

    @Test
    fun `createAddonClient expires a catalog entry after 300 s and never extends it from upstream`() {
        val time = Clock()
        val cached = spy { CATALOG_BODY_RESPONSE }
        val client =
            AddonClient(
                transport = cached.first,
                now = time::now,
            )

        runBlocking { client.searchCatalog(listOf(addon("one", 1)), CatalogRequest("popular")) }
        time.advance(300_000)
        runBlocking { client.searchCatalog(listOf(addon("one", 1)), CatalogRequest("popular")) }
        assertEquals(2, cached.second.size)

        val longer = Clock()
        val shared =
            spy {
                ResourceResponse(200, json(CATALOG_BODY), "public, max-age=86400")
            }
        val longClient = AddonClient(transport = shared.first, now = longer::now)
        runBlocking { longClient.searchCatalog(listOf(addon("one", 1)), CatalogRequest("popular")) }
        longer.advance(300_000)
        runBlocking { longClient.searchCatalog(listOf(addon("one", 1)), CatalogRequest("popular")) }
        assertEquals(2, shared.second.size)
    }

    @Test
    fun `createAddonClient honors an upstream max-age shorter than the default`() {
        val time = Clock()
        val (transport, urls) = spy { ResourceResponse(200, json(CATALOG_BODY), "max-age=30") }
        val client = AddonClient(transport = transport, now = time::now)

        runBlocking { client.searchCatalog(listOf(addon("one", 1)), CatalogRequest("popular")) }
        time.advance(29_999)
        runBlocking { client.searchCatalog(listOf(addon("one", 1)), CatalogRequest("popular")) }
        assertEquals(1, urls.size)

        time.advance(1)
        runBlocking { client.searchCatalog(listOf(addon("one", 1)), CatalogRequest("popular")) }
        assertEquals(2, urls.size)
    }

    @Test
    fun `createAddonClient keeps catalog and stream entries in separate namespaces`() {
        val (transport, urls) =
            spy { url ->
                if (url.contains("/stream/")) {
                    ResourceResponse(200, json("""{ "streams": [{ "url": "https://cdn.test/a.epub" }] }"""))
                } else {
                    CATALOG_BODY_RESPONSE
                }
            }
        val client = AddonClient(transport = transport, now = Clock()::now)

        runBlocking {
            client.searchCatalog(listOf(addon("one", 1)), CatalogRequest("popular"))
            client.getStreams(listOf(addon("one", 1)), IdRequest("popular"))
        }

        assertEquals(2, urls.size)
    }

    @Test
    fun `createAddonClient keeps results from healthy addons when one times out`() {
        val (transport, _) =
            spy { url ->
                if (url.contains("slow.test")) throw AddonTransportTimeoutException() else CATALOG_BODY_RESPONSE
            }
        val client = AddonClient(transport = transport)

        val result =
            runBlocking {
                client.searchCatalog(listOf(addon("healthy", 1), addon("slow", 2)), CatalogRequest("popular"))
            }

        assertEquals(listOf("healthy"), result.items.map { it.addonId })
        assertEquals(listOf(AddonFailure("slow", FailureReason.TIMEOUT)), result.failures)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `createAddonClient classifies status payload and network failures`() {
        val (status, _) = spy { ResourceResponse(503, json("{}")) }
        val statusResult =
            runBlocking {
                AddonClient(transport = status).searchCatalog(listOf(addon("broken", 1)), CatalogRequest("popular"))
            }
        assertEquals(emptyList<AddonMetaItem>(), statusResult.items)
        assertEquals(listOf(AddonFailure("broken", FailureReason.STATUS)), statusResult.failures)

        val (malformed, _) = spy { throw JSONException("Unexpected token < in JSON at position 0") }
        val payloadResult =
            runBlocking {
                AddonClient(transport = malformed).searchCatalog(listOf(addon("html", 1)), CatalogRequest("popular"))
            }
        assertEquals(listOf(AddonFailure("html", FailureReason.PAYLOAD)), payloadResult.failures)

        val (fat, _) =
            spy {
                ResourceResponse(
                    200,
                    json("""{ "metas": [{ "id": "gutenberg:1", "type": "book", "name": "${"n".repeat(600 * 1024)}" }] }"""),
                )
            }
        val fatResult =
            runBlocking {
                AddonClient(transport = fat).searchCatalog(listOf(addon("fat", 1)), CatalogRequest("popular"))
            }
        assertEquals(emptyList<AddonMetaItem>(), fatResult.items)
        assertEquals(listOf(AddonFailure("fat", FailureReason.PAYLOAD)), fatResult.failures)

        val (offline, _) = spy { throw IllegalStateException("fetch failed") }
        val networkResult =
            runBlocking {
                AddonClient(transport = offline).searchCatalog(listOf(addon("offline", 1)), CatalogRequest("popular"))
            }
        assertEquals(listOf(AddonFailure("offline", FailureReason.NETWORK)), networkResult.failures)
    }

    @Test
    fun `createAddonClient skips an open-circuit addon without a failure entry`() {
        val breaker = CircuitBreaker(now = Clock()::now)
        repeat(3) { breaker.recordFailure("dead") }
        val (transport, urls) = spy { CATALOG_BODY_RESPONSE }
        val client = AddonClient(transport = transport, breaker = breaker)

        val result =
            runBlocking {
                client.searchCatalog(listOf(addon("dead", 1), addon("healthy", 2)), CatalogRequest("popular"))
            }

        assertEquals(1, urls.size)
        assertTrue(urls[0].contains("healthy.test"))
        assertEquals(1, result.skipped)
        assertEquals(emptyList<AddonFailure>(), result.failures)
        assertEquals(listOf("healthy"), result.items.map { it.addonId })
    }

    @Test
    fun `createAddonClient opens the circuit after three failed fan-outs`() {
        val breaker = CircuitBreaker(now = Clock()::now)
        val (transport, urls) = spy { ResourceResponse(500, json("{}")) }
        val client = AddonClient(transport = transport, breaker = breaker)

        runBlocking {
            repeat(3) { client.searchCatalog(listOf(addon("dead", 1)), CatalogRequest("popular")) }
        }
        val result =
            runBlocking {
                client.searchCatalog(listOf(addon("dead", 1)), CatalogRequest("popular"))
            }

        assertEquals(3, urls.size)
        assertEquals(1, result.skipped)
        assertEquals(emptyList<AddonFailure>(), result.failures)
    }

    @Test
    fun `createAddonClient closes the circuit again after a successful probe`() {
        val time = Clock()
        val breaker = CircuitBreaker(now = time::now)
        var healthy = false
        val (transport, urls) =
            spy { if (healthy) CATALOG_BODY_RESPONSE else ResourceResponse(500, json("{}")) }
        val client = AddonClient(transport = transport, breaker = breaker)

        runBlocking {
            repeat(3) { client.searchCatalog(listOf(addon("flaky", 1)), CatalogRequest("popular")) }
        }
        healthy = true
        time.advance(300_000)

        val probe = runBlocking { client.searchCatalog(listOf(addon("flaky", 1)), CatalogRequest("popular")) }
        assertEquals(emptyList<AddonFailure>(), probe.failures)
        assertTrue(breaker.canRequest("flaky"))

        val after = runBlocking { client.searchCatalog(listOf(addon("flaky", 1)), CatalogRequest("popular")) }
        assertEquals(1, after.items.size)
        assertEquals(4, urls.size)
    }

    @Test
    fun `createAddonClient dedupes by normalized url keeping the first addon`() {
        val (transport, urls) = spy { CATALOG_BODY_RESPONSE }
        val client = AddonClient(transport = transport)

        val result =
            runBlocking {
                client.searchCatalog(
                    listOf(
                        addon("first", 1).copy(installUrl = "https://shared.test/addons/manifest.json"),
                        addon("second", 2).copy(installUrl = "https://SHARED.test/addons/"),
                    ),
                    CatalogRequest("popular"),
                )
            }

        assertEquals(1, urls.size)
        assertEquals(listOf("first"), result.items.map { it.addonId })
        assertEquals(emptyList<AddonFailure>(), result.failures)
    }

    @Test
    fun `normalizeUrl lowercases scheme and host and drops the fragment and trailing slash`() {
        assertEquals(
            normalizeUrl("https://example.test/addons#section"),
            normalizeUrl("https://Example.TEST/addons/"),
        )
        assertEquals(
            normalizeUrl("https://example.test/addons?q=1"),
            normalizeUrl("https://example.test/addons/?q=1"),
        )
        assertEquals("https://example.test/addons", normalizeUrl("https://example.test/addons"))
        assertEquals("  not a url  ".trim(), normalizeUrl("  not a url  "))
    }

    @Test
    fun `createAddonClient sends only extra names the catalog declares`() {
        val (transport, urls) = spy { CATALOG_BODY_RESPONSE }
        val client = AddonClient(transport = transport)
        val addons =
            listOf(
                addon(
                    "one",
                    1,
                    manifest =
                        manifest(
                            catalogs =
                                listOf(
                                    AddonCatalogEntryV3(
                                        "book",
                                        "search",
                                        "Search",
                                        listOf(AddonExtraArg("search"), AddonExtraArg("skip")),
                                    ),
                                ),
                        ),
                ),
            )

        runBlocking {
            client.searchCatalog(
                addons,
                CatalogRequest("search", extra = linkedMapOf("search" to "dune", "genre" to "scifi", "skip" to 0)),
            )
        }

        assertTrue(urls[0].contains("search=dune"))
        assertTrue(urls[0].contains("skip=0"))
        assertFalse(urls[0].contains("genre"))
    }

    @Test
    fun `createAddonClient reads a meta payload and stamps the addon`() {
        val (transport, _) =
            spy {
                ResourceResponse(200, json("""{ "meta": { "id": "isbn:9780141439518", "type": "book", "name": "Emma" } }"""))
            }
        val client = AddonClient(transport = transport)

        val result = runBlocking { client.getMeta(listOf(addon("meta", 7)), IdRequest("isbn:9780141439518")) }

        assertEquals(1, result.items.size)
        assertEquals("Emma", result.items[0].meta.name)
        assertEquals("meta", result.items[0].addonId)
        assertEquals(7L, result.items[0].addedAt)
    }

    @Test
    fun `createAddonClient stamps every stream and orders across addons`() {
        val (transport, _) =
            spy { url ->
                if (url.contains("late.test")) {
                    ResourceResponse(
                        200,
                        json("""{ "streams": [{ "url": "https://cdn.test/free.epub", "behaviorHints": { "accessType": "public-domain" } }] }"""),
                    )
                } else {
                    ResourceResponse(
                        200,
                        json(
                            """
                            {
                              "streams": [
                                { "url": "https://cdn.test/borrowed.epub", "behaviorHints": { "accessType": "borrow" } },
                                { "infoHash": "deadbeef" }
                              ]
                            }
                            """.trimIndent(),
                        ),
                    )
                }
            }
        val client = AddonClient(transport = transport)

        val result =
            runBlocking {
                client.getStreams(listOf(addon("early", 1), addon("late", 2)), IdRequest("gutenberg:1342"))
            }

        assertEquals(listOf("late", "early"), result.items.map { it.addonId })
        assertEquals(listOf(2L, 1L), result.items.map { it.addedAt })
        assertEquals(emptyList<AddonFailure>(), result.failures)
    }

    private fun spy(handler: (String) -> ResourceResponse): Pair<AddonResourceTransport, MutableList<String>> {
        val urls = mutableListOf<String>()
        val transport =
            object : AddonResourceTransport {
                override suspend fun fetch(
                    url: String,
                    timeoutMs: Long,
                ): ResourceResponse {
                    urls += url
                    return handler(url)
                }
            }
        return transport to urls
    }

    private companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        const val INSTALL_URL = "https://a.test/manifest.json"
        const val CATALOG_BODY = """{ "metas": [{ "id": "gutenberg:1342", "type": "book", "name": "Pride and Prejudice" }] }"""
        val CATALOG_BODY_RESPONSE = ResourceResponse(200, JSONTokener(CATALOG_BODY).nextValue())
    }
}
