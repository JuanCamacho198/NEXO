package com.nexo.presentation.screen.readium

/**
 * Normalizes text captured from a Readium/WebView selection before it reaches
 * the ViewModel.
 *
 * The bridge reads the selection via `window.getSelection().toString()`, which
 * reflects the browser's character-adjusted selection boundaries and can carry
 * leading/trailing whitespace. Only the edges are trimmed: internal spacing is
 * part of the selection and a two-word highlight must keep its space.
 */
internal fun normalizeSelectedText(text: String): String = text.trim()
