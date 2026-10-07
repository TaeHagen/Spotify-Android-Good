package com.taehagen.spotifygood.connect

import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.nativebridge.NativeRpc
import com.taehagen.spotifygood.playback.PlaybackModes
import com.taehagen.spotifygood.playback.ResumeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
) {
    private val _devices = MutableStateFlow(events.devices.value)

    /** Latest device list: the latest `devices` event. */
    val devices: StateFlow<DeviceList> = _devices.asStateFlow()

    private val _pendingTarget = MutableStateFlow<String?>(null)

    /**
     * The Connect device picked while nothing played anywhere (and there was no session to
     * resume): the next play goes there (`player.load {deviceId}`, see [consumePendingTarget]).
     * Cleared once any device is active, when this phone is picked, on logout, and once used.
     */
    val pendingTarget: StateFlow<String?> = _pendingTarget.asStateFlow()

    /**
     * Invoked before playback is pulled to this phone, so the playback service can start while the
     * app is visible. Installed by the playback coordinator.
     */
    @Volatile var onTransferToThisDevice: (() -> Unit)? = null

    init {
        scope.launch {
            events.devices.collect {
                _devices.value = it
                if (activeIn(it)) _pendingTarget.value = null
            }
        }
        scope.launch { events.playback.collect { if (activeIn(it)) _pendingTarget.value = null } }
    }

    /** The pending target for the next play, cleared (it is used once). */
    fun consumePendingTarget(): String? = _pendingTarget.getAndUpdate { null }

    /** Forgets the pending target (logout). */
    fun clearPendingTarget() {
        _pendingTarget.value = null
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
            _pendingTarget.value = null
            onTransferToThisDevice?.invoke()
        }
        // Sent whether or not the (possibly stale) list shows an active device: the engine decides.
        val args = transferArgs(deviceId, play, lastSession())
        try {
            rpc.callUnit("connect.transfer", args)
        } catch (e: NativeException) {
            // Nothing to transfer or resume: the device stays picked for the next play (the
            // error still reaches the caller, which says so).
            pendingAfterFailure(e.code, deviceId, isThisDevice, args)?.let { _pendingTarget.value = it }
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
                putJsonObject("resume") {
                    resume.contextUri?.takeIf { it.isNotBlank() && it != resume.trackUri }?.let { put("contextUri", it) }
                    put("trackUri", resume.trackUri)
                    put("positionMs", resume.positionMs.coerceAtLeast(0))
                    // The session's modes; the engine plays smart shuffle as a plain shuffle on
                    // another device.
                    put("shuffle", resume.shuffle || resume.smartShuffle)
                    put("smartShuffle", resume.smartShuffle)
                    put("repeat", PlaybackModes.wire(resume.repeat))
                }
            }
        }
    }
}
