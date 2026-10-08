package com.taehagen.spotifygood.ui.screens.album

import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.EngineReach
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail pages opened while the session connects (a shared link, cold start, after the idle stop):
 * catalog calls fail NOT_CONNECTED until it is ONLINE. The page must end loaded by itself, never
 * telling an online user "You're offline", and a stale cached copy must be revalidated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionReachTest {
    private fun native(code: String) = NativeException(NativeErrorInfo(code, "boom"))

    /** A fake catalog: a stale-while-revalidate load that fails NOT_CONNECTED unless [reach] is ONLINE. */
    private class FakeCatalog(initial: EngineReach, private val cached: String? = null) {
        val reach = MutableStateFlow(initial)
        var calls = 0

        fun album(): Flow<Resource<String>> = flow {
            calls++
            emit(Resource.Loading(cached))
            if (reach.value == EngineReach.ONLINE) {
                emit(Resource.Success("album v$calls"))
            } else {
                emit(Resource.Error(NativeException(NativeErrorInfo(NativeErrorCode.NOT_CONNECTED, "no session")), cached))
            }
        }

        /** What [sessionConnecting] reports: connecting (not offline, not in backoff). */
        var backoff = false
        fun connecting() = reach.value == EngineReach.CONNECTING && !backoff

        /** What [awaitConnectingSession] does: bounded wait while connecting. */
        suspend fun awaitSession() {
            if (connecting()) withTimeoutOrNull(10_000) { reach.first { it == EngineReach.ONLINE } }
        }
    }

    /**
     * A page wired like AlbumViewModel: [retry] re-collects [resourceOnceConnected], and
     * [retryWhenOnline] with the page's settled state does what DetailViewModel.reloadWhenOnline does.
     */
    private class Page(scope: TestScope, catalog: FakeCatalog) {
        val retryTrigger = MutableStateFlow(0)
        val shown = mutableListOf<LoadState<String>>()
        val state = MutableStateFlow<LoadState<String>>(LoadState.Loading)

        init {
            scope.launch {
                retryTrigger
                    .flatMapLatest { resourceOnceConnected(catalog::connecting, catalog::awaitSession, catalog::album) }
                    .map { resource -> resource.toLoadState { it } }
                    .collect {
                        shown += it
                        state.value = it
                    }
            }
            scope.launch {
                retryWhenOnline(catalog.reach, { state.first { it.isSettled } }, { it.needsReloadWhenOnline }) {
                    retryTrigger.update { it + 1 }
                }
            }
        }
    }

    @Test
    fun aPageOpenedWhileConnectingEndsReadyWithoutRetryAndIsNeverShownOffline() = runTest {
        val catalog = FakeCatalog(EngineReach.CONNECTING)
        val page = Page(this, catalog)
        runCurrent()
        assertEquals("the first call failed NOT_CONNECTED", 1, catalog.calls)
        assertEquals(LoadState.Loading, page.state.value)

        advanceTimeBy(1_500) // AP connect + auth
        catalog.reach.value = EngineReach.ONLINE
        runCurrent()
        assertEquals(LoadState.Ready("album v2"), page.state.value)
        assertEquals(2, catalog.calls)
        assertFalse("never 'You're offline'", page.shown.any { it is LoadState.Failed })
        coroutineContext.cancelChildren()
    }

    @Test
    fun aCachedCopyShowsAtOnceWhileTheSessionConnects() = runTest {
        val catalog = FakeCatalog(EngineReach.CONNECTING, cached = "cached album")
        val page = Page(this, catalog)
        runCurrent()
        assertEquals(LoadState.Ready("cached album", refreshing = true), page.state.value)
        catalog.reach.value = EngineReach.ONLINE
        runCurrent()
        assertEquals(LoadState.Ready("album v2"), page.state.value)
        assertFalse(page.shown.any { it is LoadState.Ready && it.stale })
        coroutineContext.cancelChildren()
    }

    @Test
    fun aStaleCachedCopyIsRevalidatedOnceOnline() = runTest {
        // In backoff (no wait): the refresh fails and the 3-week-old copy shows stale.
        val catalog = FakeCatalog(EngineReach.CONNECTING, cached = "old rows").apply { backoff = true }
        val page = Page(this, catalog)
        runCurrent()
        assertEquals(LoadState.Ready("old rows", stale = true), page.state.value)

        catalog.reach.value = EngineReach.ONLINE
        runCurrent()
        assertEquals(LoadState.Ready("album v2"), page.state.value)
        coroutineContext.cancelChildren()
    }

    @Test
    fun aFailedPageLoadsAgainOnceOnline() = runTest {
        // The bounded wait ran out (slow AP login): the page shows its error, then loads by itself.
        val catalog = FakeCatalog(EngineReach.CONNECTING)
        val page = Page(this, catalog)
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(page.state.value is LoadState.Failed)

        catalog.reach.value = EngineReach.ONLINE
        runCurrent()
        assertEquals(LoadState.Ready("album v3"), page.state.value)
        coroutineContext.cancelChildren()
    }

    @Test
    fun aLoadedPageIsNotFetchedAgainOnReconnect() = runTest {
        val catalog = FakeCatalog(EngineReach.ONLINE)
        val page = Page(this, catalog)
        runCurrent()
        assertEquals(LoadState.Ready("album v1"), page.state.value)
        catalog.reach.value = EngineReach.CONNECTING
        runCurrent()
        catalog.reach.value = EngineReach.ONLINE
        runCurrent()
        assertEquals(1, catalog.calls)
        coroutineContext.cancelChildren()
    }

    @Test
    fun otherErrorsPassThroughWhileConnecting() = runTest {
        val results = mutableListOf<Resource<String>>()
        launch {
            resourceOnceConnected({ true }, { error("must not wait") }) {
                flow<Resource<String>> {
                    emit(Resource.Loading())
                    emit(Resource.Error(native(NativeErrorCode.NOT_FOUND)))
                }
            }.collect { results += it }
        }
        runCurrent()
        assertTrue(results.last() is Resource.Error)
    }

    @Test
    fun notConnectingPassesThrough() = runTest {
        var waited = false
        val results = mutableListOf<Resource<String>>()
        launch {
            resourceOnceConnected({ false }, { waited = true }) {
                flow<Resource<String>> {
                    emit(Resource.Loading())
                    emit(Resource.Error(native(NativeErrorCode.NOT_CONNECTED)))
                }
            }.collect { results += it }
        }
        runCurrent()
        assertFalse(waited)
        assertTrue("offline / backoff: the error is shown", results.last() is Resource.Error)
    }

    @Test
    fun aLoadStillRunningWhenOnlineIsWaitedForNotDuplicated() = runTest {
        val reach = MutableStateFlow(EngineReach.CONNECTING)
        val state = MutableStateFlow<LoadState<String>>(LoadState.Loading)
        var retries = 0
        val job = launch { retryWhenOnline(reach, { state.first { it.isSettled } }, { it.needsReloadWhenOnline }) { retries++ } }
        runCurrent()
        reach.value = EngineReach.ONLINE
        runCurrent()
        assertEquals(0, retries)
        state.value = LoadState.Failed(FailureReason.OFFLINE) // the in-flight request failed just after
        runCurrent()
        assertEquals(1, retries)
        job.cancel()
    }

    @Test
    fun whatNeedsAReloadOnceOnline() {
        assertTrue(LoadState.Failed(FailureReason.OFFLINE).needsReloadWhenOnline)
        assertTrue(LoadState.Ready("x", stale = true).needsReloadWhenOnline)
        assertFalse(LoadState.Ready("x").needsReloadWhenOnline)
        assertFalse(LoadState.Loading.isSettled)
        assertFalse(LoadState.Ready("x", refreshing = true).isSettled)
        assertTrue(LoadState.Ready("x", stale = true).isSettled)
    }
}
