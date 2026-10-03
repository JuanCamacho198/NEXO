package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [
        Index(value = ["deleted_at", "updated_at"], orders = [Index.Order.ASC, Index.Order.DESC]),
        Index(value = ["reading_state"]),
    ],
)
data class BookEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val author: String?,
    @ColumnInfo(name = "cover_path")
    val coverPath: String?,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    val format: String,
    @ColumnInfo(name = "total_pages")
    val totalPages: Int? = null,
    @ColumnInfo(name = "chapter_count")
    val chapterCount: Int? = null,
    val description: String? = null,
    @ColumnInfo(name = "user_rating")
    val userRating: Int? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "deleted_at")
    val deletedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "status")
    val status: String? = null,
    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,
    @ColumnInfo(name = "reading_state")
    val readingState: String = "to_read",
    @ColumnInfo(name = "started_at")
    val startedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "completed_at")
    val completedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "progress_updated_at")
    val progressUpdatedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long = 0L,
    @ColumnInfo(name = "remote_file_id") val remoteFileId: String? = null,
    @ColumnInfo(name = "remote_path") val remotePath: String? = null,
    @ColumnInfo(name = "remote_lifecycle") val remoteLifecycle: String = "imported",
    @ColumnInfo(name = "remote_catalog_version") val remoteCatalogVersion: Long = 0L,
    @ColumnInfo(name = "remote_cover_ref") val remoteCoverRef: String? = null,
    @ColumnInfo(name = "remote_provider") val remoteProvider: String? = null,
    @ColumnInfo(name = "remote_protocol_version") val remoteProtocolVersion: Int? = null,
    @ColumnInfo(name = "genre") val genre: String? = null,
    @ColumnInfo(name = "language") val language: String? = null,
    @ColumnInfo(name = "publisher") val publisher: String? = null,
    @ColumnInfo(name = "tags") val tags: String? = null,
    @ColumnInfo(name = "published_date") val publishedDate: String? = null,
)
