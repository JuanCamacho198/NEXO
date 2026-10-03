package com.nexo.data.sync

import com.nexo.data.local.DictionaryNormalizer
import com.nexo.data.local.dao.DictionaryWordDao
import com.nexo.data.local.entity.DictionaryWordEntity
import com.nexo.data.remote.supabase.SupabaseDictionaryDataSource
import com.nexo.data.session.SessionManager
import com.nexo.debug.DebugLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Upsert payload for `user_dictionary_words` (FR-09, desktop contract).
 *
 * Two deliberate omissions from the desktop payload, both load-bearing:
 * - No `id`: the server owns the primary key; the conflict target is the
 *   natural key `(user_id, normalized_word)`. Sending a local id lets a
 *   conflicting upsert overwrite the server id another device already knows.
 * - No `tags`/`srs_stage`/`is_favorite`: Android cannot represent those columns,
 *   so sending defaults would silently clobber desktop-side enrichment on every
 *   Android push. PostgREST upsert only touches provided columns, so omitting
 *   them preserves them.
 *
 * The nullable evidence columns have NO default value so kotlinx always encodes
 * them, including explicit `null` — an edit that clears a field must clear the
 * remote value, not be omitted.
 */
@Serializable
data class DictionaryWordPushRow(
    @SerialName("user_id") val userId: String,
    val word: String,
    @SerialName("normalized_word") val normalizedWord: String,
    val definition: String?,
    @SerialName("part_of_speech") val partOfSpeech: String?,
    val phonetic: String?,
    val example: String?,
    val quote: String?,
    @SerialName("source_book_id") val sourceBookId: String?,
    @SerialName("source_book_title") val sourceBookTitle: String?,
    @SerialName("source_book_author") val sourceBookAuthor: String?,
    @SerialName("source_chapter") val sourceChapter: String?,
    @SerialName("source_locator") val sourceLocator: String?,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String?,
)

/** Read-side projection of a remote `user_dictionary_words` row. */
@Serializable
data class DictionaryWordRemoteRow(
    val id: String? = null,
    @SerialName("user_id") val userId: String = "",
    val word: String = "",
    @SerialName("normalized_word") val normalizedWord: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("deleted_at") val deletedAt: String? = null,
    val definition: String? = null,
    @SerialName("part_of_speech") val partOfSpeech: String? = null,
    val phonetic: String? = null,
    val example: String? = null,
    val quote: String? = null,
    @SerialName("source_book_id") val sourceBookId: String? = null,
    @SerialName("source_book_title") val sourceBookTitle: String? = null,
    @SerialName("source_book_author") val sourceBookAuthor: String? = null,
    @SerialName("source_chapter") val sourceChapter: String? = null,
    @SerialName("source_locator") val sourceLocator: String? = null,
)

/**
 * Remote access seam for the dictionary sync. Kept as an interface so the sync
 * service is unit-testable without a live Supabase client; the production
 * implementation is [SupabaseDictionaryDataSource].
 */
interface DictionaryRemoteDataSource {
    suspend fun upsert(row: DictionaryWordPushRow)

    suspend fun softDelete(
        userId: String,
        normalizedWord: String,
    )

    suspend fun listChanges(
        userId: String,
        updatedAfterIso: String?,
    ): List<DictionaryWordRemoteRow>
}

/**
 * Android dictionary push/pull against the `user_dictionary_words` Supabase
 * contract (FR-09). Mirrors desktop's [SupabaseDictionarySync] shape: direct
 * upsert / soft-delete on every local mutation, plus a pull that merges remote
 * rows into Room. No outbox table — the remote table is the cross-device
 * source of truth and the reinstall restore path.
 *
 * Identity is `normalized_word` (the remote unique key), computed with the
 * shared [DictionaryNormalizer] contract (byte-identical across Android,
 * desktop and Rust). Last-write-wins is `updated_at`; on an exact tie the local
 * row stays, so a pull never churns equal timestamps.
 *
 * Every method is session-gated and best-effort: a missing session or a network
 * failure returns `false`/`0` and never throws, so an offline local save still
 * succeeds.
 */
