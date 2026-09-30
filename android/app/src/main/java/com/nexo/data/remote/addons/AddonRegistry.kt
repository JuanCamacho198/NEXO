package com.nexo.data.remote.addons

import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.entity.AddonEntity
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.isHttpSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Row shape for [catalogProvidersWithAddons]; mirrors desktop `InstalledAddonRow`.
 */
data class InstalledAddonRow(
    val id: String,
    val url: String,
    val manifest: AddonManifest,
    val enabled: Boolean,
    val addedAt: Long,
)

/**
 * Minimal install-by-URL seam contract (settings ViewModel + rebuild callers).
 *
 * Additive addon-deeplink-v1 surface: [fetchManifest] exposes fetch+validate
 * (preview, no persistence) and [installManifest] persists an already-fetched
 * manifest - the confirm-install split. `install()` keeps its atomic
 * fetch+validate+persist behavior.
 */
interface AddonRegistryLike {
    suspend fun listInstalled(): List<InstalledAddonRow>

    suspend fun install(url: String): AddonManifest

    suspend fun fetchManifest(url: String): AddonManifest

    suspend fun installManifest(
        url: String,
        manifest: AddonManifest,
    ): AddonManifest

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    )

    suspend fun uninstall(id: String)
}

/**
 * Install-by-URL service over the [AddonDao] registry (Android mirror of desktop
 * `AddonRegistry.ts`). install(url): HTTPS check → platform transport →
 * ManifestValidator → sha256 addonId → UPSERT preserving enabled. Enable/
 * disable/uninstall mutate rows; the composite's existence-check cache guard
 * (design A1) keeps uninstalled or disabled addon sources from ever serving
 * cached entries.
 */
class AddonRegistry(
    private val dao: AddonDao,
    private val transport: AddonHttpTransport,
    val consent: AddonConsentStore = InMemoryAddonConsentStore(),
    private val now: () -> Long = { System.currentTimeMillis() },
) : AddonRegistryLike {
    private val changeListeners = mutableListOf<(Int) -> Unit>()
    private var mutationVersion = 0

    /** Live-composite hook: fires after every registry mutation. */
    fun addOnChangedListener(listener: (Int) -> Unit) {
        changeListeners.add(listener)
    }

    private fun notifyChanged() {
        mutationVersion += 1
        for (listener in changeListeners.toList()) listener(mutationVersion)
    }

    /**
     * U4: record capability-disclosure consent for [addonId] (idempotent —
     * recorded once per addon). Called after the disclosure surface (U5 UI);
     * until recorded, [AddonCatalogProvider.resolveAccess] stays empty.
     */
    fun recordAddonConsent(addonId: String) = consent.recordConsent(addonId)

    /** U4: true once disclosure consent was recorded for [addonId]. */
    fun hasAddonConsent(addonId: String): Boolean = consent.hasConsent(addonId)

    /** U4: withdraw disclosure consent for [addonId] (resolve goes empty again). */
    fun revokeAddonConsent(addonId: String) = consent.revokeConsent(addonId)

    /** install(url): HTTPS check → fetch → validate → addonId → upsert preserving enabled. */
    override suspend fun install(url: String): AddonManifest {
        val manifest = fetchManifest(url)
        return installManifest(url, manifest)
    }

    /**
     * Fetch + validate only (no persistence) - the deep-link preview step.
     * HTTPS is enforced here so a preview can never trigger non-https I/O.
     */
    override suspend fun fetchManifest(url: String): AddonManifest {
        ManifestValidator.assertHttpsInstallUrl(url)
        val fetched = transport.fetch(url)
        if (!fetched.status.isHttpSuccess()) {
            throw AddonFetchException(AddonFetchErrorCode.NETWORK, "addon fetch status ${fetched.status}")
        }
        return ManifestValidator.validate(fetched.body, fetched.contentType)
    }

    /**
     * Persist an already-fetched manifest (sha256 addonId → upsert preserving
     * enabled/addedAt → notifyChanged). No fetch; built-in id collision guard.
     */
    override suspend fun installManifest(
        url: String,
        manifest: AddonManifest,
    ): AddonManifest {
        if (
            manifest.id in BUILTIN_SOURCE_NAMES ||
            manifest.id.contains(':') ||
            manifest.id.startsWith("builtin")
        ) {
            throw AddonFetchException(
                AddonFetchErrorCode.INVALID_MANIFEST,
                "addon id must not collide with built-in source ids: ${manifest.id}",
            )
        }
        val addonId = AddonId.fromUrl(url)
        val existing = dao.getById(addonId)
        dao.upsert(
            AddonEntity(
                id = addonId,
                url = url,
                manifestJson =
                    JSONObject(
                        mapOf(
                            "id" to manifest.id,
                            "name" to manifest.name,
                            "version" to manifest.version,
                            "catalogs" to
                                org.json.JSONArray().apply {
                                    manifest.catalogs.forEach {
                                        put(
                                            org.json
                                                .JSONObject()
                                                .put("type", it.type)
                                                .put("id", it.id)
                                                .put("name", it.name),
                                        )
                                    }
                                },
                            "resources" to org.json.JSONArray().apply { manifest.resources.forEach { put(it) } },
                        ),
                    ).apply {
                        manifest.searchUrl?.let { put("searchUrl", it) }
                        manifest.detailsUrl?.let { put("detailsUrl", it) }
                        manifest.resolveUrl?.let { put("resolveUrl", it) }
                        if (manifest.capabilities.isNotEmpty()) {
                            put(
                                "capabilities",
                                org.json.JSONArray().apply {
                                    manifest.capabilities.forEach { put(it) }
                                },
                            )
                        }
                    }.toString(),
                enabled = existing?.enabled ?: true,
                addedAt = existing?.addedAt ?: now(),
            ),
        )
        notifyChanged()
        return manifest
    }

    /** Rows in install order (addedAt, then id tiebreak) with parsed manifests. */
    override suspend fun listInstalled(): List<InstalledAddonRow> =
        withContext(Dispatchers.IO) { dao.getAll() }
            .mapNotNull { row ->
                runCatching { AddonManifestJson.decode(row.manifestJson) }.getOrNull()?.let { manifest ->
                    InstalledAddonRow(row.id, row.url, manifest, row.enabled, row.addedAt)
                }
            }.sortedWith(compareBy({ it.addedAt }, { it.id }))

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        try {
            dao.setEnabled(id, enabled)
        } finally {
            notifyChanged()
        }
    }

    override suspend fun uninstall(id: String) {
        try {
            dao.delete(id)
        } finally {
            notifyChanged()
        }
    }

    private companion object {
        /** Closed set of built-in source names addon manifests must not claim. */
        val BUILTIN_SOURCE_NAMES = setOf("gutendex", "openlibrary")
    }
}

