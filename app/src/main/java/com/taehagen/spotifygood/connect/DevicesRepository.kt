package com.taehagen.spotifygood.connect

import android.os.SystemClock
import com.taehagen.spotifygood.data.StoredPosition
import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.nativebridge.NativeRpc
import com.taehagen.spotifygood.playback.PlaybackModes
import com.taehagen.spotifygood.playback.ResumeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Spotify Connect devices of the account (docs/ARCHITECTURE.md §8).
 *
 * [lastSession] is the last local session (the resume store); [transferTo] hands it to the
 * engine so a device can be picked even when nothing is playing anywhere (cold start).
 */
class DevicesRepository(
    scope: CoroutineScope,
    private val rpc: NativeRpc,
    events: NativeEvents,
    private val lastSession: suspend () -> ResumeState? = { null },
    /**
     * Clock of the pending target's expiry (ms, counting deep sleep). Coroutine delays don't count
     * deep sleep, so the expiry timer waits in short chunks and checks this clock each time.
     */
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _devices = MutableStateFlow(events.devices.value)
    private val playback: StateFlow<PlaybackSnapshot> = events.playback

    /** Latest device list: the latest `devices` event. */
    val devices: StateFlow<DeviceList> = _devices.asStateFlow()

    private val pending = PendingTarget(clock)

    /**
     * The Connect device picked while nothing played anywhere (and there was no session to
     * resume): the next in-app play goes there (`player.load {deviceId}`, see
     * [consumePendingTarget]). Cleared once any device is active, when this phone is picked, on
     * logout, once used, when its device left the account's device list, and
     * [PendingTarget.TTL_MS] after it was picked (a pick made long ago must not take over a play).
     */
    val pendingTarget: StateFlow<String?> = pending.value

    /**
     * Invoked before playback is pulled to this phone, so the playback service can start while the
     * app is visible. Installed by the playback coordinator.
     */
    @Volatile var onTransferToThisDevice: (() -> Unit)? = null

    /**
     * Where a play of an episode starts ([com.taehagen.spotifygood.playback.PlayerController.episodeResume]):
     * a transfer that starts the [lastSession] on a device starts its episode at the same point as
     * a play of it here (the stored position only when newer, after a bounded lookup when Spotify
     * may know better; docs §6.5). Installed by the app graph.
     */
    @Volatile var episodeResume: (suspend (episodeUri: String, stored: StoredPosition?) -> Long?)? = null

    init {
        scope.launch {
            events.devices.collect {
                _devices.value = it
                if (activeIn(it)) pending.clear()
                // The picked device left the account's devices (a list of only this phone, while
                // reconnecting or hidden, says nothing about it).
                pending.value.value?.let { target -> if (it.others.isNotEmpty() && !listed(target, it)) pending.clear() }
                expirePendingTarget()
            }
        }
        scope.launch { events.playback.collect { if (activeIn(it)) pending.clear() } }
        scope.launch {
            // Expiry. The delay doesn't count deep sleep, so it waits in short chunks and checks
            // the clock each time; consume() and expirePendingTarget() check it too.
            pending.value.collectLatest { target ->
                if (target == null) return@collectLatest
                while (!pending.expire(target)) delay(pending.remainingMs().coerceIn(1, EXPIRY_CHECK_MS))
            }
        }
    }

    /**
     * The pending target for the next play, cleared (it is used once); null once expired, or when
     * its device isn't listed any more (the banner doesn't show it then either).
     */
    fun consumePendingTarget(): String? = pending.consume()?.takeIf { listed(it, _devices.value) }

    /** Clears an expired pending target now (the app came to the foreground after a sleep). */
    fun expirePendingTarget() {
        pending.value.value?.let(pending::expire)
    }

    /** Forgets the pending target (logout). */
    fun clearPendingTarget() {
        pending.clear()
    }

    /** [deviceId] becomes the pending target (a transfer found nothing to play). */
    internal fun pick(deviceId: String) {
        pending.set(deviceId)
    }

    /**
     * Moves playback to [deviceId] (this phone included). When no device is active, the engine
     * starts the [lastSession] there instead (it ignores it otherwise); without one that fails
     * with NOT_ACTIVE_DEVICE. Throws NativeException on failure.
     */
    suspend fun transferTo(deviceId: String, play: Boolean = true) {
        val list = _devices.value
        val isThisDevice = deviceId == list.thisDeviceId || list.devices.any { it.id == deviceId && it.isThisDevice }
        if (isThisDevice) {
            pending.clear()
            onTransferToThisDevice?.invoke()
        }
        // Sent whether or not the (possibly stale) list shows an active device: the engine decides.
        val session = lastSession()?.let { last ->
            // The engine starts it only when nothing is loaded: an ordinary transfer waits for no lookup.
            val resolve = episodeResume
            if (resolve != null && resumeMayStart(playback.value)) withEpisodeStart(last, resolve) else last
        }
        val args = transferArgs(deviceId, play, session)
        try {
            rpc.callUnit("connect.transfer", args)
        } catch (e: NativeException) {
            // Nothing to transfer or resume: the device stays picked for the next play (the
            // error still reaches the caller, which says so).
            pendingAfterFailure(e.code, deviceId, isThisDevice, args)?.let(::pick)
            throw e
        }
    }

    /**
     * Fetches the device list from Spotify again (e.g. when the device picker opens or refresh is
     * tapped). The engine sends at most one request every 2.5 s and waits up to 3 s for it; when
     * debounced, offline or timed out it keeps the cached list. The refreshed list arrives as a
     * `devices` event, which the engine posts before the call returns: the result itself isn't
     * assigned, so it can't overwrite a newer event (cluster updates come in bursts).
     */
    suspend fun refresh() {
        rpc.callUnit("connect.refreshDevices")
    }

    internal companion object {
        /** The longest wait of the expiry timer between two looks at the clock. */
        const val EXPIRY_CHECK_MS = 15_000L

        /**
         * Whether a transfer may start the stored session (the engine's rule, §6.2: nothing is
         * active, or this phone is with nothing loaded): nothing plays or is paused anywhere.
         */
        fun resumeMayStart(snapshot: PlaybackSnapshot): Boolean = !snapshot.isActive

        /**
         * [state] with its episode starting where a play of it does ([resumeOf]: the store's
         * decision, which weighs the stored position by [ResumeState.positionAt]; 0: of unknown
         * age); music, and a session the decision leaves alone, as stored.
         */
        suspend fun withEpisodeStart(state: ResumeState, resumeOf: suspend (String, StoredPosition?) -> Long?): ResumeState {
            if (!state.trackUri.startsWith("spotify:episode:")) return state
            // A stored 0 names no position (as a play naming none).
            val stored = state.positionMs.takeIf { it > 0 }?.let { StoredPosition(it, state.positionAt ?: 0) }
            val position = resumeOf(state.trackUri, stored) ?: return state
            return state.copy(positionMs = position.coerceAtLeast(0))
        }

        /**
         * [id] is a listed Connect device other than this phone, with a name: the same rule as the
         * UI's pending target name (the banner shows it only then).
         */
        fun listed(id: String, list: DeviceList): Boolean =
            list.devices.any { it.id == id && !it.isThisDevice && it.name.isNotBlank() }

        /** The pending target a failed transfer leaves (see [pendingTarget]), or null. */
        fun pendingAfterFailure(code: String, deviceId: String, isThisDevice: Boolean, args: JsonObject): String? =
            deviceId.takeIf { code == NativeErrorCode.NOT_ACTIVE_DEVICE && !isThisDevice && args["resume"] == null }

        /** A device is active: a pending target is moot. */
        fun activeIn(list: DeviceList): Boolean = !list.activeDeviceId.isNullOrEmpty()

        fun activeIn(snapshot: PlaybackSnapshot): Boolean = snapshot.activeDevice != null

        /** `connect.transfer` arguments (docs/ARCHITECTURE.md §6.2). */
        fun transferArgs(deviceId: String, play: Boolean, resume: ResumeState?): JsonObject = buildJsonObject {
            put("deviceId", deviceId)
            put("play", play)
            if (resume != null && resume.trackUri.isNotBlank()) {
                // The same load as a local resume (ResumeState.toPlayRequest): the track-list form
                // plays its list in the saved order, the context form its context.
                val load = resume.resumeLoad
                val list = load.trackUris?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                putJsonObject("resume") {
                    if (list == null) {
                        resume.contextUri?.takeIf { it.isNotBlank() && it != resume.trackUri }?.let { put("contextUri", it) }
                    }
                    put("trackUri", resume.trackUri)
                    put("positionMs", resume.positionMs.coerceAtLeast(0))
                    // The session's modes; the engine plays smart shuffle as a plain shuffle on
                    // another device.
                    put("shuffle", load.shuffle || load.smartShuffle)
                    put("smartShuffle", load.smartShuffle)
                    put("repeat", PlaybackModes.wire(load.repeat))
                    list?.let { uris -> putJsonArray("trackUris") { uris.forEach { add(it) } } }
                }
            }
        }
    }
}

