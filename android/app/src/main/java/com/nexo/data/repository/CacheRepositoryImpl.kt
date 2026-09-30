package com.nexo.data.repository

import android.content.Context
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import com.nexo.data.local.dao.DiscoverCacheDao
import com.nexo.domain.repository.CacheRepository
import java.io.File

/**
 * Platform implementation of [CacheRepository].
 *
 * Three independent cache stores are reached here, none of which holds a user
 * book: the `discover_cache` Room table (via [DiscoverCacheDao]), the Coil disk
 * cache (downloaded cover images, already capped at 64 MB by `CoilModule`), and
 * the extracted-reader cache under `epub_cache` on disk.
 */
@OptIn(ExperimentalCoilApi::class)
class CacheRepositoryImpl(
    private val discoverCacheDao: DiscoverCacheDao,
    private val appContext: Context,
    private val imageLoader: ImageLoader,
) : CacheRepository {
    override suspend fun discoverCacheSizeBytes(): Long = discoverCacheDao.payloadBytes()

    override suspend fun pruneDiscoverCache(): Int = discoverCacheDao.deleteExpired(nowEpochSecs())

    override suspend fun purgeLegacyDiscoverCache(): Int = discoverCacheDao.deleteLegacyNamespaces()

    override suspend fun clearDiscoverCache(): Int = discoverCacheDao.deleteAll()

    override suspend fun imageCacheSizeBytes(): Long = imageLoader.diskCache?.size ?: 0L

    override suspend fun clearImageCache() {
        imageLoader.diskCache?.clear()
    }

    override suspend fun readerCacheSizeBytes(): Long = folderSize(readerCacheDir())

    override suspend fun clearReaderCache() {
        readerCacheDir().deleteRecursively()
    }

    /** Prefers the files-dir copy, matching the reader cache writer's lookup order. */
    private fun readerCacheDir(): File {
        val filesCopy = File(appContext.filesDir, EPUB_CACHE_DIR)
        return if (filesCopy.exists()) filesCopy else File(appContext.cacheDir, EPUB_CACHE_DIR)
    }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    private fun nowEpochSecs(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val EPUB_CACHE_DIR = "epub_cache"
    }
}
