package com.nexo.presentation.viewmodel.library

import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.UserBookRow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogPollingHolderTest {
    private fun createHolder(
        catalogSync: SupabaseBookCatalogSync,
        ioDispatcher: TestDispatcher,
        fastLists: MutableList<List<UserBookRow>>,
        enrichedLists: MutableList<List<UserBookRow>>,
        loadingDoneCount: MutableList<Int>,
    ): CatalogPollingHolder =
        CatalogPollingHolder(
            catalogSync = catalogSync,
            ioDispatcher = ioDispatcher,
            onFastList = { fastLists.add(it) },
            onEnriched = { enrichedLists.add(it) },
            onLoadingDone = { loadingDoneCount.add(1) },
        )

    @Test
    fun fastEmit_thenEnrichViaAsync() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val catalogSync = mockk<SupabaseBookCatalogSync>(relaxed = false)
            val bookNoSize =
                UserBookRow(
                    id = "1",
                    userId = "user-1",
                    title = "A",
                    author = null,
                    format = "epub",
                    importedAt = "2026-01-01T00:00:00.000Z",
                    fileSize = null,
                    coverUrl = null,
                    updatedAt = "2026-01-01T00:00:00.000Z",
                )
            val enriched = bookNoSize.copy(fileSize = 1234L)

            coEvery { catalogSync.getDownloadableBooks() } returns Result.success(listOf(bookNoSize))
            coEvery { catalogSync.currentUserId() } returns "user-1"
            coEvery { catalogSync.enrichFileSizes(any(), any()) } returns listOf(enriched)

            val fast = mutableListOf<List<UserBookRow>>()
            val enrichedLists = mutableListOf<List<UserBookRow>>()
            val loadingDone = mutableListOf<Int>()

            val holder = createHolder(catalogSync, testDispatcher, fast, enrichedLists, loadingDone)
            holder.start(this)

            // run first poll iteration — use runCurrent not advanceUntilIdle (infinite poller)
            runCurrent()
            // allow async enrich on ioDispatcher to complete
            testScheduler.advanceTimeBy(1)
            runCurrent()

            assertEquals(1, fast.size)
            assertEquals(1, enrichedLists.size)
            assertEquals(enriched.fileSize, enrichedLists.first().first().fileSize)
            coVerify { catalogSync.enrichFileSizes(listOf(bookNoSize), "user-1") }

            holder.stop()
            runCurrent()
        }

    @Test
    fun tickAfter30s_emitsAgain() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val catalogSync = mockk<SupabaseBookCatalogSync>(relaxed = false)
            val book =
                UserBookRow(
                    id = "1",
                    userId = "user-1",
                    title = "A",
                    author = null,
                    format = "epub",
                    importedAt = "2026-01-01T00:00:00.000Z",
                    fileSize = 100L,
                    coverUrl = null,
                    updatedAt = "2026-01-01T00:00:00.000Z",
                )
            coEvery { catalogSync.getDownloadableBooks() } returns Result.success(listOf(book))

            val fast = mutableListOf<List<UserBookRow>>()
            val enriched = mutableListOf<List<UserBookRow>>()
            val loadingDone = mutableListOf<Int>()

            val holder = createHolder(catalogSync, testDispatcher, fast, enriched, loadingDone)
            holder.start(this)

            runCurrent()
            testScheduler.advanceTimeBy(1)
            runCurrent()
            assertEquals(1, fast.size)

            // advance 30s to trigger next poll
            advanceTimeBy(30_000)
            runCurrent()
            testScheduler.advanceTimeBy(1)
            runCurrent()

            assertEquals(2, fast.size)

            holder.stop()
            runCurrent()
        }

    @Test
    fun cancelStopsLoop_noPostClearUiState() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val catalogSync = mockk<SupabaseBookCatalogSync>(relaxed = false)
            var callCount = 0
            coEvery { catalogSync.getDownloadableBooks() } answers {
                callCount++
                Result.success(emptyList())
            }

            val fast = mutableListOf<List<UserBookRow>>()
            val enriched = mutableListOf<List<UserBookRow>>()
            val loadingDone = mutableListOf<Int>()

            val holder = createHolder(catalogSync, testDispatcher, fast, enriched, loadingDone)
            holder.start(this)

            runCurrent()
            testScheduler.advanceTimeBy(1)
            runCurrent()
            assertTrue(holder.isActive())
            assertEquals(1, callCount)

            holder.stop()
            runCurrent()
            assertFalse(holder.isActive())

            // advance past next tick — should not emit
            advanceTimeBy(60_000)
            runCurrent()

            // callCount stays 1
            assertEquals(1, callCount)
            assertEquals(1, fast.size)
        }

    @Test
    fun noEnrich_whenFileSizePresent() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val catalogSync = mockk<SupabaseBookCatalogSync>(relaxed = false)
            val bookWithSize =
                UserBookRow(
                    id = "1",
                    userId = "user-1",
                    title = "A",
                    author = null,
                    format = "epub",
                    importedAt = "2026-01-01T00:00:00.000Z",
                    fileSize = 999L,
                    coverUrl = null,
                    updatedAt = "2026-01-01T00:00:00.000Z",
                )
            coEvery { catalogSync.getDownloadableBooks() } returns Result.success(listOf(bookWithSize))

            val fast = mutableListOf<List<UserBookRow>>()
            val enriched = mutableListOf<List<UserBookRow>>()
            val loadingDone = mutableListOf<Int>()

            val holder = createHolder(catalogSync, testDispatcher, fast, enriched, loadingDone)
            holder.start(this)

            runCurrent()
            testScheduler.advanceTimeBy(1)
            runCurrent()
            assertEquals(1, fast.size)
            assertEquals(0, enriched.size)
            coVerify(exactly = 0) { catalogSync.enrichFileSizes(any(), any()) }

            holder.stop()
            runCurrent()
        }
}
