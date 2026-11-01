package com.nexo.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Konsist rule — no production log line may carry book content.
 *
 * `FeedbackCapture` now attaches the `CrashLogStore` snapshot to every Sentry
 * feedback envelope. Anything a log line writes therefore egresses off-device,
 * which turns "it is only a logcat line" into a privacy decision. The log
 * pipeline ([com.nexo.debug.LogRedaction]) scrubs keys and paths, not prose:
 * a raw selection or chapter excerpt placed in a log message is not
 * recoverable by a pattern list and would ship verbatim.
 *
 * The rule that makes the snapshot safe is structural, and it is this one:
 * book content is never interpolated into a log message in the first place.
 * When diagnosis needs to know what happened, log the shape of the value
 * (`len`, `words`, `rect`, `id`) — never the value.
 *
 * ## What the rule matches
 *
 * A console-style log call — `Log.d/i/w/e/v(tag, message[, throwable])` or
 * `DebugLog.debug/info/warn/error/success(tag, message)` — whose variadic
 * message arguments interpolate a variable that names user content:
 *
 * - `text`, `rawText`, `selectedText`, `excerpt` — the selected passage;
 * - `chapterText`, `bookText`, `content`, `noteText`, `title`.
 *
 * ## What the rule deliberately misses (known gaps)
 *
 * - A content variable renamed to something outside [CONTENT_VARIABLE]
 *   (e.g. `snippet`, `passage`, or a single letter).
 * - A multi-line string template interpolated as `$"""…"""` rather than
 *   `"$var"`.
 * - Content reaching the snapshot through a helper, e.g.
 *   `formatLine(selectedText)`.
 * A runtime guard cannot close these either: the value is a `String`, and no
 * runtime check can distinguish a chapter excerpt from a tag name. The rule is
 * a review tripwire over the source, not a proof.
 */
class BookContentLogHygieneTest {
    /**
     * A console-style log call: `Log.<level>(` or `DebugLog.<level>(`.
     *
     * The argument list is walked character by character rather than matched
     * with one regex. This is not gold-plating — a single `Regex` that splits
     * on `",\s*` desynchronises the moment the tag or message literal contains
     * a comma, and it desynchronises *in favour of the offender*: the exact
     * two lines that leak book content (`…onReadiumSelection(text='…` and
     * `…onTextSelection: "…`) are the two the naive split mis-frames, so the
     * gate reported green while both leaks stood. Structure has to be parsed.
     */
    private val LOG_CALL = Regex("""\b(?:Log\.[dviwe]|DebugLog\.(?:debug|info|warn|error|success))\(""")

    /**
     * Variables whose value is user book content, matched by exact lowercase
     * name at the START of an identifier.
     *
     * Two shapes are handled deliberately:
     * - The whole name is `text` / `excerpt` / `content` — the common case.
     * - The name *begins* with one of those and capitalises the next word,
     *   e.g. `textContent`, `excerptText`, `contentSnippet`.
     *
     * A plain `contains` is wrong here and the earlier version proved it:
     * `context` ends in `text` and got flagged while `rawText` — the real leak
     * in `ReadiumSelectionBridge` — did not match at all. Anchoring at the
     * start keeps `context` and `contentHash` out while catching `rawText`.
     */
    private val CONTENT_PREFIXES = listOf("text", "excerpt", "content", "note", "passage", "snippet", "book", "chapter")

    /**
     * The name *is* one of these — the canonical content variables, which do
     * not need the CamelCase continuation a prefix match requires. `note` is
     * here (a user's note is user content) but deliberately NOT in
     * [CONTENT_WORDS]: `noteId`/`noteCount` are identifiers that merely start
     * with it, and flagging those would drown the gate in false positives.
     */
    private val CONTENT_EXACT = setOf("text", "content", "excerpt", "note", "passage", "snippet")

