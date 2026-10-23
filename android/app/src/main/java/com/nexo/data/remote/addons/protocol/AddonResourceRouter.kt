package com.nexo.data.remote.addons.protocol

/**
 * Candidate routing: which installed addons can answer a request. Pure, no I/O.
 *
 * Two tiers answer here. A routed addon declares the resource in `resources`;
 * a templated addon declares `nexo.templates[resource]` and is selectable even
 * when its `resources` list does not mention it, because the template is the
 * only thing needed to build the URL.
 */
data class AddonRouteRequest(
    val resource: String,
    val type: String,
    val id: String? = null,
)

object AddonResourceRouter {
    /**
     * Only `meta` and `stream` address a single item, so only they honor
     * `idPrefixes`. `catalog` ids are catalog identifiers, never item ids.
     */
    private val PREFIX_FILTERED_RESOURCES = setOf("meta", "stream")

    private fun hasTemplate(
        manifest: AddonManifestV3,
        resource: String,
    ): Boolean = manifest.nexo?.templates?.get(resource) != null

    /** Per-resource list first, then the manifest-wide list, then no filter. */
    private fun effectivePrefixes(
        resource: AddonResourceV3,
        manifest: AddonManifestV3,
    ): List<String> {
        val perResource = resource.idPrefixes
        if (perResource != null && perResource.isNotEmpty()) return perResource
        val manifestWide = manifest.idPrefixes
        if (manifestWide != null && manifestWide.isNotEmpty()) return manifestWide
        return emptyList()
    }

    /** A request without an id has nothing to filter on, so it is not filtered. */
    private fun matchesIdPrefixes(
        prefixes: List<String>,
        id: String?,
    ): Boolean {
        if (prefixes.isEmpty()) return true
        if (id == null) return true
        return prefixes.any { id.startsWith(it) }
    }

    private fun matchesDeclaration(
        resource: AddonResourceV3,
        requested: String,
        type: String,
        id: String?,
        manifest: AddonManifestV3,
    ): Boolean {
        if (resource.types != null && !resource.types.contains(type)) return false
        if (!PREFIX_FILTERED_RESOURCES.contains(requested)) return true
        return matchesIdPrefixes(effectivePrefixes(resource, manifest), id)
    }

    fun declaresResource(
        manifest: AddonManifestV3,
        resource: String,
        type: String,
        id: String? = null,
    ): Boolean {
        val declared = manifest.resources.any { it.name == resource && matchesDeclaration(it, resource, type, id, manifest) }
        return declared || hasTemplate(manifest, resource)
    }

    /**
     * Ascending `addedAt`, then ascending `id`. Plain code-unit comparison on
     * purpose: locale-aware collation would make the order depend on the host.
     */
    private val COMPARE_ADDONS =
        Comparator<AddonDescriptorV3> { left, right ->
            if (left.addedAt != right.addedAt) {
                left.addedAt.compareTo(right.addedAt)
            } else {
                left.id.compareTo(right.id)
            }
        }

    fun selectAddons(
        addons: List<AddonDescriptorV3>,
        request: AddonRouteRequest,
    ): List<AddonDescriptorV3> =
        addons
            .filter { it.enabled && declaresResource(it.manifest, request.resource, request.type, request.id) }
            .sortedWith(COMPARE_ADDONS)

    /** The catalog's own allow-list, in declaration order. Unknown ids yield `[]`. */
    fun declaredExtras(
        manifest: AddonManifestV3,
        catalogId: String,
    ): List<String> {
        val catalog = manifest.catalogs.firstOrNull { it.id == catalogId } ?: return emptyList()
        return catalog.extra?.map { it.name } ?: emptyList()
    }
}
