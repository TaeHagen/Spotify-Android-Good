package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.PlaybackContext
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.RepeatMode
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeFallbackTest {
    private val notActive = NativeException(NativeErrorInfo(NativeErrorCode.NOT_ACTIVE_DEVICE, "no active device"))

    private fun state(contextUri: String?, trackUri: String = "spotify:track:t") = ResumeState(
        contextUri = contextUri,
        trackUri = trackUri,
        positionMs = 42_000,
        title = "Song",
        artist = null,
        album = null,
        artworkUrl = null,
        durationMs = 200_000,
        isEpisode = false,
    )

    /** Records calls; [fail] decides which methods throw. */
    private class FakeRpc(val fail: (String) -> NativeException?) {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        suspend fun call(method: String, args: JsonObject) {
            calls += method to args
            fail(method)?.let { throw it }
        }
    }

    @Test
    fun playWithAnActiveDeviceDoesNotTouchTheResumeState() = runTest {
        val rpc = FakeRpc { null }
        var read = false
        PlayerController.resumeOrLoadLast("player.play", rpc::call) { read = true; state("spotify:album:a") }
        assertEquals(listOf("player.play"), rpc.calls.map { it.first })
        assertFalse(read)
    }

    @Test
    fun noActiveDeviceLoadsTheLastSession() = runTest {
        val rpc = FakeRpc { if (it == "player.play") notActive else null }
        PlayerController.resumeOrLoadLast("player.play", rpc::call) { state("spotify:album:a") }
        assertEquals(listOf("player.play", "player.load"), rpc.calls.map { it.first })
        val load = rpc.calls[1].second
        assertEquals(JsonPrimitive("spotify:album:a"), load["contextUri"])
        assertEquals(JsonPrimitive("spotify:track:t"), load["startUri"])
        assertEquals(42_000L, load["positionMs"]?.jsonPrimitive?.content?.toLong())
        assertEquals(JsonPrimitive(true), load["play"])
        assertNull(load["trackUris"])
        // This phone's session: played here, not pushed onto a remote device.
        assertEquals(JsonPrimitive(true), load["local"])
    }

    @Test
    fun noSessionYetLoadsTheLastSessionToo() = runTest {
        // Cold start: the session did not come up in time (or there is no network).
        val notConnected = NativeException(NativeErrorInfo(NativeErrorCode.NOT_CONNECTED, "not connected"))
        val rpc = FakeRpc { if (it == "player.play") notConnected else null }
        PlayerController.resumeOrLoadLast(
            "player.play",
            rpc::call,
            prepare = { it.copy(trackUris = listOf("spotify:track:t")) },
        ) { state("spotify:album:a") }
        assertEquals(listOf("player.play", "player.load"), rpc.calls.map { it.first })
        assertEquals(listOf("spotify:track:t"), rpc.calls[1].second["trackUris"]?.jsonArray?.map { it.jsonPrimitive.content })

        val remote = FakeRpc { notConnected }
        val thrown = runCatching {
            PlayerController.resumeOrLoadLast("player.play", remote::call, fallBackOn = { false }) { state(null) }
        }.exceptionOrNull()
        assertSame(notConnected, thrown)
        assertEquals(listOf("player.play"), remote.calls.map { it.first })
    }

    @Test
    fun whenToResumeTheLastSession() {
        fun resume(code: String, remote: Boolean = false, reach: EngineReach? = EngineReach.ONLINE) =
            PlayerController.shouldResumeLast(code, remote, reach)
        assertTrue(resume(NativeErrorCode.NOT_ACTIVE_DEVICE))
        assertTrue(resume(NativeErrorCode.NOT_CONNECTED, reach = EngineReach.CONNECTING))
        assertTrue(resume(NativeErrorCode.NOT_CONNECTED, reach = EngineReach.OFFLINE))
        assertTrue(resume(NativeErrorCode.UNAVAILABLE, reach = EngineReach.CONNECTING))
        assertFalse(resume(NativeErrorCode.UNAVAILABLE, reach = EngineReach.OFFLINE))
        assertFalse(resume(NativeErrorCode.UNAVAILABLE, reach = EngineReach.ONLINE))
        assertFalse("mirroring a remote device", resume(NativeErrorCode.NOT_CONNECTED, remote = true))
        assertFalse(resume(NativeErrorCode.UNAVAILABLE, remote = true, reach = EngineReach.CONNECTING))
        assertFalse(resume(NativeErrorCode.NETWORK))
        assertFalse(resume(NativeErrorCode.PREMIUM_REQUIRED))
    }

    @Test
    fun nothingToResumeReportsTheOriginalError() = runTest {
        val rpc = FakeRpc { notActive }
        val thrown = runCatching { PlayerController.resumeOrLoadLast("player.togglePlay", rpc::call) { null } }.exceptionOrNull()
        assertSame(notActive, thrown)
        assertEquals(listOf("player.togglePlay"), rpc.calls.map { it.first })
    }

    @Test
    fun otherErrorsAreNotMaskedByTheFallback() = runTest {
        val network = NativeException(NativeErrorInfo(NativeErrorCode.NETWORK, "offline"))
        val rpc = FakeRpc { network }
        val thrown = runCatching { PlayerController.resumeOrLoadLast("player.play", rpc::call) { state(null) } }.exceptionOrNull()
        assertSame(network, thrown)
        assertEquals(1, rpc.calls.size)
    }

    @Test
    fun resumeRequestWithoutAContextPlaysTheTrack() {
        // spotify:web-api is what a plain track list (Liked Songs as tracks, downloads, search)
        // reports as its context: it cannot be loaded again.
        for (context in listOf(null, "spotify:track:t", "spotify:episode:e", "spotify:web-api", "spotify:web-api:tracks", "spotify:local:a:b:c:1")) {
            val request = state(context).toPlayRequest()
            assertNull(request.contextUri)
            assertEquals(listOf("spotify:track:t"), request.trackUris)
            assertEquals("spotify:track:t", request.startUri)
            assertEquals(42_000L, request.positionMs)
        }
        val args = PlayerController.loadArgs(state(null).toPlayRequest())
        assertEquals(listOf("spotify:track:t"), args["trackUris"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertNull(args["local"]) // only when asked for
        assertEquals("spotify:track:t", state("spotify:web-api").mediaId)
        assertEquals(MediaIds.inContext("spotify:playlist:p", "spotify:track:t"), state("spotify:playlist:p").mediaId)
        assertEquals("spotify:user:u:collection", state("spotify:user:u:collection").toPlayRequest().contextUri)
    }

    @Test
    fun loadsOfANonLoadableContextPlayTheirStartTrack() {
        val fixed = PlayerController.withLoadableContext(PlayRequest(contextUri = "spotify:web-api", startUri = "spotify:track:x", startUid = "u1", positionMs = 7))
        assertNull(fixed.contextUri)
        assertEquals(listOf("spotify:track:x"), fixed.trackUris)
        assertEquals(0, fixed.startIndex)
        assertNull(fixed.startUid)
        assertEquals(7L, fixed.positionMs)
        val tracks = PlayRequest(contextUri = "spotify:web-api", trackUris = listOf("spotify:track:a"), startIndex = 0)
        assertEquals(tracks.copy(contextUri = null), PlayerController.withLoadableContext(tracks))
        val album = PlayRequest(contextUri = "spotify:album:a", startUri = "spotify:track:x")
        assertSame(album, PlayerController.withLoadableContext(album))
        val single = PlayRequest(contextUri = "spotify:track:x")
        assertSame(single, PlayerController.withLoadableContext(single))
    }

    @Test
    fun theLastSessionComesBackWithItsShuffleAndRepeat() = runTest {
        val snapshot = PlaybackSnapshot(
            status = PlaybackStatus.PAUSED,
            positionMs = 9_000,
            context = PlaybackContext("spotify:playlist:p"),
            track = PlaybackTrack(uri = "spotify:track:t"),
            shuffle = false, // smart shuffle implies shuffle, even when a snapshot says otherwise
            smartShuffle = true,
            repeat = RepeatMode.TRACK,
        )
        val saved = checkNotNull(ResumeState.from(snapshot))
        assertTrue(saved.shuffle)
        assertTrue(saved.smartShuffle)
        assertEquals(RepeatMode.TRACK, saved.repeat)

        val rpc = FakeRpc { if (it == "player.play") notActive else null }
        PlayerController.resumeOrLoadLast("player.play", rpc::call) { saved }
        val load = rpc.calls[1].second
        assertEquals(JsonPrimitive(true), load["shuffle"])
        assertEquals(JsonPrimitive(true), load["smartShuffle"])
        assertEquals(JsonPrimitive("track"), load["repeat"])

        // Shuffle and repeat-all.
        val args = PlayerController.loadArgs(state("spotify:album:a").copy(shuffle = true, repeat = RepeatMode.CONTEXT).toPlayRequest())
        assertEquals(JsonPrimitive(true), args["shuffle"])
        assertEquals(JsonPrimitive(false), args["smartShuffle"])
        assertEquals(JsonPrimitive("context"), args["repeat"])
    }

    @Test
    fun aStateWithoutModesResumesWithThemOff() {
        // States stored by older versions read with the defaults; the load still names the modes.
        val args = PlayerController.loadArgs(state("spotify:album:a").toPlayRequest())
        assertEquals(JsonPrimitive(false), args["shuffle"])
        assertEquals(JsonPrimitive(false), args["smartShuffle"])
        assertEquals(JsonPrimitive("off"), args["repeat"])
    }
}
