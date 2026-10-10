package com.taehagen.spotifygood.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteColors
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.ui.components.BackgroundMessages
import com.taehagen.spotifygood.ui.components.OfflineBanner
import com.taehagen.spotifygood.ui.components.friendlyErrorMessage
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.AppNavHost
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalOptionalAppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalPageCovered
import com.taehagen.spotifygood.ui.navigation.MainNavigator
import com.taehagen.spotifygood.ui.navigation.MainTab
import com.taehagen.spotifygood.ui.navigation.rememberMainNavigator
import com.taehagen.spotifygood.ui.components.AddToPlaylistSheet
import com.taehagen.spotifygood.ui.components.AlreadyAddedDialog
import com.taehagen.spotifygood.ui.components.MediaActionsSheet
import com.taehagen.spotifygood.ui.screens.player.DevicePicks
import com.taehagen.spotifygood.ui.screens.player.DevicesSheet
import com.taehagen.spotifygood.ui.screens.player.ExpandingPlayer
import com.taehagen.spotifygood.ui.screens.player.LyricsScreen
import com.taehagen.spotifygood.ui.screens.player.PendingDeviceBanner
import com.taehagen.spotifygood.ui.screens.player.PlayerBackHandler
import com.taehagen.spotifygood.ui.screens.player.PlayerDock
import com.taehagen.spotifygood.ui.screens.player.PlayerSheetState
import com.taehagen.spotifygood.ui.screens.player.pendingTargetNameFlow
import com.taehagen.spotifygood.ui.screens.player.QueueScreen
import com.taehagen.spotifygood.ui.screens.player.SleepTimerSheet
import com.taehagen.spotifygood.ui.screens.player.rememberPlayerHasContent
import com.taehagen.spotifygood.ui.screens.player.rememberPlayerSheetState
import com.taehagen.spotifygood.ui.screens.player.surfaceCoversStatusBar
import com.taehagen.spotifygood.ui.screens.status.PlaybackRefusedBanner
import com.taehagen.spotifygood.ui.screens.status.PlaybackRefusedScreen
import com.taehagen.spotifygood.ui.theme.LocalSystemBarsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The signed-in app: adaptive navigation (bottom bar / rail), the navigation host, the expanding
 * player (docked mini player ↔ Now Playing), full-screen overlays (Queue, Lyrics), global sheets
 * and snackbars.
 */
