package com.nexo.data.remote.addons.protocol

/** Client-bundled payload adapter names, in the frozen protocol order. */
enum class AddonShape(
    val wire: String,
) {
    RAW("raw"),
    GUTENDEX("gutendex"),
    OPENLIBRARY("openlibrary"),
    LEGACY_V2("legacy-v2"),
    ;

    companion object {
        fun fromWire(value: String): AddonShape? = entries.firstOrNull { it.wire == value }
    }
}

data class AddonExtraArg(
    val name: String,
    val isRequired: Boolean? = null,
)

data class AddonCatalogEntryV3(
    val type: String,
    val id: String,
    val name: String,
    val extra: List<AddonExtraArg>? = null,
)

/** A resource declaration. String entries normalize to `{ name }`. */
data class AddonResourceV3(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

/** NEXO-only extension. Ignored by Stremio, so it can never collide. */
data class AddonNexo(
    val homepage: String? = null,
    val license: String? = null,
    val templates: Map<String, String> = emptyMap(),
    val shape: AddonShape? = null,
)

data class AddonBehaviorHints(
    val adult: Boolean? = null,
)

data class AddonManifestV3(
    val id: String,
    val version: String,
    val name: String,
    val types: List<String>,
    val catalogs: List<AddonCatalogEntryV3>,
    val resources: List<AddonResourceV3>,
    val description: String? = null,
    val idPrefixes: List<String>? = null,
    val logo: String? = null,
    val background: String? = null,
    val behaviorHints: AddonBehaviorHints? = null,
    val nexo: AddonNexo? = null,
)

/** An installed addon: the manifest plus the install metadata the protocol never reads. */
data class AddonDescriptorV3(
    val id: String,
    val installUrl: String,
    val addedAt: Long,
    val enabled: Boolean,
    val manifest: AddonManifestV3,
)
