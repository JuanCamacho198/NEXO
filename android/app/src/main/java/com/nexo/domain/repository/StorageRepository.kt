package com.nexo.domain.repository

import com.nexo.domain.model.BookStorageItem

/**
 * Storage maintenance and measurement over the app's internal book files.
 */
interface StorageRepository {
    /**
     * Measures the local footprint of every live library book as the sum of its
     * backing file plus its cover, when the cover is a local file.
     *
     * Contract:
     *  - computed off the main thread (`Dispatchers.IO`);
     *  - paths are deduped, so a cover equal to the backing file (or a file
     *    shared by two rows) is counted once;
     *  - a missing/unreadable path contributes 0 bytes rather than failing.
     *
     * @return one [BookStorageItem] per live book, in library order.
     */
    suspend fun bookStorageUsage(): List<BookStorageItem>

    /**
     * Deletes backing files no live book references (orphans left by deletes
     * whose sync-settle gate timed out, or by failed imports).
     *
     * Safety contract:
     *  - runs only after sync has settled (`SyncSettleGate`);
     *  - `*.part` files are in-flight downloads and are never candidates;
     *  - a file modified within the grace window is skipped, so a download
     *    that has just renamed into place is not swept mid-import;
     *  - only files under the managed `catalog`/`pdfs`/`epubs` directories are
     *    considered.
     *
     * @return the number of files deleted.
     */
    suspend fun sweepOrphanBookFiles(): Int
}
