package com.nexo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.DictionaryWordDao
import com.nexo.data.local.dao.DiscoverCacheDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.dao.ReadingStatsDao
import com.nexo.data.local.dao.SyncFileMappingDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.AddonEntity
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.BookmarkEntity
import com.nexo.data.local.entity.DictionaryWordEntity
import com.nexo.data.local.entity.DiscoverCacheEntity
import com.nexo.data.local.entity.HighlightEntity
import com.nexo.data.local.entity.ReadingProgressEntity
import com.nexo.data.local.entity.ReadingSessionEntity
import com.nexo.data.local.entity.ReadingStatsEntity
import com.nexo.data.local.entity.SyncFileMappingEntity
import com.nexo.data.local.entity.SyncOutboxEntity

@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        ReadingStatsEntity::class,
        ReadingSessionEntity::class,
        HighlightEntity::class,
        BookmarkEntity::class,
        SyncOutboxEntity::class,
        SyncFileMappingEntity::class,
        DictionaryWordEntity::class,
        DiscoverCacheEntity::class,
        AddonEntity::class,
    ],
    version = 31,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun readingProgressDao(): ReadingProgressDao

    abstract fun readingStatsDao(): ReadingStatsDao

    abstract fun readingSessionDao(): ReadingSessionDao

    abstract fun highlightDao(): HighlightDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun syncOutboxDao(): SyncOutboxDao

    abstract fun syncFileMappingDao(): SyncFileMappingDao

    abstract fun dictionaryWordDao(): DictionaryWordDao

    abstract fun discoverCacheDao(): DiscoverCacheDao

    abstract fun addonDao(): AddonDao
}
