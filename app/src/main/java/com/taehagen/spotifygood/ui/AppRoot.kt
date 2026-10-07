package com.taehagen.spotifygood.ui

import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.SearchType
import com.taehagen.spotifygood.engine.EngineState
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.SearchResults
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.ui.components.BackgroundMessages
import com.taehagen.spotifygood.ui.screens.login.LoginScreen
import com.taehagen.spotifygood.ui.screens.status.PremiumRequiredScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How the PLAYBACK_REFUSED condition is presented. */
enum class PlaybackRefusal { NONE, SCREEN, BANNER }

/** Which top-level surface the app shows. */
private enum class RootScreen { LOGIN, PREMIUM_REQUIRED, MAIN }

/** Voice search request ("Play X on SpotifyGood", MEDIA_PLAY_FROM_SEARCH). */
data class MediaSearchRequest(
    val query: String?,
    val focus: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
    val playlist: String? = null,
)

/**
 * Activity-scoped shell state: splash readiness, login/premium gating, the PLAYBACK_REFUSED
 * condition, deep links and "open Now Playing" requests waiting for the main scaffold and
 * voice-search playback.
 */
class ShellViewModel(private val graph: AppGraph) : ViewModel() {
    private val _ready = MutableStateFlow(false)
    /** False while the login state is still unknown (keeps the splash screen up). */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val engineState: StateFlow<EngineState> = graph.engine.state
    val isLoggedIn: StateFlow<Boolean> = graph.engine.isLoggedIn

    private val refusedEvent = MutableStateFlow(false)
    private val refusedDismissed = MutableStateFlow(false)

    val playbackRefusal: StateFlow<PlaybackRefusal> =
        combine(refusedEvent, graph.engine.state, refusedDismissed) { event, state, dismissed ->
            val refused = event || state.error?.code == NativeErrorCode.PLAYBACK_REFUSED
            when {
                !refused -> PlaybackRefusal.NONE
                dismissed -> PlaybackRefusal.BANNER
                else -> PlaybackRefusal.SCREEN
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackRefusal.NONE)

    private val links = Channel<String>(Channel.UNLIMITED)
    /** Deep links (spotify: URIs / open.spotify.com) to open once the main UI is up. */
    val pendingLinks: Flow<String> = links.receiveAsFlow()

    private val openPlayerRequests = Channel<Unit>(Channel.CONFLATED)
    /** Requests to show Now Playing (media notification / lock-screen taps), also from before login. */
    val pendingOpenPlayer: Flow<Unit> = openPlayerRequests.receiveAsFlow()

    private val mainSessions = MainSessionStore()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    /** Snackbar messages produced outside the navigator (intents, voice search). */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch { resolveInitialLoginState() }
        viewModelScope.launch {
            graph.events.errors.collect { error ->
                if (error.code == NativeErrorCode.PLAYBACK_REFUSED) {
                    refusedEvent.value = true
                    refusedDismissed.value = false
                }
            }
        }
        viewModelScope.launch {
            graph.engine.isLoggedIn.collect { loggedIn ->
                if (!loggedIn) {
                    refusedEvent.value = false
                    refusedDismissed.value = false
                }
            }
        }
        viewModelScope.launch {
            // Audible local playback proves the account is not refused (any more).
            graph.playback.snapshot.collect { snapshot ->
                if (snapshot.isPlaying && snapshot.source == PlaybackSource.LOCAL && snapshot.lastError == null) {
                    refusedEvent.value = false
                    if (graph.engine.state.value.error?.code == NativeErrorCode.PLAYBACK_REFUSED) graph.engine.clearError()
                }
            }
        }
    }

    /**
     * The engine reads stored credentials asynchronously; keep the splash until it knows whether
     * we are logged in (bounded so a slow keystore never blocks startup).
     */
    private suspend fun resolveInitialLoginState() {
        withTimeoutOrNull(SPLASH_MAX_MS) { graph.engine.awaitReady() }
        _ready.value = true
    }

    /** ViewModel store owner of the signed-in UI; see [MainSessionStore]. */
    fun mainSessionOwner(): ViewModelStoreOwner = mainSessions.acquire()