    /**
     * True when [name] names book content rather than an identifier that merely
     * contains a content word.
     *
     * Three shapes, and the boundaries are the whole point:
     * - the name IS `text` / `excerpt` / `content` / `note` …;
     * - the name ENDS in one of those words in CamelCase (`rawText`,
     *   `chapterText`, `selectedExcerpt`) — `rawText` is the live leak in
     *   `ReadiumSelectionBridge` and an earlier suffix test missed it;
     * - the name STARTS with one and continues in CamelCase (`textContent`).
     *
     * Excluded by the same boundaries: `context` (ends in `text` but not at a
     * word boundary — the earlier `endsWith("text")` test wrongly flagged it),
     * `contentHash`, `bookId`, `noteCount`, `textColor`.
     */
    private fun isContentVariable(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in CONTENT_EXACT) return true
        for (word in CONTENT_WORDS) {
            // Trailing CamelCase: `rawText` = `raw` + `Text`. The matched word
            // must itself open a new CamelCase word — i.e. the character where
            // it starts is an uppercase `T`. That is what separates `rawText`
            // (content) from `context` (one word ending in "text", lowercase).
            if (lower.endsWith(word) && name.length > word.length) {
                val wordStart = name.length - word.length
                if (name[wordStart].isUpperCase()) return true
            }
            // Leading CamelCase: `textContent` = `text` + `Content`, the word
            // followed by an uppercase start of the next word. `content` is
            // excluded here because `contentHash` matches the shape while being
            // a digest, not the content — a leading `content` prefix names a
            // property of the content, and only the trailing form (`rawText`)
            // or the bare word is content itself.
            if (word != "content" &&
                lower.startsWith(word) &&
                name.length > word.length &&
                name[word.length].isUpperCase() &&
                !isMetadataName(name.substring(word.length))
            ) {
                return true
            }
        }
        return false
    }

    /** Content words matched at a CamelCase boundary, longest first. */
    private val CONTENT_WORDS =
        listOf("excerpt", "content", "passage", "snippet", "chapter", "text")

    /**
     * The remainder of a content-prefixed name that describes the value rather
     * than being it: `textLen`, `textLength`, `textCount`, `textSize`. These
     * are the metadata vocabulary the gate prescribes, so a name carrying one
     * is not itself content.
     */
    private val METADATA_SUFFIXES = setOf("Len", "Length", "Count", "Size", "Words", "WordCount")

    private fun isMetadataName(remainder: String): Boolean = remainder in METADATA_SUFFIXES

    /**
     * Every `$name` / `${name…}` substitution in a template, paired with the
     * member the projection reads (if any).
     *
     * This is a real scan rather than a regex because the two shapes the gate
     * must catch are precisely the ones a naive `Regex` gets wrong:
     * `${text.take(50)}` is not a bare `${name}` and is invisible to one, while
     * `$DEBUG_LOG_TEXT_LIMIT` inside a template is a false positive for
     * another. Both were measured against the fixtures in
     * `scanner detects a known book-content interpolation`.
     */
    private fun substitutionsIn(template: String): List<Substitution> {
        val found = mutableListOf<Substitution>()
        var index = 0
        while (index < template.length) {
            if (template[index] != '$') {
                index++
                continue
            }
            // `\$` is a literal dollar sign, not a substitution.
            if (isEscaped(template, index)) {
                index++
                continue
            }
            val next = template.getOrNull(index + 1)
            if (next == '{') {
                val close = matchingBrace(template, index + 1)
                if (close == -1) break
                // The body is an EXPRESSION, not a template — it carries no
                // leading `$`, so it cannot be fed back through this loop.
                // Read its root identifier and member directly.
                substitutionsIn(template.substring(index + 2, close).trimStart())
                    .ifEmpty { rootOf(template.substring(index + 2, close))?.let { listOf(it) } ?: emptyList() }
                    .let { found += it }
                index = close + 1
                continue
            }
            if (next == null || !(next.isLetter() || next == '_')) {
                index++
                continue
            }
            var end = index + 1
            while (end < template.length && (template[end].isLetterOrDigit() || template[end] == '_')) {
                end++
            }
            val name = template.substring(index + 1, end)
            found +=
                if (template.getOrNull(end) == '.') {
                    var memberEnd = end + 1
                    while (memberEnd < template.length &&
                        (template[memberEnd].isLetterOrDigit() || template[memberEnd] == '_')
                    ) {
                        memberEnd++
                    }
                    Substitution(
                        name = name,
                        member = template.substring(end + 1, memberEnd).ifEmpty { null },
                    )
                } else {
                    Substitution(name = name, member = null)
                }
            index = end
        }
        return found
    }

    private data class Substitution(
        val name: String,
        val member: String?,
    )

    /**
     * The root identifier and member of a `${…}` body, e.g. `text`/`take` from
     * `text.take(50)`, or `text`/`length` from `text.length`. Returns null when
     * the body does not open with an identifier (a literal, a nested call).
     */
    private fun rootOf(body: String): Substitution? {
        val trimmed = body.trimStart()
        if (trimmed.isEmpty() || !(trimmed[0].isLetter() || trimmed[0] == '_')) return null
        var end = 0
        while (end < trimmed.length && (trimmed[end].isLetterOrDigit() || trimmed[end] == '_')) {
            end++
        }
        val name = trimmed.substring(0, end)
        if (trimmed.getOrNull(end) != '.') return Substitution(name, null)
        var memberEnd = end + 1
        while (memberEnd < trimmed.length &&
            (trimmed[memberEnd].isLetterOrDigit() || trimmed[memberEnd] == '_')
        ) {
            memberEnd++
        }
        return Substitution(name, trimmed.substring(end + 1, memberEnd).ifEmpty { null })
    }

    // ── Test seams (used by the positive-control test in this file) ────────

    internal fun parseSubstitutions(template: String): List<String> = substitutionsIn(template).map { "${it.name}.${it.member ?: "-"}" }

    internal fun checkInterpolation(template: String): Boolean = interpolatesWholeVariable(template)

    internal fun checkName(name: String): Boolean = isContentVariable(name)

    /** Index of the `}` matching the `{` at [openBrace], or -1. */
    private fun matchingBrace(
        source: String,
        openBrace: Int,
    ): Int {
        var depth = 0
        var index = openBrace
        var inString = false
        while (index < source.length) {
            val c = source[index]
            if (c == '"' && !isEscaped(source, index)) inString = !inString
            if (!inString) {
                when (c) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }
        return -1
    }

    /**
     * True when the message template interpolates book content *as a value*.
     *
     * The distinction the gate enforces: `${text.length}` and
     * `${text.count { it.isWhitespace() }}` describe the selection and are what
     * authors are asked to write; `${text.take(50)}` and `$rawText` *are* the
     * selection. A braced body is inspected by recursing into it, so
     * `${text.count { … }}` reports `count` (metadata) while
     * `${text.take(50)}` reports `take` (not metadata) — the recursive call
     * takes the FIRST substitution, which is the variable the expression is
     * rooted at.
     */
    private fun interpolatesWholeVariable(argument: String): Boolean = substitutionsIn(argument).any { sub -> isContentVariable(sub.name) && !isMetadataProjection(sub) }

    /**
     * Metadata members: they read a *property* of the value rather than the
     * value. `len`, `words`, `rect`, `id` are the vocabulary the gate
     * prescribes, and these are the same idea. Everything else — `take`,
     * `substring`, `replace`, `uppercase`, `trim` — returns text and is the
     * content.
     */
    private val METADATA_MEMBERS =
        setOf(
            "length",
            "count",
            "size",
            "isEmpty",
            "isBlank",
            "isNotBlank",
            "hashCode",
            "indices",
            "lastIndex",
            "wordCount",
            "lineCount",
        )

    private fun isMetadataProjection(sub: Substitution): Boolean = sub.member in METADATA_MEMBERS

    /**
     * Extracts the body of the call whose `(` sits at [openParen], respecting
     * nested parens, brace templates, and parentheses inside string literals.
     * Returns null when the call is unterminated within the file text.
     */
    private fun callBodyAt(
        source: String,
        openParen: Int,
    ): String? {
        var index = openParen + 1
        var depth = 1
        var inString = false
        var braceDepth = 0
        while (index < source.length) {
            val c = source[index]
            when {
                // A `"` only opens a string when not already inside one; a
                // template `$` inside a string is handled by braceDepth.
                c == '"' && !isEscaped(source, index) -> inString = !inString
            }
            if (!inString) {
                when (c) {
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                }
                if (braceDepth <= 0) {
                    when (c) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) return source.substring(openParen + 1, index)
                        }
                    }
                }
            }
            index++
        }
        return null
    }

    private fun isEscaped(
        source: String,
        index: Int,
    ): Boolean {
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && source[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    /**
     * Splits a call body into top-level arguments (ignoring nested parens and
     * commas inside string literals).
     */
    private fun splitArguments(body: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        var inString = false
        var braceDepth = 0
        for (index in body.indices) {
            val c = body[index]
            if (c == '"' && !isEscaped(body, index)) inString = !inString
            if (!inString) {
                when (c) {
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                }
                if (braceDepth <= 0) {
                    when (c) {
                        '(' -> depth++
                        ')' -> depth--
                        ',' ->
                            if (depth == 0) {
                                parts += body.substring(start, index)
                                start = index + 1
                            }
                    }
                }
            }
        }
        parts += body.substring(start)
        return parts
    }

    @Test
    fun `no production log line interpolates book content`() {
        val offenders = mutableListOf<String>()
        var scannedCalls = 0

        Konsist
            .scopeFromProduction()
            .files
            .filter { isProductionFile(it.path) && it.path.endsWith(".kt") }
            .forEach { file ->
                for (match in LOG_CALL.findAll(file.text)) {
                    val openParen = match.range.last
                    val body = callBodyAt(file.text, openParen) ?: continue
                    scannedCalls++

                    // args[0] is the tag, args[1] is the message template; a
                    // 3rd argument is a Throwable, never template text.
                    val message = splitArguments(body).getOrNull(1)?.trim() ?: continue
                    if (!interpolatesWholeVariable(message)) continue

                    val line = file.text.take(match.range.first).count { it == '\n' } + 1
                    offenders +=
                        "${file.path.substringAfterLast('/')}:$line → " +
                        message.replace(Regex("\\s+"), " ").trim().take(140)
                }
            }

        if (offenders.isNotEmpty()) {
            fail(
                "A production log line interpolates book content. Every log line now reaches " +
                    "Sentry through the FeedbackCapture snapshot, and LogRedaction scrubs keys " +
                    "and paths, not prose — the chapter text would ship verbatim. Log the shape " +
                    "of the value instead (len=/words=/rect=/id=), never the value:\n" +
                    offenders.joinToString("\n") { "  - $it" },
            )
        }

        // A silent zero-scan is indistinguishable from a clean codebase. The
        // production tree has hundreds of log lines; anything near zero means
        // the extractor broke, not that the codebase got quieter.
        assertTrue(
            "The scanner walked only $scannedCalls log calls across production — that is " +
                "implausibly low, so LOG_CALL/callBodyAt broke and the check above passed " +
                "vacuously.",
            scannedCalls > 100,
        )
    }

    /**
     * The scanner must actually see the two interpolations this gate exists to
     * forbid. A regex that goes blind on exactly the leaking shape is how the
     * first version of this test reported green with both leaks live, so the
     * positive control is pinned here rather than trusted.
     */
    @Test
    fun `scanner detects a known book-content interpolation`() {
        val leaks =
            listOf(
                // `${text.take(N)}` — a transform that returns text, not a property.
                """Log.d("ReaderVM", "onTextSelection: \"${'$'}{text.take(50)}\" rect=${'$'}rect")""",
                // Same shape nested inside a longer template.
                """Log.d("SelectionDebug", "Calling onReadiumSelection(text='${'$'}{text.take(50)}', rect=${'$'}selRect)")""",
                // Bare braced variable.
                """Log.d("SelectionDebug", "Normalized selection text: '${'$'}{rawText}'")""",
                // Simple `$var` followed by punctuation / end.
                """DebugLog.info("InspectHL", "attrs=${'$'}attrsStr, text=${'$'}text")""",
                // A renamed content variable that the earlier regex missed.
                """DebugLog.warn("HL", "excerpt kept: '${'$'}rawText'")""",
            )

        val safe =
            listOf(
                // The prescribed metadata vocabulary.
                """Log.d("ReaderVM", "onTextSelection: len=${'$'}{text.length} rect=${'$'}rect")""",
                """Log.d("ReaderVM", "words=${'$'}{text.count { it.isWhitespace() }}")""",
                // An unrelated variable that merely contains "text".
                """Log.d("AuthVM", "Auth diagnostics [${'$'}context]: loading=${'$'}{state.isLoading}")""",
                // A content-prefixed value that is a hash, not the content.
                """DebugLog.info(TAG, "duplicate hash ${'$'}{contentHash.take(16)}")""",
                // A constant, not a variable — `$DEBUG_LOG_TEXT_LIMIT`.
                """Log.d("ReaderVM", "limit ${'$'}DEBUG_LOG_TEXT_LIMIT")""",
            )

        leaks.forEach { line ->
            val message = messageArgumentOf(line)
            assertTrue("failed to parse leaking fixture: $line", message != null)
            assertTrue(
                "scanner must flag a whole-value interpolation of book content, missed: $line",
                interpolatesWholeVariable(message!!),
            )
        }

        safe.forEach { line ->
            val message = messageArgumentOf(line)
            assertTrue("failed to parse safe fixture: $line", message != null)
            assertTrue(
                "scanner must not flag metadata or an unrelated variable, flagged: $line",
                !interpolatesWholeVariable(message!!),
            )
        }
    }

    /**
     * Both separators: Konsist reports Windows paths with `\` on this host, so
     * a `/main/` literal silently matches nothing and every assertion above it
     * goes vacuous. Normalising is cheaper than trusting the host.
     */
    private fun isProductionFile(path: String): Boolean {
        val normalised = path.replace('\\', '/')
        return normalised.contains("/main/") && !normalised.contains("/test/")
    }

    private fun messageArgumentOf(source: String): String? {
        val match = LOG_CALL.find(source) ?: return null
        val body = callBodyAt(source, match.range.last) ?: return null
        return splitArguments(body).getOrNull(1)?.trim()
    }
}
