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
fun normalizeSelectedText(text: String): String = text.trim()

/**
 * The string a script returned through `WebView.evaluateJavascript`.
 *
 * The WebView hands the result back JSON-encoded, so a selected `aspecto`
 * arrives as `"aspecto"`, quotes included, with any quote, backslash or line
 * break inside escaped. Taking that verbatim put the quotes into copied text,
 * new highlights, saved dictionary words and translation requests. A value
 * that is not a JSON string (`null`, a number) is returned unchanged.
 */
fun decodeJsStringResult(raw: String): String {
    if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return raw
    val end = raw.length - 1
    val out = StringBuilder(end)
    var i = 1
    while (i < end) {
        val c = raw[i]
        if (c != '\\' || i + 1 >= end) {
            out.append(c)
            i++
            continue
        }
        val escaped = raw[i + 1]
        when (escaped) {
            'n' -> out.append('\n')
            't' -> out.append('\t')
            'r' -> out.append('\r')
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'u' -> {
                val code = raw.substring(i + 2, minOf(i + 6, end)).toIntOrNull(16)
                if (code != null && i + 6 <= end) {
                    out.append(code.toChar())
                    i += 6
                    continue
                }
                out.append(escaped)
            }
            else -> out.append(escaped)
        }
        i += 2
    }
    return out.toString()
}
