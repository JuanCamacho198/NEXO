package com.nexo.di.modules

import android.content.Context
import android.util.Log
import com.nexo.data.repository.CacheRepositoryImpl
import com.nexo.data.repository.DictionaryRepositoryImpl
import com.nexo.data.repository.HomeRepositoryImpl
import com.nexo.data.repository.LibraryRepositoryImpl
import com.nexo.data.repository.ReaderRepositoryImpl
import com.nexo.data.repository.ReadingStatsRepositoryImpl
import com.nexo.data.repository.StorageRepositoryImpl
import com.nexo.domain.repository.CacheRepository
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsRepository
import com.nexo.domain.repository.StorageRepository
import com.nexo.domain.sync.OutboxDrainScheduler
import com.nexo.domain.sync.SyncSettleGate

class RepositoryModule(
    context: Context,
    databaseModule: DatabaseModule,
    storageModule: StorageModule,
    @Suppress("UNUSED_PARAMETER") preferencesModule: PreferencesModule,
    syncSettleGateProvider: () -> SyncSettleGate,
    /**
     * Resolves the outbox drain scheduler lazily so this module can be built
     * while the app container is still wiring the network module (the scheduler
     * needs the session manager). Defaults to a no-op so non-wired callers
     * (tests) keep working.
     */
    drainSchedulerProvider: () -> OutboxDrainScheduler = { OutboxDrainScheduler.NoOp },
) {
    companion object {
        private const val TAG = "RepositoryModule"
    }

    /** Resolved once; the scheduler resolves its WorkManager lazily per enqueue. */
    private val drainScheduler: OutboxDrainScheduler = drainSchedulerProvider()

    /**
     * Resolves the orchestrator-backed gate lazily, on each cleanup attempt, so
     * this module can be built before the (lazy) SyncOrchestrator exists.
     */
    val syncSettleGate: SyncSettleGate = SyncSettleGate { syncSettleGateProvider().awaitSettled() }

    private val epubImportStartTime = System.currentTimeMillis()
    val libraryRepository: LibraryRepository =
        LibraryRepositoryImpl(
            appContext = context.applicationContext,
            bookDao = databaseModule.bookDao,
            readingStatsDao = databaseModule.readingStatsDao,
            epubParserService = storageModule.epubParserService,
            pdfParserService = storageModule.pdfParserService,
            coverStorage = storageModule.coverStorage,
            readingProgressDao = databaseModule.readingProgressDao,
            outboxDao = databaseModule.syncOutboxDao,
            settleGate = syncSettleGate,
            drainScheduler = drainScheduler,
        )
    val epubImportInitTimeMs: Long = System.currentTimeMillis() - epubImportStartTime

    init {
        Log.d(TAG, "LibraryRepository initialized in ${epubImportInitTimeMs}ms")
    }

    private val readerRepoStartTime = System.currentTimeMillis()
    val readerRepository: ReaderRepository =
        ReaderRepositoryImpl(
            readingProgressDao = databaseModule.readingProgressDao,
            highlightDao = databaseModule.highlightDao,
            bookmarkDao = databaseModule.bookmarkDao,
            bookDao = databaseModule.bookDao,
            outboxDao = databaseModule.syncOutboxDao,
            drainScheduler = drainScheduler,
        )
    val readerRepoInitTimeMs: Long = System.currentTimeMillis() - readerRepoStartTime

    init {
        Log.d(TAG, "ReaderRepository initialized in ${readerRepoInitTimeMs}ms")
    }

    val readingStatsRepository: ReadingStatsRepository =
        ReadingStatsRepositoryImpl(
            readingStatsDao = databaseModule.readingStatsDao,
            readingSessionDao = databaseModule.readingSessionDao,
            outboxDao = databaseModule.syncOutboxDao,
            drainScheduler = drainScheduler,
        )

    val homeRepository: HomeRepository =
        HomeRepositoryImpl(
            bookDao = databaseModule.bookDao,
            readingProgressDao = databaseModule.readingProgressDao,
            readingSessionDao = databaseModule.readingSessionDao,
        )

    val dictionaryRepository: DictionaryRepository =
        DictionaryRepositoryImpl(
            dao = databaseModule.dictionaryWordDao,
        )

    val cacheRepository: CacheRepository =
        CacheRepositoryImpl(
            discoverCacheDao = databaseModule.discoverCacheDao,
            appContext = context.applicationContext,
            imageLoader = storageModule.coilImageLoader,
        )

    val storageRepository: StorageRepository =
        StorageRepositoryImpl(
            appContext = context.applicationContext,
            bookDao = databaseModule.bookDao,
            settleGate = syncSettleGate,
        )
}
