package com.nexo.data.repository

import android.content.Context
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import com.nexo.data.local.dao.DiscoverCacheDao
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Slice 6a — cache breakdown and clear contract.
 * Clearing cache must never touch books/covers (spec ST2, structural here).
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalCoilApi::class)
class CacheRepositoryImplTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dao = mockk<DiscoverCacheDao>(relaxed = true)
    private val imageLoader = mockk<ImageLoader>()
    private val diskCache = mockk<DiskCache>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private fun repository() = CacheRepositoryImpl(dao, context, imageLoader)

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun discoverCacheSize_readsPayloadBytesOnly() =
        runTest {
            coEvery { dao.payloadBytes() } returns 1_234L

            assertEquals(1_234L, repository().discoverCacheSizeBytes())
            coVerify(exactly = 1) { dao.payloadBytes() }
        }

    @Test
    fun pruneDiscoverCache_usesExpiryCutoff() =
        runTest {
            coEvery { dao.deleteExpired(any()) } returns 4

            assertEquals(4, repository().pruneDiscoverCache())
            coVerify(exactly = 1) { dao.deleteExpired(any()) }
        }

    @Test
    fun purgeLegacyDiscoverCache_deletesLegacyNamespaces() =
        runTest {
            coEvery { dao.deleteLegacyNamespaces() } returns 7

            assertEquals(7, repository().purgeLegacyDiscoverCache())
        }

    @Test
    fun clearDiscoverCache_deletesCacheRowsOnly() =
        runTest {
            coEvery { dao.deleteAll() } returns 3

            assertEquals(3, repository().clearDiscoverCache())
            coVerify(exactly = 1) { dao.deleteAll() }
            // No per-row or book/cover mutation belongs to "clear cache".
            coVerify(exactly = 0) { dao.getByKey(any()) }
        }

    @Test
    fun imageCache_sizeAndClear_targetCoilDiskCache() =
        runTest {
            every { imageLoader.diskCache } returns diskCache
            every { diskCache.size } returns 42L

            assertEquals(42L, repository().imageCacheSizeBytes())
            repository().clearImageCache()
            verify(exactly = 1) { diskCache.clear() }
        }

    @Test
    fun imageCache_withoutDiskCache_isZero() =
        runTest {
            every { imageLoader.diskCache } returns null

            assertEquals(0L, repository().imageCacheSizeBytes())
        }

    @Test
    fun readerCache_sizesAndClearsEpubCache() =
        runTest {
            val root = tempFolder.newFolder("files")
            val cacheRoot = tempFolder.newFolder("cache")
            val cacheDir = File(root, "epub_cache").apply { mkdirs() }
            File(cacheDir, "a.bin").writeBytes(ByteArray(10))
            File(cacheDir, "b.bin").writeBytes(ByteArray(5))
            every { context.filesDir } returns root
            // After the clear, the files-dir copy is gone and the lookup falls back
            // to cacheDir; stub it to a real (empty) dir so the fallback returns 0.
            every { context.cacheDir } returns cacheRoot

            val repo = repository()
            assertEquals(15L, repo.readerCacheSizeBytes())

            repo.clearReaderCache()
            assertEquals(0L, repo.readerCacheSizeBytes())
        }
}
