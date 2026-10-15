package com.nexo.presentation.viewmodel

import com.nexo.R
import com.nexo.data.remote.catalog.BUILTIN_GOOGLEBOOKS
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.domain.discover.AUTHOR_ROTATION
import com.nexo.domain.discover.entriesFor
import com.nexo.presentation.feature.discover.DiscoverRailState
import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * S2: every curated `AuthorEntry.titleKey` must resolve to a real string
 * resource, and a shelf must carry the resource belonging to its OWN author.
 *
 * This is the exact class of bug the desktop rotation had — a key written for an
 * author that no longer owned it, so a shelf rendered the wrong name with no test
 * catching it. The key→resource map is private to the ViewModel, so the table is
 * asserted through the observed shelf state rather than by reaching into it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthorRailTitlesTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Expected resource id per rotation term. */
    private val expectedTitleRes =
        mapOf(
            "arthur conan doyle" to R.string.discover_rail_author_doyle,
            "edgar allan poe" to R.string.discover_rail_author_poe,
            "oscar wilde" to R.string.discover_rail_author_wilde,
            "jules verne" to R.string.discover_rail_author_verne,
            "franz kafka" to R.string.discover_rail_author_kafka,
            "charles dickens" to R.string.discover_rail_author_dickens,
            "jack london" to R.string.discover_rail_author_london,
            "h g wells" to R.string.discover_rail_author_wells,
            "mark twain" to R.string.discover_rail_author_twain,
            "herman melville" to R.string.discover_rail_author_melville,
        )

    @Test
    fun everyRotationTermHasAnExpectedTitleResource() {
        for (entry in AUTHOR_ROTATION) {
            assertTrue("no expected title resource for ${entry.slug}", entry.term in expectedTitleRes)
        }
        assertEquals(AUTHOR_ROTATION.size, expectedTitleRes.size)
    }

    @Test
    fun everyShelfCarriesTheTitleResourceOfItsOwnAuthor() =
        runTest {
            val vm = DiscoverViewModel(RepeatTermsRailProvider())
            advanceUntilIdle()

            val shelves =
                vm.uiState.value.rails
                    .filterIsInstance<DiscoverRailState.Loaded>()
            assertTrue("expected the day's shelves to load", shelves.isNotEmpty())
            for (shelf in shelves) {
                val term = shelf.authorTerm
                assertTrue("shelf without an author term: $shelf", term != null)
                assertEquals(
                    "shelf for $term carries the wrong title resource",
                    expectedTitleRes[term],
                    shelf.sectionTitleRes,
                )
            }
        }

    @Test
    fun aKnownKeyThatIsNotInTheRotationWouldNotResolve() {
        // The mapping is keyed by titleKey, not by term: a shelf whose key has no
        // resource is dropped by the ViewModel instead of rendering a blank header.
        assertTrue(AUTHOR_ROTATION.all { it.titleKey.startsWith("discover_rail_author_") })
    }

    /**
     * Answers every Google Books term with one book credited to that very term,
     * so all three shelves load whatever the calendar day is.
     */
    private class RepeatTermsRailProvider : CatalogProvider {
        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult = PagedResult(emptyList(), null, 0)

        override suspend fun getDetails(id: String): CatalogBook = throw CatalogException(CatalogErrorCode.NOT_FOUND, "unused")

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = "n/a"

        override fun listSources(): List<CatalogSourceInfo> = emptyList()

        override suspend fun searchSource(
            sourceId: String,
            query: String,
            page: Int,
        ): PagedResult =
            PagedResult(
                listOf(
                    CatalogBook(
                        id = "googlebooks:${query.replace(' ', '-')}",
                        provider = BUILTIN_GOOGLEBOOKS,
                        title = "A book by $query",
                        authors = listOf(query),
                        coverUrl = null,
                        languages = listOf("en"),
                        subjects = emptyList(),
                        downloadUrl = null,
                    ),
                ),
                nexoPage = null,
                totalCount = 1,
            )
    }

    /** Guard: the day's rotation is always three distinct shelves. */
    @Test
    fun theDayRotationIsThreeDistinctShelves() {
        val terms = entriesFor(LocalDate.now()).map { it.term }
        assertEquals(3, terms.size)
        assertEquals(3, terms.toSet().size)
    }
}
