package com.nexo.debug

import io.sentry.Breadcrumb
import io.sentry.SentryEvent

/**
 * PII scrubber for Sentry outbound events on Android.
 *
 * Kotlin port of `desktop/src/lib/shared/logger/sentryPiiScrubber.ts` plus the
 * reader-domain denylist and the breadcrumb allowlist required by the
 * pii-redaction spec (rule 5 gate for ALL Android attribute work).
 *
 * Policy:
 * - Key-contains denylist (credentials, case-insensitive) → `[Redacted]`.
 * - Reader-domain denylist (book/reader identity keys) → `[Redacted]`.
 *   Paths are dropped entirely (stricter than desktop's basename rule) because
 *   Android file paths embed account/external-storage identifiers.
 * - Structural: `user.{ip_address,email,username}` and `request.cookies`.
 * - String values and exception messages scanned for `<pattern>:<value>`.
 * - Feedback carve-out: `contexts.feedback` events may keep `bookTitle` and
 *   `chapterLabel` (mirrors FEEDBACK_ONLY_EXTRA_KEYS on desktop).
 * - `beforeBreadcrumb`: drop everything not in the DebugDual allowlist; apply
 *   the same key denylist to allowed breadcrumbs' data maps.
 *
 * Pure functions: never mutate inputs; idempotent; safe from any thread.
 */
object SentryPiiScrubber {
    const val REDACTED = "[Redacted]"

    /** Credential/token denylist — ported verbatim from the desktop scrubber. */
    private val CREDENTIAL_PATTERNS: Set<String> =
        setOf(
            "password",
            "token",
            "secret",
            "api_key",
            "apikey",
            "access_token",
            "accesstoken",
            "refresh_token",
            "refreshtoken",
            "notetext",
            "note",
            "tagname",
            "tag",
        )

    /**
     * Reader-domain denylist (this change's new risk surface). Paths are
     * dropped entirely, never basename-kept.
     */
    private val READER_DENYLIST: Set<String> =
        setOf(
            "booktitle",
            "bookid",
            "title",
            "author",
            "isbn",
            "cfi",
            "locator",
            "filepath",
            "bookpath",
            "epubpath",
            "highlight",
            "notetext",
            "query",
            "email",
            "userid",
        )

    /** Path-like keys dropped entirely on Android. */
    val PATH_KEYS: Set<String> = setOf("filepath", "bookpath", "epubpath", "iframesource")

    /** Keys emitted ONLY by the feedback path (desktop parity, sentryPiiScrubber.ts:88-90). */
    private val FEEDBACK_ONLY_KEYS: Set<String> = setOf("booktitle", "chapterlabel")

    private val SENSITIVE_QUERY_PARAMS: Set<String> =
        setOf(
            "code",
            "state",
            "token",
            "access_token",
            "refresh_token",
            "id_token",
        )

    /**
     * Breadcrumb allowlist: DebugDual.addCrumb names (message prefixes) plus
     * the `metric.` prefix added by P0 instrumentation. Everything else —
     * system/SDK navigation/network crumbs — is dropped by default.
     */
    val BREADCRUMB_ALLOWLIST: Set<String> =
        setOf(
            "progress.emit",
            "footer.chapterResolved",
            "reader.footerMismatch",
            "highlights.applied",
            "highlights.skipped",
            "sync.outboxFailed",
            "sync.receive",
            "metric.",
        )

    private fun shouldRedactKey(key: String): Boolean {
        val lower = key.lowercase()
        return CREDENTIAL_PATTERNS.any { lower.contains(it) } ||
            READER_DENYLIST.any { lower == it || lower.contains(it) }
    }

    private fun isPathKey(key: String): Boolean = PATH_KEYS.any { key.lowercase().contains(it) }

