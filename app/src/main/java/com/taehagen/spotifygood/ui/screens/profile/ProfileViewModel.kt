package com.taehagen.spotifygood.ui.screens.profile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.Rootlist
import com.taehagen.spotifygood.model.User
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    /** Playlists owned by this user (only known for the logged-in user, from the rootlist). */
    val playlists: List<MediaRef> = emptyList(),
    val followedArtists: Int? = null,
    val loggingOut: Boolean = false,
)

private data class Fetched(val user: User?, val error: Throwable?, val loading: Boolean)

/** Profile of [username] (null = the logged-in user). */
class ProfileViewModel(private val graph: AppGraph, private val username: String?) : ViewModel() {
    private val retry = MutableStateFlow(0)
    private val loggingOut = MutableStateFlow(false)

    private val me: Flow<User?> = graph.engine.user

    private val isMe: Flow<Boolean> = me.map { user -> username == null || username == user?.username }.distinctUntilChanged()

    /** Cached session user first (instant), then the fresh profile. */
    private val fetched: Flow<Fetched> = combine(retry, isMe, graph.offlineFlow(), ::Triple)
        .transformLatest { (_, isMe, offline) ->
            val cached = if (isMe) graph.engine.user.value else null
            emit(Fetched(cached, null, loading = cached == null))
            if (offline && cached != null) return@transformLatest
            attempt { graph.catalog.user(if (isMe) null else username) }
                .onSuccess { emit(Fetched(it, null, loading = false)) }
                .onFailure { emit(Fetched(cached, it, loading = false)) }
        }

    private val playlists: Flow<List<MediaRef>> = combine(isMe, me, ::Pair).flatMapLatest { (isMe, user) ->
        if (!isMe || user == null) {
            flowOf(emptyList())
        } else {
            graph.library.playlists().map { resource -> resource.dataOrNull?.ownedBy(user.username).orEmpty() }
        }
    }.catch { emit(emptyList()) }.onStart { emit(emptyList()) }.distinctUntilChanged()

    private val followed: Flow<Int?> = isMe.flatMapLatest { isMe ->
        if (!isMe) flowOf(null) else graph.library.artists().map { it.dataOrNull?.size }
    }.catch { emit(null) }.onStart { emit(null) }.distinctUntilChanged()

    val state: StateFlow<ProfileUiState> = combine(isMe, fetched, playlists, followed, loggingOut) { isMe, fetched, playlists, followed, loggingOut ->
        ProfileUiState(
            isMe = isMe,
            user = fetched.user,
            isLoading = fetched.loading,
            error = if (fetched.user == null) fetched.error?.toBrowseError() else null,
            playlists = playlists,
            followedArtists = followed,
            loggingOut = loggingOut,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState(isMe = username == null))

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
fun Rootlist.ownedBy(username: String): List<MediaRef> = flatPlaylists().mapNotNull { entry ->
    val uri = entry.uri
    if (uri == null || entry.owner?.username != username) null else MediaRef(MediaType.PLAYLIST, uri, entry.name, entry.owner.displayName, entry.images)
}.distinctBy { it.uri }
