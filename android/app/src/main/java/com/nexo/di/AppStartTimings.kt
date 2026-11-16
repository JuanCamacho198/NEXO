package com.nexo.di

import android.util.Log
import com.nexo.presentation.debug.InitTimingsSection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cold-start construction timings for the app-scoped database and repositories.
 *
 * This is measurement, not wiring: it records how long each expensive singleton
 * took to build and reports the durations the debug panel renders. It owns no
 * dependency graph, so it cannot become a service locator.
 *
 * Every duration is 0 until the measured object has been constructed, which is
 * the same "unknown" the debug panel's zero defaults already represent.
 */
@Singleton
class AppStartTimings
    @Inject
    constructor() {
        private val processStartMs = System.currentTimeMillis()

        @Volatile private var dbInitMs: Long = 0L

        @Volatile private var epubImportInitMs: Long = 0L

        @Volatile private var readerRepoInitMs: Long = 0L

        fun recordDatabaseInit(elapsedMs: Long) {
            dbInitMs = elapsedMs
            Log.d(TAG, "Database initialized in ${elapsedMs}ms")
        }

        fun recordEpubImportInit(elapsedMs: Long) {
            epubImportInitMs = elapsedMs
            Log.d(TAG, "LibraryRepository initialized in ${elapsedMs}ms")
        }

        fun recordReaderRepoInit(elapsedMs: Long) {
            readerRepoInitMs = elapsedMs
            Log.d(TAG, "ReaderRepository initialized in ${elapsedMs}ms")
        }

        fun snapshot(): InitTimingsSection =
            InitTimingsSection(
                dbInitMs = dbInitMs,
                epubImportInitMs = epubImportInitMs,
                readerRepoInitMs = readerRepoInitMs,
                totalInitMs = System.currentTimeMillis() - processStartMs,
            )

        companion object {
            private const val TAG = "AppStartTimings"
        }
    }
