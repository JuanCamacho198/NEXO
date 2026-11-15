package com.nexo.data.remote.supabase

import com.nexo.data.remote.supabase.SupabaseClientProvider
import com.nexo.domain.model.Device
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Devices CRUD via Supabase PostgREST (using supabase-kt postgrest-kt).
 *
 * Uses the session-authenticated client from [SupabaseClientProvider]
 * so RLS policies apply automatically.
 *
 * Replaces the old raw-Ktor implementation (which used the anon key directly).
 *
 * Supports Realtime subscriptions for live device list updates.
 * Call [subscribeToChanges] to start listening, [unsubscribe] to stop.
 */
@OptIn(SupabaseExperimental::class)
class SupabaseDeviceDataSource {
    private val postgrest get() = SupabaseClientProvider.client.postgrest
    private val realtime get() = SupabaseClientProvider.client.realtime

    private var changesChannel: RealtimeChannel? = null

    /**
     * Subscribe to realtime device changes for a given [userId].
     * Returns a Flow that emits updated [Device] objects on INSERT, UPDATE, DELETE.
     */
    suspend fun subscribeToChanges(userId: String): Flow<PostgresAction> {
        unsubscribe()
        val channel = SupabaseClientProvider.client.channel("devices-changes")
        changesChannel = channel
        val flow =
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "devices"
                filter("user_id", FilterOperator.EQ, userId)
            }
        channel.subscribe()
        return flow
    }

    /**
     * Flow that emits [Device] lists whenever a change is detected.
     * Requires [subscribeToChanges] to have been called first.
     */
    suspend fun observeDevices(userId: String): Flow<Device> {
        val channel = changesChannel ?: return emptyFlow()
        return channel
            .postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "devices"
                filter("user_id", FilterOperator.EQ, userId)
            }.mapNotNull { action ->
                when (action) {
                    is PostgresAction.Update -> action.decodeRecord<Device>()
                    is PostgresAction.Insert -> action.decodeRecord<Device>()
                    is PostgresAction.Delete, is PostgresAction.Select -> null
                }
            }
    }

    /**
     * Unsubscribe from realtime changes.
     */
    suspend fun unsubscribe() {
        changesChannel?.unsubscribe()
        changesChannel = null
    }

    suspend fun listDevices(userId: String): List<Device> =
        postgrest["devices"]
            .select {
                filter {
                    eq("user_id", userId)
                }
                order("last_active", Order.DESCENDING)
            }.decodeList<Device>()

    suspend fun upsertDevice(device: Device): Device {
        // Empty response body = successful write (see decodeSingleOrNullTolerant).
        return postgrest["devices"]
            .upsert(device) {
                onConflict = "user_id,hardware_id"
                headers.append("Prefer", "return=representation")
            }.decodeSingleOrNullTolerant<Device>()
            ?: device
    }

    suspend fun updateHeartbeat(deviceId: String) {
        postgrest["devices"]
            .update(
                mapOf(
                    "last_active" to
                        java.time.Instant
                            .now()
                            .toString(),
                ),
            ) {
                filter {
                    eq("id", deviceId)
                }
            }
    }

    suspend fun removeDevice(
        deviceId: String,
        userId: String,
    ) {
        postgrest["devices"]
            .delete {
                filter {
                    eq("id", deviceId)
                    eq("user_id", userId)
                }
            }
    }
}
