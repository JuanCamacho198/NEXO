package com.nexo.presentation.viewmodel.reader

private const val ELLIPSIS = "…"
private val WHITESPACE = Regex("\\s+")

/**
 * The end of the text before a match, at most [max] characters, cut on a word
 * boundary so a result never opens mid-word, with an ellipsis when cut.
 */
internal fun snippetBefore(
    text: String,
    max: Int = SNIPPET_CONTEXT_CHARS,
): String {
    val clean = text.replace(WHITESPACE, " ")
    if (clean.length <= max) return clean.trimStart()
    val tail = clean.takeLast(max)
    val firstSpace = tail.indexOf(' ')
    val fromWord = if (firstSpace in 0 until tail.length - 1) tail.substring(firstSpace + 1) else tail
    return ELLIPSIS + fromWord
}

/**
 * The start of the text after a match, at most [max] characters, cut on a word
 * boundary so a result never ends mid-word, with an ellipsis when cut.
 */
internal fun snippetAfter(
    text: String,
    max: Int = SNIPPET_CONTEXT_CHARS,
): String {
    val clean = text.replace(WHITESPACE, " ")
    if (clean.length <= max) return clean.trimEnd()
    val head = clean.take(max)
    val lastSpace = head.lastIndexOf(' ')
    val toWord = if (lastSpace > 0) head.substring(0, lastSpace) else head
    return toWord + ELLIPSIS
}

/** Characters of context kept on each side of a match in the results list. */
internal const val SNIPPET_CONTEXT_CHARS = 60

/** Shortest query that starts a search: one letter matches most of any book. */
internal const val MIN_SEARCH_QUERY_LENGTH = 2

/** Upper bound on results collected for one query, so a common word stays fast. */
internal const val MAX_SEARCH_RESULTS = 300
