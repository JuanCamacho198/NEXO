package com.nexo.domain.model

/**
 * Result of one wikitext fetch — never throws across the boundary.
 *
 * [Success] carries the page's raw wikitext. [MissingPage] is the honest "this
 * wiki has no such word": the MediaWiki API answers 200 with an `error` body.
 * [Failure] is the SERVICE failing (network, HTTP status, undecodable body) and
 * is deliberately distinct from [MissingPage], so a caller can tell "the word is
 * not here" from "the wiki did not answer".
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
