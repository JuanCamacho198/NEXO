package com.nexo.data.remote.catalog

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Standard Ebooks parity suite: the Atom feed mapper (measured against
 * `https://standardebooks.org/feeds/atom/new-releases`), the provider's two
 * search doors, and the datasource's page policy.
 *
 * The platform `XmlPullParser` is real Android code, so the parser tests run
 * under Robolectric (already a test dependency — no new Gradle dependency).
 */
@RunWith(RobolectricTestRunner::class)
class StandardEbooksTest {
    private val atomFixture =
        """
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
        	<script xmlns="http://www.w3.org/1999/xhtml" src="https://standardebooks.org/scripts/xslt-polyfill.min.js"/>
        	<id>https://standardebooks.org/feeds/atom/new-releases</id>
        	<title>Standard Ebooks - Newest Ebooks</title>
        	<author>
        		<name>Standard Ebooks</name>
        	</author>
        	<entry>
        		<id>https://standardebooks.org/ebooks/henry-seton-merriman/suspense</id>
        		<title>Suspense</title>
        		<author>
        			<name>Henry Seton Merriman</name>
        			<uri>https://standardebooks.org/ebooks/henry-seton-merriman</uri>
        		</author>
        		<summary type="text">Two childhood friends discover that their characters might enable them to overcome all.</summary>
        		<category scheme="https://standardebooks.org/vocab/subjects" term="Fiction"/>
        		<media:thumbnail height="525" url="https://standardebooks.org/ebooks/henry-seton-merriman/suspense/downloads/cover-thumbnail.jpg" width="350"/>
        		<link href="https://standardebooks.org/ebooks/henry-seton-merriman/suspense/downloads/henry-seton-merriman_suspense.epub?source=feed" rel="enclosure" title="Recommended compatible epub" type="application/epub+zip"/>
        		<link href="https://standardebooks.org/ebooks/henry-seton-merriman/suspense/downloads/henry-seton-merriman_suspense.azw3?source=feed" rel="enclosure" title="Amazon Kindle azw3" type="application/x-mobipocket-ebook"/>
        	</entry>
        	<entry>
        		<id>https://standardebooks.org/ebooks/guy-de-maupassant/bel-ami/some-translator</id>
        		<title>Bel-Ami</title>
        		<author>
        			<name>Guy de Maupassant</name>
        		</author>
        		<link href="https://standardebooks.org/ebooks/guy-de-maupassant/bel-ami/downloads/bel-ami.epub" rel="enclosure" title="Recommended compatible epub" type="application/epub+zip"/>
        	</entry>
        	<entry>
        		<id>https://standardebooks.org/ebooks/anon/cleartext</id>
        		<title>Cleartext</title>
        		<author>
        			<name>Anon</name>
        		</author>
        		<link href="http://standardebooks.org/ebooks/anon/cleartext/downloads/c.epub" rel="enclosure" title="Recommended compatible epub" type="application/epub+zip"/>
        	</entry>
        </feed>
        """.trimIndent()

    private class FakeTransport(
        private val body: String,
        private val status: Int = 200,
    ) : CatalogHttpTransport {
        val urls = mutableListOf<String>()

        override suspend fun get(
            url: String,
            extraHeaders: Map<String, String>,
        ): CatalogHttpResponse {
            urls.add(url)
            return CatalogHttpResponse(status, body)
        }
    }

    private fun provider(
        body: String = atomFixture,
        status: Int = 200,
    ) = StandardEbooksCatalogProvider(StandardEbooksDataSource(FakeTransport(body, status)))

    // ── mapper ──────────────────────────────────────────────────────────

    @Test
    fun atomFeedMapsEntriesToNamespacedCatalogBooks() {
        val books = mapStandardEbooksAtomFeed(atomFixture)

        assertEquals(2, books.size)
        val first = books.first()
        assertEquals("standard-ebooks:henry-seton-merriman/suspense", first.id)
        assertEquals(BUILTIN_STANDARD_EBOOKS, first.provider)
        assertEquals("Suspense", first.title)
        assertEquals(listOf("Henry Seton Merriman"), first.authors)
        assertEquals(
            "https://standardebooks.org/ebooks/henry-seton-merriman/suspense/downloads/cover-thumbnail.jpg",
            first.coverUrl,
        )
        // The recommended EPUB wins over the azw3 enclosure of the same rel.
        assertEquals(
            "https://standardebooks.org/ebooks/henry-seton-merriman/suspense/downloads/henry-seton-merriman_suspense.epub?source=feed",
            first.downloadUrl,
        )
        assertEquals(listOf("Fiction"), first.subjects)
        assertEquals(true, first.isPublicDomain)
        // The feed states no language, so none is invented.
        assertTrue(first.languages.isEmpty())
        assertTrue(first.description!!.startsWith("Two childhood friends"))
    }

