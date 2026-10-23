package com.nexo.data.remote.addons.protocol

import com.nexo.data.remote.addons.AddonManifest

/**
 * Lossless v2 → v3 adapter. Pure, no I/O.
 *
 * A v2 manifest describes the same addon with flat endpoint URLs instead of
 * resources. Adapting rather than re-fetching keeps every installed addon
 * working through one code path, and `nexo.shape: "legacy-v2"` keeps today's
 * payload parsers in charge of the response.
 */
object AddonLegacyAdapter {
    /** v2 template placeholders and their v3 names. */
    private val LEGACY_PLACEHOLDERS: List<Pair<Regex, String>> =
        listOf(
            Regex("\\{query\\}") to "{search}",
            Regex("\\{page\\}") to "{skip}",
            Regex("\\{bookId\\}") to "{id}",
        )

    private fun rewriteTemplate(template: String): String = LEGACY_PLACEHOLDERS.fold(template) { rewritten, (pattern, token) -> pattern.replace(rewritten, token) }

    /** v2 declared `book-catalog`; v3 has a single book type. */
    private fun adaptCatalog(manifest: AddonManifest): List<AddonCatalogEntryV3> {
        val extra =
            if (manifest.searchUrl == null) {
                null
            } else {
                listOf(AddonExtraArg("search"), AddonExtraArg("skip"))
            }
        return manifest.catalogs.map { entry ->
            AddonCatalogEntryV3(type = AddonProtocolConstants.TYPE_BOOK, id = entry.id, name = entry.name, extra = extra)
        }
    }

    /**
     * v2 resources are endpoint names (`search`, `book-details`, `resolve`); v3
     * resources are `catalog` / `meta` / `stream`. Unmapped v2 names contribute
     * nothing rather than an unknown v3 resource.
     */
    private fun adaptResources(manifest: AddonManifest): List<AddonResourceV3> {
        val declared = manifest.resources.toSet()
        val hasStream =
            manifest.resolveUrl != null ||
                declared.contains("resolve") ||
                manifest.capabilities.contains("resolve")

        val resources = mutableListOf<AddonResourceV3>()
        // The v2 validator guarantees at least one catalog.
        resources += AddonResourceV3("catalog")
        if (manifest.detailsUrl != null || declared.contains("book-details") || declared.contains("details")) {
            resources += AddonResourceV3("meta", listOf(AddonProtocolConstants.TYPE_BOOK))
        }
        if (hasStream) resources += AddonResourceV3("stream", listOf(AddonProtocolConstants.TYPE_BOOK))
        return resources
    }

    fun adaptLegacyManifest(manifest: AddonManifest): AddonManifestV3 {
        val templates = LinkedHashMap<String, String>()
        manifest.searchUrl?.let { templates["catalog"] = rewriteTemplate(it) }
        manifest.detailsUrl?.let { templates["meta"] = rewriteTemplate(it) }
        manifest.resolveUrl?.let { templates["stream"] = rewriteTemplate(it) }

        return AddonManifestV3(
            id = manifest.id,
            version = manifest.version,
            name = manifest.name,
            types = listOf(AddonProtocolConstants.TYPE_BOOK),
            catalogs = adaptCatalog(manifest),
            resources = adaptResources(manifest),
            nexo = AddonNexo(templates = templates, shape = AddonShape.LEGACY_V2),
        )
    }
}
