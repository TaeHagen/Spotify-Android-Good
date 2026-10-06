package com.taehagen.spotifygood.ui.navigation

import android.os.Handler
import android.os.Looper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Top-level destinations shown in the navigation bar / rail. */
enum class MainTab(
    val route: Route,
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME(Route.Home, R.string.shell_tab_home, Icons.Outlined.Home, Icons.Rounded.Home),
    SEARCH(Route.Search, R.string.shell_tab_search, Icons.Outlined.Search, Icons.Rounded.Search),
    LIBRARY(Route.Library, R.string.shell_tab_library, Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
}

/**
 * [AppNavigator] of the main scaffold: drives the [NavHostController], the full-screen overlays
 * (Now Playing, Queue, Lyrics) and the global sheets, and queues snackbar messages.
 * Safe to call from any thread (work is posted to the main thread).
 */
@Stable
class MainNavigator internal constructor(
    private val navController: NavHostController,
    private val graph: AppGraph,
    nowPlayingState: MutableState<Boolean>,
    queueState: MutableState<Boolean>,
    lyricsState: MutableState<Boolean>,
) : AppNavigator {
    var isNowPlayingOpen: Boolean by nowPlayingState
        private set
    var isQueueOpen: Boolean by queueState
        private set
    var isLyricsOpen: Boolean by lyricsState
        private set
    var isDevicesOpen: Boolean by mutableStateOf(false)
        private set
    var isSleepTimerOpen: Boolean by mutableStateOf(false)
        private set
    var actionTarget: MediaActionTarget? by mutableStateOf(null)
        private set
    var addToPlaylistUris: List<String>? by mutableStateOf(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Snackbar messages (collected by the scaffold). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _tabReselected = MutableSharedFlow<MainTab>(extraBufferCapacity = 1)
    /** Emits when the user re-selects the tab whose root is already showing (scroll to top). */
    val tabReselected: SharedFlow<MainTab> = _tabReselected.asSharedFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    // ---- AppNavigator -------------------------------------------------------------------------

    override fun navigate(route: Route) = onMain {
        closeOverlays()
        val tab = MainTab.entries.firstOrNull { it.route == route }
        if (tab != null) {
            switchTo(tab)
        } else if (!isShowing(route)) {
            // Not launchSingleTop: that matches the destination only, so Album A → Album B would
            // replace A instead of pushing B. Only an identical page (double tap) is skipped.
            navController.navigate(route)
        }
    }

    override fun back() = onMain {
        when {
            isLyricsOpen -> isLyricsOpen = false
            isQueueOpen -> isQueueOpen = false
            isNowPlayingOpen -> isNowPlayingOpen = false
            else -> navController.navigateUp()
        }
    }

    override fun open(ref: MediaRef) {
        when (ref.type) {
            MediaType.TRACK -> playTrack(SpotifyLinks.canonicalUri(ref.uri) ?: ref.uri)
            MediaType.COLLECTION -> navigate(
                if (SpotifyLinks.parse(ref.uri) == SpotifyLink.Library) Route.Library else Route.LikedSongs,
            )
            MediaType.ALBUM -> navigate(Route.Album(normalized(ref.uri)))
            MediaType.ARTIST -> navigate(Route.Artist(normalized(ref.uri)))
            MediaType.PLAYLIST -> navigate(Route.Playlist(normalized(ref.uri)))
            MediaType.SHOW -> navigate(Route.Show(normalized(ref.uri)))
            MediaType.EPISODE -> navigate(Route.Episode(normalized(ref.uri)))
        }
    }

    override fun openUri(uri: String): Boolean {
        val link = SpotifyLinks.parse(uri) ?: return false
        if (link is SpotifyLink.Media && link.type == MediaType.TRACK) {
            playTrack(link.uri)
            return true
        }
        val route = SpotifyLinks.routeFor(link) ?: return false
        navigate(route)
        return true
    }

    override fun openNowPlaying() = onMain { isNowPlayingOpen = true }

    override fun closeNowPlaying() = onMain {
        isLyricsOpen = false
        isQueueOpen = false
        isNowPlayingOpen = false
    }

    override fun openQueue() = onMain {
        isLyricsOpen = false
        isQueueOpen = true
    }

    override fun openLyrics() = onMain {
        isQueueOpen = false
        isLyricsOpen = true
    }

    override fun openDevices() = onMain { isDevicesOpen = true }

    override fun showActions(target: MediaActionTarget) = onMain { actionTarget = target }

    override fun addToPlaylist(uris: List<String>) = onMain {
        if (uris.isEmpty()) return@onMain
        actionTarget = null
        addToPlaylistUris = uris
    }

    override fun showMessage(message: String) {
        _messages.tryEmit(message)
    }

    // ---- Extras used by the shell -----------------------------------------------------------

    fun openSleepTimer() = onMain { isSleepTimerOpen = true }

    fun dismissDevices() = onMain { isDevicesOpen = false }
    fun dismissSleepTimer() = onMain { isSleepTimerOpen = false }
    fun dismissActions() = onMain { actionTarget = null }
    fun dismissAddToPlaylist() = onMain { addToPlaylistUris = null }
    fun closeQueue() = onMain { isQueueOpen = false }
    fun closeLyrics() = onMain { isLyricsOpen = false }

    /** Navigation-bar click: switches tab (restoring its stack) or pops a re-selected tab to its root. */
    fun selectTab(tab: MainTab) = onMain {
        closeOverlays()
        if (currentTab() == tab) {
            val atRoot = navController.currentBackStackEntry?.destination?.hasRoute(tab.route::class) == true
            if (atRoot) {
                _tabReselected.tryEmit(tab)
            } else if (!navController.popBackStack(tab.route, inclusive = false)) {
                switchTo(tab)
            }
        } else {
            switchTo(tab)
        }
    }

    /** The tab owning the current back stack (the last tab root on the stack). */
    fun currentTab(backStack: List<NavBackStackEntry> = navController.currentBackStack.value): MainTab =
        tabOf(backStack)

    /** Pops the current page if it is the playlist [uri] (e.g. after deleting it). */
    fun leavePlaylist(uri: String) = onMain {
        val entry = navController.currentBackStackEntry ?: return@onMain
        if (entry.destination.hasRoute(Route.Playlist::class) && entry.toRoute<Route.Playlist>().uri == uri) {
            navController.popBackStack()
        }
    }

    /** Plays a single track inside its album context when known (so playback continues naturally). */
    fun playTrack(trackUri: String) {
        graph.appScope.launch {
            val albumUri = withTimeoutOrNull(ALBUM_LOOKUP_TIMEOUT_MS) {
                runCatching { graph.catalog.tracks(listOf(trackUri)).firstOrNull()?.album?.uri }.getOrNull()
            }
            if (albumUri != null) {
                graph.player.playContext(albumUri, startUri = trackUri)
            } else {
                graph.player.playTracks(listOf(trackUri))
            }
        }
    }

    private fun switchTo(tab: MainTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** True when the top page is exactly [route] (same destination and arguments). */
    private fun isShowing(route: Route): Boolean {
        val entry = navController.currentBackStackEntry ?: return false
        if (!entry.destination.hasRoute(route::class)) return false
        return runCatching { entry.toRoute<Route>(route::class) == route }.getOrDefault(false)
    }

    private fun closeOverlays() {
        isLyricsOpen = false
        isQueueOpen = false
        isNowPlayingOpen = false
        isDevicesOpen = false
        isSleepTimerOpen = false
    }

    private fun normalized(uri: String): String = SpotifyLinks.canonicalUri(uri) ?: uri

    companion object {
        private const val ALBUM_LOOKUP_TIMEOUT_MS = 3_000L

        fun tabOf(backStack: List<NavBackStackEntry>): MainTab =
            backStack.asReversed().firstNotNullOfOrNull { entry ->
                MainTab.entries.firstOrNull { entry.destination.hasRoute(it.route::class) }
            } ?: MainTab.HOME
    }
}

@Composable
fun rememberMainNavigator(navController: NavHostController): MainNavigator {
    val graph = (LocalContext.current.applicationContext as App).graph
    val nowPlaying = rememberSaveable { mutableStateOf(false) }
    val queue = rememberSaveable { mutableStateOf(false) }
    val lyrics = rememberSaveable { mutableStateOf(false) }
    return remember(navController) { MainNavigator(navController, graph, nowPlaying, queue, lyrics) }
}

/** Opens the sleep timer sheet (no-op outside the main scaffold). */
fun AppNavigator.openSleepTimer() {
    (this as? MainNavigator)?.openSleepTimer()
}

/**
 * Runs [onReselect] when the user taps the navigation item of [tab] while its root page is already
 * showing (e.g. `listState.animateScrollToItem(0)`). No-op outside the main scaffold.
 */
@Composable
fun TabReselectedEffect(tab: MainTab, onReselect: suspend () -> Unit) {
    val navigator = LocalOptionalAppNavigator.current as? MainNavigator ?: return
    val callback by rememberUpdatedState(onReselect)
    LaunchedEffect(navigator, tab) {
        navigator.tabReselected.collect { if (it == tab) callback() }
    }
}

/**
 * Same navigator as [LocalAppNavigator] but null outside the main scaffold (login, previews), for
 * shared components that only optionally navigate.
 */
val LocalOptionalAppNavigator = staticCompositionLocalOf<AppNavigator?> { null }
