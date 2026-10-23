package com.nexo.data.remote.addons

import com.nexo.data.remote.addons.protocol.AddonClient
import com.nexo.data.remote.addons.protocol.AddonDescriptorV3
import com.nexo.data.remote.addons.protocol.AddonManifestParserV3
import com.nexo.data.remote.addons.protocol.AddonManifestV3
import com.nexo.data.remote.addons.protocol.CircuitBreaker
import com.nexo.data.remote.addons.protocol.TtlCache
import com.nexo.data.storage.CoverStorage
import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingState
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val META_ADDON = "abcd000000000010"
private const val ISBN = "9780141439518"

private fun metaManifest(
    id: String,
    resources: String,
    idPrefixes: String? = null,
): AddonManifestV3 =
    AddonManifestParserV3.loadManifestV3(
        buildString {
            append("""{"id":"$id","version":"3.0.0","name":"Meta $id","types":["book"],"catalogs":[],"resources":$resources""")
            if (idPrefixes != null) append(""","idPrefixes":$idPrefixes""")
            append("}")
        }.toByteArray(Charsets.UTF_8),
        "application/json",
    )

private fun descriptor(
    id: String,
    manifest: AddonManifestV3,
    enabled: Boolean = true,
): AddonDescriptorV3 = AddonDescriptorV3(id, "https://$id.example/manifest.json", 1L, enabled, manifest)

private fun ownedBook(
    id: String = "book-1",
    sourceItemId: String? = null,
    isbn13: String? = null,
    coverPath: String? = null,
    description: String? = null,
    publisher: String? = null,
    publishedDate: String? = null,
    language: String? = null,
    genre: String? = null,
    tags: String? = null,
): Book =
    Book(
        id = id,
        title = "Pride and Prejudice",
        author = "Jane Austen",
        coverPath = coverPath,
        filePath = "/books/$id.epub",
        format = "epub",
        totalPages = 432,
        chapterCount = 61,
        description = description,
        userRating = 4,
        updatedAtEpochMillis = 10L,
        status = "reading",
        readingState = ReadingState.READING,
        progressPercentage = 42f,
        progressUpdatedAtEpochMillis = 12L,
        stateVersion = 7L,
        genre = genre,
        language = language,
        publisher = publisher,
        tags = tags,
        publishedDate = publishedDate,
        sourceAddonId = META_ADDON,
        sourceItemId = sourceItemId,
        isbn13 = isbn13,
    )

private fun bookMeta(
    description: String? = null,
    publisher: String? = null,
    publishedDate: String? = null,
    language: String? = null,
    genre: String? = null,
    tags: String? = null,
    coverUrl: String? = null,
): LibraryMeta =
    LibraryMeta(
        description = description,
        publisher = publisher,
        publishedDate = publishedDate,
        language = language,
        genre = genre,
        tags = tags,
        coverUrl = coverUrl,
    )

/** The routed `meta` URL for a candidate id, with the prefix colon left readable. */
private fun metaUrl(
    addonId: String,
    candidateId: String,
): String = "https://$addonId.example/meta/book/$candidateId.json"

private fun metaAnswer(
    id: String,
    description: String? = null,
    poster: String? = null,
    releaseInfo: String? = null,
    languages: List<String> = emptyList(),
    subjects: List<String> = emptyList(),
): String {
    val meta = JSONObject().put("id", id).put("type", "book").put("name", "Pride and Prejudice")
    if (description != null) meta.put("description", description)
    if (poster != null) meta.put("poster", poster)
    if (releaseInfo != null) meta.put("releaseInfo", releaseInfo)
    meta.put("languages", JSONArray().apply { languages.forEach { put(it) } })
    meta.put("subjects", JSONArray().apply { subjects.forEach { put(it) } })
    return JSONObject().put("meta", meta).toString()
}

/** A client cache that never hits, so each test observes the service-level cache alone. */
private fun neverCachingClient(transport: AddonHttpTransport): AddonClient =
    AddonClient(
        transport = AddonProtocolTransport(transport),
        cache = TtlCache(now = { Long.MAX_VALUE }),
        breaker = CircuitBreaker(),
    )

private class RecordingCoverStorage : CoverStorage {
    val saved = mutableListOf<String>()

    override suspend fun saveCover(
        bookId: String,
        coverBytes: ByteArray,
    ): Result<String> {
        saved += bookId
        return Result.success("/covers/$bookId.jpg")
    }

    override suspend fun deleteCover(bookId: String): Result<Unit> = Result.success(Unit)
}

class LibraryMetadataServiceTest {
    // ------------------------------------------------------------ candidateIds

    @Test
    fun `candidateIds prefers the namespaced source item id`() {
        assertEquals(listOf("gutenberg:1342"), LibraryMetadataPolicy.candidateIds("gutenberg:1342", ISBN))
    }

