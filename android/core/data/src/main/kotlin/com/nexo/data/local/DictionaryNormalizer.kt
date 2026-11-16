package com.nexo.data.local

import java.text.Normalizer

/**
 * Shared normalization contract (REQ-DSI-004), byte-identical to the Rust and TypeScript
 * implementations: trim, lowercase, NFD decompose, strip U+0300-U+036F.
 *
 * Two rules this object must not "improve" away:
 * - Punctuation is preserved (`Abyss,` -> `abyss,`). Stripping surrounding punctuation belongs to
 *   the selection tokenizer, which runs before normalization.
 * - `lowercase()` is the locale-independent overload. A locale-sensitive call would produce a
 *   different key on a Turkish device and break the shared vectors.
 */
object DictionaryNormalizer {
    private const val COMBINING_MARKS_START = 0x0300
    private const val COMBINING_MARKS_END = 0x036F

    fun normalize(input: String): String =
        Normalizer
            .normalize(input.trim().lowercase(), Normalizer.Form.NFD)
            .filterNot { it.code in COMBINING_MARKS_START..COMBINING_MARKS_END }
}
