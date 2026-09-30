package com.nexo.data.remote.supabase

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reading progress CRUD via Supabase PostgREST (using supabase-kt postgrest-kt).
 *
 * Uses the session-authenticated client from [SupabaseClientProvider]
 * so RLS policies apply automatically.
 *
 * Supports Realtime subscriptions for live cross-device progress sync.
 * Call [subscribeToUserChanges] to start listening, [unsubscribe] to stop.
 */
@OptIn(SupabaseExperimental::class)
class SupabaseProgressDataSource {
    private val postgrest get() = SupabaseClientProvider.client.postgrest
    private val realtime get() = SupabaseClientProvider.client.realtime

    private var changesChannel: RealtimeChannel? = null
    private var bookmarksChannel: RealtimeChannel? = null
    private var highlightsChannel: RealtimeChannel? = null
    private var sessionsChannel: RealtimeChannel? = null

    /**
     * Subscribe to realtime reading_progress changes for a given [userId].
     * Single Realtime supervisor channel `progress:uid` (PR2 — Supabase SoT hot).
     * Gated by hasLiveSession before any subscription; caller must verify session.
     * Returns a Flow that emits [PostgresAction] for INSERT, UPDATE, DELETE.
     */
    suspend fun subscribeToUserChanges(userId: String): Flow<PostgresAction> {
        unsubscribe()
        val channel = SupabaseClientProvider.client.channel("progress:$userId")
        changesChannel = channel
        val flow =
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "reading_progress"
                filter("user_id", FilterOperator.EQ, userId)
            }
        channel.subscribe()
        return flow
    }

    /**
     * Unsubscribe from realtime changes.
     */
    suspend fun unsubscribe() {
        changesChannel?.unsubscribe()
        changesChannel = null
    }

    suspend fun upsertProgress(progress: ReadingProgressRow): ReadingProgressRow {
        // An empty response body (204 / return=minimal / RLS-stripped representation)
        // is a successful write, not a failure: fall back to the row we sent.
        return postgrest["reading_progress"]
            .upsert(progress) {
                onConflict = "user_id, book_id"
                headers.append("Prefer", "return=representation")
            }.decodeSingleOrNullTolerant<ReadingProgressRow>()
            ?: progress
    }

    suspend fun getProgress(
        userId: String,
        bookId: String,
    ): ReadingProgressRow? =
        postgrest["reading_progress"]
            .select {
                filter {
                    eq("user_id", userId)
                    eq("book_id", bookId)
                }
            }.decodeSingleOrNullTolerant<ReadingProgressRow>()

    suspend fun fetchBookState(
        userId: String,
        bookId: String,
    ): SupabaseBookState =
        SupabaseBookState(
            progress = getProgress(userId, bookId),
            bookmarks = listBookmarks(userId, bookId, includeDeleted = true),
            highlights = listHighlights(userId, bookId, includeDeleted = true),
        )

    suspend fun listProgress(userId: String): List<ReadingProgressRow> =
        postgrest["reading_progress"]
            .select {
                filter {
                    eq("user_id", userId)
                }
                order("updated_at", Order.DESCENDING)
            }.decodeList<ReadingProgressRow>()

    suspend fun deleteProgress(
        userId: String,
        bookId: String,
    ) {
        postgrest["reading_progress"]
            .delete {
                filter {
                    eq("user_id", userId)
                    eq("book_id", bookId)
                }
            }
    }

    // ─── Bookmarks ───────────────────────────────────────────────

    suspend fun upsertBookmark(bookmark: BookmarkRow): BookmarkRow {
        // Empty response body = successful write (see decodeSingleOrNullTolerant).
        return postgrest["bookmarks"]
            .upsert(bookmark) {
                onConflict = "user_id, book_id, cfi_location"
                headers.append("Prefer", "return=representation")
            }.decodeSingleOrNullTolerant<BookmarkRow>()
            ?: bookmark
    }

    suspend fun getBookmark(
        userId: String,
        bookId: String,
        cfiLocation: String,
    ): BookmarkRow? =
        postgrest["bookmarks"]
            .select {
                filter {
                    eq("user_id", userId)
                    eq("book_id", bookId)
                    eq("cfi_location", cfiLocation)
                }
            }.decodeSingleOrNullTolerant<BookmarkRow>()

    suspend fun listBookmarks(
        userId: String,
        bookId: String? = null,
        includeDeleted: Boolean = false,
    ): List<BookmarkRow> =
        postgrest["bookmarks"]
            .select {
                filter {
                    eq("user_id", userId)
                    if (bookId != null) eq("book_id", bookId)
                    if (!includeDeleted) exact("deleted_at", null)
                }
                order("updated_at", Order.DESCENDING)
            }.decodeList<BookmarkRow>()

    suspend fun softDeleteBookmark(
        id: String,
        userId: String,
    ) {
        postgrest["bookmarks"]
            .update(
                mapOf(
                    "deleted_at" to
                        java.text
                            .SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                java.util.Locale.US,
                            ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date()),
                ),
            ) {
                filter {
                    eq("id", id)
                    eq("user_id", userId)
                }
            }
    }

    /**
     * Subscribe to realtime bookmark changes for a given [userId].
     * Single supervisor channel `bookmarks:uid` — teardown with unsubscribeAll on logout.
     */
    suspend fun subscribeToBookmarkChanges(userId: String): Flow<PostgresAction> {
        bookmarksChannel?.unsubscribe()
        val channel = SupabaseClientProvider.client.channel("bookmarks:$userId")
        bookmarksChannel = channel
        val flow =
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "bookmarks"
                filter("user_id", FilterOperator.EQ, userId)
            }
        channel.subscribe()
        return flow
    }

    // ─── Highlights ─────────────────────────────────────────────

    suspend fun upsertHighlight(highlight: HighlightRow): HighlightRow {
        // Empty response body = successful write (see decodeSingleOrNullTolerant).
        return postgrest["highlights"]
            .upsert(highlight) {
                onConflict = "id"
                headers.append("Prefer", "return=representation")
            }.decodeSingleOrNullTolerant<HighlightRow>()
            ?: highlight
    }

    suspend fun getHighlight(id: String): HighlightRow? =
        postgrest["highlights"]
            .select {
                filter { eq("id", id) }
            }.decodeSingleOrNullTolerant<HighlightRow>()

    suspend fun listHighlights(
        userId: String,
        bookId: String? = null,
        includeDeleted: Boolean = false,
    ): List<HighlightRow> =
        postgrest["highlights"]
            .select {
                filter {
                    eq("user_id", userId)
                    if (bookId != null) eq("book_id", bookId)
                    if (!includeDeleted) exact("deleted_at", null)
                }
                order("updated_at", Order.DESCENDING)
            }.decodeList<HighlightRow>()

    suspend fun softDeleteHighlight(
        id: String,
        userId: String,
    ) {
        postgrest["highlights"]
            .update(
                mapOf(
                    "deleted_at" to
                        java.text
                            .SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                java.util.Locale.US,
                            ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date()),
                ),
            ) {
                filter {
                    eq("id", id)
                    eq("user_id", userId)
                }
            }
    }

    /**
     * Subscribe to realtime highlight changes for a given [userId].
     * Single supervisor channel `highlights:uid`.
     */
    suspend fun subscribeToHighlightChanges(userId: String): Flow<PostgresAction> {
        highlightsChannel?.unsubscribe()
        val channel = SupabaseClientProvider.client.channel("highlights:$userId")
        highlightsChannel = channel
        val flow =
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "highlights"
                filter("user_id", FilterOperator.EQ, userId)
            }
        channel.subscribe()
        return flow
    }

    // ─── Tags ───────────────────────────────────────────────────

    suspend fun findTagByName(
        userId: String,
        name: String,
    ): TagRow? =
        postgrest["tags"]
            .select {
                filter {
                    eq("user_id", userId)
                    eq("name", name)
                }
                limit(1)
            }.decodeSingleOrNullTolerant<TagRow>()

    suspend fun createTag(tag: TagRow): TagRow {
        // Empty response body = successful write (see decodeSingleOrNullTolerant).
        // The caller already owns the tag id, so the sent row is the correct success value.
        return postgrest["tags"]
            .upsert(tag) {
                onConflict = "user_id, name"
                headers.append("Prefer", "return=representation")
            }.decodeSingleOrNullTolerant<TagRow>()
            ?: tag
    }

    suspend fun findOrCreateTag(
        userId: String,
        name: String,
        color: String? = null,
    ): TagRow {
        val existing = findTagByName(userId, name)
        if (existing != null) {
            if (color != null && color != existing.color) {
                val tagId = requireNotNull(existing.id) { "Existing tag from database is missing an id" }
                postgrest["tags"].update(
                    mapOf("color" to color),
                ) {
                    filter {
                        eq("id", tagId)
                        eq("user_id", userId)
                    }
                }
            }
            return existing
        }
        return createTag(
            TagRow(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                userId = userId,
                name = name,
                color = color,
            ),
        )
    }

    suspend fun linkTagToHighlight(
        highlightId: String,
        tagId: String,
    ) {
        postgrest["highlight_tags"]
            .upsert(
                mapOf(
                    "highlight_id" to highlightId,
                    "tag_id" to tagId,
                ),
            ) {
                onConflict = "highlight_id, tag_id"
            }
    }

    suspend fun listTagsForHighlight(highlightId: String): List<TagRow> =
        postgrest["highlight_tags"]
            .select {
                filter { eq("highlight_id", highlightId) }
            }.decodeList<TagRow>()

    /**
     * Batch import bookmark rows (used when seeding Supabase from Drive).
     */
    suspend fun batchImportBookmarks(rows: List<BookmarkRow>) {
        for (row in rows) {
            upsertBookmark(row)
        }
    }

    /**
     * Batch import highlight rows (used when seeding Supabase from Drive).
     */
    suspend fun batchImportHighlights(rows: List<HighlightRow>) {
        for (row in rows) {
            upsertHighlight(row)
        }
    }

    /**
     * Batch import progress rows (used when seeding Supabase from Drive).
     */
    suspend fun batchImport(rows: List<ReadingProgressRow>) {
        for (row in rows) {
            upsertProgress(row)
        }
    }

    // ─── Reading sessions (REQ-reading-sessions-sync-3/4) ────────────

    /**
     * Upsert a reading-session row. Idempotent via the deterministic `id`
     * primary key + `onConflict = "id"` (SCEN-reading-sessions-sync-3/7).
     */
    suspend fun upsertReadingSession(session: ReadingSessionRow): ReadingSessionRow {
        // Empty response body = successful write (see decodeSingleOrNullTolerant).
        return postgrest["reading_sessions"]
            .upsert(session) {
                onConflict = "id"
                headers.append("Prefer", "return=representation")
            }.decodeSingleOrNullTolerant<ReadingSessionRow>()
            ?: session
    }

    /**
     * Subscribe to realtime reading_session changes for a given [userId].
     * Single supervisor channel `sessions:uid`.
     */
    suspend fun subscribeToReadingSessionChanges(userId: String): Flow<PostgresAction> {
        sessionsChannel?.unsubscribe()
        val channel = SupabaseClientProvider.client.channel("sessions:$userId")
        sessionsChannel = channel
        val flow =
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "reading_sessions"
                filter("user_id", FilterOperator.EQ, userId)
            }
        channel.subscribe()
        return flow
    }

    // ─── Channel cleanup ────────────────────────────────────────

    suspend fun unsubscribeAll() {
        unsubscribe()
        bookmarksChannel?.unsubscribe()
        bookmarksChannel = null
        highlightsChannel?.unsubscribe()
        highlightsChannel = null
        sessionsChannel?.unsubscribe()
        sessionsChannel = null
    }
}

