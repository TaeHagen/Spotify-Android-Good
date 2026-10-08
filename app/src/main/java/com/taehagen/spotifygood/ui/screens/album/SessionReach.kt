package com.taehagen.spotifygood.ui.screens.album

import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.ui.screens.library.TRACK_START_SESSION_WAIT_MS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

// Browsing and the session (docs §9.8): native catalog calls don't wait for one, they fail
// NOT_CONNECTED at once while it is connecting or reconnecting (cold start, idle stop, a network
// change, a link opened from another app). Screens wait for a connecting session (bounded) and
// load again by themselves once it is ONLINE. Search, the detail pages, the profile and the
// library all use these.

/**
 * Whether a session is connecting right now and waiting for it is worth it: the reach is
 * CONNECTING (a usable network, not offline mode) and the engine is not in backoff (it reported a
 * retry). Never while OFFLINE: a captive portal must not stall a page.
 */
internal fun AppGraph.sessionConnecting(): Boolean =
    engineReach() == EngineReach.CONNECTING && engine.state.value.nextRetryMs == null

/**
 * Bounded wait for a session that is connecting right now ([sessionConnecting]), before a catalog
 * call: returns once it is ONLINE, at once when nothing is connecting (offline, backoff, ERROR), or
 * after [TRACK_START_SESSION_WAIT_MS].
 */
internal suspend fun AppGraph.awaitConnectingSession() {
    if (sessionConnecting()) engine.awaitOnline(TRACK_START_SESSION_WAIT_MS)
}

/**
 * Each time [reach] becomes ONLINE (a change, not the value at start): once what is on screen has
 * settled ([settled] suspends until it is no longer loading; null: nothing to check), [retry]s it
 * when [needsRetry] (it failed, or shows a stale or downloaded copy). Waiting for the settle covers
 * a request that fails just after the session came online. Runs until cancelled.
 */
internal suspend fun <S : Any> retryWhenOnline(
    reach: Flow<EngineReach>,
    settled: suspend () -> S?,
    needsRetry: (S) -> Boolean,
    retry: () -> Unit,
) {
    reach.drop(1).filter { it == EngineReach.ONLINE }.collectLatest {
        val state = settled() ?: return@collectLatest
        if (needsRetry(state)) retry()
    }
}

/**
 * A stale-while-revalidate page [load] (Loading(cached) → Success / Error) with the session in
 * mind. It starts at once, so a cached copy shows right away. When the session is [connecting] at
 * the start, a connection failure of that load (the session isn't up yet) is not shown as an error
 * ("You're offline" to an online user): the page stays loading, its cached copy refreshing, while
 * [awaitSession] waits for the session (bounded), and then [load] runs again; whatever that one
 * gives is shown, failures included ([retryWhenOnline] reloads them once the session is ONLINE).
 * Otherwise (online, offline, in backoff) [load] passes through unchanged.
 */
internal fun <T> resourceOnceConnected(
    connecting: () -> Boolean,
    awaitSession: suspend () -> Unit,
    load: () -> Flow<Resource<T>>,
): Flow<Resource<T>> = flow {
    if (!connecting()) {
        emitAll(load())
        return@flow
    }
    var deferred = false
    emitAll(
        load().transformWhile { resource ->
            if (resource is Resource.Error && isNetworkClassError(resource.error)) {
                deferred = true
                emit(Resource.Loading(resource.cached))
                false
            } else {
                emit(resource)
                true
            }
        },
    )
    if (!deferred) return@flow
    awaitSession()
    emitAll(load())
}

/** [resourceOnceConnected] with this graph's session: for page loads started from a screen. */
internal fun <T> AppGraph.resourceOnceConnected(load: () -> Flow<Resource<T>>): Flow<Resource<T>> =
    resourceOnceConnected({ sessionConnecting() }, { awaitConnectingSession() }, load)
