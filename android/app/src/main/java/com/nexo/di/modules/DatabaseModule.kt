package com.nexo.di.modules

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nexo.BuildConfig
import com.nexo.data.local.AppDatabase
import com.nexo.data.local.AppDatabaseMigrations
import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.DefinitionCacheDao
import com.nexo.data.local.dao.DictionaryWordDao
import com.nexo.data.local.dao.DiscoverCacheDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.NotificationDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.dao.ReadingStatsDao
import com.nexo.data.local.dao.SyncFileMappingDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.dao.TranslationCacheDao
import com.nexo.di.AppStartTimings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room database and every DAO it exposes.
 *
 * The init-duration log moved with the measurement: [DatabaseInitTiming] sits
 * next to the provider that builds the database rather than being forwarded by
 * a container.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        timings: AppStartTimings,
    ): AppDatabase {
        val start = System.currentTimeMillis()
        val db =
            Room
                .databaseBuilder(
                    context = context,
                    klass = AppDatabase::class.java,
                    name = "nexo.db",
                ).addMigrations(*AppDatabaseMigrations.ALL)
                // Bundled SQLite is compiled with SQLITE_ENABLE_FTS5; Android's
                // platform SQLite is not, so MIGRATION_15_16's fts5 virtual table
                // cannot be created on any API level without this driver.
                .setDriver(BundledSQLiteDriver())
                .let { builder ->
                    if (BuildConfig.DEBUG) {
                        builder.fallbackToDestructiveMigration(dropAllTables = true)
                    } else {
                        builder
                    }
                }.build()
        timings.recordDatabaseInit(System.currentTimeMillis() - start)
        return db
    }

    @Provides @Singleton fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()

    @Provides @Singleton fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()

    @Provides @Singleton fun provideHighlightDao(db: AppDatabase): HighlightDao = db.highlightDao()

    @Provides @Singleton fun provideReadingProgressDao(db: AppDatabase): ReadingProgressDao = db.readingProgressDao()

    @Provides @Singleton fun provideReadingSessionDao(db: AppDatabase): ReadingSessionDao = db.readingSessionDao()

    @Provides @Singleton fun provideReadingStatsDao(db: AppDatabase): ReadingStatsDao = db.readingStatsDao()

    @Provides @Singleton fun provideSyncOutboxDao(db: AppDatabase): SyncOutboxDao = db.syncOutboxDao()

    @Provides @Singleton fun provideSyncFileMappingDao(db: AppDatabase): SyncFileMappingDao = db.syncFileMappingDao()

    @Provides @Singleton fun provideDictionaryWordDao(db: AppDatabase): DictionaryWordDao = db.dictionaryWordDao()

    @Provides @Singleton fun provideDiscoverCacheDao(db: AppDatabase): DiscoverCacheDao = db.discoverCacheDao()

    @Provides @Singleton fun provideInstalledAddonDao(db: AppDatabase): AddonDao = db.addonDao()

    @Provides @Singleton fun provideNotificationDao(db: AppDatabase): NotificationDao = db.notificationDao()

    @Provides @Singleton fun provideTranslationCacheDao(db: AppDatabase): TranslationCacheDao = db.translationCacheDao()

    @Provides @Singleton fun provideDefinitionCacheDao(db: AppDatabase): DefinitionCacheDao = db.definitionCacheDao()
}
