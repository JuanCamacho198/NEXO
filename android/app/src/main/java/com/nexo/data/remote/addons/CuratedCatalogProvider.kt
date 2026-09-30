package com.nexo.data.remote.addons

import android.content.Context
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.data.remote.catalog.parseCatalogSource
import com.nexo.data.remote.catalog.resolveDownloadUrl
import org.json.JSONObject

/**
 * Curated first-party bundle: read-only, manifest-shaped `assets/addons/curated.json`
 * (byte-identical to desktop `services/addons/curated.json`). Every entry passes
 * [ManifestValidator] at construction — a malformed bundle fails fast. Browse-only:
 * search contributes nothing to the composite (empty page, no I/O, keeping the
 * zero-addon parity byte-for-byte) and getDetails fails stable NOT_FOUND
 * ("not routable"). Never a registry row. Mirrors desktop `CuratedCatalogProvider.ts`.
 */
class CuratedCatalogProvider private constructor(
    private val sources: List<CatalogSourceInfo>,
) : CatalogProvider {
    constructor(context: Context) : this(loadSources(context))

    /** Test/fixture entry: parse + validate a bundle payload without assets. */
    constructor(bundleJson: String) : this(CuratedCatalogProvider.parseBundle(bundleJson))

    override fun listSources(): List<CatalogSourceInfo> = sources

    /** Browse-only: contributes nothing to composite search, never I/O. */
    override suspend fun search(
        query: String,
        page: Int,
    ): PagedResult = PagedResult(emptyList(), null, 0)

    /** Curated sources are not routable; stable NOT_FOUND, no I/O. */
    override suspend fun getDetails(id: String): CatalogBook =
        throw com.nexo.data.remote.catalog.catalogError(
            CatalogErrorCode.NOT_FOUND,
            "curated sources are not routable: $id",
        )

    override fun resolveDownloadUrl(
        formats: Map<String, String>,
        preferEpub: Boolean,
    ): String = resolveDownloadUrl(formats, preferEpub)

    companion object {
        private const val ASSET_PATH = "addons/curated.json"

        private fun loadSources(context: Context): List<CatalogSourceInfo> {
            val json =
                context.assets
                    .open(ASSET_PATH)
                    .bufferedReader()
                    .use { it.readText() }
            return parseBundle(json)
        }

        /** Visible for tests: parse + validate a bundle payload without assets. */
        fun parseBundle(json: String): List<CatalogSourceInfo> {
            val root = JSONObject(json)
            val addons =
                root.optJSONArray("addons")
                    ?: throw AddonFetchException(AddonFetchErrorCode.INVALID_MANIFEST, "curated bundle must be {addons: [...]}")
            val out = mutableListOf<CatalogSourceInfo>()
            for (i in 0 until addons.length()) {
                val entry =
                    addons.opt(i) as? JSONObject
                        ?: throw AddonFetchException(AddonFetchErrorCode.INVALID_MANIFEST, "curated entry must be an object")
                // Shared validation gate: same rules as registry installs.
                ManifestValidator.validate(entry.toString().toByteArray(Charsets.UTF_8), "application/json")
                val manifestId = entry.getString("id")
                out.add(
                    CatalogSourceInfo(
                        sourceId = parseCatalogSource("builtin:$manifestId"),
                        name = entry.getString("name"),
                        kind = CatalogSourceKind.CURATED,
                    ),
                )
            }
            return out
        }
    }
}
