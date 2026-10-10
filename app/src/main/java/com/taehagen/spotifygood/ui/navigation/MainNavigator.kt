package com.taehagen.spotifygood.ui.navigation

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
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
import com.taehagen.spotifygood.ui.components.PlaylistAddPrompt
import com.taehagen.spotifygood.ui.screens.library.launchTrackStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json

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
    devicesState: MutableState<Boolean>,
    sleepTimerState: MutableState<Boolean>,
    actionTargetState: MutableState<MediaActionTarget?>,
    addToPlaylistState: MutableState<List<String>?>,
    addToPlaylistExcludeState: MutableState<String?>,
) : AppNavigator {
    var isNowPlayingOpen: Boolean by nowPlayingState
        private set
    var isQueueOpen: Boolean by queueState
        private set
    var isLyricsOpen: Boolean by lyricsState
        private set
    // Sheets are saved like the overlays: a configuration change (rotation, dark mode, font size,
    // split-screen resize) recreates the activity, and the sheets' own saved state (the devices
    // sheet's token and LAN results, a half-typed playlist name) expects to come back.
    var isDevicesOpen: Boolean by devicesState
        private set
    var isSleepTimerOpen: Boolean by sleepTimerState
        private set
    var actionTarget: MediaActionTarget? by actionTargetState
        private set
    var addToPlaylistUris: List<String>? by addToPlaylistState
        private set
    /** A playlist the picker doesn't offer (the one the items come from). */
    var addToPlaylistExclude: String? by addToPlaylistExcludeState
        private set
    /** "Already added", waiting for the user (not saved: a recreated activity drops the question). */
    var playlistAddPrompt: PlaylistAddPrompt? by mutableStateOf(null)
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

    override fun addToPlaylist(uris: List<String>, excludeUri: String?) = onMain {
        if (uris.isEmpty()) return@onMain
        actionTarget = null
        addToPlaylistExclude = excludeUri
        addToPlaylistUris = uris
    }

    override fun confirmPlaylistAdd(prompt: PlaylistAddPrompt) = onMain { playlistAddPrompt = prompt }

    override fun showMessage(message: String) {
        _messages.tryEmit(message)
    }

    // ---- Extras used by the shell -----------------------------------------------------------

    fun openSleepTimer() = onMain { isSleepTimerOpen = true }

    fun dismissDevices() = onMain { isDevicesOpen = false }
    fun dismissSleepTimer() = onMain { isSleepTimerOpen = false }
    fun dismissActions() = onMain { actionTarget = null }
    fun dismissAddToPlaylist() = onMain {
        addToPlaylistUris = null
        addToPlaylistExclude = null
    }
    fun dismissPlaylistAddPrompt() = onMain { playlistAddPrompt = null }
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

    /**
     * Plays a single track (deep link, Home tile, recent search) inside its album context when known
     * (so playback continues naturally). A track the catalog marks unplayable, or one that isn't
     * downloaded while the session can't stream, is not started: a different track would play.
     */
    fun playTrack(trackUri: String) {
        // The track is looked up there, once the session can answer (links mostly arrive while it
        // is still connecting: cold start, or after the session idled out in the background).
        graph.launchTrackStart(trackUri, track = null)
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
    val devices = rememberSaveable { mutableStateOf(false) }
    val sleepTimer = rememberSaveable { mutableStateOf(false) }
    val actionTarget = rememberSaveable(stateSaver = ActionTargetSaver) { mutableStateOf<MediaActionTarget?>(null) }
    val addToPlaylist = rememberSaveable(stateSaver = UriListSaver) { mutableStateOf<List<String>?>(null) }
    val addToPlaylistExclude = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(navController) {
        MainNavigator(navController, graph, nowPlaying, queue, lyrics, devices, sleepTimer, actionTarget, addToPlaylist, addToPlaylistExclude)
    }
}

private val savedStateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    // Not "type": a model class could have a property of that name.
    classDiscriminator = "targetKind"
}

/** Larger saved targets are dropped (the sheet closes) rather than risk the saved-state size limit. */
private const val MAX_SAVED_TARGET_CHARS = 32_000

/**
 * The saved form of an action sheet's target: its model as JSON, without what the sheet doesn't
 * show (an episode's description). Null when it can't be encoded or is too large.
 */
internal fun encodeActionTarget(target: MediaActionTarget): String? {
    val slim = when (target) {
        is MediaActionTarget.EpisodeTarget -> target.copy(episode = target.episode.copy(description = ""))
        else -> target
    }
    return runCatching { savedStateJson.encodeToString(MediaActionTarget.serializer(), slim) }
        .getOrNull()
        ?.takeIf { it.length <= MAX_SAVED_TARGET_CHARS }
}

/** The target saved by [encodeActionTarget]; null (the sheet stays closed) when it can't be read. */
internal fun decodeActionTarget(saved: String): MediaActionTarget? =
    runCatching { savedStateJson.decodeFromString(MediaActionTarget.serializer(), saved) }.getOrNull()

internal val ActionTargetSaver: Saver<MediaActionTarget?, String> = Saver(
    save = { target -> target?.let(::encodeActionTarget) },
    restore = ::decodeActionTarget,
)

/** URI lists as a bundle-safe ArrayList (the list's own runtime type may not be). */
internal val UriListSaver: Saver<List<String>?, ArrayList<String>> = Saver(
    // A whole playlist's items can be too large for the saved state: then the picker closes.
    save = { uris -> uris?.takeIf { list -> list.sumOf { it.length } <= MAX_SAVED_TARGET_CHARS }?.let { ArrayList(it) } },
    restore = { it },
)

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

/** True while an overlay (Now Playing, Queue, Lyrics, the playback-refused screen) covers the pages. */
val LocalPageCovered = compositionLocalOf { false }

/**
 * Back for a page's own state (a search query, a Library folder, edit mode). Off while an overlay
 * covers the pages ([LocalPageCovered]), so a hidden page never takes Back from what is on screen,
 * whichever handler was added last.
 */
@Composable
fun PageBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled && !LocalPageCovered.current, onBack = onBack)
}
