package com.nexo.debug

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

/**
 * SharedPreferences-backed persistence for the crash-feedback flow.
 *
 * Two keys under [PREFS_NAME]:
 * - `feedback_queue`     → JSONArray of [FeedbackEvent.FeedbackEntry] (FIFO, cap 25).
 * - `feedback_dismissed` → JSONArray of eventIds the user already dismissed (idempotent).
 *
 * Why SharedPreferences instead of DataStore:
 * - Mirrors the existing `NexoApplication.PREFS_NAME` / `KEY_LAST_CRASH` pattern;
 * - No new dependency to pull in (no androidx.datastore in the project today);
 * - Volume is tiny (≤25 entries × ~200 bytes ≈ 5KB max) — perf is irrelevant;
 * - Synchronous read/write semantics match the test-friendly contract:
 *   unit tests can drive state in a single thread without `runTest` plumbing.
 *
 * Thread safety: SharedPreferences `apply()` is process-safe; `getString`
 * is lock-free. We never expose the SharedPreferences editor directly to
 * keep mutation sites auditable.
 */
class FeedbackPersistence(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Persist the last Sentry eventId of a captured crash (CrashDetailActivity path). */
    fun recordLastEventId(eventId: String) {
        prefs.edit().putString(KEY_LAST_EVENT_ID, eventId).apply()
    }

    /** Read the last Sentry eventId (or null when never captured / cleared). */
    fun readLastEventId(): String? = prefs.getString(KEY_LAST_EVENT_ID, null)

    /** Clear the last eventId after the feedback sheet is shown / dismissed. */
    fun clearLastEventId() {
        prefs.edit().remove(KEY_LAST_EVENT_ID).apply()
    }

    // ── Queue ───────────────────────────────────────────────────────────

    /**
     * Read the full queue (oldest first). Returns empty list when the key is
     * missing or the stored JSON is malformed (corrupted entries are dropped
     * defensively — spec D3 only requires us not to LOSE feedback).
     */
    fun readQueue(): List<FeedbackEvent.FeedbackEntry> {
        val raw = prefs.getString(KEY_FEEDBACK_QUEUE, null) ?: return emptyList()
        return runCatching {
            FeedbackEvent.jsonArrayToEntries(JSONArray(raw))
        }.getOrElse { emptyList() }
    }

    /**
     * Persist the queue (full overwrite — caller is responsible for FIFO +
     * cap invariants via [FeedbackQueue]).
     */
    fun writeQueue(entries: List<FeedbackEvent.FeedbackEntry>) {
        prefs
            .edit()
            .putString(KEY_FEEDBACK_QUEUE, FeedbackEvent.entriesToJsonArray(entries).toString())
            .apply()
    }

    fun clearQueue() {
        prefs.edit().remove(KEY_FEEDBACK_QUEUE).apply()
    }

    // ── Dismissed ───────────────────────────────────────────────────────

    fun readDismissed(): Set<String> {
        val raw = prefs.getString(KEY_FEEDBACK_DISMISSED, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            buildSet(arr.length()) {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i, "").takeIf { it.isNotEmpty() } ?: continue)
                }
            }
        }.getOrElse { emptySet() }
    }

    fun writeDismissed(ids: Set<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        prefs.edit().putString(KEY_FEEDBACK_DISMISSED, arr.toString()).apply()
    }

    companion object {
        /**
         * Shared with [NexoApplication.PREFS_NAME] so all debug/crash
         * prefs live in the same XML file (simpler dumpsys inspection during
         * support, and one less SharedPreferences handle to leak).
         */
        const val PREFS_NAME = "nexo_debug_crash"

        /** Last Sentry eventId captured by `installCrashHandler` — set in PR4 wiring. */
        const val KEY_LAST_EVENT_ID = "feedback_last_event_id"

        /** JSONArray of [FeedbackEvent.FeedbackEntry] waiting to be flushed. */
        const val KEY_FEEDBACK_QUEUE = "feedback_queue"

        /** JSONArray of eventIds the user already dismissed (dismiss-once). */
        const val KEY_FEEDBACK_DISMISSED = "feedback_dismissed"
    }
}

/** Convenience: detect a JSON parse error from a raw string without leaking JSONException types. */
@Suppress("unused")
internal fun String.toJsonArrayOrNull(): JSONArray? =
    try {
        JSONArray(this)
    } catch (_: JSONException) {
        null
    }
