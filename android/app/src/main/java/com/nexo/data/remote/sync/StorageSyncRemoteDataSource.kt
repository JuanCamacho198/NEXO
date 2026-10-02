package com.nexo.data.remote.sync

import com.nexo.data.sync.DriveGuardPort

/**
 * Remote binary surface plus the FR-08 write-guard port (marker read + guarded
 * write). The guard's upload entry only accepts a `CanonicalName`, so an
 * uncanonicalized raw string cannot reach [upload] through it.
 */
interface StorageSyncRemoteDataSource : DriveGuardPort {
    suspend fun upload(
        path: String,
        bytes: ByteArray,
    )

    suspend fun download(path: String): ByteArray

    suspend fun list(prefix: String): List<String>

    /**
     * Returns the size in bytes of the remote file at [path], or `null`
     * when the provider cannot report it (or the file does not exist).
     * Used to show the download size in the cross-device section.
     */
    suspend fun getFileSize(path: String): Long?
}
