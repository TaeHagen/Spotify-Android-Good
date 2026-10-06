package com.taehagen.spotifygood.engine

/**
 * Outcome of `NativeBridge.nativeInit` (set once by `AppInitializer`). When the native library
 * failed to load or initialise, the engine never calls into it and reports an error state so the
 * UI can explain instead of crashing.
 */
object NativeStatus {
    @Volatile
    var initError: Throwable? = null
        internal set

    @Volatile
    var initialized: Boolean = false
        internal set

    val isAvailable: Boolean get() = initialized && initError == null
}
