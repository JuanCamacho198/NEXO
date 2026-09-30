package com.nexo.domain.access

import com.nexo.data.remote.catalog.BUILTIN_GUTENDEX
import com.nexo.data.remote.catalog.CatalogBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U3: resolver groups FREE/BUY/SUBSCRIBE as https-only web links with no
 * lending branch; only `isPublicDomain == true` books resolve to an in-app
 * download path; identity-free books fall back to generic web search.
 */
class BookAccessResolverTest {
    private companion object {
        const val HTTPS_PREFIX = "https://"
        const val HTTP_PREFIX = "http://"
        const val TITLE_FREE_MARKER = "openlibrary.org"
        const val TITLE_BUY_MARKER = "google.com/search"
        const val PD_DOWNLOAD_URL = "https://www.gutenberg.org/cache/epub/1342/pg1342.txt"
        const val INSECURE_DOWNLOAD_URL = "http://www.gutenberg.org/cache/epub/1342/pg1342.txt"
    }

    private fun book(
        id: String = "gutendex:1342",
        title: String = "Pride and Prejudice",
        authors: List<String> = listOf("Jane Austen"),
        downloadUrl: String? = PD_DOWNLOAD_URL,
        isPublicDomain: Boolean? = true,
        isbn13: String? = null,
        isbn10: String? = null,
        openLibraryWorkId: String? = null,
        internetArchiveId: String? = null,
        googleBooksId: String? = null,
    ): CatalogBook =
        CatalogBook(
            id = id,
            provider = BUILTIN_GUTENDEX,
            title = title,
            authors = authors,
            coverUrl = null,
            languages = listOf("en"),
            subjects = emptyList(),
            downloadUrl = downloadUrl,
            isPublicDomain = isPublicDomain,
            isbn13 = isbn13,
            isbn10 = isbn10,
            openLibraryWorkId = openLibraryWorkId,
            internetArchiveId = internetArchiveId,
            googleBooksId = googleBooksId,
        )

    @Test fun groups_coverFreeBuySubscribe() {
        val access =
            resolveAccess(
                book(
                    openLibraryWorkId = "/works/OL66554W",
                    isbn13 = "9780141439518",
                ),
            )
        val groups = access.options.map { it.group }.toSet()
        assertTrue(groups.contains(AccessGroup.FREE))
        assertTrue(groups.contains(AccessGroup.BUY))
        assertTrue(groups.contains(AccessGroup.SUBSCRIBE))
        assertEquals(groups.size, AccessGroup.entries.size)
    }

    @Test fun allLinks_areHttpsOnly() {
        val access =
            resolveAccess(
                book(
                    downloadUrl = PD_DOWNLOAD_URL,
                    isPublicDomain = true,
                    openLibraryWorkId = "/works/OL66554W",
                    internetArchiveId = "prideandprejudice0000aust",
                    googleBooksId = "abc123",
                    isbn13 = "9780141439518",
                ),
            )
        assertTrue(access.options.isNotEmpty())
        access.options.forEach { option ->
            assertTrue(option.url.startsWith(HTTPS_PREFIX))
            assertFalse(option.url.startsWith(HTTP_PREFIX + "www"))
        }
    }

    @Test fun noLendingBranch_absentFromGroupsAndUrls() {
        val access = resolveAccess(book(isbn13 = "9780141439518"))
        val groupNames = access.options.map { it.group.name }
        assertFalse(groupNames.contains("BORROW"))
        access.options.forEach { option ->
            assertFalse(option.url.lowercase().contains("borrow"))
            assertFalse(option.url.lowercase().contains("lending"))
        }
        assertEquals(AccessGroup.entries.size, 3)
    }

    @Test fun publicDomainDownload_resolvesInAppPath() {
        val access = resolveAccess(book(downloadUrl = PD_DOWNLOAD_URL, isPublicDomain = true))
        assertTrue(access.canDownloadInApp)
        assertEquals(PD_DOWNLOAD_URL, access.downloadUrl)
        val download = access.options.firstOrNull { it.opensInApp }
        assertNotNull(download)
        assertEquals(AccessGroup.FREE, download?.group)
        assertEquals(PD_DOWNLOAD_URL, download?.url)
    }

    @Test fun inCopyrightBook_resolvesExternalOnly() {
        val access =
            resolveAccess(
                book(downloadUrl = PD_DOWNLOAD_URL, isPublicDomain = false),
            )
        assertFalse(access.canDownloadInApp)
        assertNull(access.downloadUrl)
        assertTrue(access.options.none { it.opensInApp })
    }

    @Test fun unknownPublicDomain_resolvesExternalOnly() {
        val access = resolveAccess(book(downloadUrl = PD_DOWNLOAD_URL, isPublicDomain = null))
        assertFalse(access.canDownloadInApp)
        assertNull(access.downloadUrl)
        assertTrue(access.options.none { it.opensInApp })
    }

    @Test fun insecureDownloadUrl_neverResolvesInApp() {
        val access =
            resolveAccess(
                book(downloadUrl = INSECURE_DOWNLOAD_URL, isPublicDomain = true),
            )
        assertFalse(access.canDownloadInApp)
        assertNull(access.downloadUrl)
        assertTrue(access.options.none { it.opensInApp })
        access.options.forEach { assertTrue(it.url.startsWith(HTTPS_PREFIX)) }
    }

    @Test fun identityLinks_pointAtCanonicalHttpsHosts() {
        val access =
            resolveAccess(
                book(
                    downloadUrl = null,
                    isPublicDomain = false,
                    openLibraryWorkId = "/works/OL66554W",
                    internetArchiveId = "prideandprejudice0000aust",
                    googleBooksId = "abc123",
                ),
            )
        val freeUrls = access.options.filter { it.group == AccessGroup.FREE }.map { it.url }
        assertTrue(freeUrls.any { it.contains(TITLE_FREE_MARKER) })
        assertTrue(freeUrls.any { it.contains("archive.org/details/") })
        assertTrue(freeUrls.any { it.contains("books.google.com/books") })
    }

    @Test fun gutenbergId_resolvesFreeEbookLink() {
        val access = resolveAccess(book(id = "gutendex:1342", downloadUrl = null, isPublicDomain = true))
        val freeUrls = access.options.filter { it.group == AccessGroup.FREE }.map { it.url }
        assertTrue(freeUrls.any { it.contains("gutenberg.org/ebooks/1342") })
    }

    @Test fun noIdentity_fallsBackToGenericWebSearch() {
        val access =
            resolveAccess(
                book(
                    id = "openlibrary:/works/OL00000W",
                    downloadUrl = null,
                    isPublicDomain = null,
                ),
            )
        val groups = access.options.map { it.group }.toSet()
        assertTrue(groups.contains(AccessGroup.FREE))
        assertTrue(groups.contains(AccessGroup.BUY))
        assertTrue(groups.contains(AccessGroup.SUBSCRIBE))
        assertTrue(
            access.options.any {
                it.group == AccessGroup.FREE && it.url.contains(TITLE_BUY_MARKER)
            },
        )
        access.options.forEach { assertTrue(it.url.startsWith(HTTPS_PREFIX)) }
    }
}
