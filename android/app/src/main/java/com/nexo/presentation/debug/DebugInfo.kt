package com.nexo.presentation.debug

import com.nexo.debug.CacheClearReport
import com.nexo.debug.DebugErrorEntry

data class DebugInfo(
    val session: SessionSection = SessionSection(),
    val initTimings: InitTimingsSection = InitTimingsSection(),
    val dbCounts: DbCountsSection = DbCountsSection(),
    val syncDebug: SyncDebugSection = SyncDebugSection(),
    val supabaseSyncDebug: SupabaseSyncDebugSection = SupabaseSyncDebugSection(),
    val pdfDebug: PdfDebugSection? = null,
    val isLoadingDbCounts: Boolean = false,
    val recentErrors: List<DebugErrorEntry> = emptyList(),
    val lastCacheClear: CacheClearReport? = null,
)

data class SessionSection(
    val userId: String = "",
    val email: String? = null,
    val displayName: String? = null,
    val authMode: String = "",
    val isSupabaseConfigured: Boolean = false,
    val hasWiringIssue: Boolean = false,
)

data class InitTimingsSection(
    val dbInitMs: Long = 0,
    val epubImportInitMs: Long = 0,
    val readerRepoInitMs: Long = 0,
    val totalInitMs: Long = 0,
)

data class DbCountsSection(
    val books: Int = -1,
    val highlights: Int = -1,
    val bookmarks: Int = -1,
    val readingSessions: Int = -1,
    val readingProgress: Int = -1,
)

data class SyncDebugSection(
    val state: String = "unknown",
    val pendingCount: Int = 0,
    val lastSyncAtMs: Long? = null,
    val lastFailure: String? = null,
)

data class SupabaseSyncDebugSection(
    val state: String = "unknown",
    val gatedReason: String? = null,
    val pendingCount: Int = 0,
)

data class PdfDebugSection(
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val loadTimeMs: Long? = null,
    val filePath: String? = null,
)
