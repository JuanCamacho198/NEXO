package com.nexo.domain.repository

/**
 * Read/clear contract for the app's disposable caches.
 *
 * Implementations own the platform details (Room, Coil disk cache, files) so the
 * domain and presentation layers stay free of Android/Room dependencies. Every
 * method here touches cache storage only: books and covers are never candidates
 * for removal.
 */
interface CacheRepository {
    /** Payload bytes currently stored in `discover_cache` (DB pages/index excluded). */
    suspend fun discoverCacheSizeBytes(): Long

    /** Deletes every discover-cache row; returns rows removed. Never touches books or covers. */
    suspend fun clearDiscoverCache(): Int

    /** Bytes currently held by the Coil disk cache (downloaded covers). */
    suspend fun imageCacheSizeBytes(): Long

    /** Clears the Coil disk cache. Books are never removed. */
    suspend fun clearImageCache()

    /** Bytes currently held by the extracted-reader cache (`epub_cache`). */
    suspend fun readerCacheSizeBytes(): Long

    /** Clears the extracted-reader cache. Books are never removed. */
    suspend fun clearReaderCache()
}
