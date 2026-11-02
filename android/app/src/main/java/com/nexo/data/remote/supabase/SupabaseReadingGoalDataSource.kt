package com.nexo.data.remote.supabase

import com.nexo.data.sync.GoalValue
import com.nexo.data.sync.ReadingGoalRemoteDataSource
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * PostgREST implementation of the daily-goal remote seam (`user_preferences`).
 *
 * The session-authenticated client from [SupabaseClientProvider] makes RLS apply
 * automatically (`user_id = auth.uid()`), so no policy is touched here. Writes
 * upsert on the natural key `user_id`, mirroring how `reading_progress` upserts
 * on `user_id, book_id`.
 */
class SupabaseReadingGoalDataSource : ReadingGoalRemoteDataSource {
    private val postgrest get() = SupabaseClientProvider.client.postgrest

    override suspend fun fetchGoal(userId: String): GoalValue? {
        val row =
            postgrest["user_preferences"]
                .select {
                    filter { eq("user_id", userId) }
                    limit(1)
                }.decodeSingleOrNullTolerant<UserPreferencesRow>()
        val minutes = row?.dailyGoalMinutes ?: return null
        return GoalValue(minutes = minutes, updatedAt = row.updatedAtEpochMillis())
    }

    override suspend fun saveGoal(
        userId: String,
        minutes: Int,
        updatedAt: Long,
    ) {
        postgrest["user_preferences"].upsert(
            UserPreferencesRow(
                userId = userId,
                dailyGoalMinutes = minutes,
                updatedAt = Instant.ofEpochMilli(updatedAt).toString(),
            ),
        ) {
            onConflict = "user_id"
        }
    }
}

/**
 * Row of `public.user_preferences`. `daily_goal_minutes` is nullable in the
 * table (a future preference may not include a goal), so the model keeps the
 * null and the data source decides what an absent goal means.
 */
@Serializable
data class UserPreferencesRow(
    @SerialName("user_id")
    val userId: String,
    @SerialName("daily_goal_minutes")
    val dailyGoalMinutes: Int? = null,
    @SerialName("updated_at")
    val updatedAt: String,
) {
    /**
     * [updatedAt] as epoch millis, or `0` when absent/unparseable.
     *
     * `0` is the correct degradation: it loses every LWW comparison, so a row
     * whose clock cannot be read is treated as the oldest write rather than
     * silently outranking a real one.
     */
    fun updatedAtEpochMillis(): Long = runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrDefault(0L)
}
