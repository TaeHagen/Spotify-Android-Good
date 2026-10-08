package com.taehagen.spotifygood.ui.screens.search

import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.ui.screens.album.isNetworkClassError
import com.taehagen.spotifygood.ui.screens.library.PageResult
import com.taehagen.spotifygood.ui.screens.library.PagedLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A search issued while the session connects (cold start, idle stop, a network change) must end
 * with results by itself: native calls fail NOT_CONNECTED until the session is ONLINE.
 */
class SearchSessionTest {
    private fun native(code: String) = NativeException(NativeErrorInfo(code, "boom"))

    /** A fake engine: catalog calls fail NOT_CONNECTED unless [reach] is ONLINE. */
    private class FakeSession(initial: EngineReach) {
        val reach = MutableStateFlow(initial)
        var calls = 0

        fun search(): String {
            calls++
            if (reach.value != EngineReach.ONLINE) throw NativeException(NativeErrorInfo(NativeErrorCode.NOT_CONNECTED, "no session"))
            return "results"
        }

        /** What [awaitConnectingSession] does: bounded wait while CONNECTING. */
        suspend fun awaitConnecting() {
            if (reach.value == EngineReach.CONNECTING) withTimeoutOrNull(10_000) { reach.first { it != EngineReach.CONNECTING } }
        }
    }

    private fun TestScope.collect(session: FakeSession, awaitSession: suspend () -> Unit = session::awaitConnecting): MutableList<SearchAttempt<String>> {
        val runs = mutableListOf<SearchAttempt<String>>()
        launch {
            searchWhenOnline(session.reach, { session.reach.value }, awaitSession, ::isNetworkClassError) { session.search() }.toList(runs)
        }
        return runs
    }

    @Test
    fun aSearchWhileConnectingWaitsAndEndsWithResults() = runTest {
        val session = FakeSession(EngineReach.CONNECTING)
        val runs = collect(session)
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue("still waiting, no error shown", runs.isEmpty())
        assertEquals(0, session.calls)

        session.reach.value = EngineReach.ONLINE
        advanceUntilIdle()
        assertEquals(listOf<SearchAttempt<String>>(SearchAttempt.Succeeded("results")), runs)
        assertEquals(1, session.calls)
    }

    @Test
    fun aSearchThatFailedBeforeOnlineRunsAgainOnceOnlineWithoutRetry() = runTest {
        // In backoff (or the bounded wait ran out): the first attempt fails NOT_CONNECTED.
        val session = FakeSession(EngineReach.CONNECTING)
        val runs = collect(session, awaitSession = {})
        runCurrent()
        assertEquals(1, runs.size)
        assertTrue(runs[0] is SearchAttempt.Failed)

        advanceTimeBy(60_000)
        assertEquals("no polling while the session is down", 1, session.calls)

        session.reach.value = EngineReach.ONLINE
        advanceUntilIdle()
        assertEquals(SearchAttempt.Running, runs[1])
        assertEquals(SearchAttempt.Succeeded("results"), runs[2])
        assertEquals(2, session.calls)
    }

    @Test
    fun aSessionThatCameOnlineDuringTheFailingCallIsRetriedAtOnce() = runTest {
        val session = FakeSession(EngineReach.CONNECTING)
        val runs = mutableListOf<SearchAttempt<String>>()
        launch {
            searchWhenOnline(session.reach, { session.reach.value }, {}, ::isNetworkClassError) {
                session.calls++
                if (session.calls == 1) {
                    // Fails NOT_CONNECTED, but the session is ONLINE by the time the error arrives.
                    session.reach.value = EngineReach.ONLINE
                    throw native(NativeErrorCode.NOT_CONNECTED)
                }
                "results"
            }.toList(runs)
        }
        advanceUntilIdle()
        assertEquals(SearchAttempt.Succeeded("results"), runs.last())
        assertEquals(2, session.calls)
    }

