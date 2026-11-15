package com.nexo.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.nexo.domain.connectivity.ConnectivityObserver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ConnectivityObserver] backed by [ConnectivityManager] + [ConnectivityManager.NetworkCallback].
 *
 * App-lifetime singleton: network callbacks are process-global, so there is no
 * cleanup hook. Callbacks are delivered on the looper of the registering thread.
 *
 * The initial value is read from the active network so the first UI frame is
 * already correct without waiting for a callback.
 */
class AndroidConnectivityObserver(
    context: Context,
    @Suppress("UNUSED_PARAMETER") mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ConnectivityObserver {
    private val connectivityManager: ConnectivityManager? =
        context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val online = MutableStateFlow(connectivityManager?.isCurrentlyOnline() ?: true)

    override val isOnline: StateFlow<Boolean> = online.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online.value = true
            }

            override fun onLost(network: Network) {
                online.value = connectivityManager?.isCurrentlyOnline() ?: false
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                online.value = capabilities.hasInternet()
            }
        }

    init {
        connectivityManager?.let { manager ->
            val request =
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
            // Degrade to "assume online" rather than crash when registration is
            // rejected (e.g. missing permission on a custom/rooted ROM).
            runCatching { manager.registerNetworkCallback(request, callback) }
                .onFailure { online.value = true }
        }
    }

    override fun current(): Boolean = online.value
}

private fun ConnectivityManager.isCurrentlyOnline(): Boolean {
    val network = activeNetwork ?: return false
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasInternet()
}

private fun NetworkCapabilities.hasInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
