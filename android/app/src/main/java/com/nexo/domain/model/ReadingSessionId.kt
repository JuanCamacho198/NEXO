package com.nexo.domain.model

import java.security.MessageDigest

/**
 * Deterministic reading-session id.
 *
 * `"sess_" + sha256("$userId|$bookId|$startTimeEpochMillis").hex.take(32)`
 *
 * The id MUST also be the local Room PK so the LWW REPLACE path matches by id
 * across devices (SCEN-reading-sessions-sync-7). A different [startTimeEpochMillis]
 * per minute-flush yields a distinct id → no double counting.
 */
fun readingSessionId(
    userId: String,
    bookId: String,
    startTimeEpochMillis: Long,
): String {
    val input = "$userId|$bookId|$startTimeEpochMillis"
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "sess_" + hex.take(32)
}
