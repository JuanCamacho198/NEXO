package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id"]), Index(value = ["date"]), Index(value = ["date", "userId"])],
)
data class ReadingSessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "start_time")
    val startTimeEpochMillis: Long,
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int,
    val date: Long, // Date only (no time) - epoch days or epoch millis at midnight
    val userId: String = "",
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long = 0L,
)
