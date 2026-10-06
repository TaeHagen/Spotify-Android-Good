package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Docked mini player shown above the navigation bar while something is playing. */
@Composable
fun MiniPlayer(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    MiniPlayerContent(onExpand = onExpand, modifier = modifier)
}

/** Full-screen now playing (shown as an overlay by the scaffold). */
@Composable
fun NowPlayingScreen(onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    NowPlayingContent(onCollapse = onCollapse, modifier = modifier)
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