class DictionarySyncService(
    private val dao: DictionaryWordDao,
    private val sessionManager: SessionManager,
    private val remote: DictionaryRemoteDataSource = SupabaseDictionaryDataSource(),
    /** Injectable clock for deterministic tests. */
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Cursor for incremental pulls. `null` means "never pulled" and forces a
     * full pull — the reinstall case, where local Room is empty and every remote
     * row is restored. Kept in memory (no new table); a process restart simply
     * performs one extra full pull, which is idempotent.
     */
    @Volatile
    private var lastPullEpochMillis: Long? = null

    /** Push one locally created or edited entry. Returns true when it reached remote. */
    suspend fun pushWord(
        entity: DictionaryWordEntity,
        updatedAtEpochMillis: Long = entity.updatedAtEpochMillis,
    ): Boolean {
        val session = sessionManager.ensureFreshSession().getOrNull() ?: return false
        return try {
            remote.upsert(entity.toPushRow(session.userId, updatedAtEpochMillis))
            true
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "dictionary push failed for word='${entity.word}': ${t.message}")
            false
        }
    }

    /** Push a soft-delete by natural key (never by local id). */
    suspend fun deleteWord(word: String): Boolean {
        val session = sessionManager.ensureFreshSession().getOrNull() ?: return false
        return try {
            remote.softDelete(session.userId, DictionaryNormalizer.normalize(word))
            true
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "dictionary delete failed for word='$word': ${t.message}")
            false
        }
    }

    /**
     * Pull remote rows (including tombstones) and merge into Room.
     * Returns the number of remote rows read, or 0 when gated/failed.
     */
    suspend fun pullRemote(): Int {
        val session = sessionManager.ensureFreshSession().getOrNull() ?: return 0
        return try {
            val since = lastPullEpochMillis?.let(::isoUtc)
            val rows = remote.listChanges(session.userId, since)
            val localByNormalized =
                dao
                    .getAll()
                    .associateBy { DictionaryNormalizer.normalize(it.word) }
                    .toMutableMap()
            for (row in rows) {
                mergeRemote(row, localByNormalized)
            }
            // TODO(sync-cursor): the cursor is the device wall clock; if the clock
            // jumps forward, remote rows written in the skipped window are not
            // pulled until the next process restart (cursor null -> full pull).
            lastPullEpochMillis = nowMillis()
            rows.size
        } catch (t: Throwable) {
            DebugLog.warn(TAG, "dictionary pull failed: ${t.message}")
            0
        }
    }

    private suspend fun mergeRemote(
        row: DictionaryWordRemoteRow,
        localByNormalized: MutableMap<String, DictionaryWordEntity>,
    ) {
        if (row.normalizedWord.isBlank()) return
        val local = localByNormalized[row.normalizedWord]
        val remoteTime = parseTimestamp(row.updatedAt)

        val deletedAt = row.deletedAt
        if (deletedAt != null) {
            // Tombstone: drop the local row only when the deletion is newer than
            // the local change; a newer local edit must not be resurrected away.
            val remoteDeleted = parseTimestamp(deletedAt)
            if (local != null && remoteDeleted > local.updatedAtEpochMillis) {
                dao.delete(local.id)
                localByNormalized.remove(row.normalizedWord)
            }
            return
        }

        if (local == null) {
            val inserted = row.toEntity(remoteTime)
            dao.insert(inserted)
            localByNormalized[row.normalizedWord] = inserted
            return
        }

        if (remoteTime > local.updatedAtEpochMillis) {
            val merged =
                local.copy(
                    word = row.word,
                    // Keep the local "first added" time; only the write clock moves.
                    updatedAtEpochMillis = remoteTime,
                    definition = row.definition,
                    partOfSpeech = row.partOfSpeech,
                    phonetic = row.phonetic,
                    example = row.example,
                    quote = row.quote,
                    sourceBookId = row.sourceBookId,
                    sourceBookTitle = row.sourceBookTitle,
                    sourceBookAuthor = row.sourceBookAuthor,
                    sourceChapter = row.sourceChapter,
                    sourceLocator = row.sourceLocator,
                )
            dao.insert(merged)
            localByNormalized[row.normalizedWord] = merged
        }
    }

    private fun DictionaryWordEntity.toPushRow(
        userId: String,
        updatedAtEpochMillis: Long,
    ) = DictionaryWordPushRow(
        userId = userId,
        word = word,
        normalizedWord = DictionaryNormalizer.normalize(word),
        definition = definition,
        partOfSpeech = partOfSpeech,
        phonetic = phonetic,
        example = example,
        quote = quote,
        sourceBookId = sourceBookId,
        sourceBookTitle = sourceBookTitle,
        sourceBookAuthor = sourceBookAuthor,
        sourceChapter = sourceChapter,
        sourceLocator = sourceLocator,
        updatedAt = isoUtc(updatedAtEpochMillis),
        deletedAt = null,
    )

    private fun DictionaryWordRemoteRow.toEntity(updatedAtEpochMillis: Long) =
        DictionaryWordEntity(
            id = id ?: UUID.randomUUID().toString(),
            word = word,
            addedAtEpochMillis = updatedAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
            definition = definition,
            partOfSpeech = partOfSpeech,
            phonetic = phonetic,
            example = example,
            quote = quote,
            sourceBookId = sourceBookId,
            sourceBookTitle = sourceBookTitle,
            sourceBookAuthor = sourceBookAuthor,
            sourceChapter = sourceChapter,
            sourceLocator = sourceLocator,
        )

    private fun isoUtc(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    /**
     * PostgREST serializes `timestamptz` with a numeric UTC offset, which
     * `Instant.parse` rejects; parse it with [OffsetDateTime] and treat
     * unparsable input as the oldest possible instant so it can never win LWW.
     */
    private fun parseTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(raw).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    companion object {
        private const val TAG = "DictionarySyncService"
    }
}