    @Test
    fun `candidateIds falls back to the isbn when the source id is not namespaced`() {
        assertEquals(listOf("isbn:$ISBN"), LibraryMetadataPolicy.candidateIds("addon-own-42", ISBN))
    }

    @Test
    fun `candidateIds accepts a ten digit isbn`() {
        assertEquals(listOf("isbn:0141439518"), LibraryMetadataPolicy.candidateIds(null, "0141439518"))
    }

    @Test
    fun `candidateIds is empty without an isbn or a namespaced source id`() {
        assertEquals(emptyList<String>(), LibraryMetadataPolicy.candidateIds(null, null))
        assertEquals(emptyList<String>(), LibraryMetadataPolicy.candidateIds("  ", "   "))
        assertEquals(emptyList<String>(), LibraryMetadataPolicy.candidateIds("some-title", "12345"))
        assertEquals(emptyList<String>(), LibraryMetadataPolicy.candidateIds("", "97801414395X"))
    }

    // ------------------------------------------------- shouldConsultLibraryMeta

    @Test
    fun `shouldConsultLibraryMeta never consults an addon that declares meta without id prefixes`() {
        val addon = descriptor(META_ADDON, metaManifest(META_ADDON, """["catalog",{"name":"meta","types":["book"]}]"""))

        assertFalse(LibraryMetadataPolicy.shouldConsultLibraryMeta(addon, listOf("isbn:$ISBN")))
    }

    @Test
    fun `shouldConsultLibraryMeta uses the per resource id prefixes`() {
        val addon =
            descriptor(
                META_ADDON,
                metaManifest(META_ADDON, """[{"name":"meta","types":["book"],"idPrefixes":["isbn:"]}]"""),
            )

        assertTrue(LibraryMetadataPolicy.shouldConsultLibraryMeta(addon, listOf("isbn:$ISBN")))
        assertFalse(LibraryMetadataPolicy.shouldConsultLibraryMeta(addon, listOf("gutenberg:1342")))
    }

    @Test
    fun `shouldConsultLibraryMeta falls back to the top level id prefixes of a string form meta`() {
        val addon = descriptor(META_ADDON, metaManifest(META_ADDON, """["meta"]""", idPrefixes = """["gutenberg:"]"""))

        assertTrue(LibraryMetadataPolicy.shouldConsultLibraryMeta(addon, listOf("gutenberg:1342")))
        assertFalse(LibraryMetadataPolicy.shouldConsultLibraryMeta(addon, listOf("isbn:$ISBN")))
    }

    @Test
    fun `shouldConsultLibraryMeta is false for a disabled addon`() {
        val addon =
            descriptor(
                META_ADDON,
                metaManifest(META_ADDON, """[{"name":"meta","types":["book"],"idPrefixes":["isbn:"]}]"""),
                enabled = false,
            )

        assertFalse(LibraryMetadataPolicy.shouldConsultLibraryMeta(addon, listOf("isbn:$ISBN")))
    }

    @Test
    fun `shouldConsultLibraryMeta is false without a meta resource`() {
        val addon = descriptor(META_ADDON, metaManifest(META_ADDON, """["catalog"]""", idPrefixes = """["isbn:"]"""))

        assertFalse(LibraryMetadataPolicy.shouldConsultLibraryMeta(addon, listOf("isbn:$ISBN")))
    }

    // -------------------------------------------------------------- mergeMeta

    @Test
    fun `mergeMeta fills only the fields that are null or blank`() {
        val book = ownedBook(description = "Kept", publisher = " ", language = null)

        val (merged, changed) = LibraryMetadataPolicy.mergeMeta(book, bookMeta(description = "Replaced", publisher = "Penguin", language = "en"))

        assertTrue(changed)
        assertEquals("Kept", merged.description)
        assertEquals("Penguin", merged.publisher)
        assertEquals("en", merged.language)
    }

    @Test
    fun `mergeMeta never overwrites an existing cover`() {
        val book = ownedBook(coverPath = "/covers/book-1.jpg")

        val (merged, changed) = LibraryMetadataPolicy.mergeMeta(book, bookMeta(coverUrl = "https://covers.example/x.jpg"), coverPath = "/covers/new.jpg")

        assertFalse(changed)
        assertEquals("/covers/book-1.jpg", merged.coverPath)
    }

    @Test
    fun `mergeMeta sets the cover only when the book has none`() {
        val book = ownedBook(coverPath = null)

        val (merged, changed) = LibraryMetadataPolicy.mergeMeta(book, bookMeta(coverUrl = "https://covers.example/x.jpg"), coverPath = "/covers/downloaded.jpg")

        assertTrue(changed)
        assertEquals("/covers/downloaded.jpg", merged.coverPath)
    }

