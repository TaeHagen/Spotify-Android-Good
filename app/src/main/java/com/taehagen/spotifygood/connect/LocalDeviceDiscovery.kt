package com.taehagen.spotifygood.connect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
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
    /** Interface index for a link-local IPv6 [url] (a URL can't carry the zone); null otherwise. */
    val scopeId: Int? = null,
)

/** Where to reach a resolved ZeroConf service: its URL, plus the interface for link-local IPv6. */
internal data class ServiceTarget(val url: String, val scopeId: Int?)

/** Pure address handling for resolved services (JVM-testable). */
internal object ServiceAddress {
    /**
     * The address to use: IPv4 first (always routable on the LAN), then a non-link-local IPv6,
     * then a link-local one (only usable with a scope).
     */
    fun pick(addresses: List<InetAddress>): InetAddress? =
        addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull { !it.isLinkLocalAddress }
            ?: addresses.firstOrNull()

    /** The `CPath` TXT record as a URL path (`/` when absent or blank). */
    fun path(cpath: String?): String {
        val value = cpath?.trim()
        return when {
            value.isNullOrEmpty() -> "/"
            value.startsWith("/") -> value
            else -> "/$value"
        }
    }

    /** Looks the `CPath` TXT key up case-insensitively. */
    fun cPath(attributes: Map<String, ByteArray?>): String? =
        attributes.entries.firstOrNull { it.key.equals("CPath", ignoreCase = true) }?.value?.toString(Charsets.UTF_8)

    /**
     * The target for [host]:[port]. A link-local IPv6 host needs an interface: its own scope id,
     * else [lanInterfaceIndex] (the legacy NSD backend drops the zone). Without one the service
     * can't be reached and null is returned.
     */
    fun target(host: InetAddress, port: Int, cpath: String?, lanInterfaceIndex: () -> Int?): ServiceTarget? {
        if (port !in 1..65535) return null
        val raw = host.hostAddress ?: return null
        val scopeId = if (host is Inet6Address && host.isLinkLocalAddress) {
            host.scopeId.takeIf { it > 0 } ?: lanInterfaceIndex()?.takeIf { it > 0 } ?: return null
        } else {
            null
        }
        // URLs can't carry an IPv6 zone (fe80::1%wlan0); it travels as scopeId instead.
        val address = if (host is Inet6Address) "[${raw.substringBefore('%')}]" else raw
        return ServiceTarget("http://$address:$port${path(cpath)}", scopeId)
    }
}

