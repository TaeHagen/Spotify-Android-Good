package com.taehagen.spotifygood.playback

import android.media.AudioDeviceInfo
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
    private val remoteLoading = remotePlaying.copy(status = PlaybackStatus.LOADING)
    private val localPlaying = remotePlaying.copy(source = PlaybackSource.LOCAL)
    private val suppressed = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
    private val none = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    @Test
    fun anotherDevicePlayingReadsPausedOnlyWithABluetoothOutput() {
        // A headset or car may read the session: paused, never "this phone plays".
        assertEquals(suppressed, RemotePlayback.suppressionReason(remotePlaying, bluetoothOutput = true))
        assertEquals(suppressed, RemotePlayback.suppressionReason(remoteLoading, bluetoothOutput = true))
        assertTrue(RemotePlayback.readsPaused(remotePlaying, bluetoothOutput = true))
        // Outputs not watched (the engine starting or stopping): the safe side.
        assertEquals(suppressed, RemotePlayback.suppressionReason(remotePlaying, bluetoothOutput = null))
        assertTrue(RemotePlayback.readsPaused(remoteLoading, bluetoothOutput = null))
        // No Bluetooth output: nothing reads the session, it reads playing (pause, moving position).
        assertEquals(none, RemotePlayback.suppressionReason(remotePlaying, bluetoothOutput = false))
        assertEquals(none, RemotePlayback.suppressionReason(remoteLoading, bluetoothOutput = false))
        assertFalse(RemotePlayback.readsPaused(remotePlaying, bluetoothOutput = false))
    }

    @Test
    fun localPlaybackAndAPausedDeviceAreNeverSuppressed() {
        listOf(true, false, null).forEach { bluetooth ->
            // Local playback stays exactly as it was: the platform state says playing.
            assertEquals("$bluetooth", none, RemotePlayback.suppressionReason(localPlaying, bluetooth))
            assertEquals("$bluetooth", none, RemotePlayback.suppressionReason(localPlaying.copy(status = PlaybackStatus.LOADING), bluetooth))
            // The other device paused or stopped: an honest pause, nothing to suppress.
            assertEquals("$bluetooth", none, RemotePlayback.suppressionReason(remotePlaying.copy(status = PlaybackStatus.PAUSED), bluetooth))
            assertEquals("$bluetooth", none, RemotePlayback.suppressionReason(remotePlaying.copy(status = PlaybackStatus.STOPPED), bluetooth))
            // Nothing shown (the session is idle then).
            assertEquals("$bluetooth", none, RemotePlayback.suppressionReason(remotePlaying.copy(track = null), bluetooth))
            assertEquals("$bluetooth", none, RemotePlayback.suppressionReason(PlaybackSnapshot(), bluetooth))
        }
    }

    @Test
    fun bluetoothMediaOutputsAreA2dpLeAudioAndBroadcast() {
        listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        ).forEach { assertTrue("$it", RemotePlayback.isBluetoothMediaOutput(it)) }
        // No AVRCP or media control there: calls only, a hearing aid, wired, USB, the speaker.
        listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        ).forEach { assertFalse("$it", RemotePlayback.isBluetoothMediaOutput(it)) }
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
            assertTrue("$requester", RemotePlayback.playMeansPause(remotePlaying, suppressed = true, requester))
            assertTrue("$requester", RemotePlayback.playMeansPause(remoteLoading, suppressed = true, requester))
        }
    }

    @Test
    fun aPlayStaysAPlayWhenTheSessionReadsPlayingOrAnAssistantAsks() {
        RemotePlayback.VOICE_ASSISTANTS.forEach { assistant ->
            assertFalse(assistant, RemotePlayback.playMeansPause(remotePlaying, suppressed = true, assistant))
        }
        // No Bluetooth output: the session reads playing (pause shown), a play (a Media3 controller
        // sends one although playWhenReady is set) must not pause the device.
        assertFalse(RemotePlayback.playMeansPause(remotePlaying, suppressed = false, "com.android.systemui"))
        // The other device paused: play resumes it.
        assertFalse(RemotePlayback.playMeansPause(remotePlaying.copy(status = PlaybackStatus.PAUSED), true, "com.android.systemui"))
        // Playing here: a play is a play (Media3 resolves the toggle).
        assertFalse(RemotePlayback.playMeansPause(localPlaying, suppressed = false, "com.android.systemui"))
        assertFalse(RemotePlayback.playMeansPause(localPlaying.copy(status = PlaybackStatus.PAUSED), false, null))
        assertFalse(RemotePlayback.playMeansPause(PlaybackSnapshot(), false, null))
    }

    @Test
    fun theVolumeKeysSessionOnlyWhileTheSessionReadsPausedForAPlayingDeviceWithAVolume() {
        assertTrue(RemotePlayback.volumeKeysSession(remotePlaying, suppressed = true, volumeSupported = true))
        assertTrue(RemotePlayback.volumeKeysSession(remoteLoading, suppressed = true, volumeSupported = true))
        // No Bluetooth output: the session reads playing and takes the keys itself.
        assertFalse(RemotePlayback.volumeKeysSession(remotePlaying, suppressed = false, volumeSupported = true))
        // A device with a fixed volume: the keys stay the phone's, as before.
        assertFalse(RemotePlayback.volumeKeysSession(remotePlaying, suppressed = true, volumeSupported = false))
        // Paused there: the session is inactive, the keys go to the phone (as before the change).
        assertFalse(RemotePlayback.volumeKeysSession(remotePlaying.copy(status = PlaybackStatus.PAUSED), true, true))
        // Local playback: the media session in its playing state takes them itself.
        assertFalse(RemotePlayback.volumeKeysSession(localPlaying, suppressed = false, volumeSupported = true))
    }

    @Test
    fun aPendingPlayOfAPausedDeviceReadsPausedWithABluetoothOutput() {
        val remotePaused = remotePlaying.copy(status = PlaybackStatus.PAUSED)
        listOf(true, null).forEach { bluetooth ->
            // Media3's placeholder of a play: the paused state with playWhenReady set. It lasts
            // until the device reports playing, so it reads paused like the state that follows.
            assertEquals("$bluetooth", suppressed, RemotePlayback.placeholderSuppression(remotePaused, true, none, bluetooth, localLoadPending = false))
            assertEquals("$bluetooth", suppressed, RemotePlayback.placeholderSuppression(remotePlaying.copy(status = PlaybackStatus.STOPPED), true, none, bluetooth, false))
            // While it plays or loads (a seek, a skip, a queue change): never unsuppressed.
            assertEquals("$bluetooth", suppressed, RemotePlayback.placeholderSuppression(remotePlaying, true, suppressed, bluetooth, false))
            assertEquals("$bluetooth", suppressed, RemotePlayback.placeholderSuppression(remoteLoading, true, none, bluetooth, false))
            // A pause (or a seek while paused) reads paused anyway.
            assertEquals("$bluetooth", none, RemotePlayback.placeholderSuppression(remotePaused, false, none, bluetooth, false))
        }
        // No Bluetooth output: nothing reads the session, the play shows pause at once, as before.
        assertEquals(none, RemotePlayback.placeholderSuppression(remotePaused, true, none, bluetoothOutput = false, localLoadPending = false))
        assertEquals(none, RemotePlayback.placeholderSuppression(remotePlaying, true, none, false, false))
    }

    @Test
    fun localPlaybackAndAMediaSessionLoadKeepTheirPlaceholder() {
        listOf(true, false, null).forEach { bluetooth ->
            // Playing here: the placeholder reads playing as before.
            assertEquals("$bluetooth", none, RemotePlayback.placeholderSuppression(localPlaying.copy(status = PlaybackStatus.PAUSED), true, none, bluetooth, false))
            assertEquals("$bluetooth", none, RemotePlayback.placeholderSuppression(localPlaying, true, none, bluetooth, false))
            // Auto's (a watch's, resumption's) load and its play while another device is shown:
            // they play on this phone.
            assertEquals("$bluetooth", none, RemotePlayback.placeholderSuppression(remotePlaying.copy(status = PlaybackStatus.PAUSED), true, none, bluetooth, localLoadPending = true))
            // Nothing shown.
            assertEquals("$bluetooth", none, RemotePlayback.placeholderSuppression(PlaybackSnapshot(), true, none, bluetooth, false))
            assertEquals("$bluetooth", none, RemotePlayback.placeholderSuppression(remotePlaying.copy(track = null), true, none, bluetooth, false))
        }
        // A suppression the placeholder starts from is never lifted here.
        assertEquals(suppressed, RemotePlayback.placeholderSuppression(remotePlaying, true, suppressed, true, localLoadPending = true))
    }

    @Test
    fun aBluetoothOutputComingOrGoingSwitchesEveryRuleTogether() {
        // The mode follows the output while the device plays; the toggle and the volume key session
        // follow the state the session then publishes.
        listOf(true to true, false to false, null to true).forEach { (bluetooth, paused) ->
            val reads = RemotePlayback.readsPaused(remotePlaying, bluetooth)
            assertEquals("$bluetooth", paused, reads)
            assertEquals("$bluetooth", if (paused) suppressed else none, RemotePlayback.suppressionReason(remotePlaying, bluetooth))
            assertEquals("$bluetooth", paused, RemotePlayback.playMeansPause(remotePlaying, reads, "com.android.systemui"))
            assertEquals("$bluetooth", paused, RemotePlayback.volumeKeysSession(remotePlaying, reads, volumeSupported = true))
        }
    }
}