/**
 * The pending Connect target ([DevicesRepository.pendingTarget]): picked at [clock] time, it
 * expires [ttlMs] later. Thread-safe.
 */
internal class PendingTarget(private val clock: () -> Long, private val ttlMs: Long = TTL_MS) {
    private val _value = MutableStateFlow<String?>(null)
    val value: StateFlow<String?> = _value.asStateFlow()
    private var pickedAt = 0L

    @Synchronized fun set(deviceId: String) {
        pickedAt = clock()
        _value.value = deviceId
    }

    @Synchronized fun clear() {
        _value.value = null
    }

    /** The target, cleared (it is used once); null when there is none or it expired. */
    @Synchronized fun consume(): String? {
        val target = _value.value ?: return null
        _value.value = null
        return target.takeIf { remainingMs() > 0 }
    }

    /** Time left before the current target expires (≤ 0: expired). */
    @Synchronized fun remainingMs(): Long = pickedAt + ttlMs - clock()

    /** Clears [target] if it is still the target and expired; true when it is no longer pending. */
    @Synchronized fun expire(target: String): Boolean {
        if (_value.value != target) return true
        if (remainingMs() > 0) return false
        _value.value = null
        return true
    }

    companion object {
        /** How long a picked device waits for the next play. */
        const val TTL_MS = 10 * 60 * 1000L
    }
}
