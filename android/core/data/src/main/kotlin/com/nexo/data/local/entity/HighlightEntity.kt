package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["user_id", "book_id", "deleted_at"])],
)
data class HighlightEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "cfi_range")
    val cfiRange: String,
    @ColumnInfo(name = "text_content")
    val textContent: String,
    val note: String?,
    val color: String,
    @ColumnInfo(name = "updated_at")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "deleted_at")
    val deletedAtEpochMillis: Long?,
    @ColumnInfo(name = "locator_json")
    val locatorJson: String? = null,
    @ColumnInfo(name = "type")
    val type: String? = null,
    @ColumnInfo(name = "tag")
    val tag: String? = null,
    @ColumnInfo(name = "user_id")
    val userId: String = "",
    /**
     * Page the highlight starts on, **1-based**. PDF only; null for EPUB and for PDF rows
     * written before geometry existed. `cfi_range` keeps its own 0-based `pdfpage:N` convention.
     */
    @ColumnInfo(name = "page")
    val page: Int? = null,
    /** Approved `{"v":1,"rects":[…]}` payload — page-relative CSS px at scale 1. Null without geometry. */
    @ColumnInfo(name = "rect_json")
    val rectJson: String? = null,
)
