package com.nexo.presentation.navigation

import android.net.Uri
import com.nexo.data.remote.addons.AddonCollectionEntry
import com.nexo.data.remote.addons.AddonCollectionParser
import com.nexo.data.remote.addons.AddonFetchErrorCode
import com.nexo.data.remote.addons.AddonFetchException
import com.nexo.data.remote.addons.AddonHttpTransport
import com.nexo.data.remote.addons.AddonManifestPreview
import com.nexo.data.remote.addons.AddonRegistryLike
import com.nexo.data.remote.catalog.isHttpSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI states for the addon collection deep-link flow (no silent installs). */
sealed class CollectionInstallUiState {
    data object Idle : CollectionInstallUiState()

    data object Fetching : CollectionInstallUiState()

    /**
     * The confirmation payload: every importable addon, with
     * [AddonCollectionEntry.requestsStream] telling the dialog which rows need
     * the reader's own `stream` consent later, and [skipped] counting the
     * entries the import refused.
     */
    data class Confirming(
        val collectionUrl: String,
        val entries: List<AddonCollectionEntry>,
        val skipped: Int,
    ) : CollectionInstallUiState()

    data object Installing : CollectionInstallUiState()

    data class Error(
        val code: AddonFetchErrorCode,
    ) : CollectionInstallUiState()
}

/**
 * Holds a pending addon-collection deep link (`nexo://collection?url=…`). MainActivity
 * routes collection URIs here (BEFORE supabase handleDeeplinks); the NavHost-root
 * dialog host observes [state].
 *
 * Flow: onCollectionUri → parse → fetch the collection JSON → parse it
 * ([AddonCollectionParser]) → Confirming → confirm() → one
 * [AddonRegistryLike.installManifest] per entry → Idle. Cancel and error-dismiss
 * return to Idle with nothing installed.
 *
 * Two rules make this different from a single install:
 *
 * - One bad entry never fails the set: each install is attempted on its own, and
 *   only a set where every install failed reports an error.
 * - The collection NEVER grants `stream` consent. A collection is untrusted
 *   input and cannot consent on the reader's behalf, so no entry triggers
 *   `recordAddonConsent`: every addon that requests `stream` stays unconsented
 *   until the reader allows it individually in the addon's own settings row.
 *   Do not "simplify" this by recording consent for the whole set on confirm.
 */
class CollectionInstallController(
    private val registry: AddonRegistryLike,
    private val transport: AddonHttpTransport,
    private val mainDispatcher: CoroutineDispatcher,
) {
    constructor(
        registry: AddonRegistryLike,
        transport: AddonHttpTransport,
    ) : this(registry, transport, Dispatchers.Main)

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val _state = MutableStateFlow<CollectionInstallUiState>(CollectionInstallUiState.Idle)
    val state: StateFlow<CollectionInstallUiState> = _state.asStateFlow()

    private var pendingConfirming: CollectionInstallUiState.Confirming? = null
    private var installJob: Job? = null

    fun onCollectionUri(uri: Uri?) {
        val url = CollectionDeepLinkParser.parse(uri)
        if (url == null) {
            if (CollectionDeepLinkParser.isCollectionUri(uri)) {
                // Collection host with a missing/non-https url param: surface the
                // https-required error instead of dropping it silently.
                _state.value = CollectionInstallUiState.Error(AddonFetchErrorCode.HTTPS_REQUIRED)
            }
            return
        }
        if (_state.value is CollectionInstallUiState.Fetching) return
        if (_state.value is CollectionInstallUiState.Installing) return
        if (_state.value is CollectionInstallUiState.Confirming && pendingConfirming?.collectionUrl == url) return
        _state.value = CollectionInstallUiState.Fetching
        scope.launch {
            _state.value =
                try {
                    val fetched = transport.fetch(url)
                    if (!fetched.status.isHttpSuccess()) {
                        throw AddonFetchException(
                            AddonFetchErrorCode.NETWORK,
                            "collection fetch status ${fetched.status}",
                        )
                    }
                    val parsed = AddonCollectionParser.parse(fetched.body.decodeToString())
                    if (parsed.entries.isEmpty()) {
                        throw AddonFetchException(
                            AddonFetchErrorCode.INVALID_MANIFEST,
                            "collection holds no importable addons",
                        )
                    }
                    CollectionInstallUiState
                        .Confirming(collectionUrl = url, entries = parsed.entries, skipped = parsed.skipped)
                        .also { pendingConfirming = it }
                } catch (err: AddonFetchException) {
                    CollectionInstallUiState.Error(err.code)
                } catch (err: Exception) {
                    CollectionInstallUiState.Error(AddonFetchErrorCode.NETWORK)
                }
        }
    }

    fun confirm() {
        val confirming = pendingConfirming
        if (confirming == null || installJob?.isActive == true) return
        _state.value = CollectionInstallUiState.Installing
        installJob =
            scope.launch {
                var failed = 0
                for (entry in confirming.entries) {
                    try {
                        // The embedded bytes are persisted, exactly like a single
                        // install: nothing is re-fetched and no field is lost.
                        registry.installManifest(
                            entry.transportUrl,
                            AddonManifestPreview(manifest = entry.manifest, rawJson = entry.rawJson),
                        )
                    } catch (err: Exception) {
                        failed++
                    }
                }
                pendingConfirming = null
                _state.value =
                    if (failed == confirming.entries.size) {
                        CollectionInstallUiState.Error(AddonFetchErrorCode.INVALID_MANIFEST)
                    } else {
                        CollectionInstallUiState.Idle
                    }
            }
    }

    fun cancel() {
        pendingConfirming = null
        _state.value = CollectionInstallUiState.Idle
    }

    fun dismissError() {
        _state.value = CollectionInstallUiState.Idle
    }
}
