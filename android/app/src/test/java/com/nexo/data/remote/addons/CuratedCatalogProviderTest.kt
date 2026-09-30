package com.nexo.data.remote.addons

import com.nexo.data.remote.catalog.BUILTIN_GUTENDEX
import com.nexo.data.remote.catalog.BUILTIN_OPENLIBRARY
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.CatalogSourceKind.CURATED
import com.nexo.data.remote.catalog.CompositeCatalogProvider
import com.nexo.data.remote.catalog.DEBOUNCE_MS
import com.nexo.data.remote.catalog.DEFAULT_PAGE_SIZE
import com.nexo.data.remote.catalog.DETAIL_TTL_S
import com.nexo.data.remote.catalog.MAX_PAGE_SIZE
import com.nexo.data.remote.catalog.MIN_PAGE_SIZE
import com.nexo.data.remote.catalog.OL_MIN_GAP_MS
import com.nexo.data.remote.catalog.PAGE_TTL_S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Curated first-party bundle parity suite. Mirrors desktop
 * curated-catalog-provider.test.ts: exactly six sources, every entry passes
 * ManifestValidator, browse-only behavior, stable not-routable errors.
 */
class CuratedCatalogProviderTest {
    private val curatedIds =
        listOf(
            "gutendex",
            "openlibrary",
            "standard-ebooks",
            "librivox",
            "wikisource",
            "faded-page",
        )

    private val curatedNames =
        listOf(
            "Gutendex",
            "Open Library",
            "Standard Ebooks",
            "LibriVox",
            "Wikisource",
            "Faded Page",
        )

    private fun providerFromBundle(): CuratedCatalogProvider {
        // Validate THE bundled asset (src/main/assets), not a test copy, so the
        // suite fails if the shipped bundle drifts.
        val candidates =
            listOf(
                java.io.File("src/main/assets/addons/curated.json"),
                java.io.File("app/src/main/assets/addons/curated.json"),
            )
        val file =
            candidates.firstOrNull { it.exists() }
                ?: error("missing bundled asset addons/curated.json (tried ${candidates.joinToString()})")
        return CuratedCatalogProvider(file.readText())
    }

    @Test
    fun `bundle contains exactly the six first-party manifests in order`() {
        val provider = providerFromBundle()
        val sources = provider.listSources()
        assertEquals(6, sources.size)
        assertEquals(curatedIds.map { "builtin:$it" }, sources.map { it.sourceId })
        assertEquals(curatedNames, sources.map { it.name })
        assertEquals(List(6) { CURATED }, sources.map { it.kind })
    }

    @Test
    fun `every bundled entry passes ManifestValidator`() {
        // fromBundle validates each entry; a malformed bundle throws AddonFetchException.
        assertEquals(6, providerFromBundle().listSources().size)
    }

    @Test
    fun `search is browse-only empty page without I-O`() =
        kotlinx.coroutines.test.runTest {
            val provider = providerFromBundle()
            val page = provider.search("pride", 1)
            assertEquals(0, page.results.size)
            assertEquals(null, page.nextPage)
            assertEquals(0, page.totalCount)
        }

    @Test
    fun `getDetails fails stable not-routable`() =
        kotlinx.coroutines.test.runTest {
            val provider = providerFromBundle()
            try {
                provider.getDetails("standardebooks:some-book")
                fail("expected CatalogException")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.NOT_FOUND, err.code)
            }
        }

    @Test
    fun `curated sources never shadow the built-ins in the composite`() =
        kotlinx.coroutines.test.runTest {
            val builtIn =
                object : CatalogProvider {
                    override suspend fun search(
                        query: String,
                        page: Int,
                    ): com.nexo.data.remote.catalog.PagedResult {
                        error("not expected")
                    }

                    override suspend fun getDetails(id: String): com.nexo.data.remote.catalog.CatalogBook {
                        error("not expected")
                    }

                    override fun resolveDownloadUrl(
                        formats: Map<String, String>,
                        preferEpub: Boolean,
                    ): String = ""

                    override fun listSources(): List<CatalogSourceInfo> =
                        listOf(
                            CatalogSourceInfo(BUILTIN_GUTENDEX, "Gutendex", CatalogSourceKind.BUILTIN),
                            CatalogSourceInfo(BUILTIN_OPENLIBRARY, "Open Library", CatalogSourceKind.BUILTIN),
                        )
                }
            val composite = CompositeCatalogProvider(listOf(builtIn, providerFromBundle()))
            val sources = composite.listSources()
            assertEquals(BUILTIN_GUTENDEX, sources.first().sourceId)
            assertEquals(CatalogSourceKind.BUILTIN, sources.first().kind)
            assertEquals(4, sources.count { it.kind == CatalogSourceKind.CURATED })
        }

    @Test
    fun `courtesy policy constants are untouched by the curated layer`() {
        assertEquals(350L, DEBOUNCE_MS)
        assertEquals(1_000L, OL_MIN_GAP_MS)
        assertEquals(24, DEFAULT_PAGE_SIZE)
        assertEquals(20, MIN_PAGE_SIZE)
        assertEquals(32, MAX_PAGE_SIZE)
        assertEquals(86_400L, PAGE_TTL_S)
        assertEquals(604_800L, DETAIL_TTL_S)
        assertTrue(true)
    }
}
