package com.nexo.domain.model

import kotlinx.serialization.Serializable

/**
 * A book as a catalog source describes it: identity, display fields and the
 * download hints a source published. Pure data — it carries no transport,
 * storage or Android dependency, so both the catalog data layer and the
 * presentation layer can consume it.
 *
 * Lives in `domain` because it crosses the repository boundary: the catalog
 * is queried and its results are handed to the UI, and the domain owns the
 * shape of what crosses that boundary.
 */
@Serializable
data class CatalogBook(
    val id: String,
    val provider: String,
    val title: String,
    val authors: List<String>,
    val coverUrl: String?,
    val languages: List<String>,
    val subjects: List<String>,
    val downloadUrl: String?,
    /**
     * Additive fields (defaulted) so payloads cached before they existed keep
     * decoding with `ignoreUnknownKeys = true` and call sites keep compiling.
     */
    val description: String? = null,
    val isPublicDomain: Boolean? = null,
    val formats: Map<String, String> = emptyMap(),
    /**
     * U3 identity fields (all additive + defaulted): ISBN pair split by length,
     * Open Library work key (`/works/OL…W`), first Internet Archive id, and the
     * Google Books volume id. Unknown stays null; old cached payloads keep
     * decoding via `ignoreUnknownKeys = true`. No Room migration.
     */
    val isbn13: String? = null,
    val isbn10: String? = null,
    val openLibraryWorkId: String? = null,
    val internetArchiveId: String? = null,
    val googleBooksId: String? = null,
)
