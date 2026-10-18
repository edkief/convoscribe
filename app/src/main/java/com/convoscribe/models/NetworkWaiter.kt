package com.convoscribe.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for the device to be online again before a model download
 * retries (issue #10): returns at once when the default network has
 * internet, otherwise when one appears, or `false` after [timeoutMs].
 */
class NetworkWaiter(context: Context, private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private val connectivity = context.getSystemService<ConnectivityManager>()

    suspend fun awaitNetwork(): Boolean {
        val cm = connectivity ?: return true
        if (hasInternet(cm, cm.activeNetwork)) return true
        var callback: ConnectivityManager.NetworkCallback? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                cont.isActive
                            ) {
                                cont.resume(true)
                            }
                        }
                    }
                    callback = cb
                    cm.registerDefaultNetworkCallback(cb)
                }
            } ?: false
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    private fun hasInternet(cm: ConnectivityManager, network: Network?): Boolean {
        val capabilities = network?.let(cm::getNetworkCapabilities) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        /** How long one retry waits for the network to come back. */
        const val DEFAULT_TIMEOUT_MS = 10L * 60L * 1_000L
    }
}
