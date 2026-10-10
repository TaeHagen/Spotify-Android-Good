package com.taehagen.spotifygood.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import com.taehagen.spotifygood.connect.DevicesRepository
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the media session publishes while a play sent through it is pending (Media3's placeholder,
 * [SpotifyPlayer.getPlaceholderState]): a play of another, paused device must never read as "this
 * phone plays" while a Bluetooth output is connected (docs/ARCHITECTURE.md §9.4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpotifyPlayerPlaceholderTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = NativeEvents(json)
    private val playback = PlaybackRepository(scope, events)

    /** Engine calls held until released (a Connect command's round trip) or failed. */
    private val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val calls = mutableListOf<String>()
    private val args = mutableListOf<Pair<String, JsonObject>>()
    private val controller = PlayerController(
        scope = scope,
        transport = { method, arguments ->
            calls += method
            args += method to arguments
            held[method]?.await()
            JsonObject(emptyMap())
        },
        json = json,
        snapshot = playback.snapshot,
        lastSession = { null },
    )
    private var bluetooth: Boolean? = true
    /** The controller whose request the session handles (`controllerForCurrentRequest`). */
    private var requester: String? = null
    private val player: SpotifyPlayer by lazy {
        val context = RuntimeEnvironment.getApplication()
        SpotifyPlayer(
            context = context,
            playback = playback,
            controller = controller,
            devices = DevicesRepository(scope, NativeRpc(json), events),
            volume = VolumeSync(context, NativeRpc(json), playback),
            audioSessionId = 0,
            downloadedQueue = { emptyList() },
            requester = { requester },
            bluetoothOutput = { bluetooth },
        )
    }

    /** Whether each state the player published reads playing on the platform session (AVRCP). */
    private val published = mutableListOf<Boolean>()

    private val track = PlaybackTrack(uri = "spotify:track:t", uid = "u1", name = "Song")
    private val remotePaused = PlaybackSnapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, track = track)
    private val remotePlaying = remotePaused.copy(status = PlaybackStatus.PLAYING)
    private val remoteLoading = remotePaused.copy(status = PlaybackStatus.LOADING)
    private val localPaused = remotePaused.copy(source = PlaybackSource.LOCAL)

    /** What a controller picks (a car's browse list, Auto's list or search, a watch). */
    private val picked = MediaItem.Builder().setMediaId("spotify:track:other").build()
    /** The pick playing on this phone (the load took the session over). */
    private val pickedHere = PlaybackSnapshot(
        source = PlaybackSource.LOCAL,
        status = PlaybackStatus.PLAYING,
        track = PlaybackTrack(uri = "spotify:track:other", uid = "u9", name = "Other"),
    )

    @After
    fun tearDown() {
        held.values.forEach { it.complete(Unit) }
        idle()
        player.release()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The engine's next snapshot; the service then refreshes the player (ignored while a command is pending). */
    private fun publish(s: PlaybackSnapshot) {
        events.dispatch("playback", json.encodeToString(s))
        player.refresh()
        idle()
    }

    private fun hold(method: String) {
        held[method] = CompletableDeferred()
    }

    private fun release(method: String) {
        held.remove(method)?.complete(Unit)
        idle()
    }

    /** The held engine call fails (the engine's error). */
    private fun fail(method: String) {
        held.remove(method)?.completeExceptionally(IllegalStateException("$method failed"))
        idle()
    }

    /** The `play` argument of the `player.load` sent. */
    private fun loadPlays(): Boolean? = args.single { it.first == "player.load" }.second["play"]?.jsonPrimitive?.boolean

    /**
     * Media3's own mapping (`MediaSessionLegacyStub`, `showPlayButtonIfPlaybackIsSuppressed` at its
     * default): no play button means platform PLAYING (READY) or BUFFERING, both "playing" to AVRCP.
     */
    private fun readsPlaying() = !Util.shouldShowPlayButton(player, true)

    private fun start(s: PlaybackSnapshot, bluetoothOutput: Boolean?) {
        bluetooth = bluetoothOutput
        publish(s)
        player.addListener(
            object : Player.Listener {
                // Called for every published state, right after it is set.
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { published += readsPlaying() }
                override fun onPlaybackStateChanged(playbackState: Int) { published += readsPlaying() }
                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) { published += readsPlaying() }
                override fun onIsPlayingChanged(isPlaying: Boolean) { published += readsPlaying() }
            },
        )
        assertEquals(s.status == PlaybackStatus.PLAYING || s.status == PlaybackStatus.LOADING, player.playWhenReady)
        assertFalse(readsPlaying())
    }

    @Test
    fun aPlayOfAPausedDeviceWithABluetoothOutputNeverReadsPlaying() {
        listOf(true, null).forEach { bluetoothOutput ->
            published.clear()
            calls.clear()
            start(remotePaused, bluetoothOutput)
            hold("player.play")

            // The notification, lock screen, widget, Auto, Wear or a headset: Media3's play().
            player.play()
            idle()
            assertTrue("$bluetoothOutput", "player.play" in calls)
            // Pending: the optimistic state (playWhenReady) reads paused, as the settled one will.
            assertTrue(player.playWhenReady)
            assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, player.playbackSuppressionReason)
            assertFalse(readsPlaying())
            assertTrue(player.readsPaused)

            // The command went through, the device reports playing: still paused for Bluetooth.
            release("player.play")
            assertFalse(readsPlaying())
            publish(remotePlaying)
            assertTrue(player.playWhenReady)
            assertFalse(readsPlaying())
            assertTrue("$bluetoothOutput: $published", published.isNotEmpty() && published.none { it })

            // Paused again for the next round.
            publish(remotePaused)
        }
    }

    @Test
    fun seeksAndSkipsWhileAPlayIsPendingStayPaused() {
        start(remotePaused.copy(nextTracks = listOf(PlaybackTrack(uri = "spotify:track:n", uid = "u2"))), true)
        hold("player.play")
        hold("player.next")
        hold("player.seek")
        player.play()
        player.seekTo(30_000)
        player.seekToNext()
        idle()
        assertTrue(player.playWhenReady)
        assertFalse(readsPlaying())
        assertTrue("$published", published.none { it })
    }

    @Test
    fun withoutABluetoothOutputAPlayOfAnotherDeviceReadsPlayingAtOnce() {
        start(remotePaused, false)
        hold("player.play")
        player.play()
        idle()
        // As before: the pause button right away.
        assertTrue(readsPlaying())
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, player.playbackSuppressionReason)
        release("player.play")
        publish(remotePlaying)
        assertTrue(readsPlaying())
    }

    @Test
    fun localPlaybackReadsPlayingAtOnce() {
        start(localPaused, true)
        hold("player.play")
        player.play()
        idle()
        assertTrue(readsPlaying())
        release("player.play")
        publish(localPaused.copy(status = PlaybackStatus.PLAYING))
        assertTrue(readsPlaying())
    }

    @Test
    fun aMediaSessionLoadWhileMirroringPlaysHereAndReadsPlaying() {
        // Auto (or a watch, playback resumption) loads and plays while another device is shown:
        // it plays on this phone (onThisPhone), so its placeholder reads playing as before.
        start(remotePaused, true)
        hold("player.load")
        player.setMediaItems(listOf(MediaItem.Builder().setMediaId("spotify:track:other").build()))
        player.prepare()
        player.play()
        idle()
        assertTrue("player.load" in calls)
        assertTrue(player.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, player.playbackSuppressionReason)
        assertTrue(readsPlaying())
    }

    /**
     * A controller's pick while another device plays or loads ([elsewhere]) with a Bluetooth output
     * connected: Media3 runs setMediaItems, then [then] (its play), as `MediaSessionLegacyStub`
     * does for a car's browse list, Auto's list or search and a watch, and a Media3 controller
     * does with two commands. The load plays on this phone; nothing pauses it.
     */
    private fun pickWhileAnotherDevicePlays(elsewhere: PlaybackSnapshot, then: () -> Unit) {
        start(elsewhere, true)
        assertTrue(player.readsPaused)
        hold("player.load")
        requester = "com.android.bluetooth"
        player.setMediaItems(listOf(picked))
        then()
        idle()

        assertFalse("$calls", "player.pause" in calls)
        // playWhenReady was set (the other device plays): the load itself plays, here.
        assertEquals(true, loadPlays())
        assertEquals(JsonPrimitive(true), args.single { it.first == "player.load" }.second["local"])
        assertTrue(player.playWhenReady)
        // The phone is about to play: that is what Bluetooth reads (and no toggle meanwhile).
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, player.playbackSuppressionReason)
        assertTrue(readsPlaying())
        assertFalse(player.readsPaused)

        // The load goes through, the play after it is sent, the pick plays here.
        release("player.load")
        publish(pickedHere)
        assertFalse("$calls", "player.pause" in calls)
        assertEquals("$calls", "player.play", calls.last())
        assertTrue(player.playWhenReady)
        assertTrue(readsPlaying())
    }

    @Test
    fun aPickWhileAnotherDevicePlaysPlaysHere() {
        pickWhileAnotherDevicePlays(remotePlaying) {
            player.prepare()
            player.play()
        }
        assertEquals(listOf("player.load", "player.play"), calls)
    }

    @Test
    fun aPickWhileAnotherDeviceLoadsPlaysHere() {
        pickWhileAnotherDevicePlays(remoteLoading) {
            player.prepare()
            player.play()
        }
        assertEquals(listOf("player.load", "player.play"), calls)
    }

    @Test
    fun aMedia3ControllersPickPlaysHere() {
        // setMediaItems and play as two commands (a Media3 controller, e.g. a watch).
        pickWhileAnotherDevicePlays(remotePlaying) {
            idle()
            requester = "com.google.android.wearable.app"
            player.play()
        }
        assertEquals(listOf("player.load", "player.play"), calls)
    }

    @Test
    fun aPickWithASeekBeforeItsPlayPlaysHere() {
        pickWhileAnotherDevicePlays(remotePlaying) {
            player.seekTo(30_000)
            player.play()
        }
        assertEquals(listOf("player.load", "player.seek", "player.play"), calls)
    }

    @Test
    fun anotherControllersPlayWhileAPickIsPendingPlaysToo() {
        pickWhileAnotherDevicePlays(remoteLoading) {
            idle()
            // The notification (or a headset key) while the load is on its way: a play, not a toggle.
            requester = "com.android.systemui"
            player.play()
        }
        assertEquals(listOf("player.load", "player.play"), calls)
    }

    @Test
    fun aPlayFromAButtonShowingPausedStillPausesTheOtherDevice() {
        // The notification, the media notification controller (Bluetooth keys), Auto's play button.
        listOf("com.android.systemui", "com.taehagen.spotifygood", "com.google.android.projection.gearhead").forEach { from ->
            calls.clear()
            start(remotePlaying, true)
            requester = from
            player.play()
            idle()
            assertEquals(from, listOf("player.pause"), calls)
            // The device reports the pause; it plays again for the next round.
            publish(remotePaused)
            publish(remotePlaying)
        }
    }

    @Test
    fun afterAFailedPickAPlayPausesTheOtherDeviceAgain() {
        start(remotePlaying, true)
        hold("player.load")
        requester = "com.google.android.projection.gearhead"
        player.setMediaItems(listOf(picked))
        player.prepare()
        player.play()
        idle()
        assertFalse("$calls", "player.pause" in calls)

        // The load fails: the play that followed it goes with it, the other device plays on, and
        // the session reads paused again.
        fail("player.load")
        assertEquals(listOf("player.load"), calls)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, player.playbackSuppressionReason)
        assertFalse(readsPlaying())

        // The notification shows play: its play is a toggle again.
        requester = "com.android.systemui"
        player.play()
        idle()
        assertEquals(listOf("player.load", "player.pause"), calls)
    }
}
