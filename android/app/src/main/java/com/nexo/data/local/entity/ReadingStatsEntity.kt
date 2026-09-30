package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_stats",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id"])],
)
data class ReadingStatsEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: String,
    val totalMinutesRead: Long = 0,
    val lastReadDateEpochMillis: Long = 0,
    val sessionsCount: Int = 0,
    val userId: String = "",
)