@Composable
fun MainScaffold(shell: ShellViewModel, modifier: Modifier = Modifier) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val navController = rememberNavController()
    val navigator = rememberMainNavigator(navController)
    val snackbarHostState = remember { SnackbarHostState() }

    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val currentTab = remember(backStack) { MainNavigator.tabOf(backStack) }
    // A loaded item, or the last session (cold start) that the mini player offers to resume.
    val hasTrackState = rememberPlayerHasContent()
    val hasTrack by hasTrackState
    val networkAvailable by graph.engine.isNetworkAvailable.collectAsStateWithLifecycle()
    val offlineMode by remember(graph) {
        graph.settings.settings.map { it.offlineMode }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = graph.settings.settings.value.offlineMode)
    val refusal by shell.playbackRefusal.collectAsStateWithLifecycle()
    // A Connect device picked for the next play while nothing plays anywhere.
    val pendingDevice by remember(graph) { graph.devices.pendingTargetNameFlow() }
        .collectAsStateWithLifecycle(initialValue = null)
    val overlayOpen = navigator.isNowPlayingOpen || navigator.isQueueOpen || navigator.isLyricsOpen ||
        refusal == PlaybackRefusal.SCREEN
    // The expanding player: its progress follows the finger, its target the saved Now Playing flag.
    val sheet = rememberPlayerSheetState(initiallyExpanded = navigator.isNowPlayingOpen)
    SideEffect {
        sheet.onSettle = { expanded -> if (expanded) navigator.openNowPlaying() else navigator.closeNowPlaying() }
    }
    LaunchedEffect(sheet, navigator) {
        // Taps, back, the notification, links and navigation change the flag; a settled gesture
        // already set it together with the sheet's target.
        snapshotFlow { navigator.isNowPlayingOpen }.collectLatest { open ->
            if (open != sheet.isExpanded) sheet.animateTo(open)
        }
    }
    // The player covers the page: keep accessibility services off what is underneath.
    val playerCoversPage by remember(sheet) { derivedStateOf { sheet.progress >= 1f } }

    // Deep links received by the activity (also those that arrived before login).
    val unsupportedLink = stringResource(R.string.shell_msg_unsupported_link)
    LaunchedEffect(navigator) {
        // The NavHost sets its graph during (sub)composition; wait for it before navigating.
        navController.currentBackStack.first { it.isNotEmpty() }
        shell.pendingLinks.collect { uri ->
            if (!navigator.openUri(uri)) navigator.showMessage(unsupportedLink)
        }
    }

    // Media notification / lock-screen player taps (PlaybackService.EXTRA_OPEN_PLAYER).
    LaunchedEffect(navigator) {
        shell.pendingOpenPlayer.collect {
            // Right after a cold start the player may need a moment to have something to show; a
            // stale tap after playback ended opens nothing.
            val hasContent = withTimeoutOrNull(OPEN_PLAYER_WAIT_MS) {
                snapshotFlow { hasTrackState.value }.first { it }
            } ?: false
            if (hasContent) navigator.openNowPlaying()
        }
    }

    // All user-visible transient errors and confirmations → one snackbar (newest wins).
    LaunchedEffect(navigator, snackbarHostState) {
        var last: String? = null
        var lastAt = 0L
        merge(
            navigator.messages,
            shell.messages,
            // Results of writes that outlive their page (detail pages run them in the app scope).
            BackgroundMessages.messages,
            graph.player.errors,
            graph.events.errors
                .filter { it.code != NativeErrorCode.PLAYBACK_REFUSED && it.code != NativeErrorCode.CANCELLED }
                .map { friendlyErrorMessage(context, it) },
        ).filter { message ->
            val now = SystemClock.elapsedRealtime()
            val duplicate = message == last && now - lastAt < DUPLICATE_WINDOW_MS
            if (!duplicate) {
                last = message
                lastAt = now
            }
            !duplicate
        }.collectLatest { message ->
            // Cancelling the previous showSnackbar (collectLatest) dismisses it: newest wins.
            snackbarHostState.showSnackbar(message)
        }
    }

    NotificationPermissionRequest()

    CompositionLocalProvider(
        LocalAppNavigator provides navigator,
        LocalOptionalAppNavigator provides navigator,
        LocalPageCovered provides overlayOpen,
    ) {
        Box(modifier.fillMaxSize()) {
            val suiteType = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfoV2())
            val colors = MaterialTheme.colorScheme
            val itemColors = ShortNavigationBarItemDefaults.colors(
                selectedIconColor = colors.onSurface,
                selectedTextColor = colors.onSurface,
                selectedIndicatorColor = colors.surfaceContainerHigh,
                unselectedIconColor = colors.onSurfaceVariant,
                unselectedTextColor = colors.onSurfaceVariant,
            )
            NavigationScaffold(
                navigationSuiteType = suiteType,
                navigationSuiteColors = NavigationSuiteDefaults.colors(
                    shortNavigationBarContainerColor = colors.surfaceContainerLowest,
                    shortNavigationBarContentColor = colors.onSurface,
                ),
                containerColor = colors.background,
                playerProgress = { sheet.progress },
                modifier = if (playerCoversPage) Modifier.clearAndSetSemantics {} else Modifier,
                navigationItems = {
                    MainTab.entries.forEach { tab ->
                        val selected = tab == currentTab
                        NavigationSuiteItem(
                            selected = selected,
                            onClick = { navigator.selectTab(tab) },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                            label = {
                                Text(
                                    text = stringResource(tab.labelRes),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                )
                            },
                            navigationSuiteType = suiteType,
                            colors = itemColors,
                        )
                    }
                },
            ) {
                MainContent(
                    navigator = navigator,
                    sheet = sheet,
                    navController = navController,
                    snackbarHostState = snackbarHostState,
                    showSnackbars = !overlayOpen,
                    hasTrack = hasTrack,
                    showOffline = !networkAvailable || offlineMode,
                    refusal = refusal,
                    onRefusalDetails = shell::showRefusalDetails,
                    // Offline the next play stays on this phone (downloads).
                    pendingDevice = pendingDevice.takeIf { networkAvailable && !offlineMode },
                    onPendingDeviceClick = navigator::openDevices,
                    onPendingDeviceCancel = {
                        DevicePicks.mark()
                        graph.devices.clearPendingTarget()
                    },
                )
            }

            PlayerOverlay(
                navigator = navigator,
                sheet = sheet,
                hasTrack = hasTrack,
                // Back is Now Playing's while nothing is drawn over it (the overlays below are).
                onTop = navigator.isNowPlayingOpen && !navigator.isQueueOpen && !navigator.isLyricsOpen &&
                    refusal != PlaybackRefusal.SCREEN,
            )
            FullScreenOverlay(visible = navigator.isQueueOpen, onBack = navigator::closeQueue) {
                QueueScreen(onDismiss = navigator::closeQueue)
            }
            FullScreenOverlay(visible = navigator.isLyricsOpen, onBack = navigator::closeLyrics) {
                LyricsScreen(onDismiss = navigator::closeLyrics)
            }
            FullScreenOverlay(visible = refusal == PlaybackRefusal.SCREEN, onBack = shell::dismissRefusal) {
                PlaybackRefusedScreen(
                    onRetry = shell::retryPlayback,
                    onLogout = shell::logout,
                    onContinue = shell::dismissRefusal,
                )
            }
            // While a full-screen overlay covers the content, snackbars show above it instead.
            if (overlayOpen) {
                AppSnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }

        // Global sheets (each composable renders its own ModalBottomSheet).
        navigator.actionTarget?.let { target ->
            key(target) { MediaActionsSheet(target = target, onDismiss = navigator::dismissActions) }
        }
        navigator.addToPlaylistUris?.let { uris ->
            key(uris) {
                AddToPlaylistSheet(uris = uris, onDismiss = navigator::dismissAddToPlaylist, excludeUri = navigator.addToPlaylistExclude)
            }
        }
        navigator.playlistAddPrompt?.let { prompt ->
            key(prompt) { AlreadyAddedDialog(prompt, onDismiss = navigator::dismissPlaylistAddPrompt) }
        }
        if (navigator.isDevicesOpen) DevicesSheet(onDismiss = navigator::dismissDevices)
        if (navigator.isSleepTimerOpen) SleepTimerSheet(onDismiss = navigator::dismissSleepTimer)
    }
}

