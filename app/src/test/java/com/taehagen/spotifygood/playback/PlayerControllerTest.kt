package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControllerTest {
    private val playlist = "spotify:playlist:p"
    private fun t(n: Int) = "spotify:track:$n"

    private class Env(var reach: EngineReach, var members: OfflineMembers? = null) : PlaybackEnvironment {
        var gate: CompletableDeferred<Unit>? = null
        var gateCalls = 0
        override fun reach() = reach
        override suspend fun awaitSessionStart() {
            gateCalls++
            gate?.await()
        }
        override suspend fun downloadedMembers(contextUri: String, startUri: String?) = members
    }

    private class Harness(scope: TestScope, val env: Env?, val resume: ResumeState? = null) {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var fail: (String) -> NativeException? = { null }
        /** Suspends inside the transport (e.g. a slow request to a remote device). */
        var hold: suspend (String) -> Unit = {}
        val snapshot = MutableStateFlow(PlaybackSnapshot())
        val controller = PlayerController(
            scope = scope.backgroundScope,
            transport = { method, args ->
                calls += method to args
                hold(method)
                fail(method)?.let { throw it }
                JsonObject(emptyMap()) as JsonElement
            },
            json = Json,
            snapshot = snapshot,
            lastSession = { resume },
        ).also { c ->
            env?.let { c.environment = it }
            c.errorMessages = PlaybackErrorMessages { kind, _ -> kind.name }
        }
        val errors = mutableListOf<String>()

        init {
            scope.backgroundScope.launch { controller.errors.collect { errors += it } }
        }

        fun methods() = calls.map { it.first }
    }

    private fun resumeState(context: String?) = ResumeState(
        contextUri = context, trackUri = t(2), positionMs = 1_000, title = null, artist = null,
        album = null, artworkUrl = null, durationMs = null, isEpisode = false,
    )

    @Test
    fun offlineContextLoadSendsTheDownloadsInOrder() = runTest {
        val h = Harness(this, Env(EngineReach.OFFLINE, OfflineMembers(listOf(t(1), t(2), t(3)), setOf(t(1), t(3)))))
        assertTrue(h.controller.playAsync(PlayRequest(contextUri = playlist, startUri = t(3))).await())
        val (method, args) = h.calls.single()
        assertEquals("player.load", method)
        assertEquals(listOf(t(1), t(3)), args["trackUris"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(1, args["startIndex"]?.jsonPrimitive?.content?.toInt())
        assertEquals(playlist, args["contextUri"]?.jsonPrimitive?.content)
    }

    @Test
    fun offlineWithNothingDownloadedSaysSoWithoutCallingTheEngine() = runTest {
        val h = Harness(this, Env(EngineReach.OFFLINE, OfflineMembers(listOf(t(1)), emptySet())))
        assertFalse(h.controller.playAsync(PlayRequest(contextUri = playlist)).await())
        runCurrent()
        assertTrue(h.calls.isEmpty())
        assertEquals(listOf(PlaybackErrorKind.NOT_AVAILABLE_OFFLINE.name), h.errors)
        assertEquals(PlaybackErrorKind.NOT_AVAILABLE_OFFLINE, h.controller.failure.value?.kind)
    }

    @Test
    fun onlineLoadsAreSentUnchanged() = runTest {
        val h = Harness(this, Env(EngineReach.ONLINE, OfflineMembers(listOf(t(1)), setOf(t(1)))))
        assertTrue(h.controller.playAsync(PlayRequest(contextUri = playlist)).await())
        assertNull(h.calls.single().second["trackUris"])
    }

    @Test
    fun playWaitsForAStartingSession() = runTest {
        val env = Env(EngineReach.CONNECTING).apply { gate = CompletableDeferred() }
        val h = Harness(this, env)
        val done = h.controller.resumeAsync()
        runCurrent()
        assertEquals(1, env.gateCalls)
        assertTrue("nothing sent before the session is up", h.calls.isEmpty())
        env.reach = EngineReach.ONLINE
        env.gate!!.complete(Unit)
        assertTrue(done.await())
        assertEquals(listOf("player.play"), h.methods())
    }

    @Test
    fun pauseCancelsAPlayWaitingForTheSession() = runTest {
        val env = Env(EngineReach.CONNECTING).apply { gate = CompletableDeferred() }
        val h = Harness(this, env)
        val play = h.controller.resumeAsync()
        runCurrent()
        val pause = h.controller.pauseAsync()
        assertFalse(play.await())
        assertTrue(pause.await())
        assertEquals(listOf("player.pause"), h.methods())
    }

    @Test
    fun pauseCancelsEveryStartQueuedWhileTheSessionStarts() = runTest {
        val env = Env(EngineReach.CONNECTING).apply { gate = CompletableDeferred() }
        val h = Harness(this, env)
        val load = h.controller.playAsync(PlayRequest(contextUri = playlist, play = false))
        val skip = h.controller.skipToAsync("uid-1")
        runCurrent()
        val pause = h.controller.pauseAsync()
        env.reach = EngineReach.ONLINE
        env.gate!!.complete(Unit)
        assertFalse(load.await())
        assertFalse(skip.await())
        assertTrue(pause.await())
        assertEquals(listOf("player.pause"), h.methods())
        // A start queued after the pause runs normally.
        assertTrue(h.controller.resumeAsync().await())
        assertEquals(listOf("player.pause", "player.play"), h.methods())
    }

    @Test
    fun playMergesIntoAPausedLoadThatWasNotSentYet() = runTest {
        val h = Harness(this, Env(EngineReach.ONLINE), resume = resumeState("spotify:album:stored"))
        val controller = h.controller
        // Media3: setMediaItems (load, play = false), then play().
        val load = controller.playAsync(PlayRequest(contextUri = playlist, play = false))
        val play = controller.resumeAsync()
        assertTrue(load.await())
        assertTrue(play.await())
        assertEquals(listOf("player.load"), h.methods())
        assertEquals("true", h.calls.single().second["play"]?.jsonPrimitive?.content)
    }

    @Test
    fun playRightAfterALoadWaitsForActivationInsteadOfLoadingTheStoredSession() = runTest {
        val h = Harness(this, Env(EngineReach.ONLINE), resume = resumeState("spotify:album:stored"))
        var plays = 0
        h.fail = { method ->
            when (method) {
                // The engine activates this device asynchronously after the load.
                "player.load" -> {
                    backgroundScope.launch {
                        kotlinx.coroutines.delay(200)
                        h.snapshot.value = PlaybackSnapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, track = PlaybackTrack(uri = t(1)))
                    }
                    null
                }
                "player.play" -> if (plays++ == 0) NativeException(NativeErrorInfo(NativeErrorCode.NOT_ACTIVE_DEVICE, "inactive")) else null
                else -> null
            }
        }
        assertTrue(h.controller.playAsync(PlayRequest(contextUri = playlist, play = false)).await())
        assertTrue(h.controller.resumeAsync().await())
        assertEquals(listOf("player.load", "player.play", "player.play"), h.methods())
    }

    @Test
    fun playQueuedBehindAFailingLoadIsDropped() = runTest {
        val h = Harness(this, Env(EngineReach.ONLINE), resume = resumeState("spotify:album:stored"))
        h.fail = { if (it == "player.load") NativeException(NativeErrorInfo(NativeErrorCode.UNAVAILABLE, "gone")) else null }
        val load = h.controller.playAsync(PlayRequest(contextUri = playlist, play = false))
        // Something queued in between, so the play cannot merge into the load.
        h.controller.seekAsync(0)
        val play = h.controller.resumeAsync()
        assertFalse(load.await())
        assertFalse(play.await())
        runCurrent()
        assertEquals(listOf("player.load", "player.seek"), h.methods())
        assertEquals("only the load's error", listOf(PlaybackErrorKind.UNAVAILABLE.name), h.errors)
    }

    @Test
    fun countedQueueAddsStopAtTheFirstFailureAndLeaveTheMessageToTheCaller() = runTest {
        val h = Harness(this, null)
        var adds = 0
        val full = NativeException(NativeErrorInfo(NativeErrorCode.UNAVAILABLE, "The queue is full"))
        h.fail = { if (it == "queue.add" && ++adds > 2) full else null }
        val result = h.controller.addToQueueCounted(listOf(t(1), t(2), t(3), t(4))).await()
        runCurrent()
        assertEquals(QueueAddResult(2, full.info), result)
        assertEquals(listOf("queue.add", "queue.add", "queue.add"), h.methods())
        assertTrue("no generic player error", h.errors.isEmpty())
        assertEquals(QueueAddResult(0, null), h.controller.addToQueueCounted(emptyList()).await())
    }

    @Test
    fun bulkQueueAddsLetOtherCommandsThroughAndEachAddHasItsOwnTimeout() = runTest {
        val h = Harness(this, null)
        val slow = CompletableDeferred<Unit>()
        var adds = 0
        h.hold = { method -> if (method == "queue.add" && ++adds == 2) slow.await() }
        val result = h.controller.addToQueueCounted(listOf(t(1), t(2), t(3)))
        runCurrent()
        // The user pauses while the second add is still in flight: it does not wait for the rest.
        val pause = h.controller.pauseAsync()
        slow.complete(Unit)
        assertTrue(pause.await())
        assertEquals(QueueAddResult(3, null), result.await())
        assertEquals(listOf("queue.add", "queue.add", "player.pause", "queue.add"), h.methods())

        // A request that never answers fails that item (and stops the batch) after its own timeout.
        h.calls.clear()
        adds = 0
        h.hold = { method -> if (method == "queue.add" && ++adds == 2) CompletableDeferred<Unit>().await() }
        val stuck = h.controller.addToQueueCounted(listOf(t(1), t(2), t(3))).await()
        assertEquals(1, stuck.added)
        assertEquals(NativeErrorCode.NETWORK, stuck.error?.code)
        assertEquals(listOf("queue.add", "queue.add"), h.methods())
        runCurrent()
        assertTrue("silent: the caller reports it", h.errors.isEmpty())
    }

    @Test
    fun bulkQueueAddsStayInOrderAndAreCappedAtTheQueueSize() = runTest {
        val h = Harness(this, null)
        val first = h.controller.addToQueueCounted(listOf(t(1), t(2)))
        val second = h.controller.addToQueueCounted(listOf(t(3), t(4)))
        assertEquals(QueueAddResult(2, null), first.await())
        assertEquals(QueueAddResult(2, null), second.await())
        assertEquals((1..4).map(::t), h.calls.map { it.second["uri"]?.jsonPrimitive?.content })

        h.calls.clear()
        val many = h.controller.addToQueueCounted((1..100).map(::t)).await()
        assertEquals(PlayerController.MAX_QUEUE_ADD, many.added)
        assertEquals(NativeErrorCode.UNAVAILABLE, many.error?.code)
        assertEquals(PlayerController.MAX_QUEUE_ADD, h.calls.size)
    }

    @Test
    fun clearingTheQueueStopsBulkAddsQueuedBeforeIt() = runTest {
        val h = Harness(this, null)
        val slow = CompletableDeferred<Unit>()
        var adds = 0
        h.hold = { method -> if (method == "queue.add" && ++adds == 1) slow.await() }
        val running = h.controller.addToQueueCounted(listOf(t(1), t(2), t(3)))
        val waiting = h.controller.addToQueueCounted(listOf(t(4), t(5)))
        runCurrent()
        h.controller.clearQueue()
        slow.complete(Unit)
        val r = running.await()
        val w = waiting.await()
        runCurrent()
        assertEquals(listOf("queue.add", "queue.clear"), h.methods())
        assertEquals(1, r.added)
        assertEquals(NativeErrorCode.CANCELLED, r.error?.code)
        assertEquals(QueueAddResult(0, w.error), w)
        assertEquals(NativeErrorCode.CANCELLED, w.error?.code)
        // A bulk add started after the clear runs normally.
        assertEquals(QueueAddResult(1, null), h.controller.addToQueueCounted(listOf(t(6))).await())
        assertEquals(listOf("queue.add", "queue.clear", "queue.add"), h.methods())
        assertTrue("silent", h.errors.isEmpty())
    }

    @Test
    fun aLoadStopsARunningBulkAdd() = runTest {
        val h = Harness(this, Env(EngineReach.ONLINE))
        val slow = CompletableDeferred<Unit>()
        h.hold = { method -> if (method == "queue.add") slow.await() }
        val bulk = h.controller.addToQueueCounted(listOf(t(1), t(2), t(3)))
        runCurrent()
        val load = h.controller.playAsync(PlayRequest(contextUri = playlist))
        slow.complete(Unit)
        assertEquals(NativeErrorCode.CANCELLED, bulk.await().error?.code)
        assertTrue(load.await())
        assertEquals(listOf("queue.add", "player.load"), h.methods())
    }

    @Test
    fun mediaSessionQueueAddsReportTheirFailure() = runTest {
        val h = Harness(this, null)
        h.fail = { if (it == "queue.add") NativeException(NativeErrorInfo(NativeErrorCode.UNAVAILABLE, "The queue is full")) else null }
        runCurrent() // the error collector is subscribed
        assertFalse(h.controller.addToQueueAsync(listOf(t(1), t(2))).await())
        runCurrent()
        assertEquals(listOf(PlaybackErrorKind.UNAVAILABLE.name), h.errors)
        assertEquals(1, h.calls.size)
    }

    @Test
    fun controlCommandsDoNotWaitForTheSession() = runTest {
        val env = Env(EngineReach.CONNECTING).apply { gate = CompletableDeferred() }
        val h = Harness(this, env)
        assertTrue(h.controller.nextAsync().await())
        assertEquals(0, env.gateCalls)
    }

    @Test
    fun notConnectedResumeLoadsTheLastSessionsDownloads() = runTest {
        val env = Env(EngineReach.OFFLINE, OfflineMembers(listOf(t(1), t(2)), setOf(t(1), t(2))))
        val h = Harness(this, env, resume = resumeState(playlist))
        h.fail = { if (it == "player.play") NativeException(NativeErrorInfo(NativeErrorCode.NOT_CONNECTED, "offline")) else null }
        assertTrue(h.controller.resumeAsync().await())
        assertEquals(listOf("player.play", "player.load"), h.methods())
        val load = h.calls[1].second
        assertEquals(listOf(t(1), t(2)), load["trackUris"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(1, load["startIndex"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1_000L, load["positionMs"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun notConnectedWhileMirroringARemoteDeviceDoesNotStartLocalPlayback() = runTest {
        val h = Harness(this, Env(EngineReach.OFFLINE), resume = resumeState(null))
        h.snapshot.value = PlaybackSnapshot(source = PlaybackSource.REMOTE, track = PlaybackTrack(uri = t(5)))
        h.fail = { NativeException(NativeErrorInfo(NativeErrorCode.NOT_CONNECTED, "offline")) }
        assertFalse(h.controller.resumeAsync().await())
        assertEquals(listOf("player.play"), h.methods())
    }

    @Test
    fun pauseWithNothingPlayingIsQuiet() = runTest {
        val h = Harness(this, null)
        h.fail = { NativeException(NativeErrorInfo(NativeErrorCode.NOT_CONNECTED, "offline")) }
        assertFalse(h.controller.pauseAsync().await())
        runCurrent()
        assertTrue(h.errors.isEmpty())
        assertNull(h.controller.failure.value)
    }

    @Test
    fun failureIsKeptUntilTheNextAttemptOrPlayback() = runTest {
        val h = Harness(this, null)
        h.fail = { NativeException(NativeErrorInfo(NativeErrorCode.PREMIUM_REQUIRED, "premium")) }
        assertFalse(h.controller.playAsync(PlayRequest(trackUris = listOf(t(1)))).await())
        assertEquals(PlaybackErrorKind.PREMIUM_REQUIRED, h.controller.failure.value?.kind)
        // A control command does not clear it, a new attempt does.
        h.controller.nextAsync().await()
        assertNotNull(h.controller.failure.value)
        h.fail = { null }
        assertTrue(h.controller.playAsync(PlayRequest(trackUris = listOf(t(1)))).await())
        assertNull(h.controller.failure.value)

        h.controller.noteFailure(PlaybackErrorKind.UNAVAILABLE, "gone")
        assertNotNull(h.controller.failure.value)
        h.snapshot.value = PlaybackSnapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PLAYING, track = PlaybackTrack(uri = t(1)))
        runCurrent()
        assertNull(h.controller.failure.value)
        // While something plays, failures are not sticky (the playing state is what to show).
        h.controller.noteFailure(PlaybackErrorKind.UNAVAILABLE, "gone")
        assertNull(h.controller.failure.value)
    }
}
