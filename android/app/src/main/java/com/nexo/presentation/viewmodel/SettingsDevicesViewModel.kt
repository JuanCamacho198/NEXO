package com.nexo.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexo.data.device.detectDeviceInfo
import com.nexo.data.remote.supabase.SupabaseDeviceDataSource
import com.nexo.data.repository.DeviceRepositoryImpl
import com.nexo.domain.model.Device
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsDevicesUiState(
    val devices: List<Device> = emptyList(),
    val currentDeviceId: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val deviceCount: Int = 0,
)

class SettingsDevicesViewModel(
    application: Application,
    private val userId: String,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SettingsDevicesUiState())
    val uiState: StateFlow<SettingsDevicesUiState> = _uiState.asStateFlow()

    private val dataSource = SupabaseDeviceDataSource()
    private val repository = DeviceRepositoryImpl(dataSource)

    private var heartbeatJob: Job? = null

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 120_000L
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val context = getApplication<Application>()
            val deviceInfo = detectDeviceInfo(context)

            // Get existing devices
            val result = repository.getDevices(userId)
            result
                .onSuccess { devices ->
                    val existing = devices.find { it.hardwareId == deviceInfo.hardwareId }

                    if (existing != null) {
                        _uiState.value =
                            _uiState.value.copy(
                                devices = devices,
                                currentDeviceId = existing.id,
                                isLoading = false,
                                deviceCount = devices.size,
                            )
                        repository.updateHeartbeat(existing.id)
                        startHeartbeat(existing.id)
                    } else {
                        // Register new device
                        val registerResult = repository.registerDevice(userId, deviceInfo)
                        registerResult
                            .onSuccess { registered ->
                                val updated = repository.getDevices(userId).getOrDefault(emptyList())
                                _uiState.value =
                                    _uiState.value.copy(
                                        devices = updated,
                                        currentDeviceId = registered.id,
                                        isLoading = false,
                                        deviceCount = updated.size,
                                    )
                                startHeartbeat(registered.id)
                            }.onFailure { e ->
                                _uiState.value =
                                    _uiState.value.copy(
                                        isLoading = false,
                                        errorMessage = e.message ?: "Failed to register device",
                                    )
                            }
                    }
                }.onFailure { e ->
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            errorMessage = e.message ?: "Failed to load devices",
                        )
                }
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            repository.removeDevice(deviceId, userId)
            _uiState.value =
                _uiState.value.copy(
                    devices = _uiState.value.devices.filter { it.id != deviceId },
                    deviceCount = _uiState.value.devices.size - 1,
                )
        }
    }

    private fun startHeartbeat(deviceId: String) {
        stopHeartbeat()
        heartbeatJob =
            viewModelScope.launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    repository.updateHeartbeat(deviceId)
                }
            }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
    }
}
