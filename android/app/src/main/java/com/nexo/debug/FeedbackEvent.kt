package com.nexo.debug

import org.json.JSONArray
import org.json.JSONObject

/**
 * Domain types for the crash-feedback flow (PR4 / tasks 4.3-4.4).
 *
 * - `BookMeta`: minimal book context shown in the auto-context card (njdtk design).
 *   `bookId`/`chapterIndex`/`page` are ALWAYS attached; `title`/`chapterLabel` are
 *   attached only on feedback events (spec D2 — privacy allowlist).
 * - `FeedbackEntry`: queued offline feedback. `eventId` is the Sentry eventId of
 *   the originating crash; `text` is the user-typed message (≤ 240 chars mobile).
 * - `FeedbackSheetState`: state machine mirror of the desktop dialog
 *   (idle → editing → sending → sent | error).
 */
object FeedbackEvent {
    /**
     * Book context for the feedback sheet. Values are user-readable strings
     * the design uses in the auto-context card; ids-only payloads go through
     * `bookId` for Sentry attachment and `chapterIndex`/`page` for repro.
     *
     * `title` and `chapterLabel` are Sentry-attached ONLY on feedback events
     * — see [buildBookContext] in [FeedbackCapture].
     */
    data class BookMeta(
        val bookId: String,
        val title: String?,
        val chapterLabel: String?,
        val chapterIndex: Int?,
        val page: Int?,
    )

    /**
     * A queued feedback entry. The `eventId` is the Sentry eventId of the
     * crash; on flush we re-link with `Sentry.captureFeedback` so the Sentry
     * UI shows feedback ↔ event grouping (spec D3).
     */
    data class FeedbackEntry(
        val eventId: String,
        val text: String,
        val timestamp: Long,
        val bookId: String?,
        val chapterIndex: Int?,
        val page: Int?,
        val title: String?,
        val chapterLabel: String?,
    )

    /** State machine mirror of the desktop dialog (idle → editing → sending → sent | error). */
    sealed class FeedbackSheetState {
        object Idle : FeedbackSheetState()

        data class Editing(
            val text: String,
        ) : FeedbackSheetState()

        object Sending : FeedbackSheetState()

        object Sent : FeedbackSheetState()

        data class Error(
            val message: String,
        ) : FeedbackSheetState()
    }

    /**
     * JSON helpers — kept internal so the persistence layer and tests can
     * round-trip entries without exposing org.json types in the public API.
     */
    fun entryToJson(entry: FeedbackEntry): JSONObject =
        JSONObject().apply {
            put("eventId", entry.eventId)
            put("text", entry.text)
            put("timestamp", entry.timestamp)
            put("bookId", entry.bookId ?: JSONObject.NULL)
            put("chapterIndex", entry.chapterIndex ?: JSONObject.NULL)
            put("page", entry.page ?: JSONObject.NULL)
            put("title", entry.title ?: JSONObject.NULL)
            put("chapterLabel", entry.chapterLabel ?: JSONObject.NULL)
        }

    fun jsonToEntry(obj: JSONObject): FeedbackEntry =
        FeedbackEntry(
            eventId = obj.optString("eventId", ""),
            text = obj.optString("text", ""),
            timestamp = obj.optLong("timestamp", 0L),
            bookId = obj.optStringOrNull("bookId"),
            chapterIndex = obj.optIntOrNull("chapterIndex"),
            page = obj.optIntOrNull("page"),
            title = obj.optStringOrNull("title"),
            chapterLabel = obj.optStringOrNull("chapterLabel"),
        )

    fun entriesToJsonArray(entries: List<FeedbackEntry>): JSONArray =
        JSONArray().apply {
            entries.forEach { put(entryToJson(it)) }
        }

    fun jsonArrayToEntries(array: JSONArray): List<FeedbackEntry> =
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(jsonToEntry(obj))
            }
        }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
}
