package com.nexo.presentation.screen.reader

private val BARE_CHAPTER_NUMBER =
    Regex("""^(?:M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})|\d+)\.?$""")

/**
 * A chapter title as shown outside the table of contents. Many books title
 * chapters with a bare numeral ("I", "XIV", "3"), which reads as a stray
 * letter in a footer or list, so those get a "Ch." prefix through [format]
 * (a string resource with one `%1$s`). Real titles are returned unchanged.
 */
fun chapterLabel(
    title: String,
    format: String,
): String {
    val trimmed = title.trim()
    return if (trimmed.isNotEmpty() && BARE_CHAPTER_NUMBER.matches(trimmed)) format.format(trimmed.trimEnd('.')) else title
}