    /** Port of redactStringMessage: scrub `<pattern>:<value>` occurrences. */
    fun redactStringMessage(input: String): String {
        var result = input
        // Reader rule 1: CFI locators never leave the device, in any form.
        result = Regex("epubcfi[(][^)]*[)]").replace(result, "epubcfi([Redacted])")
        for (pattern in CREDENTIAL_PATTERNS + READER_DENYLIST) {
            val regex = Regex("(?i)" + Regex.escape(pattern) + ":[^\\s,}]+")
            result =
                regex.replace(result) { m ->
                    m.value.substringBefore(':') + ":" + REDACTED
                }
        }
        result = Regex("127\\.0\\.0\\.1:\\d+").replace(result, "127.0.0.1:$REDACTED")
        for (param in SENSITIVE_QUERY_PARAMS) {
            val regex = Regex("(?i)([?&])" + Regex.escape(param) + "=[^&\\s]+")
            result =
                regex.replace(result) { m ->
                    val prefix = m.groupValues[1]
                    val name = m.value.substringAfter(prefix).substringBefore('=')
                    prefix + name + "=" + REDACTED
                }
        }
        return result
    }

    /** Denylist application over an arbitrary string→string data map. */
    fun scrubData(data: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in data) {
            when {
                shouldRedactKey(key) -> out[key] = REDACTED
                isPathKey(key) -> Unit // dropped entirely on Android
                else -> out[key] = redactStringMessage(value)
            }
        }
        return out
    }

    private fun isFeedbackLike(event: SentryEvent): Boolean = event.contexts?.get("feedback") != null

    /**
     * Returns a redacted COPY of the event, or the same event unchanged when
     * there is nothing to redact. Never returns null by itself.
     */
    fun scrubEvent(event: SentryEvent): SentryEvent {
        val isFeedback = isFeedbackLike(event)

        // Extras are free-form; rebuild as a scrubbed map.
        event.extras?.let { extras ->
            val scrubbed = LinkedHashMap<String, Any>()
            for ((key, value) in extras) {
                when {
                    FEEDBACK_ONLY_KEYS.any { key.lowercase() == it } && isFeedback ->
                        scrubbed[key] = value
                    !isFeedback && FEEDBACK_ONLY_KEYS.any { key.lowercase() == it } -> Unit
                    isPathKey(key) -> Unit // dropped entirely on Android
                    shouldRedactKey(key) -> scrubbed[key] = REDACTED
                    value is String -> scrubbed[key] = redactStringMessage(value)
                    else -> scrubbed[key] = value
                }
            }
            event.extras = scrubbed
        }

        // Structural user redaction.
        event.user?.let { user ->
            if (user.ipAddress != null) user.ipAddress = REDACTED
            if (user.email != null) user.email = REDACTED
            if (user.username != null) user.username = REDACTED
        }

        event.request?.let { request ->
            request.cookies = REDACTED
            request.headers?.let { headers ->
                val scrubbedHeaders = LinkedHashMap<String, String>()
                for ((key, value) in headers) {
                    scrubbedHeaders[key] = if (shouldRedactKey(key)) REDACTED else value
                }
                request.headers = scrubbedHeaders
            }
        }

        event.message?.formatted?.let { msg ->
            event.message?.formatted = redactStringMessage(msg)
        }

        event.exceptions?.forEach { ex ->
            ex.value?.let { v ->
                ex.value = redactStringMessage(v)
            }
        }

        // Tag denylist: an errant call site must not leak book-identifying tags.
        val tagMap: Map<String, String> = event.tags?.toMap() ?: emptyMap()
        if (tagMap.isNotEmpty()) {
            val scrubbedTags = LinkedHashMap<String, String>()
            for ((key, value) in tagMap) {
                scrubbedTags[key] = if (shouldRedactKey(key)) REDACTED else value
            }
            event.tags = scrubbedTags
        }

        return event
    }

    /**
     * Breadcrumb allowlist: non-allowlisted crumbs are dropped (null); allowed
     * crumbs get their data map scrubbed. Returns null to drop.
     */
    fun filterBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb? {
        val message = breadcrumb.message ?: return null
        val allowed = BREADCRUMB_ALLOWLIST.any { message.startsWith(it) }
        if (!allowed) return null

        val data = breadcrumb.data
        if (data.isNotEmpty()) {
            val scrubbed = scrubData(data.mapValues { it.value?.toString() ?: "" })
            breadcrumb.data.clear()
            for ((key, value) in scrubbed) {
                breadcrumb.setData(key, value)
            }
        }
        return breadcrumb
    }

    /** Idempotency helper for tests: run the scrubber twice. */
    fun scrubTwice(event: SentryEvent): SentryEvent = scrubEvent(scrubEvent(event))
}