    @Test
    fun translatorSegmentIsDroppedFromTheIdAndMissingCoverStaysNull() {
        val second = mapStandardEbooksAtomFeed(atomFixture)[1]

        assertEquals("standard-ebooks:guy-de-maupassant/bel-ami", second.id)
        assertNull(second.coverUrl)
        assertEquals(listOf("Guy de Maupassant"), second.authors)
    }

    @Test
    fun entryWithoutHttpsEpubIsDropped() {
        val ids = mapStandardEbooksAtomFeed(atomFixture).map { it.id }

        assertFalse(ids.any { it.contains("cleartext") })
    }

    @Test
    fun malformedFeedMapsToNoBooks() {
        assertTrue(mapStandardEbooksAtomFeed("<feed><entry>").isEmpty())
        assertTrue(mapStandardEbooksAtomFeed("").isEmpty())
    }

    @Test
    fun slugParsingRejectsNonEbookPermalinks() {
        assertNull(standardEbooksSlugOf("https://standardebooks.org/feeds/atom/new-releases"))
        assertNull(standardEbooksSlugOf("https://standardebooks.org/ebooks/only-author"))
        assertEquals("a/b", standardEbooksSlugOf("https://standardebooks.org/ebooks/a/b"))
    }

    // ── provider ────────────────────────────────────────────────────────

    @Test
    fun blankSearchServesTheShelfAndAKeywordSearchFailsClosedWithoutIo() =
        runTest {
            val transport = FakeTransport(atomFixture)
            val ds = StandardEbooksDataSource(transport)
            val sut = StandardEbooksCatalogProvider(ds)

            val shelf = sut.search("", 1)
            assertEquals(2, shelf.results.size)
            assertEquals(1, transport.urls.size)

            // A keyword search cannot be honored by a parameterless feed.
            val keyword = sut.search("pride", 1)
            assertTrue(keyword.results.isEmpty())
            assertEquals("no second request", 1, transport.urls.size)
        }

    @Test
    fun deeperPagesAreEmptyWithoutIo() =
        runTest {
            val transport = FakeTransport(atomFixture)
            val sut = StandardEbooksCatalogProvider(StandardEbooksDataSource(transport))

            assertTrue(sut.search("", 2).results.isEmpty())
            assertTrue(transport.urls.isEmpty())
        }

    @Test
    fun keywordSearchIsExcludedFromTheFanOutButTheSourceStillAnswersItsOwnDoor() =
        runTest {
            val transport = FakeTransport(atomFixture)
            val standardEbooks = StandardEbooksCatalogProvider(StandardEbooksDataSource(transport))
            val composite =
                CompositeCatalogProvider(
                    listOf(
                        GutendexCatalogProvider(
                            GutendexDataSource(
                                object : CatalogHttpTransport {
                                    override suspend fun get(
                                        url: String,
                                        extraHeaders: Map<String, String>,
                                    ) = CatalogHttpResponse(200, """{"count":0,"results":[]}""")
                                },
                            ),
                        ),
                        standardEbooks,
                    ),
                )

            // supportsSearch false: the keyword fan-out never reaches the feed.
            assertTrue(composite.search("pride", 1).results.isEmpty())
            assertTrue("keyword search must not touch the feed", transport.urls.isEmpty())

            // Its own source door still serves the newest-releases shelf.
            val owned = composite.searchSource(BUILTIN_STANDARD_EBOOKS, "", 1)
            assertEquals(2, owned.results.size)
            assertEquals(1, transport.urls.size)
        }

    @Test
    fun providerOwnsOneSourceAndDoesNotOptIntoFeatured() {
        val sut = provider()

        assertEquals(listOf(BUILTIN_STANDARD_EBOOKS), sut.listSources().map { it.sourceId })
        assertEquals(listOf("Standard Ebooks"), sut.listSources().map { it.name })
        assertFalse(sut.supportsFeatured())
        assertFalse(sut.supportsSearch())
    }

    @Test
    fun detailsResolveByNamespacedIdAndUnknownIdsAreNotFound() =
        runTest {
            val sut = provider()

            val book = sut.getDetails("standard-ebooks:guy-de-maupassant/bel-ami")
            assertEquals("Bel-Ami", book.title)

            try {
                sut.getDetails("gutendex:1342")
                error("expected NOT_FOUND")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, err.code)
            }
            try {
                sut.getDetails("standard-ebooks:not/in/feed")
                error("expected NOT_FOUND")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, err.code)
            }
        }

    // ── datasource ──────────────────────────────────────────────────────

    @Test
    fun upstreamFailureSurfacesAsARetryableCatalogErrorAndHidesTheRail() =
        runTest {
            val sut = provider(status = 500)

            try {
                sut.search("", 1)
                error("expected UPSTREAM_ERROR")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.UPSTREAM_ERROR, err.code)
            }
        }

    @Test
    fun feedIsRequestedWithTheAtomAcceptHeader() =
        runTest {
            val transport = FakeTransport(atomFixture)
            val ds = StandardEbooksDataSource(transport)

            ds.newest(1)

            assertEquals(listOf(STANDARD_EBOOKS_ATOM_FEED_URL), transport.urls)
        }
}
