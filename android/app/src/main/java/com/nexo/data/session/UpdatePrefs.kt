package com.nexo.data.session

import android.content.Context
import com.nexo.domain.update.UpdateSuppressionStore

class UpdatePrefs(
    context: Context,
) : UpdateSuppressionStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isSuppressed(
        version: String,
        nowEpochMs: Long,
    ): Boolean {
        if (prefs.getString(KEY_DISMISSED_VERSION, null) != version) return false
        val dismissedAt = prefs.getLong(KEY_DISMISSED_AT, 0L)
        return nowEpochMs - dismissedAt < REMIND_LATER_INTERVAL_MS
    }

    override fun remindLater(
        version: String,
        nowEpochMs: Long,
    ) {
        prefs
            .edit()
            .putString(KEY_DISMISSED_VERSION, version)
            .putLong(KEY_DISMISSED_AT, nowEpochMs)
            .apply()
    }

    fun clear() {
        prefs
            .edit()
            .remove(KEY_DISMISSED_VERSION)
            .remove(KEY_DISMISSED_AT)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "nextpage_update_prefs"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
        private const val KEY_DISMISSED_AT = "dismissed_at_epoch_ms"

        /**
         * Shared remind-later interval (mirrors `REMIND_LATER_INTERVAL_MS` in the
         * release-feed contract). Value undecided until the user confirms.
         */
        const val REMIND_LATER_INTERVAL_MS: Long = 7 * 24 * 60 * 60 * 1000L
    }
}
