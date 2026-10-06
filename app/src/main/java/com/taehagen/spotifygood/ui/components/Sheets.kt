package com.taehagen.spotifygood.ui.components

import androidx.compose.runtime.Composable
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget

// Global sheets hosted by the main scaffold (shown via AppNavigator). Contract: signatures only.

/**
 * Context actions: play next/add to queue, like, add to playlist, remove from playlist/queue,
 * download/remove download, go to album/artist/show, start radio, share link, follow/unfollow.
 */
@Composable
fun MediaActionsSheet(target: MediaActionTarget, onDismiss: () -> Unit) {
}

/** Pick (or create) a playlist to add [uris] to. Only editable playlists are listed. */
@Composable
fun AddToPlaylistSheet(uris: List<String>, onDismiss: () -> Unit) {
}

@Composable
fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit, initialUris: List<String> = emptyList()) {
}