    /** The signed-in UI left the composition for good: clears all of its ViewModels. */
    fun releaseMainSession(owner: ViewModelStoreOwner) {
        mainSessions.release(owner)
        // Results of the old session's writes are not for whoever signs in next.
        BackgroundMessages.clear()
    }

    /** The signed-in UI is composed ([exitMainSession] when it leaves, also on configuration changes). */
    fun enterMainSession() = mainSessions.enter()

    fun exitMainSession() = mainSessions.exit()

    /**
     * The root shows Login / Premium: releases a session that nothing shows any more. Covers a
     * signed-in UI disposed by a configuration change while it was fading out (no release then).
     */
    fun releaseIdleMainSession() {
        if (mainSessions.releaseIfIdle()) BackgroundMessages.clear()
    }

    override fun onCleared() {
        mainSessions.clear()
        BackgroundMessages.clear()
    }

    fun openLink(uri: String) {
        links.trySend(uri)
    }

    fun showMessage(message: String) {
        messageChannel.trySend(message)
    }

    /** Opens Now Playing once the main scaffold is up (and has something to show). */
    fun openPlayer() {
        openPlayerRequests.trySend(Unit)
    }

    fun dismissRefusal() {
        refusedDismissed.value = true
    }

    fun showRefusalDetails() {
        refusedDismissed.value = false
    }

    /** PLAYBACK_REFUSED "Try again": drop the sticky error, reconnect if needed and resume. */
    fun retryPlayback() {
        refusedEvent.value = false
        refusedDismissed.value = false
        graph.engine.clearError()
        graph.engine.retry()
        graph.player.resume()
    }

    /** PREMIUM_REQUIRED "Try again" (e.g. after upgrading the account). */
    fun retryAccount() {
        graph.engine.clearError()
        graph.engine.retry()
    }

    /** Logs out and wipes account data (survives this ViewModel). */
    fun logout() {
        graph.appScope.launch {
            try {
                graph.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Logout failed", t)
            }
        }
    }

    /** MEDIA_PLAY_FROM_SEARCH: search and play the best match (empty query resumes playback). */
    fun playFromSearch(request: MediaSearchRequest) {
        viewModelScope.launch {
            // A cold start ("Play X on SpotifyGood" with no process) gets here before the engine has
            // read the stored credentials: wait for that instead of answering "log in first".
            val loggedIn = awaitLoginState(
                isLoggedIn = { graph.engine.isLoggedIn.value },
                awaitReady = graph.engine::awaitReady,
                timeoutMs = LOGIN_WAIT_MS,
            )
            if (!loggedIn) {
                showMessage(graph.app.getString(R.string.shell_msg_login_first))
                return@launch
            }
            graph.engine.awaitOnline(ONLINE_TIMEOUT_MS)
            val type = when (request.focus) {
                MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> SearchType.ARTIST
                MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> SearchType.ALBUM
                PLAYLIST_ENTRY_CONTENT_TYPE -> SearchType.PLAYLIST
                MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> SearchType.TRACK
                else -> null
            }
            val query = when (type) {
                SearchType.ARTIST -> request.artist ?: request.query
                SearchType.ALBUM -> request.album ?: request.query
                SearchType.PLAYLIST -> request.playlist ?: request.query
                SearchType.TRACK -> listOfNotNull(request.title, request.artist).joinToString(" ").ifBlank { request.query }
                else -> request.query
            }?.trim()
            if (query.isNullOrEmpty()) {
                graph.player.resume()
                return@launch
            }
            val results = try {
                graph.search.search(query, if (type != null) setOf(type) else SearchType.entries.toSet(), limit = 10)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Voice search failed", t)
                showMessage(graph.app.getString(R.string.shell_msg_voice_search_failed))
                return@launch
            }
            if (!playBestMatch(results, type)) {
                showMessage(graph.app.getString(R.string.shell_msg_no_results, query))
            }
        }
    }

