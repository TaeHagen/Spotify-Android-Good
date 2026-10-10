package com.taehagen.spotifygood.playback

import androidx.media3.common.Player
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlaybackTest {
    private val remotePlaying = PlaybackSnapshot(
        source = PlaybackSource.REMOTE,
        status = PlaybackStatus.PLAYING,
        track = PlaybackTrack(uri = "spotify:track:t"),
    )
    private val localPlaying = remotePlaying.copy(source = PlaybackSource.LOCAL)

    @Test
    fun onlyAnotherDevicePlayingIsSuppressed() {
        val suppressed = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        val none = Player.PLAYBACK_SUPPRESSION_REASON_NONE
        assertEquals(suppressed, RemotePlayback.suppressionReason(remotePlaying))
        // Loading there (a play just sent) reads paused here too, never "this phone plays".
        assertEquals(suppressed, RemotePlayback.suppressionReason(remotePlaying.copy(status = PlaybackStatus.LOADING)))
        // Local playback stays exactly as it was: the platform state says playing.
        assertEquals(none, RemotePlayback.suppressionReason(localPlaying))
        assertEquals(none, RemotePlayback.suppressionReason(localPlaying.copy(status = PlaybackStatus.LOADING)))
        // The other device paused or stopped: an honest pause, nothing to suppress.
        assertEquals(none, RemotePlayback.suppressionReason(remotePlaying.copy(status = PlaybackStatus.PAUSED)))
        assertEquals(none, RemotePlayback.suppressionReason(remotePlaying.copy(status = PlaybackStatus.STOPPED)))
        // Nothing shown (the session is idle then).
        assertEquals(none, RemotePlayback.suppressionReason(remotePlaying.copy(track = null)))
        assertEquals(none, RemotePlayback.suppressionReason(PlaybackSnapshot()))
    }

    @Test
    fun aPlayFromAButtonShowingPausedPausesTheOtherDevice() {
        // SysUI (notification, lock screen), Bluetooth and the notification's media keys (both
        // arrive as the media notification controller, our own package), Android Auto, Wear.
        listOf(
            "com.android.systemui",
            "com.taehagen.spotifygood",
            "com.android.bluetooth",
            "com.google.android.projection.gearhead",
            "com.google.android.wearable.app",
            null,
        ).forEach { requester ->
            assertTrue("$requester", RemotePlayback.playMeansPause(remotePlaying, requester))
            assertTrue("$requester", RemotePlayback.playMeansPause(remotePlaying.copy(status = PlaybackStatus.LOADING), requester))
        }
    }

    @Test
    fun anAssistantsPlayAndEveryPlayOutsideRemotePlaybackStayAPlay() {
        RemotePlayback.VOICE_ASSISTANTS.forEach { assistant ->
            assertFalse(assistant, RemotePlayback.playMeansPause(remotePlaying, assistant))
        }
        // The other device paused: play resumes it.
        assertFalse(RemotePlayback.playMeansPause(remotePlaying.copy(status = PlaybackStatus.PAUSED), "com.android.systemui"))
        // Playing here: the session reads playing, a play is a play (Media3 resolves the toggle).
        assertFalse(RemotePlayback.playMeansPause(localPlaying, "com.android.systemui"))
        assertFalse(RemotePlayback.playMeansPause(localPlaying.copy(status = PlaybackStatus.PAUSED), null))
        assertFalse(RemotePlayback.playMeansPause(PlaybackSnapshot(), null))
    }

    @Test
    fun theVolumeKeysSessionOnlyWhileAnotherDevicePlaysAndTakesAVolume() {
        assertTrue(RemotePlayback.volumeKeysSession(remotePlaying, volumeSupported = true))
        assertTrue(RemotePlayback.volumeKeysSession(remotePlaying.copy(status = PlaybackStatus.LOADING), true))
        // A device with a fixed volume: the keys stay the phone's, as before.
        assertFalse(RemotePlayback.volumeKeysSession(remotePlaying, volumeSupported = false))
        // Paused there: the session is inactive, the keys go to the phone (as before the change).
        assertFalse(RemotePlayback.volumeKeysSession(remotePlaying.copy(status = PlaybackStatus.PAUSED), true))
        // Local playback: the media session in its playing state takes them itself.
        assertFalse(RemotePlayback.volumeKeysSession(localPlaying, true))
    }
}
