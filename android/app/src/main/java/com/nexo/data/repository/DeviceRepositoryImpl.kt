package com.nexo.data.repository

import com.nexo.data.remote.supabase.SupabaseDeviceDataSource
import com.nexo.domain.model.Device
import com.nexo.domain.model.DeviceInfo
import com.nexo.domain.repository.DeviceRepository
import java.time.Instant

class DeviceRepositoryImpl(
    private val dataSource: SupabaseDeviceDataSource,
) : DeviceRepository {
    override suspend fun getDevices(userId: String): Result<List<Device>> =
        runCatching {
            dataSource.listDevices(userId)
        }

    override suspend fun registerDevice(
        userId: String,
        info: DeviceInfo,
    ): Result<Device> =
        runCatching {
            dataSource.upsertDevice(
                Device(
                    userId = userId,
                    hardwareId = info.hardwareId,
                    name = info.name,
                    os = info.os,
                    type = info.type,
                    lastActive = Instant.now().toString(),
                ),
            )
        }

    override suspend fun updateHeartbeat(deviceId: String): Result<Unit> =
        runCatching {
            dataSource.updateHeartbeat(deviceId)
        }

    override suspend fun removeDevice(
        deviceId: String,
        userId: String,
    ): Result<Unit> =
        runCatching {
            dataSource.removeDevice(deviceId, userId)
        }
}
