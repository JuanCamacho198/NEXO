package com.nexo.debug

/**
 * Universal redaction boundary for the Android log pipeline.
 *
 * Mirrors the SEMANTICS of desktop `src-tauri/src/logger.rs` (not its code):
 * 15 lowercased key-substring patterns, `<pattern>:<value>` string scrubbing,
 * absolute-path scrubbing to `[REDACTED_PATH]`, and a recursive value scrub
 * over maps/collections.
 *
 * Deliberately free of Android framework deps (`android.util.Log`, Context) so
 * every branch runs in a plain JVM unit test. Pure functions: never mutate
 * inputs, safe from any thread.
 *
 * This is ADDITIVE to [SentryPiiScrubber], which remains the Sentry-egress
 * layer. The two lists differ on purpose and are NOT merged:
 * - This list is the desktop log-pipeline parity set (includes `dsn`,
 *   `supabase`, `authorization`, `book_content`/`bookcontent`).
 * - [SentryPiiScrubber] additionally carries reader-domain identity keys
 *   (`booktitle`, `author`, `email`, `userid`, …) and its own Android-stricter
 *   path policy that DROPS path keys instead of redacting them.
 * Folding them together would either loosen the egress scrubber or push
 * reader-domain policy into Logcat. Both lists are required; a competing third
 * list is not permitted.
 */
object LogRedaction {
    const val REDACTED = "[REDACTED]"
    const val REDACTED_PATH = "[REDACTED_PATH]"

    /**
     * Key-substring denylist, mirrored from desktop `logger.rs`. Matched
     * case-insensitively against BOTH keys (whole value → [REDACTED]) and
     * `<pattern>:` occurrences in strings.
     */
    val PATTERNS: List<String> =
        listOf(
            "password",
            "token",
            "secret",
            "api_key",
            "api-key",
            "apikey",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "authorization",
            "supabase",
            "dsn",
            "highlight",
            "book_content",
            "bookcontent",
        )

    private val ALTERNATION: String =
        PATTERNS.joinToString("|") { Regex.escape(it) }

    /**
     * `<pattern>:<value>` where `<value>` runs to the next whitespace, comma,
     * or `}`. The `(?<![A-Za-z0-9])` guard keeps `mytoken:` from matching while
     * still allowing a leading boundary (`token:`, `x token:`, `?token:`).
     */
    private val KEYED_VALUE_REGEX: Regex =
        Regex("(?<![A-Za-z0-9])(?:$ALTERNATION):[^\\s,}]*", RegexOption.IGNORE_CASE)

    /**
     * Absolute-path patterns. Each is anchored to a preceding boundary that is
     * NOT `/`, `:` or a word character, so URLs such as
     * `https://host/cb?code=x` are never mangled: in that URL every `/` is
     * preceded by `:` or `/`, which the boundary excludes.
     */
    private val PATH_REGEXES: List<Regex> =
        listOf(
            // Windows drive (C:\..., C:/...) and UNC (\\host\share\...).
            Regex("(?<![A-Za-z0-9])(?:[A-Za-z]:[\\\\/]|\\\\\\\\)[^\\s,'\")}]+"),
            // unix system dirs.
            Regex(
                "(?<![A-Za-z0-9])/(?:home|users|tmp|var|etc|opt|data|storage|mnt|root|private)" +
                    "(?:/[^\\s/'\")}]+)*",
            ),
            // Generic absolute /segment/segment/tail, only at a real boundary.
            Regex("(?:^|(?<=[\\s(\"'=:,]))/(?:[^\\s/'\")}]+/)+[^\\s/'\")}]+"),
        )

    /** `~/...` home paths — consumes the tilde so it cannot survive alone. */
    private val HOME_REGEX: Regex = Regex("~(?=/)/[^\\s,'\")}]+")

    /** True when [key] contains any denylisted pattern (case-insensitive). */
    fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return PATTERNS.any { lower.contains(it) }
    }

    /**
     * Scrubs `<pattern>:<value>` occurrences and absolute/home paths.
     * Idempotent: the markers it writes contain neither a `:`-suffixed pattern
     * value nor a path shape.
     */
    fun redactString(input: String): String {
        if (input.isEmpty()) return input
        var result = input
        result =
            KEYED_VALUE_REGEX.replace(result) { match ->
                val colon = match.value.indexOf(':')
                match.value.substring(0, colon + 1) + REDACTED
            }
        for (regex in PATH_REGEXES) {
            result = regex.replace(result, REDACTED_PATH)
        }
        return HOME_REGEX.replace(result, REDACTED_PATH)
    }

    /**
     * Recursive value scrub. For a map, a key containing any pattern is
     * replaced wholesale with [REDACTED]; otherwise the value is recursed.
     * Lists/arrays map element-wise; strings go through [redactString];
     * anything else is returned unchanged.
     */
    fun redactValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> ->
                value.entries.associate { (rawKey, rawValue) ->
                    val key = rawKey?.toString() ?: return@associate rawKey to redactValue(rawValue)
                    key to if (isSensitiveKey(key)) REDACTED else redactValue(rawValue)
                }
            is Iterable<*> -> value.map { redactValue(it) }
            is Array<*> -> value.map { redactValue(it) }
            is String -> redactString(value)
            else -> value
        }
}
