package com.taehagen.spotifygood.nativebridge

import com.taehagen.spotifygood.model.NativeErrorInfo

/** Error codes from docs/ARCHITECTURE.md §3.3. */
object NativeErrorCode {
    const val NOT_LOGGED_IN = "NOT_LOGGED_IN"
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val BAD_CREDENTIALS = "BAD_CREDENTIALS"
    const val PREMIUM_REQUIRED = "PREMIUM_REQUIRED"
    const val NETWORK = "NETWORK"
    const val NOT_FOUND = "NOT_FOUND"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val NOT_ACTIVE_DEVICE = "NOT_ACTIVE_DEVICE"
    const val PLAYBACK_REFUSED = "PLAYBACK_REFUSED"
    const val CANCELLED = "CANCELLED"
    const val INTERNAL = "INTERNAL"
}

class NativeException(val info: NativeErrorInfo) : Exception("${info.code}: ${info.message}") {
    val code: String get() = info.code
    val isNetwork: Boolean get() = code == NativeErrorCode.NETWORK || code == NativeErrorCode.NOT_CONNECTED
}
