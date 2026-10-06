package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.NativeErrorInfo
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
        for (context in listOf(null, "spotify:track:t", "spotify:episode:e")) {
            val request = state(context).toPlayRequest()
            assertNull(request.contextUri)
            assertEquals(listOf("spotify:track:t"), request.trackUris)
            assertEquals("spotify:track:t", request.startUri)
            assertEquals(42_000L, request.positionMs)
        }
        val args = PlayerController.loadArgs(state(null).toPlayRequest())
        assertEquals(listOf("spotify:track:t"), args["trackUris"]?.jsonArray?.map { it.jsonPrimitive.content })
    }
}
