package com.nexo.domain.repository

import com.nexo.domain.model.Device
import com.nexo.domain.model.DeviceInfo

interface DeviceRepository {
    suspend fun getDevices(userId: String): Result<List<Device>>

    suspend fun registerDevice(
        userId: String,
        info: DeviceInfo,
    ): Result<Device>

    suspend fun updateHeartbeat(deviceId: String): Result<Unit>

    suspend fun removeDevice(
        deviceId: String,
        userId: String,
    ): Result<Unit>
}
