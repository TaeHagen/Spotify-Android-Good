package com.taehagen.spotifygood.playback

import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * The hardware volume keys for another Connect device while it plays (docs/ARCHITECTURE.md §9.4).
 *
 * Android routes the volume keys to the first session in an active playback state that handles
 * them (`MediaSessionStack.getDefaultVolumeSession`). The media session reads paused while another
 * device plays with a Bluetooth output connected ([RemotePlayback]: Bluetooth passes "playing" on
 * to the headset), so the keys would set the phone's own volume. This small platform session takes
 * them meanwhile (and only then: otherwise the media session reads playing and has them): remote volume
 * ([VolumeProvider], percent), "connecting" as its state, the one active state that Bluetooth
 * reports as stopped (`PlayStatus.playbackStateToAvrcpState`) and no notification, so nothing shows
 * it. Its transport controls (Bluetooth, an assistant, a watch may pick it, as the first active
 * session) do what the media session's do. Main thread.
 */
internal class RemoteVolumeKeys(
    private val context: Context,
    /** Opens the app, as the media session's activity does. */
    private val sessionActivity: PendingIntent?,
    /** Sets the remote volume (percent). */
    private val setVolume: (Int) -> Unit,
    /** Raises (`> 0`) or lowers (`< 0`) the remote volume one step. */
    private val adjustVolume: (Int) -> Unit,
    /** A play request of the controller with this package (null: unknown). */
    private val playRequested: (String?) -> Unit,
    private val pauseRequested: () -> Unit,
    private val nextRequested: () -> Unit,
    private val previousRequested: () -> Unit,
) {
    private var session: MediaSession? = null
    private var provider: VolumeProvider? = null
    /** The volume control id [provider] was made with (it can't change on a provider). */
    private var providerControlId: String? = null
    private var active = false
    /** The title and artist last set (the update runs on most engine and snapshot events). */
    private var shown: Pair<String?, String?>? = null

    /**
     * Takes the volume keys ([active]) at [percent], showing [title] / [artist]; or lets them go.
     * [volumeControlId]: the app's routing session for the device (API 30+, [SystemRouting]), as
     * the media session's, so whichever of the two a system surface picks names the same device.
     */
    fun update(active: Boolean, percent: Int, title: String?, artist: String?, volumeControlId: String? = null) {
        if (!active) {
            if (this.active) {
                this.active = false
                runCatching { session?.isActive = false }.onFailure { Log.w(TAG, "Cannot deactivate", it) }
            }
            return
        }
        val s = session ?: create() ?: return
        val volume = percent.coerceIn(0, MAX_VOLUME)
        val p = provider
        val controlId = volumeControlId.takeIf { Build.VERSION.SDK_INT >= 30 }
        if (p == null || controlId != providerControlId) {
            val next = if (controlId != null && Build.VERSION.SDK_INT >= 30) KeysVolume(volume, controlId) else KeysVolume(volume)
            provider = next.also { s.setPlaybackToRemote(it) }
            providerControlId = controlId
        } else if (p.currentVolume != volume) {
            p.currentVolume = volume
        }
        if (shown != (title to artist)) {
            shown = title to artist
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .build(),
            )
        }
        if (!this.active) {
            this.active = true
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_CONNECTING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .setActions(ACTIONS)
                    .build(),
            )
            s.isActive = true
        }
    }

    fun release() {
        active = false
        runCatching { session?.release() }
        session = null
        provider = null
        providerControlId = null
        shown = null
    }

    private fun create(): MediaSession? = try {
        MediaSession(context, TAG).also { s ->
            s.setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = playRequested(caller(s))
                    override fun onPause() = pauseRequested()
                    override fun onStop() = pauseRequested()
                    override fun onSkipToNext() = nextRequested()
                    override fun onSkipToPrevious() = previousRequested()
                },
            )
            sessionActivity?.let(s::setSessionActivity)
            session = s
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Cannot create the volume key session", e)
        null
    }

    private fun caller(s: MediaSession): String? =
        if (Build.VERSION.SDK_INT >= 28) runCatching { s.currentControllerInfo.packageName }.getOrNull() else null

    /** Remote volume in percent, with the routing session's id as its volume control id (API 30+). */
    private inner class KeysVolume : VolumeProvider {
        constructor(volume: Int) : super(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, MAX_VOLUME, volume)

        @RequiresApi(30)
        constructor(volume: Int, controlId: String) : super(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, MAX_VOLUME, volume, controlId)

        override fun onSetVolumeTo(volume: Int) {
            val target = volume.coerceIn(0, MAX_VOLUME)
            currentVolume = target
            setVolume(target)
        }

        override fun onAdjustVolume(direction: Int) {
            when (direction) {
                AudioManager.ADJUST_RAISE -> adjustVolume(1)
                AudioManager.ADJUST_LOWER -> adjustVolume(-1)
            }
        }
    }

    private companion object {
        const val TAG = "RemoteVolumeKeys"
        const val MAX_VOLUME = 100
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }
}
