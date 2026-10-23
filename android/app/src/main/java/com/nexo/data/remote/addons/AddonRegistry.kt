package com.nexo.data.remote.addons

import com.nexo.data.local.dao.AddonDao
import com.nexo.data.local.entity.AddonEntity
import com.nexo.data.remote.addons.protocol.AddonClient
import com.nexo.data.remote.addons.protocol.AddonManifestParserV3
import com.nexo.data.remote.addons.protocol.AddonManifestV3
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.isHttpSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The stored column is always JSON, whatever shape the addon served. */
private const val STORED_MANIFEST_CONTENT_TYPE = "application/json"

/**
 * Row shape for [catalogProvidersWithAddons]; mirrors desktop `InstalledAddonRow`.
 *
 * [manifest] is the v3 view: a stored v2 row is adapted on read
 * (`loadManifestV3`), so the column keeps whatever the addon served and nothing
 * is rewritten or re-fetched. An adapted row is served by the same v3 fan-out as
 * every other addon — the `legacy-v2` shape is a payload reading, not a
 * separate provider.
 */
data class InstalledAddonRow(
    val id: String,
    val url: String,
    val manifest: AddonManifestV3,
    val enabled: Boolean,
    val addedAt: Long,
)

/**
 * A fetched-but-not-installed manifest: the parsed v3 view the confirm dialog
 * renders plus the bytes the addon served, so persisting never re-serializes and
 * never re-fetches.
 */
data class AddonManifestPreview(
    val manifest: AddonManifestV3,
    val rawJson: String,
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

    suspend fun install(url: String): AddonManifestV3

    suspend fun fetchManifest(url: String): AddonManifestPreview

    suspend fun installManifest(
        url: String,
        preview: AddonManifestPreview,
    ): AddonManifestV3

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    )

    suspend fun uninstall(id: String)
}

/**
 * Install-by-URL service over the [AddonDao] registry (Android mirror of desktop
 * `AddonRegistry.ts`). install(url): HTTPS check → platform transport →
 * `loadManifestV3` → sha256 addonId → UPSERT preserving enabled. Enable/
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
     * until recorded, the addon's `stream` resource stays unanswered.
     */
    fun recordAddonConsent(addonId: String) = consent.recordConsent(addonId)

    /** U4: true once disclosure consent was recorded for [addonId]. */
    fun hasAddonConsent(addonId: String): Boolean = consent.hasConsent(addonId)

    /** U4: withdraw disclosure consent for [addonId] (resolve goes empty again). */
    fun revokeAddonConsent(addonId: String) = consent.revokeConsent(addonId)

    /** install(url): HTTPS check → fetch → validate → addonId → upsert preserving enabled. */
    override suspend fun install(url: String): AddonManifestV3 {
        val preview = fetchManifest(url)
        return installManifest(url, preview)
    }

    /**
     * Fetch + validate only (no persistence) - the deep-link preview step.
     * HTTPS is enforced here so a preview can never trigger non-https I/O.
     * The returned preview carries the served bytes, so the confirm step
     * persists exactly what the addon answered.
     */
    override suspend fun fetchManifest(url: String): AddonManifestPreview {
        ManifestValidator.assertHttpsInstallUrl(url)
        val fetched = transport.fetch(url)
        if (!fetched.status.isHttpSuccess()) {
            throw AddonFetchException(AddonFetchErrorCode.NETWORK, "addon fetch status ${fetched.status}")
        }
        val manifest = AddonManifestParserV3.loadManifestV3(fetched.body, fetched.contentType)
        return AddonManifestPreview(manifest = manifest, rawJson = fetched.body.decodeToString())
    }

    /**
     * Persist an already-fetched manifest (sha256 addonId → upsert preserving
     * enabled/addedAt → notifyChanged). No fetch; built-in id collision guard.
     */
    override suspend fun installManifest(
        url: String,
        preview: AddonManifestPreview,
    ): AddonManifestV3 {
        val manifest = preview.manifest
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
                // The column keeps the bytes the addon served: a v2 row stays v2
                // on disk and is adapted on read, and no field is lost on the way.
                manifestJson = preview.rawJson,
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
            .mapNotNull { row -> row.toInstalledAddon() }
            .sortedWith(compareBy({ it.addedAt }, { it.id }))

    private fun AddonEntity.toInstalledAddon(): InstalledAddonRow? {
        val bytes = manifestJson.toByteArray(Charsets.UTF_8)
        val manifest =
            runCatching { AddonManifestParserV3.loadManifestV3(bytes, STORED_MANIFEST_CONTENT_TYPE) }
                .getOrNull() ?: return null
        return InstalledAddonRow(
            id = id,
            url = url,
            manifest = manifest,
            enabled = enabled,
            addedAt = addedAt,
        )
    }

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

/**
 * Ordered provider list: built-ins first, curated, then the addons.
 *
 * Every enabled row — v3 or adapted from v2 — is served by the single
 * [AddonCatalogProvider] fan-out, which reads each payload through its
 * `nexo.shape` adapter. Disabled rows are excluded, and an addon is only
 * registered when a transport (or a ready client) can actually reach it:
 * fail-closed, never a source that can only fail. Mirrors desktop
 * `defaultCatalogProviders` + `AddonFanoutCatalogProvider`.
 */
fun catalogProvidersWithAddons(
    builtIns: List<CatalogProvider>,
    curated: CatalogProvider,
    installedAddons: List<InstalledAddonRow>,
    addonTransport: AddonHttpTransport? = null,
    addonConsent: AddonConsentStore? = null,
    addonClient: AddonClient? = null,
    isOwned: suspend (sourceItemId: String) -> Boolean = { false },
): List<CatalogProvider> {
    val enabled = installedAddons.filter { it.enabled }
    val consent = addonConsent ?: InMemoryAddonConsentStore()
    val client = addonClient ?: addonTransport?.let { AddonClient(AddonProtocolTransport(it)) }
    val addons =
        client
            ?.takeIf { enabled.isNotEmpty() }
            ?.let { AddonCatalogProvider(enabled, it, consent, isOwned) }
    return builtIns + curated + listOfNotNull(addons)
}
