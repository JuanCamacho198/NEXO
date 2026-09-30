package com.nexo.domain.model

/**
 * One live library book and the bytes its local footprint occupies.
 *
 * [sizeBytes] is the sum of the backing file plus its cover (when present),
 * measured off the main thread by `StorageRepository.bookStorageUsage()`.
 * Repeated paths are deduped, so a shared file is attributed once.
 */
data class BookStorageItem(
    val bookId: String,
    val title: String,
    val sizeBytes: Long,
)

/**
 * Byte breakdown of the disposable cache stores.
 *
 * [totalBytes] is derived (never stored) so each store stays independently
 * verifiable and neither books nor covers can leak into a cache total.
 */
data class CacheUsage(
    val discoverCacheBytes: Long = 0L,
    val imageCacheBytes: Long = 0L,
    val readerCacheBytes: Long = 0L,
) {
    val totalBytes: Long get() = discoverCacheBytes + imageCacheBytes + readerCacheBytes
}
