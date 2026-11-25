package com.nexo.presentation.viewmodel.reader

/**
 * Plain, readable text from an HTML fragment (a footnote body): tags dropped,
 * the common entities decoded, whitespace collapsed. Null when nothing is left.
 */
fun htmlToPlainText(html: String): String? =
    html
        .replace(BLOCK_BREAK, "\n")
        .replace(ANY_TAG, "")
        .let(::decodeEntities)
        .lines()
        .joinToString("\n") { it.replace(INLINE_WHITESPACE, " ").trim() }
        .replace(BLANK_LINES, "\n")
        .trim()
        .ifBlank { null }

/**
 * Text of the element a link's fragment (`#n3`) points at in [html], for a note
 * preview when the book does not mark its notes (`epub:type="noteref"`), so
 * Readium hands over no note content.
 *
 * The element with that id is read up to the end of its block. When the id sits
 * on an empty anchor (`<a id="n3"/>` before the note's text, a common pattern),
 * the text that follows it up to the end of the enclosing block is used instead.
 * Capped at [maxChars]; null when the id is absent or the block holds no text.
 */
fun extractFragmentText(
    html: String,
    fragmentId: String,
    maxChars: Int = NOTE_PREVIEW_MAX_CHARS,
): String? {
    if (fragmentId.isBlank()) return null
    val idPattern = Regex("""\sid\s*=\s*["']${Regex.escape(fragmentId)}["']""")
    val idMatch = idPattern.find(html) ?: return null
    val tagStart = html.lastIndexOf('<', idMatch.range.first).takeIf { it >= 0 } ?: return null
    val blockEnd = BLOCK_END.find(html, idMatch.range.last)?.range?.last ?: (html.length - 1)
    val segment = html.substring(tagStart, (blockEnd + 1).coerceAtMost(html.length))
    val text = htmlToPlainText(segment) ?: return null
    return if (text.length <= maxChars) text else text.take(maxChars).substringBeforeLast(' ') + "…"
}

private fun decodeEntities(text: String): String =
    text
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(OTHER_ENTITY, " ")

/** Longest note preview shown before "Go to the note" takes over. */
const val NOTE_PREVIEW_MAX_CHARS = 700

private val BLOCK_BREAK = Regex("""(?i)<\s*(br|/p|/li|/div|/h\d)\b[^>]*>""")
private val ANY_TAG = Regex("""(?s)<[^>]*>""")
private val OTHER_ENTITY = Regex("""&#?[a-zA-Z0-9]+;""")
private val INLINE_WHITESPACE = Regex("""[ \t\x0B\f\r]+""")
private val BLANK_LINES = Regex("""\n{2,}""")
private val BLOCK_END = Regex("""(?i)</\s*(p|li|aside|div|section|dd|td|blockquote)\s*>""")