/** JSON codecs for AddonManifest (org.json, mirroring ManifestValidator's parser). */
internal object AddonManifestJson {
    fun decode(json: String): AddonManifest =
        ManifestValidator.validate(
            json.toByteArray(Charsets.UTF_8),
            "application/json",
        )

    fun encode(manifest: AddonManifest): String =
        JSONObject()
            .put("id", manifest.id)
            .put("name", manifest.name)
            .put("version", manifest.version)
            .put(
                "catalogs",
                org.json.JSONArray().apply {
                    manifest.catalogs.forEach {
                        put(
                            org.json
                                .JSONObject()
                                .put("type", it.type)
                                .put("id", it.id)
                                .put("name", it.name),
                        )
                    }
                },
            ).put("resources", org.json.JSONArray().apply { manifest.resources.forEach { put(it) } })
            .apply {
                manifest.searchUrl?.let { put("searchUrl", it) }
                manifest.detailsUrl?.let { put("detailsUrl", it) }
                manifest.resolveUrl?.let { put("resolveUrl", it) }
                if (manifest.capabilities.isNotEmpty()) {
                    put(
                        "capabilities",
                        org.json.JSONArray().apply {
                            manifest.capabilities.forEach { put(it) }
                        },
                    )
                }
            }.toString()
}

/**
 * Ordered provider list: built-ins first, curated, then enabled addons in
 * install order (disabled rows excluded). Mirrors desktop `defaultCatalogProviders`.
 */
fun catalogProvidersWithAddons(
    builtIns: List<CatalogProvider>,
    curated: CatalogProvider,
    installedAddons: List<InstalledAddonRow>,
    addonTransport: AddonHttpTransport? = null,
    addonConsent: AddonConsentStore? = null,
): List<CatalogProvider> =
    builtIns + curated +
        installedAddons
            .filter { it.enabled }
            .map { AddonCatalogProvider(it.manifest, it.id, addonTransport, consent = addonConsent ?: InMemoryAddonConsentStore()) }
