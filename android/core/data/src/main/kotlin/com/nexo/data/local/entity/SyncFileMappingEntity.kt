package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_file_mappings",
    indices = [Index(value = ["book_id"])],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SyncFileMappingEntity(
    @PrimaryKey
    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "local_path")
    val localPath: String,
    @ColumnInfo(name = "updated_at")
    val updatedAtEpochMillis: Long,
)
