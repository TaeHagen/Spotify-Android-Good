package com.taehagen.spotifygood.ui.screens.search

import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.ui.screens.album.retryWhenOnline
import com.taehagen.spotifygood.ui.screens.library.PagedState
import com.taehagen.spotifygood.ui.screens.library.attempt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

// Searches and the session: native catalog calls don't wait for one, they fail NOT_CONNECTED at
// once while it is connecting or reconnecting (cold start, idle stop, a network change). Search
// waits for a connecting session (bounded, `awaitConnectingSession`) and searches again by itself
// once it is ONLINE. The shared pieces are in album/SessionReach.kt.

/** One run of a search, as [searchWhenOnline] reports it. */
internal sealed interface SearchAttempt<out T> {
    /** A new run started (after a reconnect); the first run is not announced. */
    data object Running : SearchAttempt<Nothing>

    data class Succeeded<T>(val value: T) : SearchAttempt<T>

    data class Failed(val error: Throwable) : SearchAttempt<Nothing>
}

/**
 * Runs [search] after [awaitSession] (bounded wait for a connecting session) and emits its
 * outcome. A failure is shown ([SearchAttempt.Failed]) and is final unless it may be the session's:
 * a search that started while [currentReach] was not ONLINE runs again once [reach] is ONLINE (at
 * once if it already is); one that started ONLINE and failed with a connection error
 * ([isConnectionError]) runs again after the session reconnected (left ONLINE and came back). So a
 * search issued while connecting ends with results without a Retry tap, and an error that is not
 * the connection's never loops. Completes after a success or a final failure.
 */
internal fun <T> searchWhenOnline(
    reach: Flow<EngineReach>,
    currentReach: () -> EngineReach,
    awaitSession: suspend () -> Unit,
    isConnectionError: (Throwable) -> Boolean,
    search: suspend () -> T,
): Flow<SearchAttempt<T>> = flow {
    while (true) {
        awaitSession()
        val startedOnline = currentReach() == EngineReach.ONLINE
        val result = attempt { search() }
        val error = result.exceptionOrNull()
        if (error == null) {
            emit(SearchAttempt.Succeeded(result.getOrThrow()))
            return@flow
        }
        emit(SearchAttempt.Failed(error))
        when {
            !startedOnline -> reach.first { it == EngineReach.ONLINE }
            isConnectionError(error) -> reach.dropWhile { it == EngineReach.ONLINE }.first { it == EngineReach.ONLINE }
            else -> return@flow
        }
        emit(SearchAttempt.Running)
    }
}

/**
 * Each time [reach] becomes ONLINE (a change, not the value at start): once the list on screen
 * ([state], null without one) has settled, [retry]s it if it ended with an error, e.g. a page that
 * failed NOT_CONNECTED while the session was connecting. Waiting for the settle covers a request
 * that fails just after the session came online. Runs until cancelled.
 */
internal suspend fun <T> retryFailedListWhenOnline(
    reach: Flow<EngineReach>,
    state: () -> StateFlow<PagedState<T>>?,
    retry: () -> Unit,
) = retryWhenOnline(
    reach,
    settled = { state()?.let { list -> list to list.first { !it.isLoading } } },
    needsRetry = { (list, settled) -> settled.error != null && state() === list },
    retry = retry,
)

/**
 * Keeps a result list loaded across connectivity changes: whenever [reach] is reachable (ONLINE, or
 * CONNECTING: the fetch waits for the session itself) and the settled list has nothing loaded or
 * ended with an error, [load]s. The change to ONLINE checks again, so a page that failed while the
 * session was connecting loads once it is online. Runs until cancelled.
 */
internal suspend fun <T> keepLoadedWhileReachable(
    reach: Flow<EngineReach>,
    state: StateFlow<PagedState<T>>,
    load: () -> Unit,
) {
    reach.collectLatest { current ->
        if (current == EngineReach.OFFLINE) return@collectLatest
        val settled = state.first { !it.isLoading }
        if (settled.items.isEmpty() || settled.error != null) load()
    }
}
