package com.nexo.data.remote.supabase

import io.github.jan.supabase.SupabaseClient
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
 * Book catalog CRUD via Supabase PostgREST (using supabase-kt postgrest-kt).
 *
 * Uses the session-authenticated client from [SupabaseClientProvider]
 * so RLS policies apply automatically. Accepts an optional [client] for
 * testability — defaults to [SupabaseClientProvider.client].
 *
 * Supports Realtime subscriptions for live cross-device catalog sync.
 * Call [subscribeToCatalogChanges] to start listening, [unsubscribe] to stop.
 */
@OptIn(SupabaseExperimental::class)
class SupabaseBookCatalogDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client,
) {
    private val postgrest get() = client.postgrest
    private val realtime get() = client.realtime

    private var catalogChannel: RealtimeChannel? = null

    /**
     * Upsert a book row in the `user_books` table.
     * Uses `ON CONFLICT (user_id, id) DO UPDATE` so the same book
     * on the same user gets overwritten with latest metadata.
     */
    suspend fun upsertBook(row: UserBookRow): UserBookRow {
        // Empty response body = successful write (204 / return=minimal / stripped
        // representation). Without this, PostgREST's empty body surfaced as a
        // JsonDecodingException ("Expected start of the array '[', but had 'EOF'")
        // and broke every local-book reconcile push. See decodeSingleOrNullTolerant.
        return postgrest["user_books"]
            .upsert(row) {
                onConflict = "user_id, id"
                headers.append("Prefer", "return=representation")
            }.decodeSingleOrNullTolerant<UserBookRow>()
            ?: row
    }

    /**
     * List all books in the catalog for a given [userId],
     * ordered by [updated_at] descending (most recent first).
     */
    suspend fun listUserBooks(userId: String): List<UserBookRow> =
        postgrest["user_books"]
            .select {
                filter {
                    eq("user_id", userId)
                }
                order("updated_at", Order.DESCENDING)
            }.decodeList<UserBookRow>()

    /**
     * Get a single book row by [userId] and [bookId].
     * Returns null if not found.
     *
     * Uses the tolerant decoder: `decodeSingleOrNull` first decodes the body as
     * a JSON array, so an empty body (`204`, or a proxy/RLS-stripped response)
     * throws `Expected start of the array '[', but had 'EOF'`. For a lookup that
     * legitimately means "no row", empty must map to null — not to a crash.
     * This path is reached from the DELETE branch of `processBookItem`.
     */
    suspend fun getUserBook(
        userId: String,
        bookId: String,
    ): UserBookRow? =
        postgrest["user_books"]
            .select {
                filter {
                    eq("user_id", userId)
                    eq("id", bookId)
                }
                limit(1)
            }.decodeSingleOrNullTolerant<UserBookRow>()

    /**
     * Find a book row by content hash.
     * Returns the first match or null if not found.
     * Used for content-hash dedup (PR 5) — checks if a book with the
     * same SHA-256 hash already exists in the catalog for this user.
     *
     * Tolerant decode for the same reason as [getUserBook]: an empty body means
     * "no match", which is a normal outcome here, not a protocol error.
     */
    suspend fun getUserBookByHash(
        userId: String,
        contentHash: String,
    ): UserBookRow? =
        postgrest["user_books"]
            .select {
                filter {
                    eq("user_id", userId)
                    eq("content_hash", contentHash)
                }
                limit(1)
            }.decodeSingleOrNullTolerant<UserBookRow>()

    /**
     * Delete a book row from the catalog.
     */
    suspend fun deleteUserBook(
        userId: String,
        bookId: String,
    ) {
        postgrest["user_books"]
            .delete {
                filter {
                    eq("user_id", userId)
                    eq("id", bookId)
                }
            }
    }

    /**
     * Subscribe to realtime catalog changes on `user_books` for a given [userId].
     * PR2: hot SoT channel `catalog:uid` (Realtime <2s), hash dedup, cover bucket.
     * Returns a Flow that emits [PostgresAction] for INSERT, UPDATE, DELETE.
     */
    suspend fun subscribeToCatalogChanges(userId: String): Flow<PostgresAction> {
        catalogChannel?.unsubscribe()
        val channel = client.channel("catalog:$userId")
        catalogChannel = channel
        val flow =
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "user_books"
                filter("user_id", FilterOperator.EQ, userId)
            }
        channel.subscribe()
        return flow
    }

    /**
     * Unsubscribe from realtime catalog changes.
     */
    suspend fun unsubscribe() {
        catalogChannel?.unsubscribe()
        catalogChannel = null
    }
}

/**
 * Represents a row in the `user_books` Supabase table.
 *
 * Metadata extension (REQ-data-model-4): genre/language/publisher/tags/
 * published_date are additive columns. The `user_books` migration SQL below is
 * applied to the remote database out-of-band (Supabase MCP in the SDD apply
 * phase) — the columns do NOT include `user_rating` (rating sync is follow-up):
 *
 *     ALTER TABLE public.user_books ADD COLUMN genre TEXT;
 *     ALTER TABLE public.user_books ADD COLUMN language TEXT;
 *     ALTER TABLE public.user_books ADD COLUMN publisher TEXT;
 *     ALTER TABLE public.user_books ADD COLUMN tags TEXT;
 *     ALTER TABLE public.user_books ADD COLUMN published_date TEXT;
 *
 * The `tags` column lives on `user_books` and does not collide with the
 * separate `public.tags` table (highlights) — different namespaces.
 */
@Serializable
data class UserBookRow(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val title: String,
    val author: String? = null,
    val format: String,
    @SerialName("content_hash")
    val contentHash: String? = null,
    @SerialName("file_path")
    val filePath: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val language: String? = null,
    val publisher: String? = null,
    val tags: String? = null,
    @SerialName("published_date")
    val publishedDate: String? = null,
    @SerialName("total_pages")
    val totalPages: Int? = null,
    @SerialName("source_device")
    val sourceDevice: String? = null,
    @SerialName("imported_at")
    val importedAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    val lifecycle: String = "available",
    @SerialName("catalog_version") val catalogVersion: Long = 1,
    @SerialName("remote_provider") val remoteProvider: String? = null,
    @SerialName("remote_file_id") val remoteFileId: String? = null,
    @SerialName("remote_path") val remotePath: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("cover_object_path") val coverObjectPath: String? = null,
    // Desktop persists protocol_version as NULL when the row was never written
    // by the recovery protocol. Declare it nullable so kotlinx.serialization
    // does NOT crash decoding "protocol_version":null (JsonDecodingException
    // "Unexpected symbol 'n' in numeric literal"). Consumers default to 1.
    @SerialName("protocol_version") val protocolVersion: Int? = null,
)
