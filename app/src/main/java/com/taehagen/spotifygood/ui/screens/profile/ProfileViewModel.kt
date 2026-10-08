package com.taehagen.spotifygood.ui.screens.profile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.model.PlaylistOwner
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.Rootlist
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.screens.album.awaitConnectingSession
import com.taehagen.spotifygood.ui.screens.album.engineReachFlow
import com.taehagen.spotifygood.ui.screens.album.retryWhenOnline
import com.taehagen.spotifygood.ui.screens.library.BrowseError
import com.taehagen.spotifygood.ui.screens.library.attempt
import com.taehagen.spotifygood.ui.screens.library.offlineFlow
import com.taehagen.spotifygood.ui.screens.library.toBrowseError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ProfileUiState(
    val isMe: Boolean = true,
    val user: User? = null,
    val isLoading: Boolean = true,
    val error: BrowseError? = null,
    /** My own playlists (from the rootlist), or another user's public playlists (up to 50). */
    val playlists: List<PlaylistRef> = emptyList(),
    val followedArtists: Int? = null,
    val loggingOut: Boolean = false,
    /** The logged-in user, to tell which playlists are mine (rename / delete). */
    val myUsername: String? = null,
)

/** [loading]: nothing to show yet; [pending]: the fetch is still running (a cached user may show). */
private data class Fetched(val user: User?, val error: Throwable?, val loading: Boolean, val pending: Boolean = false)

/** Profile of [username] (null = the logged-in user). */
class ProfileViewModel(private val graph: AppGraph, private val username: String?) : ViewModel() {
    private val retry = MutableStateFlow(0)
    private val loggingOut = MutableStateFlow(false)

    private val me: Flow<User?> = graph.engine.user

    private val isMe: Flow<Boolean> = me.map { user -> username == null || username == user?.username }.distinctUntilChanged()

    /** The last [fetched] value, for the reload once the session is ONLINE (see init). */
    private val lastFetched = MutableStateFlow<Fetched?>(null)

    /**
     * Cached session user first (instant), then the fresh profile. Opened while the session
     * connects (a profile link, cold start), the fetch waits for it (bounded) instead of failing
     * NOT_CONNECTED; one that failed anyway runs again once the session is ONLINE.
     */
    private val fetched: Flow<Fetched> = combine(retry, isMe, graph.offlineFlow(), ::Triple)
        .transformLatest { (_, isMe, offline) ->
            val cached = if (isMe) graph.engine.user.value else null
            val skip = offline && cached != null
            emit(Fetched(cached, null, loading = cached == null, pending = !skip))
            if (skip) return@transformLatest
            graph.awaitConnectingSession()
            attempt { graph.catalog.user(if (isMe) null else username) }
                .onSuccess { emit(Fetched(it, null, loading = false)) }
                .onFailure { emit(Fetched(cached, it, loading = false)) }
        }
        .onEach { lastFetched.value = it }

    /** My playlists from the rootlist (another user's come with their profile, [publicPlaylistsOf]). */
    private val myPlaylists: Flow<List<PlaylistRef>> = combine(isMe, me, ::Pair).flatMapLatest { (isMe, user) ->
        if (!isMe || user == null) {
            flowOf(emptyList())
        } else {
            graph.library.playlists().map { resource -> resource.dataOrNull?.ownedBy(user.username).orEmpty() }
        }
    }.catch { emit(emptyList()) }.onStart { emit(emptyList()) }.distinctUntilChanged()

    private val followed: Flow<Int?> = isMe.flatMapLatest { isMe ->
        if (!isMe) flowOf(null) else graph.library.artists().map { it.dataOrNull?.size }
    }.catch { emit(null) }.onStart { emit(null) }.distinctUntilChanged()

    private val myUsername: Flow<String?> = me.map { it?.username }.distinctUntilChanged()

    val state: StateFlow<ProfileUiState> = combine(
        isMe,
        fetched,
        myPlaylists,
        followed,
        combine(loggingOut, myUsername, ::Pair),
    ) { isMe, fetched, myPlaylists, followed, (loggingOut, myUsername) ->
        ProfileUiState(
            isMe = isMe,
            user = fetched.user,
            isLoading = fetched.loading,
            error = if (fetched.user == null) fetched.error?.toBrowseError() else null,
            playlists = if (isMe) myPlaylists else fetched.user?.let(::publicPlaylistsOf).orEmpty(),
            followedArtists = followed,
            loggingOut = loggingOut,
            myUsername = myUsername,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState(isMe = username == null))

    init {
        viewModelScope.launch {
            retryWhenOnline(
                graph.engineReachFlow(),
                settled = { lastFetched.first { it != null && !it.pending } },
                needsRetry = { it.error != null },
                retry = { retry() },
            )
        }
    }

    fun retry() = retry.update { it + 1 }

    /** Logs out and wipes account data; runs in the app scope so leaving the screen can't cancel it. */
    fun logout() {
        if (loggingOut.value) return
        loggingOut.value = true
        graph.appScope.launch {
            attempt { graph.logout() }
            loggingOut.value = false
        }
    }
}

/** Playlists in the rootlist owned by [username], folders flattened. */
fun Rootlist.ownedBy(username: String): List<PlaylistRef> = flatPlaylists().mapNotNull { entry ->
    val uri = entry.uri
    if (uri == null || entry.owner?.username != username) null else PlaylistRef(uri = uri, name = entry.name, images = entry.images, owner = entry.owner)
}.distinctBy { it.uri }

/** Most public playlists listed for another user (`catalog.user` sends up to this many). */
const val MAX_PUBLIC_PLAYLISTS = 50

/** Another user's public playlists; ones without an owner are attributed to [user] (their profile). */
fun publicPlaylistsOf(user: User): List<PlaylistRef> =
    user.publicPlaylists
        .filter { it.uri.isNotBlank() }
        .distinctBy { it.uri }
        .take(MAX_PUBLIC_PLAYLISTS)
        .map { ref -> if (ref.owner != null) ref else ref.copy(owner = PlaylistOwner(user.username, user.displayName)) }

/**
 * Action sheet target of a profile playlist. Rename / delete are offered only for a playlist I own:
 * another user's profile lists their playlists, not mine.
 */
fun profilePlaylistTarget(playlist: PlaylistRef, myUsername: String?): MediaActionTarget.PlaylistTarget =
    MediaActionTarget.PlaylistTarget(playlist, isOwned = myUsername != null && playlist.owner?.username == myUsername)
