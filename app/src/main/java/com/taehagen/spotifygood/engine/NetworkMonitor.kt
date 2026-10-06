package com.taehagen.spotifygood.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connectivity of the default network as the engine sees it. */
data class NetworkStatus(val available: Boolean, val metered: Boolean)

/**
 * Tracks the default network with `ConnectivityManager.registerDefaultNetworkCallback`.
 * The callback is registered only between [start] and [stop] (the engine does that while it
 * runs); outside that window [status] keeps its last value and nothing is observed.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _status = MutableStateFlow(NetworkStatus(available = true, metered = false))
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Registers the callback (idempotent) after taking a synchronous snapshot of the current state. */
    fun start() {
        synchronized(lock) {
            if (callback != null || connectivity == null) return
            _status.value = currentStatus()
            val cb = DefaultNetworkCallback()
            try {
                connectivity.registerDefaultNetworkCallback(cb)
                callback = cb
            } catch (e: RuntimeException) {
                // SecurityException on some Android 11 builds, TooManyRequestsException when the
                // per-app callback limit is hit. Without a callback we just keep the snapshot.
                Log.w(TAG, "registerDefaultNetworkCallback failed", e)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val cb = callback ?: return
            callback = null
            runCatching { connectivity?.unregisterNetworkCallback(cb) }
                .onFailure { Log.w(TAG, "unregisterNetworkCallback failed", it) }
        }
    }

    private fun currentStatus(): NetworkStatus {
        val cm = connectivity ?: return NetworkStatus(available = true, metered = false)
        return try {
            val network = cm.activeNetwork ?: return NetworkStatus(available = false, metered = false)
            statusOf(cm.getNetworkCapabilities(network))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Reading network state failed", e)
            _status.value
        }
    }

    private inner class DefaultNetworkCallback : ConnectivityManager.NetworkCallback() {
        // Only touched on the ConnectivityManager callback thread.
        private var current: Network? = null

        override fun onAvailable(network: Network) {
            current = network
            // onCapabilitiesChanged follows immediately with the real capabilities.
            _status.value = _status.value.copy(available = true)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (current != null && network != current) return
            current = network
            _status.value = statusOf(networkCapabilities)
        }

        override fun onLost(network: Network) {
            if (network != current) return
            current = null
            _status.value = _status.value.copy(available = false)
        }
    }

    private companion object {
        const val TAG = "NetworkMonitor"

        fun statusOf(caps: NetworkCapabilities?): NetworkStatus {
            if (caps == null) return NetworkStatus(available = false, metered = false)
            val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val notSuspended = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
            return NetworkStatus(available = internet && notSuspended, metered = !unmetered)
        }
    }
}
