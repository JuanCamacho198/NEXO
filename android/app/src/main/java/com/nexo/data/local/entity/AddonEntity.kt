package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Installed addon registry row keyed by addonId = sha256(url)[0..16].
 * Mirrors desktop `installed_addons` (migration 0017). Additive only —
 * no method on [com.nexo.data.local.dao.AddonDao] touches user_books/outbox.
 */
@Entity(
    tableName = "installed_addons",
    indices = [Index(value = ["url"], unique = true)],
)
data class AddonEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "url")
    val url: String,
    @ColumnInfo(name = "manifest_json")
    val manifestJson: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)
