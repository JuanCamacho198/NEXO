package com.nexo.domain.access

/**
 * U3 legal-access model: grouped external options for a catalog book.
 *
 * Only three groups exist by decision — there is intentionally no lending
 * branch. All [AccessOption.url] values are `https` web links; the only
 * in-app path is a public-domain download gated by [LegalAccess.canDownloadInApp].
 */
enum class AccessGroup {
    FREE,
    BUY,
    SUBSCRIBE,
}

/**
 * One external web option. [opensInApp] is true only for the gated
 * public-domain download; every other option opens externally.
 */
data class AccessOption(
    val group: AccessGroup,
    val title: String,
    val url: String,
    val opensInApp: Boolean = false,
)

/**
 * Resolved access for [bookId]. [downloadUrl] is non-null only when
 * [canDownloadInApp] is true (public-domain `https` download).
 */
data class LegalAccess(
    val bookId: String,
    val canDownloadInApp: Boolean,
    val downloadUrl: String?,
    val options: List<AccessOption>,
)
