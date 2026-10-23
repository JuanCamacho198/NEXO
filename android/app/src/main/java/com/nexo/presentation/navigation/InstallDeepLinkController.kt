package com.nexo.presentation.navigation

import android.net.Uri
import com.nexo.data.remote.addons.AddonFetchErrorCode
import com.nexo.data.remote.addons.AddonFetchException
import com.nexo.data.remote.addons.AddonManifestPreview
import com.nexo.data.remote.addons.AddonRegistryLike
import com.nexo.data.remote.addons.protocol.AddonManifestV3
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI states for the addon install deep-link flow (no silent installs). */
sealed class InstallUiState {
    data object Idle : InstallUiState()

    data object Fetching : InstallUiState()

    data class Confirming(
        val url: String,
        val preview: AddonManifestPreview,
    ) : InstallUiState() {
        /** What the dialog renders; the bytes ride along on [preview]. */
        val manifest: AddonManifestV3
            get() = preview.manifest
    }

    data class Error(
        val code: AddonFetchErrorCode,
    ) : InstallUiState()
}

/**
 * Holds pending addon-install deep links (design: MutableStateFlow in
 * appContainer). MainActivity routes install URIs here (BEFORE supabase
 * handleDeeplinks); the NavHost-root dialog host observes [state].
 *
 * Flow: onInstallUri → parse → fetchManifest (preview) → Confirming →
 * confirm() → installManifest (existing upsert path) → Idle. Cancel and
 * error-dismiss return to Idle with nothing installed. Redelivery of the
 * same URI while Confirming is ignored (idempotent warm-start routing);
 * confirm() while Installing is a no-op.
 */
class InstallDeepLinkController(
    private val registry: AddonRegistryLike,
    private val mainDispatcher: CoroutineDispatcher,
    installedManifest: AddonManifestV3? = null,
) {
    constructor(registry: AddonRegistryLike) : this(registry, kotlinx.coroutines.Dispatchers.Main)

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val _state = MutableStateFlow<InstallUiState>(InstallUiState.Idle)
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    private var pendingConfirming: InstallUiState.Confirming? = null
    private var confirmJob: Job? = null

    init {
        // Unused seam kept for future already-installed detection; mirrors
        // desktop preview behavior without adding dialog copy yet.
        @Suppress("UNUSED_EXPRESSION")
        installedManifest
    }

    fun onInstallUri(uri: Uri?) {
        val url = InstallDeepLinkParser.parse(uri)
        if (url == null) {
            if (InstallDeepLinkParser.isInstallUri(uri)) {
                // Install-host link with a missing/non-https url param: surface
                // the https-required error instead of dropping it silently
                // (verify D1 — desktop shows an error dialog here too).
                android.util.Log.d(TAG, "invalid install deep link: $uri")
                _state.value = InstallUiState.Error(AddonFetchErrorCode.HTTPS_REQUIRED)
                return
            }
            android.util.Log.d(TAG, "ignoring non-install deep link: $uri")
            return
        }
        if (_state.value is InstallUiState.Fetching) return
        if (_state.value is InstallUiState.Confirming && pendingConfirming?.url == url) return
        _state.value = InstallUiState.Fetching
        scope.launch {
            _state.value =
                try {
                    val preview = registry.fetchManifest(url)
                    val confirming = InstallUiState.Confirming(url, preview)
                    pendingConfirming = confirming
                    confirming
                } catch (err: AddonFetchException) {
                    InstallUiState.Error(err.code)
                } catch (err: Exception) {
                    InstallUiState.Error(AddonFetchErrorCode.NETWORK)
                }
        }
    }

    fun confirm() {
        val confirming = pendingConfirming
        if (confirming == null || confirmJob?.isActive == true) return
        _state.value = InstallUiState.Fetching
        confirmJob =
            scope.launch {
                try {
                    registry.installManifest(confirming.url, confirming.preview)
                } catch (err: AddonFetchException) {
                    _state.value = InstallUiState.Error(err.code)
                    return@launch
                } catch (err: Exception) {
                    _state.value = InstallUiState.Error(AddonFetchErrorCode.NETWORK)
                    return@launch
                }
                pendingConfirming = null
                _state.value = InstallUiState.Idle
            }
    }

    fun cancel() {
        pendingConfirming = null
        _state.value = InstallUiState.Idle
    }

    fun dismissError() {
        _state.value = InstallUiState.Idle
    }

    private companion object {
        const val TAG = "InstallDeepLink"
    }
}
