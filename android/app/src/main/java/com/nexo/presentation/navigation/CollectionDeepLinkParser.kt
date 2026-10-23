package com.nexo.presentation.navigation

import android.net.Uri
import com.nexo.data.remote.addons.ManifestValidator

/**
 * Pure parser for the addon collection deep link, `nexo://collection?url=<https-url>`
 * (canonical) or the legacy `nextpage://collection?url=<https-url>`. Mirrors
 * [InstallDeepLinkParser]: a whole set installs from one link instead of one URL
 * per addon.
 *
 * Non-collection URIs (auth callback, reset-password, confirm, Drive redirect,
 * and the single-addon `install` host) are never claimed — the caller must fall
 * through to the existing handling.
 */
object CollectionDeepLinkParser {
    /** The collection deep-link host, valid under every [DeepLinkSchemes.accepted] scheme. */
    const val COLLECTION_HOST = "collection"

    /**
     * True when [uri] is an ACTION_VIEW target this parser owns: an accepted
     * scheme (`nexo` canonical, `nextpage` legacy) with host exactly `collection`.
     */
    fun isCollectionUri(uri: Uri?): Boolean {
        val scheme = uri?.scheme ?: return false
        return scheme in DeepLinkSchemes.accepted && uri.host == COLLECTION_HOST
    }

    /**
     * Extracts the https collection-JSON URL, or null when the link is not a
     * collection URI, the `url` param is missing/blank, or the value is not an
     * https URL (http/file/ftp/garbage — rejected BEFORE any network fetch).
     */
    fun parse(uri: Uri?): String? {
        if (!isCollectionUri(uri)) return null
        val rawUrl = uri!!.getQueryParameter("url")
        if (rawUrl.isNullOrBlank()) return null
        if (!runCatching { ManifestValidator.assertHttpsInstallUrl(rawUrl) }.isSuccess) return null
        return rawUrl
    }
}
