package com.taehagen.spotifygood.connect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.taehagen.spotifygood.model.ConnectDevice
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

/** How a LAN device is signed in to the account. */
sealed interface LocalEndpoint {
    /** The interface index for a link-local IPv6 host (an address string can't carry the zone). */
    val scopeId: Int?

    /** The address the device was found at (no zone), to match one device's two services. */
    val host: String

    /** A Spotify Connect ZeroConf service: `connect.localLogin` at [url]. */
    data class ZeroConf(val url: String, override val host: String, override val scopeId: Int? = null) : LocalEndpoint

    /**
     * A Google Cast device (`_googlecast._tcp`): `connect.castLogin` at [host]:[port]. [castId] is
     * its TXT `id` (stable per device, unlike its name).
     */
    data class Cast(
        override val host: String,
        val port: Int,
        val castId: String,
        override val scopeId: Int? = null,
    ) : LocalEndpoint
}

/** A Spotify Connect receiver or Google Cast device found on the LAN, not yet in the account's cluster. */
data class LocalConnectDevice(
    /** ZeroConf: its getInfo `deviceID`. Cast: the Connect id it will register as ([CastServices.connectDeviceId]). */
    val deviceId: String,
    val name: String,
    val type: DeviceType,
    val endpoint: LocalEndpoint,
    val isGroup: Boolean = false,
    val brand: String? = null,
    val model: String? = null,
) {
    /** Unique in the LAN list: one soundbar can be a ZeroConf and a Cast device at once. */
    val key: String
        get() = when (endpoint) {
            is LocalEndpoint.ZeroConf -> deviceId
            is LocalEndpoint.Cast -> "cast:${endpoint.castId}"
        }

    val isCast: Boolean get() = endpoint is LocalEndpoint.Cast
}

/** Where to reach a resolved ZeroConf service: its URL, plus the interface for link-local IPv6. */
internal data class ServiceTarget(val url: String, val scopeId: Int?, val host: String = "")

/** Pure address handling for resolved services (JVM-testable). */
internal object ServiceAddress {
    /**
     * Mirrors Rust `zeroconf_client::http::is_local_ip`, the only hosts `connect.local*` accepts:
     * IPv4 loopback / RFC 1918 / link-local (169.254/16), IPv6 loopback / unique-local
     * (fc00::/7) / link-local (fe80::/10, which also needs a scope, see [target]), and
     * IPv4-mapped IPv6 by its IPv4 address. Global addresses are always refused there.
     */
    fun isLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
        is Inet6Address -> {
            val b = address.address
            val mapped = b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } &&
                b[10].toInt() == -1 && b[11].toInt() == -1
            when {
                mapped -> isLocal(InetAddress.getByAddress(b.copyOfRange(12, 16)))
                else -> address.isLoopbackAddress || isUniqueLocal(address) || address.isLinkLocalAddress
            }
        }
        else -> false
    }

    private fun isUniqueLocal(address: Inet6Address): Boolean = (address.address[0].toInt() and 0xFE) == 0xFC

    /** Preference rank of an accepted address (lower first); null when Rust would refuse it. */
    private fun rank(address: InetAddress): Int? = when {
        !isLocal(address) -> null
        address is Inet4Address -> 0
        address.isLinkLocalAddress -> 2 // only usable with a scope
        else -> 1 // unique-local, loopback, IPv4-mapped
    }

    /**
     * The addresses worth probing, best first: IPv4 (always routable on the LAN), then a
     * unique-local IPv6, then a link-local one. Never a global IPv6 (or a public IPv4).
     */
    fun candidates(addresses: List<InetAddress>): List<InetAddress> =
        addresses.mapNotNull { a -> rank(a)?.let { a to it } }.sortedBy { it.second }.map { it.first }.distinct()

    /** The best address to use, or null when none is acceptable. */
    fun pick(addresses: List<InetAddress>): InetAddress? = candidates(addresses).firstOrNull()

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
        val (raw, scopeId) = hostAndScope(host, lanInterfaceIndex) ?: return null
        // URLs can't carry an IPv6 zone (fe80::1%wlan0); it travels as scopeId instead.
        val address = if (host is Inet6Address) "[$raw]" else raw
        return ServiceTarget("http://$address:$port${path(cpath)}", scopeId, raw)
    }

    /**
     * [host] as an address literal without a zone, plus the interface a link-local IPv6 host needs
     * (its own scope id, else [lanInterfaceIndex]); null when such a host has no interface.
     */
    fun hostAndScope(host: InetAddress, lanInterfaceIndex: () -> Int?): Pair<String, Int?>? {
        val raw = host.hostAddress?.substringBefore('%') ?: return null
        val scopeId = if (host is Inet6Address && host.isLinkLocalAddress) {
            host.scopeId.takeIf { it > 0 } ?: lanInterfaceIndex()?.takeIf { it > 0 } ?: return null
        } else {
            null
        }
        return raw to scopeId
    }
}

