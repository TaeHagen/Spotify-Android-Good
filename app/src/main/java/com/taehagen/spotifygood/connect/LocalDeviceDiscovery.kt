package com.taehagen.spotifygood.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.taehagen.spotifygood.model.DeviceType
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet6Address
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** `connect.localInfo` result (docs/ARCHITECTURE.md §6.2). Key material stays in Rust. */
@Serializable
data class LocalDeviceInfo(
    val deviceId: String,
    val remoteName: String = "",
    val deviceType: String = "unknown",
    val activeUser: String? = null,
    val tokenTypes: List<String> = emptyList(),
    val supportsAccessToken: Boolean = false,
    val version: String = "",
    val brand: String? = null,
    val model: String? = null,
    val isGroup: Boolean = false,
    val availability: String? = null,
)

@Serializable
internal data class LocalLoginResult(val deviceId: String)

/** A Spotify Connect receiver found on the LAN that is not yet in the account's cluster. */
data class LocalConnectDevice(
    val deviceId: String,
    val name: String,
    val type: DeviceType,
    val url: String,
    val isGroup: Boolean = false,
    val brand: String? = null,
    val model: String? = null,
)

/**
 * Browses the local network for Spotify Connect receivers (`_spotify-connect._tcp`) and probes
 * each with `connect.localInfo`, so the user can log one into their account (the "send" side,
 * docs/ARCHITECTURE.md §8).
 *
 * Battery: discovery runs ONLY between [start] and [stop] (the devices sheet drives this). A Wi-Fi
 * [WifiManager.MulticastLock] is held only while discovering — mDNS packets are multicast and Wi-Fi
 * drops them otherwise. mDNS lives entirely here (`NsdManager`); Rust only ever gets a URL.
 *
 * Thread-safety: [start]/[stop] are called from the main thread; `NsdManager` callbacks arrive on
 * its own threads. All mutable state is guarded by [lock]; RPC probes run on [session]'s scope.
 */
