package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted notification history row. Mirrors the desktop `notifications`
 * table (migration 0021) so both platforms store the same shape.
 *
 * [NotificationTarget] is sealed, so it is flattened into [targetKind] +
 * [targetId] (`book` | `route` + the id/route name) and re-assembled by the
 * repository. A null kind means no target. Enums are stored as their names so
 * an unknown persisted value can degrade without a crash.
 *
 * [i18nKey] is a message KEY, never a resolved string, so a later language
 * switch re-renders history correctly.
 */
@Entity(
    tableName = "notifications",
    indices = [Index(value = ["created_at"])],
)
data class NotificationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "severity")
    val severity: String,
    @ColumnInfo(name = "interruption")
    val interruption: String,
    @ColumnInfo(name = "i18n_key")
    val i18nKey: String,
    @ColumnInfo(name = "i18n_params")
    val i18nParams: Map<String, String>?,
    @ColumnInfo(name = "target_kind")
    val targetKind: String?,
    @ColumnInfo(name = "target_id")
    val targetId: String?,
    @ColumnInfo(name = "read_at")
    val readAt: Long?,
    @ColumnInfo(name = "dedup_key")
    val dedupKey: String?,
)