@Composable
private fun MainContent(
    navigator: MainNavigator,
    sheet: PlayerSheetState,
    navController: androidx.navigation.NavHostController,
    snackbarHostState: SnackbarHostState,
    showSnackbars: Boolean,
    hasTrack: Boolean,
    showOffline: Boolean,
    refusal: PlaybackRefusal,
    onRefusalDetails: () -> Unit,
    pendingDevice: String?,
    onPendingDeviceClick: () -> Unit,
    onPendingDeviceCancel: () -> Unit,
) {
    // Screens only get bottom padding (contract); keep them clear of side system bars / cutouts
    // (landscape 3-button navigation). A navigation rail already consumed its side.
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        var bottomHeightPx by remember { mutableIntStateOf(0) }
        val bottomPadding = with(LocalDensity.current) { bottomHeightPx.toDp() }
        val contentPadding = remember(bottomPadding) { PaddingValues(bottom = bottomPadding) }

        AppNavHost(navController = navController, contentPadding = contentPadding, modifier = Modifier.fillMaxSize())

        // Docked bottom stack. Navigation-bar insets are consumed by the bottom bar; with a rail they
        // remain and are applied here so the mini player clears the gesture area.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomHeightPx = it.height }
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        ) {
            AnimatedVisibility(
                visible = refusal == PlaybackRefusal.BANNER,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                PlaybackRefusedBanner(onClick = onRefusalDetails)
            }
            AnimatedVisibility(
                visible = showOffline,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                OfflineBanner()
            }
            // Keeps the last name while animating out.
            var shownPendingDevice by remember { mutableStateOf(pendingDevice) }
            LaunchedEffect(pendingDevice) { if (pendingDevice != null) shownPendingDevice = pendingDevice }
            AnimatedVisibility(
                visible = pendingDevice != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                (pendingDevice ?: shownPendingDevice)?.let { name ->
                    PendingDeviceBanner(deviceName = name, onClick = onPendingDeviceClick, onCancel = onPendingDeviceCancel)
                }
            }
            // The mini player's card is drawn by the expanding player above everything; the dock
            // keeps its place (and the pages' bottom padding).
            AnimatedVisibility(
                visible = hasTrack,
                enter = expandVertically(tween(260)) + fadeIn(tween(260)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(200)),
            ) {
                PlayerDock(sheet = sheet, modifier = Modifier.fillMaxWidth())
            }
        }

        if (showSnackbars) {
            AppSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPadding),
            )
        }
    }
}

@Composable
private fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier.padding(horizontal = 8.dp, vertical = 8.dp)) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
            shape = RoundedCornerShape(8.dp),
        )
    }
}

/** Swallows touches so content below an overlay never receives them. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/**
 * The expanding player above the shell, while something is loaded or Now Playing is open (it then
 * shows "nothing playing"). While Now Playing is [onTop] Back collapses it ([PlayerBackHandler],
 * with predictive back). The system-bar icons turn light once the (always dark) player reaches
 * under the status bar.
 */