    @Test
    fun `mergeMeta never touches identity, provenance or progress`() {
        val book = ownedBook(sourceItemId = "gutenberg:1342", isbn13 = ISBN)

        val (merged, _) =
            LibraryMetadataPolicy.mergeMeta(
                book,
                bookMeta(description = "d", publisher = "p", publishedDate = "1813-01-28", language = "en", genre = "Fiction", tags = "classics, romance"),
                coverPath = "/covers/book-1.jpg",
            )

        assertEquals(book.id, merged.id)
        assertEquals(book.title, merged.title)
        assertEquals(book.author, merged.author)
        assertEquals(book.filePath, merged.filePath)
        assertEquals(book.format, merged.format)
        assertEquals(book.totalPages, merged.totalPages)
        assertEquals(book.chapterCount, merged.chapterCount)
        assertEquals(book.progressPercentage, merged.progressPercentage)
        assertEquals(book.progressUpdatedAtEpochMillis, merged.progressUpdatedAtEpochMillis)
        assertEquals(book.status, merged.status)
        assertEquals(book.readingState, merged.readingState)
        assertEquals(book.userRating, merged.userRating)
        assertEquals(book.stateVersion, merged.stateVersion)
        assertEquals(book.sourceAddonId, merged.sourceAddonId)
        assertEquals(book.sourceItemId, merged.sourceItemId)
        assertEquals(book.isbn13, merged.isbn13)
        assertEquals("Fiction", merged.genre)
        assertEquals("classics, romance", merged.tags)
        assertEquals("1813-01-28", merged.publishedDate)
    }

    @Test
    fun `mergeMeta reports no change when nothing can be filled`() {
        val book =
            ownedBook(
                coverPath = "/covers/book-1.jpg",
                description = "d",
                publisher = "p",
                publishedDate = "1813-01-28",
                language = "en",
                genre = "Fiction",
                tags = "classics",
            )

        val (merged, changed) = LibraryMetadataPolicy.mergeMeta(book, bookMeta(coverUrl = "https://covers.example/x.jpg"))

        assertFalse(changed)
        assertEquals(book, merged)
    }

    // ------------------------------------------------------------- enrich

    @Test
    fun `enrich asks the addon for the namespaced id and merges the answer`() =
        runTest {
            val transport = FanoutTransport(mapOf(metaUrl(META_ADDON, "isbn:$ISBN") to metaAnswer("isbn:$ISBN", description = "A novel", releaseInfo = "1813")))
            val service = serviceFor(rows = listOf(metaRow()), transport = transport, consented = true)

            val enriched = service.enrich(ownedBook(isbn13 = ISBN))

            assertEquals(listOf(metaUrl(META_ADDON, "isbn:$ISBN")), transport.calls)
            assertEquals("A novel", enriched?.description)
            assertEquals("1813", enriched?.publishedDate)
        }

    @Test
    fun `enrich never asks an addon without library meta consent`() =
        runTest {
            val transport = FanoutTransport(mapOf(metaUrl(META_ADDON, "isbn:$ISBN") to metaAnswer("isbn:$ISBN", description = "A novel")))
            val service = serviceFor(rows = listOf(metaRow()), transport = transport, consented = false)

            assertNull(service.enrich(ownedBook(isbn13 = ISBN)))
            assertTrue(transport.calls.isEmpty())
        }

    @Test
    fun `enrich never asks an addon that declares meta without id prefixes`() =
        runTest {
            val transport = FanoutTransport(mapOf(metaUrl(META_ADDON, "isbn:$ISBN") to metaAnswer("isbn:$ISBN", description = "A novel")))
            val service =
                serviceFor(
                    rows = listOf(metaRow(manifest = metaManifest(META_ADDON, """[{"name":"meta","types":["book"]}]"""))),
                    transport = transport,
                    consented = true,
                )

            assertNull(service.enrich(ownedBook(isbn13 = ISBN)))
            assertTrue(transport.calls.isEmpty())
        }

    @Test
    fun `enrich skips a book with no candidate identifiers`() =
        runTest {
            val transport = FanoutTransport(mapOf(metaUrl(META_ADDON, "isbn:$ISBN") to metaAnswer("isbn:$ISBN", description = "A novel")))
            val service = serviceFor(rows = listOf(metaRow()), transport = transport, consented = true)

            assertNull(service.enrich(ownedBook(sourceItemId = "not-namespaced", isbn13 = null)))
            assertTrue(transport.calls.isEmpty())
        }

