package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.ui.appViewModel

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
