package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Docked mini player shown above the navigation bar while something is playing. */
@Composable
fun MiniPlayer(onExpand: () -> Unit, modifier: Modifier = Modifier) {
}

/** Full-screen now playing (shown as an overlay by the scaffold). */
@Composable
fun NowPlayingScreen(onCollapse: () -> Unit, modifier: Modifier = Modifier) {
}

/** Queue (now playing, next in queue, next from context, suggestions). */
@Composable
fun QueueScreen(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
}

/** Full-screen synced lyrics. */
@Composable
fun LyricsScreen(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
}

/** Device picker: this phone (+ local outputs), Spotify Connect devices, system output switcher. */
@Composable
fun DevicesSheet(onDismiss: () -> Unit) {
}

/** Sleep timer picker. */
@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
}
