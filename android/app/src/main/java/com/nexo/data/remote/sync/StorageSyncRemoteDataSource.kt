package com.nexo.data.remote.sync

interface StorageSyncRemoteDataSource {
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
