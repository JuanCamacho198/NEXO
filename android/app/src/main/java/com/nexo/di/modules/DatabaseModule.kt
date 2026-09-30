package com.nexo.di.modules

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.nexo.BuildConfig
import com.nexo.data.local.AppDatabase
import com.nexo.data.local.AppDatabaseMigrations
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

class DatabaseModule(
    context: Context,
) {
    companion object {
        private const val TAG = "DatabaseModule"
    }

    private val startTime = System.currentTimeMillis()

    val appDatabase: AppDatabase =
        Room
            .databaseBuilder(
                context = context.applicationContext,
                klass = AppDatabase::class.java,
                name = "nexo.db",
            ).addMigrations(*AppDatabaseMigrations.ALL)
            .let { builder ->
                if (BuildConfig.DEBUG) {
                    builder.fallbackToDestructiveMigration(dropAllTables = true)
                } else {
                    builder
                }
            }.build()

    val dbInitTimeMs: Long = System.currentTimeMillis() - startTime

    init {
        Log.d(TAG, "Database initialized in ${dbInitTimeMs}ms")
    }

    val bookDao: BookDao get() = appDatabase.bookDao()
    val bookmarkDao: BookmarkDao get() = appDatabase.bookmarkDao()
    val highlightDao: HighlightDao get() = appDatabase.highlightDao()
    val readingProgressDao: ReadingProgressDao get() = appDatabase.readingProgressDao()
    val readingSessionDao: ReadingSessionDao get() = appDatabase.readingSessionDao()
    val readingStatsDao: ReadingStatsDao get() = appDatabase.readingStatsDao()
    val syncOutboxDao: SyncOutboxDao get() = appDatabase.syncOutboxDao()
    val syncFileMappingDao: SyncFileMappingDao get() = appDatabase.syncFileMappingDao()
    val dictionaryWordDao: DictionaryWordDao get() = appDatabase.dictionaryWordDao()
    val discoverCacheDao: DiscoverCacheDao get() = appDatabase.discoverCacheDao()
    val installedAddonDao: AddonDao get() = appDatabase.addonDao()

    fun clearAllTables() {
        appDatabase.clearAllTables()
    }
}