@Composable
private fun PlayerOverlay(navigator: MainNavigator, sheet: PlayerSheetState, hasTrack: Boolean, onTop: Boolean) {
    val wanted = hasTrack || navigator.isNowPlayingOpen
    val shown = animateFloatAsState(if (wanted) 1f else 0f, tween(if (wanted) 260 else 200), label = "player")
    val composed by remember { derivedStateOf { shown.value > 0f } }
    if (!wanted && !composed) return

    PlayerBackHandler(sheet = sheet, enabled = onTop, onCollapse = navigator::closeNowPlaying)

    val barsController = LocalSystemBarsController.current
    val statusBarHeight = rememberUpdatedState(WindowInsets.statusBars.getTop(LocalDensity.current).toFloat())
    val darkBars by remember(sheet) {
        derivedStateOf { surfaceCoversStatusBar(sheet.progress, sheet.collapsedBounds.top, statusBarHeight.value) }
    }
    DisposableEffect(barsController, darkBars) {
        barsController.forceDarkBars = darkBars
        onDispose { barsController.forceDarkBars = false }
    }

    ExpandingPlayer(
        sheet = sheet,
        onExpand = navigator::openNowPlaying,
        onCollapse = navigator::closeNowPlaying,
        modifier = Modifier.graphicsLayer { alpha = shown.value },
    )
}

/**
 * NavigationSuiteScaffold's own layout, with the navigation bar sliding down (a rail: toward the
 * start edge) and out as the player expands. [playerProgress] is read in the layer only.
 */
@Composable
private fun NavigationScaffold(
    navigationSuiteType: NavigationSuiteType,
    navigationSuiteColors: NavigationSuiteColors,
    containerColor: Color,
    playerProgress: () -> Float,
    navigationItems: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val bar = navigationSuiteType == NavigationSuiteType.ShortNavigationBarCompact ||
        navigationSuiteType == NavigationSuiteType.ShortNavigationBarMedium ||
        navigationSuiteType == NavigationSuiteType.NavigationBar
    val towardStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    Surface(modifier = modifier, color = containerColor, contentColor = NavigationSuiteScaffoldDefaults.contentColor) {
        NavigationSuiteScaffoldLayout(
            navigationSuite = {
                NavigationSuite(
                    navigationSuiteType = navigationSuiteType,
                    colors = navigationSuiteColors,
                    modifier = Modifier.graphicsLayer {
                        val p = playerProgress()
                        if (bar) translationY = size.height * p else translationX = size.width * p * towardStart
                    },
                    content = navigationItems,
                )
            },
            navigationSuiteType = navigationSuiteType,
            content = {
                Box(Modifier.consumeWindowInsets(navigationSuiteInsets(navigationSuiteType))) { content() }
            },
        )
    }
}

/** The insets the navigation component takes from the content (as NavigationSuiteScaffold does). */
@Composable
private fun navigationSuiteInsets(type: NavigationSuiteType): WindowInsets = when (type) {
    NavigationSuiteType.ShortNavigationBarCompact, NavigationSuiteType.ShortNavigationBarMedium ->
        ShortNavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
    NavigationSuiteType.WideNavigationRailCollapsed, NavigationSuiteType.WideNavigationRailExpanded ->
        WideNavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
    NavigationSuiteType.NavigationBar -> NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
    NavigationSuiteType.NavigationRail -> NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
    else -> WindowInsets(0, 0, 0, 0)
}

/** Generic full-screen overlay with slide-up animation and back handling. */
@Composable
private fun FullScreenOverlay(visible: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(280)) { it / 3 } + fadeIn(tween(280)),
        exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(220)),
    ) {
        BackHandler(enabled = visible, onBack = onBack)
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .blockTouches(),
        ) {
            content()
        }
    }
}

/**
 * Asks for POST_NOTIFICATIONS (API 33+) once after login; if it was denied and Android allows a
 * rationale, explains why once more on a later launch. Media controls work without it.
 */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val activity = LocalActivity.current ?: return
    var showRationale by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        val prefs = withContext(Dispatchers.IO) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { it.all }
        }
        val asked = prefs.getInt(KEY_NOTIFICATION_ASKS, 0)
        when {
            asked == 0 -> {
                prefs.edit().putInt(KEY_NOTIFICATION_ASKS, 1).apply()
                launcher.launch(permission)
            }
            asked == 1 && activity.shouldShowRequestPermissionRationale(permission) -> {
                prefs.edit().putInt(KEY_NOTIFICATION_ASKS, 2).apply()
                showRationale = true
            }
        }
    }
    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.shell_notifications_title)) },
            text = { Text(stringResource(R.string.shell_notifications_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.shell_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text(stringResource(R.string.shell_not_now)) }
            },
        )
    }
}

private const val DUPLICATE_WINDOW_MS = 3_000L
private const val OPEN_PLAYER_WAIT_MS = 3_000L
private const val PREFS = "shell"
private const val KEY_NOTIFICATION_ASKS = "notification_permission_asks"