/** The TXT record of a `_googlecast._tcp` service. */
internal data class CastRecord(
    /** `fn`: the name the user gave the device. */
    val friendlyName: String,
    /** `md`: the model ("Nest Audio", "Chromecast", "Google Cast Group"). */
    val model: String?,
    /** `id`: the device's Cast id. */
    val id: String?,
    /** `ca`: the capability bit mask. */
    val capabilities: Int?,
)

/**
 * Pure handling of Google Cast services (JVM-testable): TXT parsing, the Connect id a Cast
 * device gets, and which LAN entries to show next to the account's cluster.
 */
internal object CastServices {
    /** The `md` of a Cast speaker group. */
    const val GROUP_MODEL = "Google Cast Group"
    private const val CAPABILITY_VIDEO_OUT = 1
    private const val CAPABILITY_MULTIZONE_GROUP = 32

    /** Parses the TXT [attributes] (keys case-insensitive); null without a friendly name. */
    fun parse(attributes: Map<String, ByteArray?>): CastRecord? {
        fun value(key: String): String? = attributes.entries
            .firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
        val name = value("fn") ?: return null
        return CastRecord(name, value("md"), value("id"), value("ca")?.toIntOrNull())
    }

    fun isGroup(record: CastRecord): Boolean =
        record.model.equals(GROUP_MODEL, ignoreCase = true) ||
            ((record.capabilities ?: 0) and CAPABILITY_MULTIZONE_GROUP) != 0

    /** A device with video out (a TV, Chromecast) or a speaker. */
    fun deviceType(record: CastRecord): DeviceType =
        if (((record.capabilities ?: 0) and CAPABILITY_VIDEO_OUT) != 0) DeviceType.TV else DeviceType.SPEAKER

