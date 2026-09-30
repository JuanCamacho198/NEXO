package com.nexo.data.update

import android.content.Context
import android.net.ConnectivityManager
import com.nexo.domain.connectivity.ConnectivityObserver
import com.nexo.domain.update.UpdateNetworkGate

class AndroidUpdateNetworkGate(
    context: Context,
    private val observer: ConnectivityObserver,
) : UpdateNetworkGate {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean = observer.current()

    override fun isMetered(): Boolean = connectivityManager?.isActiveNetworkMetered == true
}
