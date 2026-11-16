package com.nexo.ui.util

import com.nexo.domain.model.Book
import java.io.File
import java.util.Locale

private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0

/**
 * File size in MB computed from the book file; [fallback] when the file is missing or empty.
 * Unified duplication differing only by fallback param (BookDetail vs EditMetadata).
 * Handles null `filePath` without throwing.
 */
fun formatSizeMb(
    filePath: String?,
    fallback: String = "—",
): String {
    if (filePath.isNullOrBlank()) return fallback
    val bytes = runCatching { File(filePath).length() }.getOrNull() ?: return fallback
    if (bytes <= 0L) return fallback
    val mb = bytes / BYTES_PER_MEGABYTE
    return if (mb >= 100) {
        "${mb.toInt()} MB"
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

/**
 * Displays the language display name for an ISO 639-1 [code]; [fallback] when blank or
 * unresolvable. Uses `Locale.forLanguageTag` + `getDisplayName(Locale.getDefault())` so
 * both BookDetail metadata and LanguageDropdown share the same helper.
 */
fun languageDisplayName(
    code: String?,
    fallback: String = "—",
): String {
    if (code.isNullOrBlank()) return fallback
    val display = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())
    return if (display.isNotBlank() && !display.equals(code, ignoreCase = true)) display else code
}

/**
 * Extracts the year from an ISO `yyyy-MM-dd` date; [fallback] when unset or non-numeric.
 */
fun publishedYear(
    iso: String?,
    fallback: String = "—",
): String {
    if (iso.isNullOrBlank()) return fallback
    return iso.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) } ?: fallback
}

/**
 * Pages display text: PDF uses real count, EPUB uses estimated prefix `≈`, otherwise [fallback].
 * Pure (non-composable) variant of the string-resource version to keep the util testable
 * without a Compose context. Callers that need `R.string.book_detail_estimated_pages`
 * can wrap this with `stringResource` when inside a @Composable.
 */
fun getPagesDisplayText(
    book: Book,
    fallback: String = "—",
): String =
    when {
        book.format == "pdf" && book.totalPages != null -> book.totalPages.toString()
        book.format == "epub" && book.totalPages != null -> "≈${book.totalPages}"
        else -> fallback
    }