    /**
     * The Connect device id of a Cast device: the MD5 of its friendly name in lowercase hex, as
     * Rust `cast_client::connect_device_id` computes it (the id the Spotify receiver is given).
     */
    fun connectDeviceId(friendlyName: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(friendlyName.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The LAN entry for a Cast service at [host]:[port]. */
    fun device(record: CastRecord, serviceName: String, host: String, port: Int, scopeId: Int?): LocalConnectDevice {
        val group = isGroup(record)
        return LocalConnectDevice(
            deviceId = connectDeviceId(record.friendlyName),
            name = record.friendlyName,
            type = deviceType(record),
            endpoint = LocalEndpoint.Cast(host, port, record.id ?: serviceName, scopeId),
            isGroup = group,
            model = record.model.takeUnless { group },
        )
    }

    private fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * The LAN entries worth showing: none already in the account's cluster ([cluster]), and no Cast
     * entry for a device that is also found as a ZeroConf device (one soundbar can advertise both;
     * its ZeroConf login is native, so that one stays). Ids differ between the two, so a Cast entry
     * matches by Connect id, by name, or (not for a group, which is hosted by one of its members)
     * by address.
     */
    fun visible(found: List<LocalConnectDevice>, cluster: List<ConnectDevice>): List<LocalConnectDevice> {
        val clusterIds = cluster.map { it.id }.toSet()
        val clusterIdsLower = clusterIds.map { it.lowercase() }.toSet()
        val zeroconf = found.filter { !it.isCast }
        return found.filter { device ->
            when (device.endpoint) {
                is LocalEndpoint.ZeroConf -> device.deviceId !in clusterIds
                is LocalEndpoint.Cast -> {
                    val inCluster = device.deviceId.lowercase() in clusterIdsLower || cluster.any { sameName(it.name, device.name) }
                    val twin = zeroconf.any { z ->
                        z.deviceId.equals(device.deviceId, ignoreCase = true) ||
                            sameName(z.name, device.name) ||
                            (!device.isGroup && z.endpoint.host.isNotEmpty() && z.endpoint.host == device.endpoint.host)
                    }
                    !inCluster && !twin
                }
            }
        }
    }
}

/**
 * Browses the local network for Spotify Connect receivers (`_spotify-connect._tcp`, probed with
 * `connect.localInfo`) and Google Cast devices (`_googlecast._tcp`, read from their TXT record),
 * so the user can sign one in to their account (the "send" side, docs/ARCHITECTURE.md §8).
 *
 * Battery: browsing runs ONLY between [start] and [pause]/[stop] (the devices sheet drives this
 * while it is open and STARTED). Both service types are browsed by the same run, under one Wi-Fi
 * [WifiManager.MulticastLock] held only while browsing, and below API 34 they share the one
 * resolve slot. mDNS lives entirely here (`NsdManager`); Rust only ever gets an address.
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

    /**
     * Devices found, one entry per [LocalConnectDevice.key]; ZeroConf devices already in the
     * cluster are left out. [CastServices.visible] does the rest of the dedupe for display.
     */
    val devices: StateFlow<List<LocalConnectDevice>> = _devices.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    /** The two browsed service types. */
    private enum class Kind(val serviceType: String) {
        ZEROCONF("_spotify-connect._tcp"),
        CAST("_googlecast._tcp"),
    }

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

    private class PendingResolve(val session: Any, val kind: Kind, val info: NsdServiceInfo, val attempt: Int)

    /** One browse run: its coroutine scope, NSD listeners and bookkeeping. */
    private inner class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        /** The browses that are running (one per [Kind]; a failed start drops its entry). */
        val listeners = HashMap<Kind, NsdManager.DiscoveryListener>()
        /** device key → device, in discovery order (the published list). */
        val byKey = LinkedHashMap<String, LocalConnectDevice>()
        /** Keys carried over from before a pause and not yet seen by this browse. */
        val unconfirmed = HashSet<String>()
        /** service key ([serviceKey]) → device key once probed (a repeated ZeroConf update is ignored). */
        val probed = HashMap<String, String>()
        /** Service keys with a probe in flight. */
        val probing = HashSet<String>()
        /** The latest update that arrived while its service was being probed (run if that fails). */
        val pendingProbe = HashMap<String, Pair<NsdServiceInfo, List<InetAddress>>>()
        /** API 34+: service key → registered info callback, to unregister on loss/stop. */
        val infoCallbacks = HashMap<String, Any>()
    }

    /** A service name is unique per type only (a soundbar may use one name for both). */
    private fun serviceKey(kind: Kind, serviceName: String) = "${kind.name}/$serviceName"

