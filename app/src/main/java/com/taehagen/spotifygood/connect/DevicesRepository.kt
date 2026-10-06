package com.taehagen.spotifygood.connect

import com.taehagen.spotifygood.model.DeviceList
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Spotify Connect devices of the account (docs/ARCHITECTURE.md §8). */
class DevicesRepository(scope: CoroutineScope, rpc: NativeRpc, events: NativeEvents) {
    val devices: StateFlow<DeviceList> get() = TODO()

    /** Moves playback to [deviceId] (this phone included). Throws NativeException on failure. */
    suspend fun transferTo(deviceId: String, play: Boolean = true): Unit = TODO()

    /** Asks the engine for a fresh list (e.g. when the device picker opens). */
    suspend fun refresh(): Unit = TODO()
}
