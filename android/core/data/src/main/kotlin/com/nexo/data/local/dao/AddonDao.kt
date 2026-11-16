package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nexo.data.local.entity.AddonEntity

/**
 * DAO for the addon registry. No method touches user_books, outbox,
 * or sync tables — isolation is structural, not conventional.
 */
@Dao
interface AddonDao {
    @Query("SELECT * FROM installed_addons ORDER BY added_at ASC")
    suspend fun getAll(): List<AddonEntity>

    @Query("SELECT * FROM installed_addons WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AddonEntity?

    @Query("SELECT * FROM installed_addons WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): AddonEntity?

    @Upsert
    suspend fun upsert(addon: AddonEntity)

    /** Reinstall semantics: update manifest, preserve the existing enabled flag. */
    @Transaction
    suspend fun upsertPreservingEnabled(addon: AddonEntity) {
        val existing = getByUrl(addon.url)
        if (existing != null) {
            upsert(addon.copy(id = existing.id, enabled = existing.enabled))
        } else {
            upsert(addon)
        }
    }

    @Query("UPDATE installed_addons SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    )

    @Query("DELETE FROM installed_addons WHERE id = :id")
    suspend fun delete(id: String)
}
