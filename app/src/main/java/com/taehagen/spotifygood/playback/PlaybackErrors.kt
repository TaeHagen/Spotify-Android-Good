package com.taehagen.spotifygood.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
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

/**
 * The error a media controller (Android Auto, lock screen, Wear) should see while nothing plays,
 * as Media3 `PlaybackException` data. [code] is a `PlaybackException.ERROR_CODE_*`; [signIn] asks
 * for a "Sign in" resolution action.
 */
internal data class PlayerErrorInfo(val code: Int, val kind: PlaybackErrorKind, val message: String, val signIn: Boolean = false)

/** Which error the session player publishes (docs/ARCHITECTURE.md §9.4). Pure. */
internal object PlayerErrors {
    /**
     * Nothing while something plays or loads. Otherwise, in this order: logged out (once the stored
     * credentials were read: [ready]), the sticky account errors (Premium required; playback
     * refused while the local snapshot still reports it), then the last failure to start playback.
     */
    fun select(
        ready: Boolean,
        loggedIn: Boolean,
        accountErrorCode: String?,
        snapshot: PlaybackSnapshot,
        failure: PlaybackFailure?,
        messages: PlaybackErrorMessages,
    ): PlayerErrorInfo? {
        val active = snapshot.status == PlaybackStatus.PLAYING ||
            snapshot.status == PlaybackStatus.LOADING
        if (active) return null
        fun of(kind: PlaybackErrorKind, message: String = messages.message(kind, null)) =
            PlayerErrorInfo(codeOf(kind), kind, message, signIn = kind == PlaybackErrorKind.NOT_LOGGED_IN)
        return when {
            ready && !loggedIn -> of(PlaybackErrorKind.NOT_LOGGED_IN)
            accountErrorCode == NativeErrorCode.PREMIUM_REQUIRED -> of(PlaybackErrorKind.PREMIUM_REQUIRED)
            accountErrorCode == NativeErrorCode.PLAYBACK_REFUSED &&
                snapshot.source == PlaybackSource.LOCAL && snapshot.lastError != null ->
                of(PlaybackErrorKind.PLAYBACK_REFUSED)
            failure != null -> of(failure.kind, failure.message)
            else -> null
        }
    }

    /** `PlaybackException` error code of [kind] (Media3 maps these to the legacy error codes Auto reads). */
    fun codeOf(kind: PlaybackErrorKind): Int = when (kind) {
        PlaybackErrorKind.NOT_LOGGED_IN -> PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED
        PlaybackErrorKind.PREMIUM_REQUIRED -> PlaybackException.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED
        PlaybackErrorKind.PLAYBACK_REFUSED -> PlaybackException.ERROR_CODE_PERMISSION_DENIED
        PlaybackErrorKind.NETWORK, PlaybackErrorKind.NOT_AVAILABLE_OFFLINE ->
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        PlaybackErrorKind.TIMEOUT -> PlaybackException.ERROR_CODE_TIMEOUT
        PlaybackErrorKind.NOT_ACTIVE_DEVICE, PlaybackErrorKind.UNAVAILABLE, PlaybackErrorKind.NOT_FOUND,
        PlaybackErrorKind.RATE_LIMITED, PlaybackErrorKind.GENERIC,
        -> PlaybackException.ERROR_CODE_UNSPECIFIED
    }
}