    private fun playBestMatch(results: SearchResults, type: SearchType?): Boolean {
        val player = graph.player
        fun playTrack(track: Track) {
            val album = track.album?.uri
            if (album != null) player.playContext(album, startUri = track.uri) else player.playTracks(listOf(track.uri))
        }
        /** False when the top result is a track/episode the results mark unplayable (e.g. explicit). */
        fun playRef(ref: MediaRef): Boolean {
            when (ref.type) {
                MediaType.TRACK -> {
                    val track = results.tracks.firstOrNull { it.uri == ref.uri }
                    when {
                        track == null -> player.playTracks(listOf(ref.uri))
                        track.playable -> playTrack(track)
                        else -> return false
                    }
                }
                MediaType.EPISODE -> {
                    if (results.episodes.firstOrNull { it.uri == ref.uri }?.playable == false) return false
                    player.playTracks(listOf(ref.uri))
                }
                else -> player.playContext(ref.uri)
            }
            return true
        }
        when (type) {
            SearchType.ARTIST -> results.artists.firstOrNull()?.let { player.playContext(it.uri); return true }
            SearchType.ALBUM -> results.albums.firstOrNull()?.let { player.playContext(it.uri); return true }
            SearchType.PLAYLIST -> results.playlists.firstOrNull()?.let { player.playContext(it.uri); return true }
            SearchType.TRACK -> results.tracks.firstOrNull { it.playable }?.let { playTrack(it); return true }
            else -> {
                results.topResult?.let { if (playRef(it)) return true }
                results.tracks.firstOrNull { it.playable }?.let { playTrack(it); return true }
                results.artists.firstOrNull()?.let { player.playContext(it.uri); return true }
                results.albums.firstOrNull()?.let { player.playContext(it.uri); return true }
                results.playlists.firstOrNull()?.let { player.playContext(it.uri); return true }
            }
        }
        return false
    }

    private companion object {
        const val TAG = "ShellViewModel"
        const val SPLASH_MAX_MS = 2_000L
        /** Upper bound for the credential load before voice search gives up (slow keystore). */
        const val LOGIN_WAIT_MS = 10_000L
        const val ONLINE_TIMEOUT_MS = 15_000L

        /** MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE (deprecated constant, still sent by assistants). */
        const val PLAYLIST_ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/playlist"
    }
}

/**
 * Root of the UI: login when signed out, the Premium explanation when the account cannot
 * stream, otherwise the main scaffold (which overlays the PLAYBACK_REFUSED explanation).
 */
@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val shell = appViewModel { ShellViewModel(it) }
    val ready by shell.ready.collectAsStateWithLifecycle()
    val loggedIn by shell.isLoggedIn.collectAsStateWithLifecycle()
    val engine by shell.engineState.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!ready) return@Surface // The splash screen is still on top.
        val premiumRequired = engine.error?.code == NativeErrorCode.PREMIUM_REQUIRED || engine.user?.isPremium == false
        val screen = when {
            !loggedIn -> RootScreen.LOGIN
            premiumRequired -> RootScreen.PREMIUM_REQUIRED
            else -> RootScreen.MAIN
        }
        // Away from MAIN: a session that is no longer composed is released here. One still fading
        // out is in use and releases itself when it leaves (MainSessionScope).
        LaunchedEffect(screen) {
            if (screen != RootScreen.MAIN) shell.releaseIdleMainSession()
        }
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "root",
        ) { target ->
            when (target) {
                RootScreen.LOGIN -> LoginScreen()
                RootScreen.PREMIUM_REQUIRED -> PremiumRequiredScreen(
                    onLogout = shell::logout,
                    onRetry = shell::retryAccount,
                    accountName = engine.user?.let { it.displayName ?: it.username },
                )
                RootScreen.MAIN -> MainSessionScope(shell) { MainScaffold(shell) }
            }
        }
    }
}

/**
 * Scopes every ViewModel of the signed-in UI (navigation entries, player surfaces, sheets) to a
 * store owned by [shell]: kept across configuration changes, cleared once this content leaves the
 * composition for good, i.e. after the fade-out to the login or Premium screen, or when the
 * activity finishes. A NavController never clears its entries when its host is just disposed.
 */
@Composable
private fun MainSessionScope(shell: ShellViewModel, content: @Composable () -> Unit) {
    val owner = remember(shell) { shell.mainSessionOwner() }
    val activity = LocalActivity.current
    DisposableEffect(owner) {
        shell.enterMainSession()
        onDispose {
            shell.exitMainSession()
            // A configuration change recomposes this content with the same (retained) session; if
            // the root left MAIN meanwhile, AppRoot releases it instead (releaseIdleMainSession).
            if (activity?.isChangingConfigurations != true) shell.releaseMainSession(owner)
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
