package com.taehagen.spotifygood.playback

import android.content.Context
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.nativebridge.NativeErrorCode

/** User-facing categories of playback command failures. */
enum class PlaybackErrorKind {
    NOT_ACTIVE_DEVICE,
    NETWORK,
    PREMIUM_REQUIRED,
    PLAYBACK_REFUSED,
    UNAVAILABLE,
    /** Offline and the requested context / track is not downloaded. */
    NOT_AVAILABLE_OFFLINE,
    NOT_FOUND,
    RATE_LIMITED,
    NOT_LOGGED_IN,
    TIMEOUT,
    GENERIC,
    ;

    companion object {
        /** Maps a native error code (docs §3.3); `null` for errors that must not be shown (cancellation). */
        fun fromCode(code: String): PlaybackErrorKind? = when (code) {
            NativeErrorCode.CANCELLED -> null
            NativeErrorCode.NOT_ACTIVE_DEVICE -> NOT_ACTIVE_DEVICE
            NativeErrorCode.NETWORK, NativeErrorCode.NOT_CONNECTED -> NETWORK
            NativeErrorCode.PREMIUM_REQUIRED -> PREMIUM_REQUIRED
            NativeErrorCode.PLAYBACK_REFUSED -> PLAYBACK_REFUSED
            NativeErrorCode.UNAVAILABLE -> UNAVAILABLE
            NativeErrorCode.NOT_FOUND -> NOT_FOUND
            NativeErrorCode.RATE_LIMITED -> RATE_LIMITED
            NativeErrorCode.NOT_LOGGED_IN, NativeErrorCode.BAD_CREDENTIALS -> NOT_LOGGED_IN
            else -> GENERIC
        }
    }
}

/** Turns an error category into a message for snackbars. */
fun interface PlaybackErrorMessages {
    fun message(kind: PlaybackErrorKind, detail: String?): String

    companion object {
        /** Used until resources are available: the engine's own message (never empty). */
        val Fallback = PlaybackErrorMessages { kind, detail -> detail?.takeIf { it.isNotBlank() } ?: kind.name }

        /** Localised messages from `strings_playback.xml`. */
        fun fromResources(context: Context): PlaybackErrorMessages {
            val res = context.applicationContext.resources
            return PlaybackErrorMessages { kind, _ ->
                res.getString(
                    when (kind) {
                        PlaybackErrorKind.NOT_ACTIVE_DEVICE -> R.string.playback_error_not_active_device
                        PlaybackErrorKind.NETWORK -> R.string.playback_error_network
                        PlaybackErrorKind.PREMIUM_REQUIRED -> R.string.playback_error_premium_required
                        PlaybackErrorKind.PLAYBACK_REFUSED -> R.string.playback_error_refused
                        PlaybackErrorKind.UNAVAILABLE -> R.string.playback_error_unavailable
                        PlaybackErrorKind.NOT_AVAILABLE_OFFLINE -> R.string.playback_error_not_available_offline
                        PlaybackErrorKind.NOT_FOUND -> R.string.playback_error_not_found
                        PlaybackErrorKind.RATE_LIMITED -> R.string.playback_error_rate_limited
                        PlaybackErrorKind.NOT_LOGGED_IN -> R.string.playback_error_not_logged_in
                        PlaybackErrorKind.TIMEOUT -> R.string.playback_error_timeout
                        PlaybackErrorKind.GENERIC -> R.string.playback_error_generic
                    },
                )
            }
        }
    }
}
