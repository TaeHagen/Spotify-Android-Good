package com.taehagen.spotifygood.nativebridge

/**
 * JNI surface of libspotcore (native/spotcore). See docs/ARCHITECTURE.md §3.
 *
 * All functions return immediately; results and events arrive through [NativeCallbacks].
 */
object NativeBridge {
    init {
        System.loadLibrary("spotcore")
    }

    /** Once per process. [configJson]: `{"filesDir","cacheDir","noBackupDir","deviceId","deviceName","logLevel"}`. */
    external fun nativeInit(callbacks: NativeCallbacks, audio: AudioSinkBridge, configJson: String)

    /** Asynchronous call; the result is delivered via [NativeCallbacks.onResult]. `requestId == 0` = fire and forget. */
    external fun nativeCall(requestId: Long, method: String, argsJson: String)

    /** Aborts an in-flight call; it then completes with code `CANCELLED`. */
    external fun nativeCancel(requestId: Long)
}

/** Called by native code from arbitrary threads. Implementations must not block. */
interface NativeCallbacks {
    fun onResult(requestId: Long, ok: Boolean, json: String)
    fun onEvent(type: String, json: String)
}