    @Test
    fun aConnectionErrorWhileOnlineRunsAgainAfterAReconnectOnly() = runTest {
        val reach = MutableStateFlow(EngineReach.ONLINE)
        var calls = 0
        val runs = mutableListOf<SearchAttempt<String>>()
        launch {
            searchWhenOnline(reach, { reach.value }, {}, ::isNetworkClassError) {
                calls++
                if (calls == 1) throw native(NativeErrorCode.NETWORK)
                "results"
            }.toList(runs)
        }
        advanceTimeBy(60_000)
        assertEquals("no loop while it stays online", 1, calls)
        assertTrue(runs.single() is SearchAttempt.Failed)

        reach.value = EngineReach.CONNECTING
        runCurrent()
        reach.value = EngineReach.ONLINE
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(SearchAttempt.Succeeded("results"), runs.last())
    }

    @Test
    fun anErrorThatIsNotTheConnectionsIsFinal() = runTest {
        val reach = MutableStateFlow(EngineReach.ONLINE)
        var calls = 0
        val runs = mutableListOf<SearchAttempt<String>>()
        val job = launch {
            searchWhenOnline(reach, { reach.value }, {}, ::isNetworkClassError) {
                calls++
                throw native(NativeErrorCode.INTERNAL)
            }.toList(runs)
        }
        advanceUntilIdle()
        assertTrue("the flow completed", job.isCompleted)
        assertEquals(1, calls)
        assertTrue(runs.single() is SearchAttempt.Failed)
    }

    @Test
    fun aTypedListThatFailedWhileConnectingLoadsAgainOnceOnline() = runTest {
        val session = FakeSession(EngineReach.CONNECTING)
        val loader = PagedLoader(this, pageSize = 2, keyOf = { it }) { _, _ ->
            session.search()
            PageResult(listOf("a", "b"))
        }
        val watcher = launch { retryFailedListWhenOnline(session.reach, { loader.state }) { loader.loadMore() } }
        loader.loadMore()
        runCurrent()
        assertTrue(loader.state.value.error != null)

        session.reach.value = EngineReach.ONLINE
        runCurrent()
        assertNull(loader.state.value.error)
        assertEquals(listOf("a", "b"), loader.state.value.items)
        assertEquals(2, session.calls)
        watcher.cancel()
    }

    @Test
    fun aPageFailingJustAfterTheSessionCameOnlineIsRetriedToo() = runTest {
        val reach = MutableStateFlow(EngineReach.CONNECTING)
        val firstCall = CompletableDeferred<Unit>()
        var calls = 0
        val loader = PagedLoader(this, pageSize = 2, keyOf = { it }) { _, _ ->
            calls++
            if (calls == 1) {
                firstCall.await()
                throw native(NativeErrorCode.NOT_CONNECTED)
            }
            PageResult(listOf("a"))
        }
        val watcher = launch { retryFailedListWhenOnline(reach, { loader.state }) { loader.loadMore() } }
        loader.loadMore()
        runCurrent()
        reach.value = EngineReach.ONLINE // while the first request is still in flight
        runCurrent()
        firstCall.complete(Unit) // ... which then fails
        runCurrent()
        assertEquals(2, calls)
        assertEquals(listOf("a"), loader.state.value.items)
        watcher.cancel()
    }

    @Test
    fun resultsPageLoadsWhenReachableAndAgainOnceOnline() = runTest {
        val session = FakeSession(EngineReach.OFFLINE)
        val loader = PagedLoader(this, pageSize = 2, keyOf = { it }) { _, _ ->
            session.search()
            PageResult(listOf("a", "b"))
        }
        val keeper = launch { keepLoadedWhileReachable(session.reach, loader.state) { loader.loadMore() } }
        runCurrent()
        assertEquals("nothing is requested offline", 0, session.calls)

        session.reach.value = EngineReach.CONNECTING // e.g. in backoff: the request fails
        runCurrent()
        assertEquals(1, session.calls)
        assertTrue(loader.state.value.error != null)

        session.reach.value = EngineReach.ONLINE
        runCurrent()
        assertEquals(2, session.calls)
        assertEquals(listOf("a", "b"), loader.state.value.items)

        // A later reconnect with the list loaded fetches nothing.
        session.reach.value = EngineReach.CONNECTING
        runCurrent()
        session.reach.value = EngineReach.ONLINE
        runCurrent()
        assertEquals(2, session.calls)
        keeper.cancel()
    }
}