    /** Starts browsing if not already running. Idempotent. */
    fun start() {
        val manager = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable; local discovery disabled")
            return
        }
        val (s, listeners) = synchronized(lock) {
            if (session != null) return
            val s = Session()
            session = s
            // Keep what a paused run found, until this run confirms or drops it.
            for (device in _devices.value) {
                s.byKey[device.key] = device
                s.unconfirmed += device.key
            }
            Kind.entries.forEach { kind -> s.listeners[kind] = discoveryListener(s, kind) }
            s to s.listeners.toMap()
        }
        if (s.unconfirmed.isNotEmpty()) {
            s.scope.launch {
                delay(CARRY_OVER_GRACE_MS)
                synchronized(lock) {
                    if (!isCurrent(s)) return@synchronized
                    s.unconfirmed.forEach { s.byKey.remove(it) }
                    s.unconfirmed.clear()
                    _devices.value = s.byKey.values.toList()
                }
            }
        }
        acquireLock()
        var started = 0
        for ((kind, listener) in listeners) {
            try {
                manager.discoverServices(kind.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                started++
            } catch (e: RuntimeException) {
                Log.w(TAG, "discoverServices ${kind.serviceType} failed", e)
                synchronized(lock) { s.listeners.remove(kind) }
            }
        }
        if (started > 0) _discovering.value = true else pause()
    }

