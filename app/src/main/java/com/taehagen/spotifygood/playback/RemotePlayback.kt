package com.taehagen.spotifygood.playback

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
 * device plays, the session reports its playback as suppressed ([suppressionReason]): Media3 keeps
 * `playWhenReady` (the media foreground, the notification and Media3 controllers stay as they
 * are), and the platform `PlaybackState` reads paused, which Bluetooth passes on as paused.
 * The surfaces that show that paused state (the notification and lock screen, Auto, Wear, a
 * headset's AVRCP play) offer "play" while the device plays: their play is a toggle there and
 * pauses it ([playMeansPause]), except a voice assistant's explicit play. Volume keys only reach a
 * session in an active playback state, which the paused one is not: [RemoteVolumeKeys] takes them
 * meanwhile ([volumeKeysSession]).
 */
internal object RemotePlayback {
    /** Another device plays (or loads) what the session shows. */
    fun playsElsewhere(s: PlaybackSnapshot): Boolean =
        s.source == PlaybackSource.REMOTE && s.track != null &&
            (s.status == PlaybackStatus.PLAYING || s.status == PlaybackStatus.LOADING)

    /**
     * The session's playback suppression for [s]: suppressed while another device plays, so the
     * platform state reads paused. Transient focus loss is the reason Media3 controllers resolve
     * with a plain play (they send it although `playWhenReady` is already set), which this phone
     * then handles as [playMeansPause].
     */
    fun suppressionReason(s: PlaybackSnapshot): Int =
        if (playsElsewhere(s)) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else Player.PLAYBACK_SUPPRESSION_REASON_NONE

    /**
     * Whether a play request from [requesterPackage] pauses instead: another device plays, the
     * requester saw the paused state (any button), and it is not a voice assistant asking to play.
     */
    fun playMeansPause(s: PlaybackSnapshot, requesterPackage: String?): Boolean =
        playsElsewhere(s) && requesterPackage !in VOICE_ASSISTANTS

    /** Whether the volume keys need [RemoteVolumeKeys]: another device plays and takes a volume. */
    fun volumeKeysSession(s: PlaybackSnapshot, volumeSupported: Boolean): Boolean = playsElsewhere(s) && volumeSupported

    /** Voice assistants: their play is an explicit "play" (or "resume"), not a button showing paused. */
    val VOICE_ASSISTANTS: Set<String> = setOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.googleassistant",
        "com.google.android.carassistant",
        "com.google.android.apps.bard",
    )
}
