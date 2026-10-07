package com.taehagen.spotifygood.connect

import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeRpc
import com.taehagen.spotifygood.playback.ResumeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Latest device list: `devices` events, or the result of [refresh] if newer. */
    val devices: StateFlow<DeviceList> = _devices.asStateFlow()

    /**
     * Invoked before playback is pulled to this phone, so the playback service can start while the
     * app is visible. Installed by the playback coordinator.
     */
    @Volatile var onTransferToThisDevice: (() -> Unit)? = null

    init {
        scope.launch { events.devices.collect { _devices.value = it } }
    }

    /**
     * Moves playback to [deviceId] (this phone included). When no device is active, the engine
     * starts the [lastSession] there instead (it ignores it otherwise); without one that fails
     * with NOT_ACTIVE_DEVICE. Throws NativeException on failure.
     */
    suspend fun transferTo(deviceId: String, play: Boolean = true) {
        val list = _devices.value
        val isThisDevice = deviceId == list.thisDeviceId || list.devices.any { it.id == deviceId && it.isThisDevice }
        if (isThisDevice) onTransferToThisDevice?.invoke()
        // Sent whether or not the (possibly stale) list shows an active device: the engine decides.
        rpc.callUnit("connect.transfer", transferArgs(deviceId, play, lastSession()))
    }

    /**
     * Fetches the device list from Spotify again (e.g. when the device picker opens or refresh is
     * tapped). The engine sends at most one request every 2.5 s and waits up to 3 s for it; when
     * debounced, offline or timed out it returns the cached list. The list is also emitted as a
     * `devices` event.
     */
    suspend fun refresh() {
        _devices.value = rpc.call<DeviceList>("connect.refreshDevices")
    }

    internal companion object {
        /** `connect.transfer` arguments (docs/ARCHITECTURE.md §6.2). */
        fun transferArgs(deviceId: String, play: Boolean, resume: ResumeState?): JsonObject = buildJsonObject {
            put("deviceId", deviceId)
            put("play", play)
            if (resume != null && resume.trackUri.isNotBlank()) {
                putJsonObject("resume") {
                    resume.contextUri?.takeIf { it.isNotBlank() && it != resume.trackUri }?.let { put("contextUri", it) }
                    put("trackUri", resume.trackUri)
                    put("positionMs", resume.positionMs.coerceAtLeast(0))
                }
            }
        }
    }
}
