package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id", "deleted_at"])],
)
data class BookmarkEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "cfi_location")
    val cfiLocation: String,
    @ColumnInfo(name = "title_or_snippet")
    val titleOrSnippet: String,
    @ColumnInfo(name = "updated_at")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "deleted_at")
    val deletedAtEpochMillis: Long?,
    @ColumnInfo(name = "locator_json")
    val locatorJson: String? = null,
)
