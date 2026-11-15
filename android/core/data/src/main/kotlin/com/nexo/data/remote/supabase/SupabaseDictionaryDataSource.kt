package com.nexo.data.remote.supabase

import com.nexo.data.sync.DictionaryRemoteDataSource
import com.nexo.data.sync.DictionaryWordPushRow
import com.nexo.data.sync.DictionaryWordRemoteRow
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant

/**
 * Supabase/PostgREST implementation of the dictionary remote seam
 * (`user_dictionary_words`, the same table + RLS desktop uses).
 *
 * The session-authenticated client from [SupabaseClientProvider] makes RLS
 * apply automatically; no policy is changed here. Writes use the natural-key
 * conflict target `(user_id, normalized_word)` and deletes are soft deletes by
 * that same key, mirroring desktop's `SupabaseDictionarySync`.
 */
class SupabaseDictionaryDataSource : DictionaryRemoteDataSource {
    private val postgrest get() = SupabaseClientProvider.client.postgrest

    override suspend fun upsert(row: DictionaryWordPushRow) {
        postgrest["user_dictionary_words"].upsert(row) {
            onConflict = "user_id, normalized_word"
        }
    }

    override suspend fun softDelete(
        userId: String,
        normalizedWord: String,
    ) {
        val now = Instant.now().toString()
        postgrest["user_dictionary_words"].update(
            mapOf(
                "deleted_at" to now,
                "updated_at" to now,
            ),
        ) {
            filter {
                eq("user_id", userId)
                eq("normalized_word", normalizedWord)
            }
        }
    }

    override suspend fun listChanges(
        userId: String,
        updatedAfterIso: String?,
    ): List<DictionaryWordRemoteRow> =
        postgrest["user_dictionary_words"]
            .select {
                filter {
                    eq("user_id", userId)
                    // Include tombstones so remote deletions propagate; a
                    // `deleted_at` filter would make absence indistinguishable
                    // from "not yet fetched".
                    if (updatedAfterIso != null) gt("updated_at", updatedAfterIso)
                }
                order("updated_at", Order.ASCENDING)
            }.decodeList<DictionaryWordRemoteRow>()
}
