package com.taehagen.spotifygood.playback

import android.media.AudioDeviceInfo
import androidx.media3.common.Player
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus

/**
 * How the media session shows playback on another Connect device (docs/ARCHITECTURE.md §9.4; pure,
 * JVM-testable).
 *
 * Bluetooth reads the platform session: the AVRCP target (`audio_util.MediaPlayerList`) reports
 * the play status of the media-key session's `PlaybackState` to the connected headset or car,
 * whatever its playback type, so a remote device playing while our session reads "playing" tells a
 * headset that the phone started playing. A multipoint headset then switches to the phone and
 * pauses its other source, which may be the very device the phone controls. So while another
 * device plays and a Bluetooth audio output is connected ([readsPaused]), the session reports its
 * playback as suppressed ([suppressionReason]): Media3 keeps `playWhenReady` (the media
 * foreground, the notification and Media3 controllers stay as they are), and the platform
 * `PlaybackState` reads paused, which Bluetooth passes on as paused. Without a Bluetooth output no
 * headset or car reads the session, so it reads playing as it should (pause button, moving
 * position). The surfaces that show the paused state (the notification and lock screen, Auto,
 * Wear, a headset's AVRCP play) offer "play" while the device plays: their play is a toggle there
 * and pauses it ([playMeansPause]), except a voice assistant's explicit play. Volume keys only
 * reach a session in an active playback state, which the paused one is not: [RemoteVolumeKeys]
 * takes them meanwhile ([volumeKeysSession]).
 */
internal object RemotePlayback {
    /** Another device plays (or loads) what the session shows. */
    fun playsElsewhere(s: PlaybackSnapshot): Boolean =
        s.source == PlaybackSource.REMOTE && s.track != null &&
            (s.status == PlaybackStatus.PLAYING || s.status == PlaybackStatus.LOADING)

    /**
     * Whether the session reads paused while another device plays: a Bluetooth audio output is
     * connected ([bluetoothOutput]; null, not watched, counts as connected: the safe side), so a
     * headset or car may read the session's state.
     */
    fun readsPaused(s: PlaybackSnapshot, bluetoothOutput: Boolean?): Boolean =
        playsElsewhere(s) && bluetoothOutput != false

    /**
     * The session's playback suppression for [s]: suppressed while it [readsPaused], so the
     * platform state reads paused. Transient focus loss is the reason Media3 controllers resolve
     * with a plain play (they send it although `playWhenReady` is already set), which this phone
     * then handles as [playMeansPause].
     */
    fun suppressionReason(s: PlaybackSnapshot, bluetoothOutput: Boolean?): Int =
        if (readsPaused(s, bluetoothOutput)) {
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        } else {
            Player.PLAYBACK_SUPPRESSION_REASON_NONE
        }

    /**
     * Whether a play request from [requesterPackage] pauses instead: another device plays, the
     * session reads paused ([suppressed]: the state the requester saw, any button), and it is not a
     * voice assistant asking to play. While the session reads playing a play stays a play.
     */
    fun playMeansPause(s: PlaybackSnapshot, suppressed: Boolean, requesterPackage: String?): Boolean =
        suppressed && playsElsewhere(s) && requesterPackage !in VOICE_ASSISTANTS

    /**
     * Whether the volume keys need [RemoteVolumeKeys]: the session reads paused ([suppressed]) while
     * another device plays and takes a volume. While the session reads playing it takes them itself.
     */
    fun volumeKeysSession(s: PlaybackSnapshot, suppressed: Boolean, volumeSupported: Boolean): Boolean =
        suppressed && playsElsewhere(s) && volumeSupported

    /**
     * A Bluetooth audio output whose remote may read the media session: A2DP (AVRCP), LE Audio
     * (headset or speaker: the media control service) and a broadcast sink.
     */
    fun isBluetoothMediaOutput(type: Int): Boolean = type in BLUETOOTH_MEDIA_OUTPUTS

    /** Voice assistants: their play is an explicit "play" (or "resume"), not a button showing paused. */
    val VOICE_ASSISTANTS: Set<String> = setOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.googleassistant",
        "com.google.android.carassistant",
        "com.google.android.apps.bard",
    )

    // API 31 / 33 types are compile-time constants, reported only by devices that know them.
    private val BLUETOOTH_MEDIA_OUTPUTS: Set<Int> = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
    )
}
