package com.nexo.data.remote.wiktionary

/**
 * Raw HTTP boundary for the Wiktionary MediaWiki API, so the client stays fully
 * testable offline with fakes (mirrors the translation/catalog transport seam).
 */
interface WiktionaryHttpTransport {
    suspend fun get(url: String): WiktionaryHttpResponse
}

/** Raw upstream result: HTTP status + body. */
data class WiktionaryHttpResponse(
    val status: Int,
    val body: String,
)

/**
 * Result of one wikitext fetch — never throws across the boundary.
 *
 * [Success] carries the page's raw wikitext. [MissingPage] is the honest "this
 * wiki has no such word": the MediaWiki API answers 200 with an `error` body.
 * [Failure] is the SERVICE failing (network, HTTP status, undecodable body) and
 * is deliberately distinct from [MissingPage], so the cascade can tell "the
 * word is not here" from "the wiki did not answer".
 */
sealed interface WiktionaryFetch {
    data class Success(
        val wikitext: String,
    ) : WiktionaryFetch

    data object MissingPage : WiktionaryFetch

    data class Failure(
        val code: WiktionaryErrorCode,
        val message: String,
    ) : WiktionaryFetch
}

/** Terminal failure codes for a wikitext fetch. Never thrown — always returned. */
enum class WiktionaryErrorCode {
    /** No network, timeout, or the transport threw. */
    NETWORK,

    /** A non-2xx HTTP status. */
    HTTP_ERROR,

    /** The body could not be decoded as the API's parse envelope. */
    PARSE_ERROR,

    /** The endpoint was not https — refused before any request. */
    HTTPS_REQUIRED,
}

/** Base template of the MediaWiki parse endpoint; `{lang}` and the word are filled in. */
const val WIKTIONARY_API_TEMPLATE: String =
    "https://%s.wiktionary.org/w/api.php?action=parse&page=%s&prop=wikitext&format=json"

/**
 * Builds the MediaWiki parse URL for [word] on the [wikiLanguage] Wiktionary.
 *
 * The word is percent-encoded: a multi-word selection (`give up`) contains a
 * space, and a page name with a space would otherwise truncate the query.
 */
fun wiktionaryEndpoint(
    wikiLanguage: String,
    word: String,
): String {
    val lang = wikiLanguage.trim().lowercase()
    return WIKTIONARY_API_TEMPLATE.format(lang, encodeQueryValue(word.trim()))
}

/**
 * Minimal percent-encoding for a query value (space → `%20`). A selection is a
 * word or a short phrase, so encoding accents and the handful of reserved
 * characters is sufficient; anything outside `[A-Za-z0-9._~-]` is UTF-8 encoded
 * byte by byte. Mirrors the encoder in `MyMemoryTranslationClient`.
 */
internal fun encodeQueryValue(value: String): String {
    val hex = "0123456789ABCDEF"
    val out = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and BYTE_MASK
        val c = code.toChar()
        if ((c.isLetterOrDigit() && code < ASCII_MAX) || c in "-._~") {
            out.append(c)
        } else {
            out.append('%')
            out.append(hex[(code shr NIBBLE_BITS) and NIBBLE_MASK])
            out.append(hex[code and NIBBLE_MASK])
        }
    }
    return out.toString()
}

/** Highest code point of 7-bit ASCII; percent-encoding only escapes above it. */
private const val ASCII_MAX = 128

/** Keeps one UTF-8 byte from sign-extending when widened to an Int. */
private const val BYTE_MASK = 0xFF

/** Bits per hex digit, and the mask that isolates one. */
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F
