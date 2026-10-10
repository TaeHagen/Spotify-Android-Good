package com.taehagen.spotifygood.ui.screens.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.ui.appViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Whether the mini player has something to show: the loaded item, or after a cold start the last
 * session (paused, play resumes it).
 */
@Composable
fun rememberPlayerHasContent(): State<Boolean> {
    val viewModel = appViewModel { graph -> PlayerViewModel(graph) }
    return viewModel.hasContent.collectAsStateWithLifecycle()
}

/** State of the [ExpandingPlayer]; [initiallyExpanded] comes from the saved Now Playing flag. */
@Composable
fun rememberPlayerSheetState(initiallyExpanded: Boolean): PlayerSheetState =
    rememberPlayerSheetStateInternal(initiallyExpanded)

/**
 * The player: the mini player docked above the navigation bar, growing under the finger into
 * full-screen Now Playing and back (docs §9.9). Drawn above the whole shell, which keeps the
 * card's place with [PlayerDock] and drives [PlayerSheetState] from its saved flag.
 */
@Composable
fun ExpandingPlayer(
    sheet: PlayerSheetState,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = appViewModel { graph -> PlayerViewModel(graph) }
    // The downloaded cover while offline, so there is artwork without a network.
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    // Read only in the draw phase: the colour animates on every track change.
    val artworkColor = rememberArtworkColor(artwork)
    ExpandingPlayerLayout(
        sheet = sheet,
        artwork = artwork,
        artworkColor = artworkColor,
        modifier = modifier,
        mini = { MiniPlayerContent(onExpand = onExpand) },
        nowPlaying = { NowPlayingContent(onCollapse = onCollapse) },
    )
}

/** The docked mini player's place in the shell's bottom stack (the card is drawn by [ExpandingPlayer]). */
@Composable
fun PlayerDock(sheet: PlayerSheetState, modifier: Modifier = Modifier) {
    PlayerDockSpace(sheet = sheet, modifier = modifier)
}

/**
 * Back on Now Playing while it is the top layer ([enabled]: open, no Queue, Lyrics or
 * playback-refused screen over it): collapses it ([onCollapse]). On Android 14+ the predictive
 * back gesture shrinks the player toward the mini player as it goes and springs it back when
 * cancelled.
 *
 * Composed only while enabled, not merely switched on and off: the newest enabled handler takes
 * Back, so each time Now Playing comes on top its handler is added again, above those of the pages
 * composed since (a search query, a Library folder, edit mode), which never act on the hidden page.
 */
@Composable
fun PlayerBackHandler(sheet: PlayerSheetState, enabled: Boolean, onCollapse: () -> Unit) {
    // Outlives the handler: a gesture cut short by the handler leaving still springs back.
    val scope = rememberCoroutineScope()
    if (!enabled) return
    PredictiveBackHandler { events ->
        try {
            events.collect { event -> sheet.previewBack(event.progress) }
            onCollapse()
        } catch (e: CancellationException) {
            // Cancelled gesture: spring back (unless the player is closing anyway).
            scope.launch { if (sheet.isExpanded) sheet.animateTo(expanded = true) }
            throw e
        }
    }
}

/** Queue (now playing, next in queue, next from context, suggestions). */
@Composable
fun QueueScreen(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    QueueScreenContent(onDismiss = onDismiss, modifier = modifier)
}

/** Full-screen synced lyrics. */
@Composable
fun LyricsScreen(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LyricsScreenContent(onDismiss = onDismiss, modifier = modifier)
}

/** Device picker: this phone (+ local outputs), Spotify Connect devices, system output switcher. */
@Composable
fun DevicesSheet(onDismiss: () -> Unit) {
    DevicesSheetContent(onDismiss = onDismiss)
}

/** Sleep timer picker. */
@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    SleepTimerSheetContent(onDismiss = onDismiss)
}
