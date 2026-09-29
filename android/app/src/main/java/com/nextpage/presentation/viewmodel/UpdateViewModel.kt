package com.nextpage.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextpage.data.update.UpdateDownloader
import com.nextpage.domain.update.CheckForUpdatesUseCase
import com.nextpage.domain.update.UpdateCandidate
import com.nextpage.domain.update.UpdateCheckOutcome
import com.nextpage.domain.update.UpdateErrorKind
import com.nextpage.domain.update.UpdateNetworkGate
import com.nextpage.domain.update.UpdateSuppressionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    data object UpToDate : UpdateUiState

    data class Available(
        val candidate: UpdateCandidate,
    ) : UpdateUiState

    data class Error(
        val kind: UpdateErrorKind,
    ) : UpdateUiState
}

class UpdateViewModel(
    private val useCase: CheckForUpdatesUseCase,
    private val suppression: UpdateSuppressionStore,
    private val networkGate: UpdateNetworkGate,
    private val downloader: UpdateDownloader,
    private val installedVersionCode: Int,
    private val mainDispatcher: CoroutineDispatcher,
    private val nowEpochMs: () -> Long,
) : ViewModel() {
    private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = mutableState.asStateFlow()

    private val mutableMeteredConsent = MutableStateFlow(false)
    val meteredConsentPending: StateFlow<Boolean> = mutableMeteredConsent.asStateFlow()

    private val mutableInstallGuidance = MutableStateFlow(false)
    val installGuidanceVisible: StateFlow<Boolean> = mutableInstallGuidance.asStateFlow()

    private var startupCheckDone = false

    fun isFeedEnabled(): Boolean = useCase.isFeedEnabled()

    fun checkAtStartup() {
        if (startupCheckDone) return
        startupCheckDone = true
        viewModelScope.launch(mainDispatcher) {
            mutableState.value = UpdateUiState.Checking
            when (val outcome = useCase.check(manual = false, installedVersionCode = installedVersionCode)) {
                is UpdateCheckOutcome.Available -> mutableState.value = UpdateUiState.Available(outcome.candidate)
                else -> mutableState.value = UpdateUiState.Idle
            }
        }
    }

    fun checkManually() {
        viewModelScope.launch(mainDispatcher) {
            mutableState.value = UpdateUiState.Checking
            when (val outcome = useCase.check(manual = true, installedVersionCode = installedVersionCode)) {
                is UpdateCheckOutcome.Available -> mutableState.value = UpdateUiState.Available(outcome.candidate)
                is UpdateCheckOutcome.UpToDate -> mutableState.value = UpdateUiState.UpToDate
                is UpdateCheckOutcome.Error -> mutableState.value = UpdateUiState.Error(outcome.kind)
                is UpdateCheckOutcome.Disabled -> mutableState.value = UpdateUiState.Idle
                is UpdateCheckOutcome.Deferred -> mutableState.value = UpdateUiState.Idle
            }
        }
    }

    fun remindLater() {
        val candidate = (mutableState.value as? UpdateUiState.Available)?.candidate ?: return
        suppression.remindLater(candidate.version, nowEpochMs())
        mutableState.value = UpdateUiState.Idle
    }

    fun dismiss() {
        mutableState.value = UpdateUiState.Idle
    }

    fun updateNow() {
        val candidate = (mutableState.value as? UpdateUiState.Available)?.candidate ?: return
        if (networkGate.isMetered()) {
            mutableMeteredConsent.value = true
            return
        }
        startDownload(candidate)
    }

    fun confirmMeteredDownload() {
        mutableMeteredConsent.value = false
        val candidate = (mutableState.value as? UpdateUiState.Available)?.candidate ?: return
        startDownload(candidate)
    }

    fun dismissMeteredConsent() {
        mutableMeteredConsent.value = false
    }

    fun dismissInstallGuidance() {
        mutableInstallGuidance.value = false
    }

    private fun startDownload(candidate: UpdateCandidate) {
        mutableState.value = UpdateUiState.Idle
        val fileName = downloader.fileNameFor(candidate.version, candidate.assetUrl)
        val downloadId = downloader.enqueue(candidate.version, candidate.assetUrl)
        downloader.awaitCompletion(downloadId, fileName) { installed ->
            if (!installed) mutableInstallGuidance.value = true
        }
    }
}