    @Test
    fun `enrich is silent when the addon fails`() =
        runTest {
            val url = metaUrl(META_ADDON, "isbn:$ISBN")
            val service = serviceFor(rows = listOf(metaRow()), transport = FanoutTransport(statuses = mapOf(url to 500)), consented = true)

            assertNull(service.enrich(ownedBook(isbn13 = ISBN)))
        }

    @Test
    fun `enrich caches one response per addon and candidate for twenty four hours`() =
        runTest {
            var clock = 1_000L
            val transport = FanoutTransport(mapOf(metaUrl(META_ADDON, "isbn:$ISBN") to metaAnswer("isbn:$ISBN", description = "A novel")))
            val service = serviceFor(rows = listOf(metaRow()), transport = transport, consented = true, clock = { clock })

            service.enrich(ownedBook(isbn13 = ISBN))
            clock += 24 * 60 * 60 * 1000L - 1
            service.enrich(ownedBook(isbn13 = ISBN))
            assertEquals(1, transport.calls.size)

            clock += 2
            service.enrich(ownedBook(isbn13 = ISBN))
            assertEquals(2, transport.calls.size)
        }

    @Test
    fun `enrich bypasses the cache when the refresh is forced`() =
        runTest {
            val transport = FanoutTransport(mapOf(metaUrl(META_ADDON, "isbn:$ISBN") to metaAnswer("isbn:$ISBN", description = "A novel")))
            val service = serviceFor(rows = listOf(metaRow()), transport = transport, consented = true)

            service.enrich(ownedBook(isbn13 = ISBN))
            service.enrich(ownedBook(isbn13 = ISBN), force = true)

            assertEquals(2, transport.calls.size)
        }

    @Test
    fun `enrich persists a fetched cover through the cover storage`() =
        runTest {
            val storage = RecordingCoverStorage()
            val transport = FanoutTransport(mapOf(metaUrl(META_ADDON, "isbn:$ISBN") to metaAnswer("isbn:$ISBN", poster = "https://covers.example/x.jpg")))
            val service =
                serviceFor(
                    rows = listOf(metaRow()),
                    transport = transport,
                    consented = true,
                    coverStorage = storage,
                    coverBytes = byteArrayOf(1, 2, 3),
                )

            val enriched = service.enrich(ownedBook(isbn13 = ISBN))

            assertEquals(listOf("book-1"), storage.saved)
            assertEquals("/covers/book-1.jpg", enriched?.coverPath)
        }

    @Test
    fun `enrich never fetches a cover for a book that already has one`() =
        runTest {
            val storage = RecordingCoverStorage()
            var fetched = 0
            val transport = FanoutTransport(mapOf(metaUrl(META_ADDON, "isbn:$ISBN") to metaAnswer("isbn:$ISBN", description = "A novel", poster = "https://covers.example/x.jpg")))
            val service =
                LibraryMetadataService(
                    installedAddons = { listOf(metaRow()) },
                    client = neverCachingClient(transport),
                    consent = InMemoryAddonConsentStore().apply { setLibraryMetaConsent(META_ADDON, true) },
                    cache = TtlCache(),
                    coverStorage = storage,
                    fetchCover = {
                        fetched += 1
                        byteArrayOf(1)
                    },
                )

            val enriched = service.enrich(ownedBook(isbn13 = ISBN, coverPath = "/covers/book-1.jpg"))

            assertEquals(0, fetched)
            assertEquals("A novel", enriched?.description)
            assertTrue(storage.saved.isEmpty())
        }

    @Test
    fun `stream consent alone never implies library meta consent`() {
        val store = InMemoryAddonConsentStore()
        store.recordConsent(META_ADDON)

        assertTrue(store.hasConsent(META_ADDON))
        assertFalse(store.hasLibraryMetaConsent(META_ADDON))
    }

    private fun metaRow(manifest: AddonManifestV3 = metaManifest(META_ADDON, """["catalog",{"name":"meta","types":["book"],"idPrefixes":["isbn:","gutenberg:"]}]""")): InstalledAddonRow =
        InstalledAddonRow(
            id = META_ADDON,
            url = "https://$META_ADDON.example/manifest.json",
            manifest = manifest,
            enabled = true,
            addedAt = 1L,
        )

    private fun serviceFor(
        rows: List<InstalledAddonRow>,
        transport: AddonHttpTransport,
        consented: Boolean,
        clock: () -> Long = { 1_000L },
        coverStorage: CoverStorage? = null,
        coverBytes: ByteArray? = null,
    ): LibraryMetadataService =
        LibraryMetadataService(
            installedAddons = { rows },
            client = neverCachingClient(transport),
            consent = InMemoryAddonConsentStore().apply { if (consented) setLibraryMetaConsent(META_ADDON, true) },
            cache = TtlCache(now = clock),
            coverStorage = coverStorage,
            fetchCover = { coverBytes },
        )
}