    /** Stops browsing and releases the multicast lock, keeping the results. Idempotent. */
    fun pause() {
        val (s, listeners) = synchronized(lock) {
            val s = session ?: return
            session = null
            // Queued legacy resolves of this run are dropped; one already in flight keeps the
            // platform's slot until its callback (or the watchdog) frees it.
            legacyQueue.removeAll { it.session === s }
            s to s.listeners.values.toList()
        }
        _discovering.value = false
        val manager = nsdManager
        listeners.forEach { listener -> runCatching { manager?.stopServiceDiscovery(listener) } }
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
                s.byKey.clear()
                s.unconfirmed.clear()
                s.probed.clear()
                s.pendingProbe.clear()
            }
            _devices.value = emptyList()
        }
    }

    /**
     * Signs [device] in to this account: `connect.localLogin` for a ZeroConf device,
     * `connect.castLogin` for a Google Cast device. Returns the Connect device id to transfer to.
     * Throws NativeException on failure.
     */
    suspend fun login(device: LocalConnectDevice): String {
        val result: LocalLoginResult = when (val endpoint = device.endpoint) {
            is LocalEndpoint.ZeroConf -> rpc.call(
                "connect.localLogin",
                buildJsonObject {
                    put("url", endpoint.url)
                    put("deviceId", device.deviceId)
                    putScope(endpoint.scopeId)
                },
            )
            is LocalEndpoint.Cast -> rpc.call(
                "connect.castLogin",
                buildJsonObject {
                    put("host", endpoint.host)
                    put("port", endpoint.port)
                    put("name", device.name)
                    put("isGroup", device.isGroup)
                    putScope(endpoint.scopeId)
                },
            )
        }
        return result.deviceId
    }

    private fun JsonObjectBuilder.putScope(scopeId: Int?) {
        if (scopeId != null) put("scopeId", scopeId)
    }

    private fun discoveryListener(s: Session, kind: Kind) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "start discovery of $serviceType failed: $errorCode")
            // The other browse may still run; with none left, stop (and release the lock).
            val noneLeft = synchronized(lock) {
                if (!isCurrent(s)) return
                if (s.listeners[kind] === this) s.listeners.remove(kind)
                s.listeners.isEmpty()
            }
            if (noneLeft) pause()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "stop discovery failed: $errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!isCurrent(s)) return
            if (Build.VERSION.SDK_INT >= 34) {
                registerInfoCallback(s, kind, serviceInfo)
            } else {
                enqueueResolve(s, kind, serviceInfo, attempt = 0)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            removeService(s, kind, serviceInfo.serviceName)
        }
    }

    /** A service resolved (either API): probe a ZeroConf device, read a Cast device's TXT. */
    private fun resolved(s: Session, kind: Kind, info: NsdServiceInfo, addresses: List<InetAddress>) {
        when (kind) {
            Kind.ZEROCONF -> probe(s, info, addresses)
            Kind.CAST -> publishCast(s, info, addresses)
        }
    }

    // --- API 34+: service-info callback (unchanged behaviour) --------------------------------------

    private fun registerInfoCallback(s: Session, kind: Kind, serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < 34) return
        val name = serviceInfo.serviceName
        val key = serviceKey(kind, name)
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "info callback registration failed: $errorCode")
            }

            override fun onServiceUpdated(info: NsdServiceInfo) {
                resolved(s, kind, info, info.hostAddresses)
            }

            override fun onServiceLost() {
                removeService(s, kind, name)
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        synchronized(lock) {
            if (!isCurrent(s) || s.infoCallbacks.containsKey(key)) return
            s.infoCallbacks[key] = callback
        }
        val manager = nsdManager ?: return
        val registered = runCatching { manager.registerServiceInfoCallback(serviceInfo, Executor { it.run() }, callback) }
            .onFailure { Log.w(TAG, "registerServiceInfoCallback failed", it) }
            .isSuccess
        // pause() may have run between recording the callback and the platform registering it:
        // its unregister then failed ("not registered") and nobody else would ever unregister.
        // Re-check after our own register, and undo it if this session or entry is gone.
        val stale = synchronized(lock) {
            val current = session === s && s.infoCallbacks[key] === callback
            if (!registered && s.infoCallbacks[key] === callback) s.infoCallbacks.remove(key) // allow a retry
            registered && !current
        }
        if (stale) runCatching { manager.unregisterServiceInfoCallback(callback) }
    }

    // --- API < 34: one resolve at a time, across sessions and service types ----------------------

    private fun enqueueResolve(s: Session, kind: Kind, serviceInfo: NsdServiceInfo, attempt: Int) {
        synchronized(lock) {
            val name = serviceInfo.serviceName
            // A resolved ZeroConf service is done; a Cast one is resolved again on a new find.
            if (!isCurrent(s) || (kind == Kind.ZEROCONF && s.probed.containsKey(serviceKey(kind, name)))) return
            if (legacyQueue.any { it.session === s && it.kind == kind && it.info.serviceName == name }) return
            legacyQueue.add(PendingResolve(s, kind, serviceInfo, attempt))
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
                if (s != null) resolved(s, pending.kind, serviceInfo, listOfNotNull(host))
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
            enqueueResolve(s, pending.kind, pending.info, pending.attempt + 1)
        }
    }

    // --- probe + publish --------------------------------------------------------------------------

    private fun probe(s: Session, serviceInfo: NsdServiceInfo, addresses: List<InetAddress>) {
        val name = serviceInfo.serviceName
        val key = serviceKey(Kind.ZEROCONF, name)
        val cpath = runCatching { serviceInfo.attributes }.getOrNull()?.let(ServiceAddress::cPath)
        val targets = ServiceAddress.candidates(addresses)
            .mapNotNull { ServiceAddress.target(it, serviceInfo.port, cpath, ::lanInterfaceIndex) }
        if (targets.isEmpty()) {
            Log.d(TAG, "no usable address for $name yet")
            return
        }
        synchronized(lock) {
            if (!isCurrent(s) || s.probed.containsKey(key)) return
            if (!s.probing.add(key)) {
                // A probe with older addresses is running: keep this update for when it fails.
                s.pendingProbe[key] = serviceInfo to addresses
                return
            }
        }
        s.scope.launch {
            try {
                // Best address first; the next one if a host refuses or doesn't answer.
                for (target in targets) {
                    val info = try {
                        rpc.call<LocalDeviceInfo>(
                            "connect.localInfo",
                            buildJsonObject {
                                put("url", target.url)
                                putScope(target.scopeId)
                            },
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "localInfo failed for ${target.url}: ${e.message}")
                        continue
                    }
                    publish(s, key, info, target)
                    break
                }
            } finally {
                val pending = synchronized(lock) {
                    s.probing.remove(key)
                    s.pendingProbe.remove(key)?.takeIf { session === s && !s.probed.containsKey(key) }
                }
                if (pending != null) probe(s, pending.first, pending.second)
            }
        }
    }

    private fun publish(s: Session, serviceKey: String, info: LocalDeviceInfo, target: ServiceTarget) {
        if (info.deviceId.isBlank()) return
        val device = LocalConnectDevice(
            deviceId = info.deviceId,
            name = info.remoteName.ifBlank { info.model ?: info.deviceId },
            type = deviceType(info.deviceType),
            endpoint = LocalEndpoint.ZeroConf(target.url, target.host, target.scopeId),
            isGroup = info.isGroup,
            brand = info.brand,
            model = info.model,
        )
        val inCluster = info.deviceId in clusterDeviceIds()
        synchronized(lock) {
            if (!isCurrent(s)) return
            s.probed[serviceKey] = device.key
            s.unconfirmed.remove(device.key)
            // Drop devices that are already in the cluster list; dedupe by deviceId.
            if (inCluster) s.byKey.remove(device.key) else s.byKey[device.key] = device
            _devices.value = s.byKey.values.toList()
        }
    }

    /**
     * A Cast service resolved or updated: its TXT record says everything the list needs, so there
     * is no probe (nothing connects to the device until the user taps it). Re-published on every
     * update, so a renamed or moved device stays current.
     */
    private fun publishCast(s: Session, serviceInfo: NsdServiceInfo, addresses: List<InetAddress>) {
        val name = serviceInfo.serviceName
        val record = runCatching { serviceInfo.attributes }.getOrNull()?.let(CastServices::parse) ?: run {
            Log.d(TAG, "no friendly name for Cast service $name")
            return
        }
        val port = serviceInfo.port.takeIf { it in 1..65535 } ?: return
        val (host, scopeId) = ServiceAddress.candidates(addresses)
            .firstNotNullOfOrNull { ServiceAddress.hostAndScope(it, ::lanInterfaceIndex) }
            ?: run {
                Log.d(TAG, "no usable address for Cast service $name yet")
                return
            }
        val device = CastServices.device(record, name, host, port, scopeId)
        synchronized(lock) {
            if (!isCurrent(s)) return
            val key = serviceKey(Kind.CAST, name)
            s.probed.put(key, device.key)?.takeIf { it != device.key }?.let { old ->
                if (old !in s.probed.values) s.byKey.remove(old)
            }
            s.unconfirmed.remove(device.key)
            s.byKey[device.key] = device
            _devices.value = s.byKey.values.toList()
        }
    }

    private fun removeService(s: Session, kind: Kind, serviceName: String) {
        val key = serviceKey(kind, serviceName)
        synchronized(lock) {
            if (!isCurrent(s)) return
            if (Build.VERSION.SDK_INT >= 34) {
                (s.infoCallbacks.remove(key) as? NsdManager.ServiceInfoCallback)?.let { cb ->
                    runCatching { nsdManager?.unregisterServiceInfoCallback(cb) }
                }
            }
            legacyQueue.removeAll { it.session === s && it.kind == kind && it.info.serviceName == serviceName }
            s.pendingProbe.remove(key)
            val deviceKey = s.probed.remove(key)
            // Another service (a second interface) may still announce the same device.
            if (deviceKey != null && deviceKey !in s.probed.values) s.byKey.remove(deviceKey)
            _devices.value = s.byKey.values.toList()
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
        /** Results kept across a pause are dropped if a new browse doesn't confirm them by then. */
        const val CARRY_OVER_GRACE_MS = 12_000L
        /** A legacy resolve without a callback by then frees the queue. */
        const val RESOLVE_WATCHDOG_MS = 10_000L
        const val RESOLVE_RETRY_DELAY_MS = 300L
        const val MAX_RESOLVE_ATTEMPTS = 5
    }
}
