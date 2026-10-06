package com.taehagen.spotifygood.connect

import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Spotify Connect devices of the account (docs/ARCHITECTURE.md §8). */
class DevicesRepository(scope: CoroutineScope, private val rpc: NativeRpc, events: NativeEvents) {
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

    /** Moves playback to [deviceId] (this phone included). Throws NativeException on failure. */
    suspend fun transferTo(deviceId: String, play: Boolean = true) {
        val list = _devices.value
        val isThisDevice = deviceId == list.thisDeviceId || list.devices.any { it.id == deviceId && it.isThisDevice }
        if (isThisDevice) onTransferToThisDevice?.invoke()
        rpc.callUnit(
            "connect.transfer",
            buildJsonObject {
                put("deviceId", deviceId)
                put("play", play)
            },
        )
    }

    /** Asks the engine for a fresh list (e.g. when the device picker opens). */
    suspend fun refresh() {
        _devices.value = rpc.call<DeviceList>("connect.refreshDevices")
    }
}
