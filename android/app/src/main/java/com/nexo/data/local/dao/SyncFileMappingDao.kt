package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nexo.data.local.entity.SyncFileMappingEntity

@Dao
interface SyncFileMappingDao {
    @Upsert
    suspend fun upsert(mapping: SyncFileMappingEntity)

    @Query("SELECT * FROM sync_file_mappings WHERE drive_file_id = :driveFileId LIMIT 1")
    suspend fun getByDriveFileId(driveFileId: String): SyncFileMappingEntity?

    @Query("SELECT * FROM sync_file_mappings WHERE user_id = :userId")
    suspend fun getByUserId(userId: String): List<SyncFileMappingEntity>
}