/**
 * Represents a row in the `reading_progress` Supabase table.
 * PR2: version is hot SoT LWW tie +1 (reading_progress.version, default 1).
 */
@Serializable
data class ReadingProgressRow(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    @SerialName("book_id")
    val bookId: String,
    @SerialName("cfi_location")
    val cfiLocation: String = "",
    val percentage: Double = 0.0,
    @SerialName("locator_json")
    val locatorJson: String? = null,
    @SerialName("updated_at")
    val updatedAt: String,
    val version: Int = 1,
)

data class SupabaseBookState(
    val progress: ReadingProgressRow?,
    val bookmarks: List<BookmarkRow>,
    val highlights: List<HighlightRow>,
)

/**
 * Represents a row in the `bookmarks` Supabase table.
 */
@Serializable
data class BookmarkRow(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    @SerialName("book_id")
    val bookId: String,
    @SerialName("cfi_location")
    val cfiLocation: String = "",
    @SerialName("title_snippet")
    val titleSnippet: String? = null,
    @SerialName("locator_json")
    val locatorJson: String? = null,
    @SerialName("deleted_at")
    val deletedAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * Represents a row in the `highlights` Supabase table.
 */
@Serializable
data class HighlightRow(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    @SerialName("book_id")
    val bookId: String,
    @SerialName("cfi_range")
    val cfiRange: String = "",
    @SerialName("text_content")
    val textContent: String = "",
    val note: String? = null,
    val color: String = "yellow",
    val page: Int? = null,
    val type: String? = null,
    @SerialName("rect_json")
    val rectJson: Map<String, Double>? = null,
    @SerialName("locator_json")
    val locatorJson: String? = null,
    @SerialName("deleted_at")
    val deletedAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * Represents a row in the `tags` Supabase table.
 */
@Serializable
data class TagRow(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    val name: String,
    val color: String? = null,
)

/**
 * Represents a row in the `reading_sessions` Supabase table
 * (REQ-reading-sessions-sync-5). Field names mirror the remote DDL exactly.
 */
@Serializable
data class ReadingSessionRow(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("book_id")
    val bookId: String,
    @SerialName("started_at")
    val startedAt: String,
    @SerialName("duration_minutes")
    val durationMinutes: Int,
    val date: String,
    val device: String = "android",
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("start_percentage")
    val startPercentage: Double? = null,
    @SerialName("end_percentage")
    val endPercentage: Double? = null,
)
