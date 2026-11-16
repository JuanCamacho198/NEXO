package com.nexo.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A book row as the cross-device catalog returns it.
 *
 * Moved out of the Supabase data source: the library UI state carries a list
 * of these, and a wire type from :core:data should not reach a UI state that a
 * feature module has to render.
 */
@Serializable
data class UserBookRow(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val title: String,
    val author: String? = null,
    val format: String,
    @SerialName("content_hash")
    val contentHash: String? = null,
    @SerialName("file_path")
    val filePath: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val language: String? = null,
    val publisher: String? = null,
    val tags: String? = null,
    @SerialName("published_date")
    val publishedDate: String? = null,
    @SerialName("total_pages")
    val totalPages: Int? = null,
    @SerialName("source_device")
    val sourceDevice: String? = null,
    @SerialName("imported_at")
    val importedAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    val lifecycle: String = "available",
    @SerialName("catalog_version") val catalogVersion: Long = 1,
    @SerialName("remote_provider") val remoteProvider: String? = null,
    @SerialName("remote_file_id") val remoteFileId: String? = null,
    @SerialName("remote_path") val remotePath: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("cover_object_path") val coverObjectPath: String? = null,
    // Desktop and the remote `user_books.protocol_version` column are BOTH
    // text: the column is declared `text` (cross_device_library_recovery
    // migration) and desktop persists `String(protocolVersion)` and reads it
    // back with `Number(...)`. Android must therefore model the wire type as a
    // String so the contract matches the column instead of relying on the JSON
    // decoder to coerce a quoted literal into an Int. Nullable because desktop
    // persists NULL for rows never written by the recovery protocol. Consumers
    // convert with `toIntOrNull()` and default to 1.
    @SerialName("protocol_version") val protocolVersion: String? = null,
)
