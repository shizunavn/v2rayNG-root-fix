package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.delay
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * [onHandover] is invoked on a background thread after the debounce window and may block.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val onHandover: () -> Unit,
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L

        // Plain android.util.Log: reload causes must stay readable even when the app's own log
        // level filters LogUtil.
        const val LOG_TAG = "NetworkMonitor"
    }

    private var upstream: Network? = null
    private var handoverJob: Job? = null
    private var registered = false

    /** Last IPv4 address seen on the upstream, used to tell a real address change from a callback storm. */
    private var upstreamAddress: String? = null

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = upstream
            val hadAddress = upstreamAddress != null
            upstream = network
            onUnderlyingNetworksChanged(arrayOf(network))
            // Switching network means the sockets the core holds are bound to an address that is
            // gone, so the reload is needed whenever the upstream moved. A first callback after a
            // start still needs it when an address was seen before, because that address belonged
            // to the network that just went away.
            if (previous != network && (previous != null || hadAddress)) {
                scheduleHandover(network)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // it's a good idea to refresh capabilities
            onUnderlyingNetworksChanged(arrayOf(network))
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network != upstream) return
            // A carrier NAT lease change keeps the same Network object and interface, so neither
            // onAvailable nor onLost fires; only the address moves and every established socket
            // dies silently. This callback is the event for exactly that, so react on a changed
            // address alone. Capabilities and routes change constantly, hence the narrow test.
            val address = linkProperties.linkAddresses
                .firstOrNull { it.address.hostAddress?.contains(':') == false }
                ?.address
                ?.hostAddress
            if (address == null || address == upstreamAddress) return

            val previous = upstreamAddress
            upstreamAddress = address
            if (previous == null) {
                Log.i(LOG_TAG, "upstream address $address")
                return
            }
            Log.i(LOG_TAG, "upstream address changed $previous -> $address")
            scheduleHandover(network)
        }

        override fun onLost(network: Network) {
            if (network == upstream) upstreamAddress = null
            onUnderlyingNetworksChanged(null)
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        if (registered) return
        try {
            connectivity.requestNetwork(request, callback)
            registered = true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to request network", e)
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        handoverJob?.cancel()
        handoverJob = null
        upstream = null
        if (!registered) return
        registered = false
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: Failed to unregister callback", e)
        }
    }

    private fun scheduleHandover(network: Network) {
        Log.i(LOG_TAG, "handover scheduled for $network")
        handoverJob?.cancel()
        handoverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(HANDOVER_DEBOUNCE_MS)
                Log.i(LOG_TAG, "reloading core after handover")
                onHandover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(LOG_TAG, "handover reload failed: ${e.message ?: e.javaClass.simpleName}")
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle upstream change", e)
            }
        }
    }
}
