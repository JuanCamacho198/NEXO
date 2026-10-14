package com.nexo.domain.discover

import java.text.Normalizer

/** Matches the Unicode combining diacritical marks block (U+0300..U+036F). */
private val COMBINING_MARKS = Regex("[\\u0300-\\u036f]")

/** Splits a folded author string into alphanumeric whole words. */
private val WORD_SEPARATOR = Regex("[^a-z0-9]+")

/** Whitespace runs in a raw term. */
private val TERM_WHITESPACE = Regex("\\s+")

/**
 * The surname the matcher checks: the LAST whitespace-separated token of
 * `term` (`herman melville` -> `melville`, `arthur conan doyle` -> `doyle`,
 * `h g wells` -> `wells`). Blank input yields `""`.
 */
fun surnameOf(term: String): String = term.trim().split(TERM_WHITESPACE).last()

/**
 * Accent- and case-insensitive fold used for author comparison. NFD splits a
 * precomposed character into base + combining mark, and U+0300..U+036F is
 * exactly Unicode's combining diacritical marks, so stripping it degrades
 * "Pérez" to "perez" without touching ASCII letters.
 */
fun foldForMatch(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase()

/**
 * True iff one of `authors` credits the author of `term`, by whole-word
 * surname. The surname must appear as a complete word after folding (so
 * `Melvilles` and `Poetry Collective` are rejected), never as a substring of a
 * longer word. A book with no authors, or a term with no surname, never passes.
 * Pure: no I/O, total over any input.
 */
fun matchesAuthor(authors: List<String>, term: String): Boolean {
    val surname = surnameOf(term)
    if (surname.isEmpty()) return false
    val needle = foldForMatch(surname)
    if (needle.isEmpty()) return false
    return authors.any { author ->
        foldForMatch(author).split(WORD_SEPARATOR).any { word -> word == needle }
    }
}