class LocalDeviceDiscovery(
    context: Context,
    private val rpc: NativeRpc,
    private val clusterDeviceIds: () -> Set<String>,
) {
    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? = runCatching { appContext.getSystemService(NsdManager::class.java) }.getOrNull()
    private val multicastLock: WifiManager.MulticastLock? = try {
        appContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock(LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    } catch (e: RuntimeException) {
        Log.w(TAG, "No multicast lock available", e)
        null
    }

    private val lock = Any()
    private val _devices = MutableStateFlow<List<LocalConnectDevice>>(emptyList())

    /** Receivers found so far, deduped by deviceId, excluding ones already in the cluster. */
    val devices: StateFlow<List<LocalConnectDevice>> = _devices.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    // All state below is guarded by `lock`.
    private var session: Session? = null

    /** One discovery run: its coroutine scope, NSD listener and resolve bookkeeping. */
    private inner class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var listener: NsdManager.DiscoveryListener? = null
        /** deviceId → device, in discovery order (the published list). */
        val byDeviceId = LinkedHashMap<String, LocalConnectDevice>()
        /** serviceName → deviceId once resolved+probed (so a repeat update is ignored). */
        val probed = HashMap<String, String?>()
        /** API < 34: NsdManager resolves one service at a time. */
        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        var resolveBusy = false
        /** API 34+: serviceName → registered info callback, to unregister on loss/stop. */
        val infoCallbacks = HashMap<String, Any>()
    }

    /** Starts discovery if not already running. Idempotent. */
    fun start() {
        val manager = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable; local discovery disabled")
            return
        }
        val listener = synchronized(lock) {
            if (session != null) return
            val s = Session()
            session = s
            _devices.value = emptyList()
            acquireLock()
            val l = discoveryListener(s)
            s.listener = l
            l
        }
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            _discovering.value = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "discoverServices failed", e)
            stop()
        }
    }

    /** Stops discovery and releases the multicast lock. Idempotent. */
    fun stop() {
        val s = synchronized(lock) { session.also { session = null } } ?: return
        _discovering.value = false
        val manager = nsdManager
        s.listener?.let { listener ->
            runCatching { manager?.stopServiceDiscovery(listener) }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            synchronized(lock) { s.infoCallbacks.values.toList() }.forEach { cb ->
                runCatching { manager?.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
            }
        }
        s.scope.cancel()
        releaseLock()
        _devices.value = emptyList()
    }

    /**
     * Logs the device at [url] into this account via `connect.localLogin`. Returns the Connect
     * device id to transfer to. Throws NativeException on failure.
     */
    suspend fun login(device: LocalConnectDevice): String {
        val result: LocalLoginResult = rpc.call(
            "connect.localLogin",
            buildJsonObject {
                put("url", device.url)
                put("deviceId", device.deviceId)
            },
        )
        return result.deviceId
    }

    private fun discoveryListener(s: Session) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "start discovery failed: $errorCode")
            stop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "stop discovery failed: $errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!isCurrent(s)) return
            if (Build.VERSION.SDK_INT >= 34) registerInfoCallback(s, serviceInfo) else enqueueResolve(s, serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            if (!isCurrent(s)) return
            removeService(s, serviceInfo.serviceName)
        }
    }

    // --- API 34+: continuous service-info callback ------------------------------------------------

    private fun registerInfoCallback(s: Session, serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < 34) return
        val name = serviceInfo.serviceName
        synchronized(lock) {
            if (!isCurrent(s) || s.infoCallbacks.containsKey(name)) return
        }
        val executor = Executor { it.run() }
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "info callback registration failed: $errorCode")
            }

            override fun onServiceUpdated(info: NsdServiceInfo) {
                probe(s, info)
            }

            override fun onServiceLost() {
                removeService(s, name)
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        synchronized(lock) {
            if (!isCurrent(s)) return
            s.infoCallbacks[name] = callback
        }
        runCatching { nsdManager?.registerServiceInfoCallback(serviceInfo, executor, callback) }
            .onFailure { Log.w(TAG, "registerServiceInfoCallback failed", it) }
    }

    // --- API < 34: serialized resolve -------------------------------------------------------------

    private fun enqueueResolve(s: Session, serviceInfo: NsdServiceInfo) {
        synchronized(lock) {
            if (!isCurrent(s) || s.probed.containsKey(serviceInfo.serviceName)) return
            s.resolveQueue.add(serviceInfo)
            if (s.resolveBusy) return
            s.resolveBusy = true
        }
        resolveNext(s)
    }

    private fun resolveNext(s: Session) {
        val next = synchronized(lock) {
            if (!isCurrent(s)) return
            val n = s.resolveQueue.poll()
            if (n == null) {
                s.resolveBusy = false
            }
            n
        } ?: return

        @Suppress("DEPRECATION")
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                resolveNext(s)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                probe(s, serviceInfo)
                resolveNext(s)
            }
        }
        try {
            @Suppress("DEPRECATION")
            nsdManager?.resolveService(next, listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "resolveService failed", e)
            resolveNext(s)
        }
    }

    // --- probe + publish --------------------------------------------------------------------------

    private fun probe(s: Session, serviceInfo: NsdServiceInfo) {
        val url = urlOf(serviceInfo) ?: return
        synchronized(lock) {
            if (!isCurrent(s)) return
            // Already probed this service to a known device id: nothing new.
            if (s.probed[serviceInfo.serviceName] != null) return
        }
        s.scope.launch {
            val info = try {
                rpc.call<LocalDeviceInfo>("connect.localInfo", buildJsonObject { put("url", url) })
            } catch (e: Exception) {
                Log.d(TAG, "localInfo failed for $url: ${e.message}")
                return@launch
            }
            publish(s, serviceInfo.serviceName, info, url)
        }
    }

    private fun publish(s: Session, serviceName: String, info: LocalDeviceInfo, url: String) {
        if (info.deviceId.isBlank()) return
        val device = LocalConnectDevice(
            deviceId = info.deviceId,
            name = info.remoteName.ifBlank { info.model ?: info.deviceId },
            type = deviceType(info.deviceType),
            url = url,
            isGroup = info.isGroup,
            brand = info.brand,
            model = info.model,
        )
        val snapshot = synchronized(lock) {
            if (!isCurrent(s)) return
            s.probed[serviceName] = info.deviceId
            // Drop devices that are already in the cluster list; dedupe by deviceId.
            if (info.deviceId in clusterDeviceIds()) {
                s.byDeviceId.remove(info.deviceId)
            } else {
                s.byDeviceId[info.deviceId] = device
            }
            s.byDeviceId.values.toList()
        }
        _devices.value = snapshot
    }

    private fun removeService(s: Session, serviceName: String) {
        val snapshot = synchronized(lock) {
            if (!isCurrent(s)) return
            if (Build.VERSION.SDK_INT >= 34) {
                (s.infoCallbacks.remove(serviceName) as? NsdManager.ServiceInfoCallback)?.let { cb ->
                    runCatching { nsdManager?.unregisterServiceInfoCallback(cb) }
                }
            }
            val deviceId = s.probed.remove(serviceName)
            if (deviceId != null) s.byDeviceId.remove(deviceId)
            s.byDeviceId.values.toList()
        }
        _devices.value = snapshot
    }

    /** Re-filters the published list against the current cluster (devices may have just joined). */
    fun dropClusterDevices() {
        val ids = clusterDeviceIds()
        val snapshot = synchronized(lock) {
            val s = session ?: return
            ids.forEach { s.byDeviceId.remove(it) }
            s.byDeviceId.values.toList()
        }
        _devices.value = snapshot
    }

    private fun isCurrent(s: Session): Boolean = session === s

    private fun acquireLock() {
        try {
            multicastLock?.acquire()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Acquiring multicast lock failed", e)
        }
    }

    private fun releaseLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Releasing multicast lock failed", e)
        }
    }

    private fun urlOf(info: NsdServiceInfo): String? {
        val host = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.host
        } ?: return null
        val port = info.port
        if (port !in 1..65535) return null
        val raw = host.hostAddress ?: return null
        val address = if (host is Inet6Address) {
            // URLs can't carry an IPv6 zone id (fe80::1%wlan0); drop it and bracket the address.
            "[${raw.substringBefore('%')}]"
        } else {
            raw
        }
        val cpath = cPath(info)
        val path = if (cpath.startsWith("/")) cpath else "/$cpath"
        return "http://$address:$port$path"
    }

    private fun cPath(info: NsdServiceInfo): String {
        val attributes = runCatching { info.attributes }.getOrNull() ?: return "/"
        val entry = attributes.entries.firstOrNull { it.key.equals("CPath", ignoreCase = true) }
        val value = entry?.value?.toString(Charsets.UTF_8)?.trim()
        return if (value.isNullOrEmpty()) "/" else value
    }

    private fun deviceType(raw: String): DeviceType = when (raw.lowercase()) {
        "computer" -> DeviceType.COMPUTER
        "tablet" -> DeviceType.TABLET
        "smartphone" -> DeviceType.SMARTPHONE
        "speaker" -> DeviceType.SPEAKER
        "tv" -> DeviceType.TV
        "avr" -> DeviceType.AVR
        "stb" -> DeviceType.STB
        "audio_dongle" -> DeviceType.AUDIO_DONGLE
        "game_console" -> DeviceType.GAME_CONSOLE
        "cast_audio" -> DeviceType.CAST_AUDIO
        "cast_video" -> DeviceType.CAST_VIDEO
        "automobile" -> DeviceType.AUTOMOBILE
        "smartwatch" -> DeviceType.SMARTWATCH
        "chromebook" -> DeviceType.CHROMEBOOK
        else -> DeviceType.SPEAKER
    }

    private companion object {
        const val TAG = "LocalDiscovery"
        const val LOCK_TAG = "spotifygood:localconnect"
        const val SERVICE_TYPE = "_spotify-connect._tcp"
    }
}
