package com.nexo.data.remote.sync

import com.google.gson.annotations.SerializedName

/**
 * Cold export schema only (PR3). Hot push/pull retired — Drive is cold
 * Export/Import via DriveColdBackupService. This file keeps BookStateJson
 * types for legacy backfill parsing (Drive _state.json) and cold export shape.
 *
 * Hot methods pushState/pullState/schedulePull were removed; Drive hot sync
 * is gone (Supabase SoT hot + single Realtime supervisor). Use
 * DriveColdBackupService for on-demand export/import (Settings only).
 */
data class BookStateJson(
    val progress: ProgressStateJson? = null,
    val highlights: List<HighlightStateJson> = emptyList(),
    val bookmarks: List<BookmarkStateJson> = emptyList(),
)

data class ProgressStateJson(
    val id: String,
    @SerializedName("book_id")
    val bookId: String,
    @SerializedName("cfi_location")
    val cfiLocation: String,
    val percentage: Float,
    @SerializedName("current_page")
    val currentPage: Int? = null,
    @SerializedName("updated_at")
    val updatedAtEpochMillis: Long,
)

data class HighlightStateJson(
    val id: String,
    @SerializedName("book_id")
    val bookId: String,
    @SerializedName("cfi_range")
    val cfiRange: String,
    @SerializedName("text_content")
    val textContent: String,
    val note: String?,
    val color: String,
    @SerializedName("updated_at")
    override val updatedAtEpochMillis: Long,
    @SerializedName("deleted_at")
    override val deletedAtEpochMillis: Long? = null,
    val tag: String? = null,
) : VersionedSyncRecord {
    override val recordId: String get() = id
}

data class BookmarkStateJson(
    val id: String,
    @SerializedName("book_id")
    val bookId: String,
    @SerializedName("cfi_location")
    val cfiLocation: String,
    @SerializedName("title_or_snippet")
    val titleOrSnippet: String,
    @SerializedName("updated_at")
    override val updatedAtEpochMillis: Long,
    @SerializedName("deleted_at")
    override val deletedAtEpochMillis: Long? = null,
) : VersionedSyncRecord {
    override val recordId: String get() = id
}

/**
 * Stub retained for compile compatibility — hot Drive sync removed.
 * Use DriveColdBackupService for cold export/import.
 */
@Deprecated("Drive hot sync removed — use DriveColdBackupService (cold export/import only)")
class GoogleDriveJsonStateSync(
    @Suppress("UNUSED_PARAMETER") private val remoteDataSource: StorageSyncRemoteDataSource,
) {
    @Deprecated("Hot push retired — Drive is cold only")
    suspend fun pushState(
        @Suppress("UNUSED_PARAMETER") userId: String,
        @Suppress("UNUSED_PARAMETER") bookId: String,
        @Suppress("UNUSED_PARAMETER") progress: com.nexo.domain.model.ReadingProgress?,
        @Suppress("UNUSED_PARAMETER") highlights: List<com.nexo.domain.model.Highlight>,
        @Suppress("UNUSED_PARAMETER") bookmarks: List<com.nexo.domain.model.Bookmark>,
    ): Result<ByteArray> = Result.success(ByteArray(0))

    @Deprecated("Hot pull retired — Drive is cold only")
    suspend fun pullState(
        @Suppress("UNUSED_PARAMETER") userId: String,
        @Suppress("UNUSED_PARAMETER") bookId: String,
        @Suppress("UNUSED_PARAMETER") localProgress: com.nexo.domain.model.ReadingProgress?,
        @Suppress("UNUSED_PARAMETER") localHighlights: List<com.nexo.domain.model.Highlight>,
        @Suppress("UNUSED_PARAMETER") localBookmarks: List<com.nexo.domain.model.Bookmark>,
    ): Result<PullResult> =
        Result.success(
            PullResult(progress = localProgress, highlights = localHighlights, bookmarks = localBookmarks),
        )

    data class PullResult(
        val progress: com.nexo.domain.model.ReadingProgress?,
        val highlights: List<com.nexo.domain.model.Highlight>,
        val bookmarks: List<com.nexo.domain.model.Bookmark>,
    )

    companion object {
        const val COMPONENT = "GoogleDriveJsonStateSync"
    }
}