/**
 * Browses the local network for Spotify Connect receivers (`_spotify-connect._tcp`) and probes
 * each with `connect.localInfo`, so the user can log one into their account (the "send" side,
 * docs/ARCHITECTURE.md §8).
 *
 * Battery: browsing runs ONLY between [start] and [pause]/[stop] (the devices sheet drives this
 * while it is open and STARTED). A Wi-Fi [WifiManager.MulticastLock] is held only while browsing.
 * mDNS lives entirely here (`NsdManager`); Rust only ever gets a URL.
 *
 * Results survive a [pause] (a brief trip to the background): the next [start] shows them at once
 * and drops the ones the new browse doesn't confirm within [CARRY_OVER_GRACE_MS]. [stop] and
 * [clear] forget them (logout, a new sheet).
 *
 * Thread-safety: `NsdManager` callbacks arrive on its own threads; all mutable state, including
 * every update of [devices], is guarded by [lock]. RPC probes run on the session's scope.
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

    /** Receivers found, deduped by deviceId, excluding ones already in the cluster. */
    val devices: StateFlow<List<LocalConnectDevice>> = _devices.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    // ---- guarded by `lock` ----
    private var session: Session? = null

    /**
     * API < 34: the platform resolves ONE service per app at a time, across browse sessions (a
     * stopped browse does not cancel its resolve, and there is no stopServiceResolution below 34).
     * So the queue and the in-flight resolve belong to this instance, not to a [Session].
     */
    private val legacyQueue = ArrayDeque<PendingResolve>()
    private var legacyInFlight = 0L
    private var legacyWatchdog: Job? = null
    private var nextResolveId = 0L

    /** Timers for legacy resolves (they may outlive a session); idle unless a resolve is pending. */
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class PendingResolve(val session: Any, val info: NsdServiceInfo, val attempt: Int)

    /** One browse run: its coroutine scope, NSD listener and bookkeeping. */
    private inner class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var listener: NsdManager.DiscoveryListener? = null
        /** deviceId → device, in discovery order (the published list). */
        val byDeviceId = LinkedHashMap<String, LocalConnectDevice>()
        /** Carried over from before a pause and not yet seen by this browse. */
        val unconfirmed = HashSet<String>()
        /** serviceName → deviceId once probed (a repeated update is ignored). */
        val probed = HashMap<String, String>()
        /** serviceNames with a probe in flight. */
        val probing = HashSet<String>()
        /** API 34+: serviceName → registered info callback, to unregister on loss/stop. */
        val infoCallbacks = HashMap<String, Any>()
    }

    /** Starts browsing if not already running. Idempotent. */
    fun start() {
        val manager = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable; local discovery disabled")
            return
        }
        val (s, listener) = synchronized(lock) {
            if (session != null) return
            val s = Session()
            session = s
            // Keep what a paused run found, until this run confirms or drops it.
            for (device in _devices.value) {
                s.byDeviceId[device.deviceId] = device
                s.unconfirmed += device.deviceId
            }
            val listener = discoveryListener(s)
            s.listener = listener
            s to listener
        }
        if (s.unconfirmed.isNotEmpty()) {
            s.scope.launch {
                delay(CARRY_OVER_GRACE_MS)
                synchronized(lock) {
                    if (!isCurrent(s)) return@synchronized
                    s.unconfirmed.forEach { s.byDeviceId.remove(it) }
                    s.unconfirmed.clear()
                    _devices.value = s.byDeviceId.values.toList()
                }
            }
        }
        acquireLock()
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            _discovering.value = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "discoverServices failed", e)
            pause()
        }
    }

    /** Stops browsing and releases the multicast lock, keeping the results. Idempotent. */
    fun pause() {
        val s = synchronized(lock) {
            val s = session ?: return
            session = null
            // Queued legacy resolves of this run are dropped; one already in flight keeps the
            // platform's slot until its callback (or the watchdog) frees it.
            legacyQueue.removeAll { it.session === s }
            s
        }
        _discovering.value = false
        val manager = nsdManager
        s.listener?.let { listener -> runCatching { manager?.stopServiceDiscovery(listener) } }
        if (Build.VERSION.SDK_INT >= 34) {
            synchronized(lock) { s.infoCallbacks.values.toList() }.forEach { cb ->
                runCatching { manager?.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
            }
        }
        s.scope.cancel()
        releaseLock()
    }

    /** Stops browsing and forgets the results (logout, sheet gone for good). Idempotent. */
    fun stop() {
        pause()
        clear()
    }

    /** Forgets the results (a new sheet starts from scratch). */
    fun clear() {
        synchronized(lock) {
            session?.let { s ->
                s.byDeviceId.clear()
                s.unconfirmed.clear()
                s.probed.clear()
            }
            _devices.value = emptyList()
        }
    }

    /**
     * Logs [device] into this account via `connect.localLogin`. Returns the Connect device id to
     * transfer to. Throws NativeException on failure.
     */
    suspend fun login(device: LocalConnectDevice): String {
        val result: LocalLoginResult = rpc.call(
            "connect.localLogin",
            buildJsonObject {
                put("url", device.url)
                put("deviceId", device.deviceId)
                putScope(device.scopeId)
            },
        )
        return result.deviceId
    }

    private fun JsonObjectBuilder.putScope(scopeId: Int?) {
        if (scopeId != null) put("scopeId", scopeId)
    }

    private fun discoveryListener(s: Session) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "start discovery failed: $errorCode")
            if (isCurrent(s)) pause()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "stop discovery failed: $errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!isCurrent(s)) return
            if (Build.VERSION.SDK_INT >= 34) registerInfoCallback(s, serviceInfo) else enqueueResolve(s, serviceInfo, attempt = 0)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            removeService(s, serviceInfo.serviceName)
        }
    }

    // --- API 34+: service-info callback (unchanged behaviour) --------------------------------------

    private fun registerInfoCallback(s: Session, serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < 34) return
        val name = serviceInfo.serviceName
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "info callback registration failed: $errorCode")
            }

            override fun onServiceUpdated(info: NsdServiceInfo) {
                probe(s, info, info.hostAddresses)
            }

            override fun onServiceLost() {
                removeService(s, name)
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        synchronized(lock) {
            if (!isCurrent(s) || s.infoCallbacks.containsKey(name)) return
            s.infoCallbacks[name] = callback
        }
        runCatching { nsdManager?.registerServiceInfoCallback(serviceInfo, Executor { it.run() }, callback) }
            .onFailure { Log.w(TAG, "registerServiceInfoCallback failed", it) }
    }

    // --- API < 34: one resolve at a time, across sessions -----------------------------------------

    private fun enqueueResolve(s: Session, serviceInfo: NsdServiceInfo, attempt: Int) {
        synchronized(lock) {
            val name = serviceInfo.serviceName
            if (!isCurrent(s) || s.probed.containsKey(name)) return
            if (legacyQueue.any { it.session === s && it.info.serviceName == name }) return
            legacyQueue.add(PendingResolve(s, serviceInfo, attempt))
        }
        drainLegacy()
    }

    /** Starts the next queued resolve of the current session, unless one is still in flight. */
    private fun drainLegacy() {
        val manager = nsdManager ?: return
        val (pending, id) = synchronized(lock) {
            if (legacyInFlight != 0L) return
            // Entries of an older session are dropped on the way.
            val next = generateSequence { legacyQueue.poll() }.firstOrNull { it.session === session } ?: return
            val id = ++nextResolveId
            legacyInFlight = id
            legacyWatchdog = watchdogScope.launch {
                delay(RESOLVE_WATCHDOG_MS)
                // A resolve that never calls back would hold the slot forever: give up on it. The
                // platform may still be busy; a new resolve then gets FAILURE_ALREADY_ACTIVE and
                // is retried with a backoff.
                val gaveUp = synchronized(lock) {
                    (legacyInFlight == id).also { if (it) legacyInFlight = 0L }
                }
                if (gaveUp) {
                    Log.w(TAG, "legacy resolve $id timed out")
                    drainLegacy()
                }
            }
            next to id
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                finishLegacy(id)
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    retryLater(pending)
                } else {
                    Log.d(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                }
                drainLegacy()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                finishLegacy(id)
                val s = synchronized(lock) { session?.takeIf { it === pending.session } }
                @Suppress("DEPRECATION")
                val host = serviceInfo.host
                if (s != null) probe(s, serviceInfo, listOfNotNull(host))
                drainLegacy()
            }
        }
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(pending.info, listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "resolveService failed", e)
            finishLegacy(id)
            drainLegacy()
        }
    }

    /** Frees the slot if [id] still holds it (a late callback after the watchdog does not). */
    private fun finishLegacy(id: Long) {
        synchronized(lock) {
            if (legacyInFlight == id) {
                legacyInFlight = 0L
                legacyWatchdog?.cancel()
                legacyWatchdog = null
            }
        }
    }

    /** Another resolve still holds the platform slot: try again shortly, a bounded number of times. */
    private fun retryLater(pending: PendingResolve) {
        val s = synchronized(lock) { session?.takeIf { it === pending.session } } ?: return
        if (pending.attempt + 1 >= MAX_RESOLVE_ATTEMPTS) {
            Log.w(TAG, "giving up resolving ${pending.info.serviceName}")
            return
        }
        s.scope.launch {
            delay(RESOLVE_RETRY_DELAY_MS * (pending.attempt + 1))
            enqueueResolve(s, pending.info, pending.attempt + 1)
        }
    }

    // --- probe + publish --------------------------------------------------------------------------

    private fun probe(s: Session, serviceInfo: NsdServiceInfo, addresses: List<InetAddress>) {
        val name = serviceInfo.serviceName
        val host = ServiceAddress.pick(addresses) ?: return
        val cpath = runCatching { serviceInfo.attributes }.getOrNull()?.let(ServiceAddress::cPath)
        val target = ServiceAddress.target(host, serviceInfo.port, cpath, ::lanInterfaceIndex) ?: run {
            Log.d(TAG, "no usable address for $name")
            return
        }
        synchronized(lock) {
            if (!isCurrent(s) || s.probed.containsKey(name) || !s.probing.add(name)) return
        }
        s.scope.launch {
            try {
                val info = rpc.call<LocalDeviceInfo>(
                    "connect.localInfo",
                    buildJsonObject {
                        put("url", target.url)
                        putScope(target.scopeId)
                    },
                )
                publish(s, name, info, target)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.d(TAG, "localInfo failed for ${target.url}: ${e.message}")
            } finally {
                synchronized(lock) { s.probing.remove(name) }
            }
        }
    }

    private fun publish(s: Session, serviceName: String, info: LocalDeviceInfo, target: ServiceTarget) {
        if (info.deviceId.isBlank()) return
        val device = LocalConnectDevice(
            deviceId = info.deviceId,
            name = info.remoteName.ifBlank { info.model ?: info.deviceId },
            type = deviceType(info.deviceType),
            url = target.url,
            isGroup = info.isGroup,
            brand = info.brand,
            model = info.model,
            scopeId = target.scopeId,
        )
        val inCluster = info.deviceId in clusterDeviceIds()
        synchronized(lock) {
            if (!isCurrent(s)) return
            s.probed[serviceName] = info.deviceId
            s.unconfirmed.remove(info.deviceId)
            // Drop devices that are already in the cluster list; dedupe by deviceId.
            if (inCluster) s.byDeviceId.remove(info.deviceId) else s.byDeviceId[info.deviceId] = device
            _devices.value = s.byDeviceId.values.toList()
        }
    }

    private fun removeService(s: Session, serviceName: String) {
        synchronized(lock) {
            if (!isCurrent(s)) return
            if (Build.VERSION.SDK_INT >= 34) {
                (s.infoCallbacks.remove(serviceName) as? NsdManager.ServiceInfoCallback)?.let { cb ->
                    runCatching { nsdManager?.unregisterServiceInfoCallback(cb) }
                }
            }
            legacyQueue.removeAll { it.session === s && it.info.serviceName == serviceName }
            val deviceId = s.probed.remove(serviceName)
            // Another service (a second interface) may still announce the same device.
            if (deviceId != null && deviceId !in s.probed.values) s.byDeviceId.remove(deviceId)
            _devices.value = s.byDeviceId.values.toList()
        }
    }

    private fun isCurrent(s: Session): Boolean = synchronized(lock) { session === s }

    /** The Wi-Fi / Ethernet interface index: the zone of a link-local host from legacy NSD. */
    private fun lanInterfaceIndex(): Int? {
        return try {
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!lan) return null
            val name = cm.getLinkProperties(network)?.interfaceName ?: return null
            NetworkInterface.getByName(name)?.index?.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.d(TAG, "no LAN interface index", e)
            null
        }
    }

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

    internal companion object {
        private const val TAG = "LocalDiscovery"
        private const val LOCK_TAG = "spotifygood:localconnect"
        private const val SERVICE_TYPE = "_spotify-connect._tcp"
        /** Results kept across a pause are dropped if a new browse doesn't confirm them by then. */
        const val CARRY_OVER_GRACE_MS = 12_000L
        /** A legacy resolve without a callback by then frees the queue. */
        const val RESOLVE_WATCHDOG_MS = 10_000L
        const val RESOLVE_RETRY_DELAY_MS = 300L
        const val MAX_RESOLVE_ATTEMPTS = 5
    }
}
