package com.taehagen.spotifygood.ui.components

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.PlaylistAddChoice
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.data.SpotifyUris
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.download.CollectionRef
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Rootlist
import com.taehagen.spotifygood.model.RootlistEntry
import com.taehagen.spotifygood.model.RootlistEntryType
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.components.MediaActionRunner.Companion.awaitData
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalOptionalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MainNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Global sheets hosted by the main scaffold (shown via AppNavigator). Each one renders its own
// ModalBottomSheet / dialog, so screens may also show them directly.

/**
 * Context actions: play next/add to queue, like, add to playlist, remove from playlist/queue,
 * download/remove download, go to album/artist/show, start radio, share link, follow/unfollow.
 */
@Composable
fun MediaActionsSheet(target: MediaActionTarget, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val animatedDismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        MediaActionsContent(target = target, dismiss = animatedDismiss, dismissNow = onDismiss)
    }
}

private sealed interface SheetPage {
    data object Main : SheetPage
    data class Artists(val artists: List<ArtistRef>) : SheetPage
    data object Rename : SheetPage
    data object ConfirmDelete : SheetPage
}

/** Everything an action row needs. */
@Stable
private class ActionScope(
    val graph: AppGraph,
    val navigator: AppNavigator?,
    val runner: MediaActionRunner,
    val context: Context,
    val dismiss: () -> Unit,
    val dismissNow: () -> Unit,
    val setPage: (SheetPage) -> Unit,
) {
    fun go(route: Route) {
        dismiss()
        navigator?.navigate(route)
    }

    fun goToArtists(artists: List<ArtistRef>) {
        when (artists.size) {
            0 -> Unit
            1 -> go(Route.Artist(artists.first().uri))
            else -> setPage(SheetPage.Artists(artists))
        }
    }

    fun share(uri: String, title: String) {
        dismiss()
        if (!shareSpotifyLink(context, uri, title)) runner.message(R.string.shell_msg_cannot_share)
    }

    fun radio(uri: String) {
        dismiss()
        graph.player.startRadio(uri)
        runner.message(R.string.shell_msg_starting_radio)
    }
}

@Composable
private fun MediaActionsContent(target: MediaActionTarget, dismiss: () -> Unit, dismissNow: () -> Unit) {
    val graph = rememberAppGraph()
    val navigator = LocalOptionalAppNavigator.current
    val context = LocalActivity.current ?: LocalContext.current
    var page by remember(target) { mutableStateOf<SheetPage>(SheetPage.Main) }
    val scope = remember(graph, navigator, context, dismiss, dismissNow) {
        ActionScope(
            graph = graph,
            navigator = navigator,
            runner = MediaActionRunner(graph, navigator, context),
            context = context,
            dismiss = dismiss,
            dismissNow = dismissNow,
            setPage = { page = it },
        )
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "actionsPage",
    ) { current ->
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            when (current) {
                SheetPage.Main -> when (target) {
                    is MediaActionTarget.TrackTarget -> TrackActions(target, scope)
                    is MediaActionTarget.EpisodeTarget -> EpisodeActions(target, scope)
                    is MediaActionTarget.AlbumTarget -> AlbumActions(target, scope)
                    is MediaActionTarget.ArtistTarget -> ArtistActions(target, scope)
                    is MediaActionTarget.PlaylistTarget -> PlaylistActions(target, scope)
                    is MediaActionTarget.ShowTarget -> ShowActions(target, scope)
                }
                is SheetPage.Artists -> ArtistPicker(current.artists, scope)
                SheetPage.Rename -> (target as? MediaActionTarget.PlaylistTarget)?.let { RenamePlaylist(it, scope) }
                SheetPage.ConfirmDelete -> (target as? MediaActionTarget.PlaylistTarget)?.let { ConfirmDeletePlaylist(it, scope) }
            }
        }
    }
}

// ---- Per-target action lists ------------------------------------------------------------------

@Composable
private fun TrackActions(t: MediaActionTarget.TrackTarget, s: ActionScope) {
    val track = t.track
    val liked by remember(track.uri) { s.graph.library.isSaved(track.uri) }.collectAsStateWithLifecycle(null)
    val download by remember(track.uri) { s.graph.downloads.state(track.uri) }.collectAsStateWithLifecycle(null)
    SheetHeader(
        imageUrl = track.album?.images?.best(160),
        title = track.name,
        subtitle = track.artists.joinToString { it.name },
    )
    if (t.queueUid == null) {
        SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.shell_action_add_to_queue)) {
            s.dismiss()
            s.runner.addToQueue(listOf(track.uri))
        }
    }
    LikeAction(liked, s) { shown ->
        s.runner.setSaved(track.uri, !shown, R.string.shell_msg_liked, R.string.shell_msg_unliked)
    }
    SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.shell_action_add_to_playlist)) {
        s.dismissNow()
        s.navigator?.addToPlaylist(listOf(track.uri))
    }
    val playlistUri = t.playlistUri
    val playlistIndex = t.playlistIndex
    if (playlistUri != null && playlistIndex != null) {
        SheetAction(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.shell_action_remove_from_playlist)) {
            s.dismiss()
            s.runner.launch(R.string.shell_msg_removed_from_playlist) {
                s.graph.playlists.removeItems(playlistUri, listOf(track.uri to playlistIndex), t.playlistRevision)
            }
        }
    }
    t.queueUid?.let { uid ->
        SheetAction(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.shell_action_remove_from_queue)) {
            s.dismiss()
            s.graph.player.removeFromQueue(uid)
            s.runner.message(R.string.shell_msg_removed_from_queue)
        }
    }
    ItemDownloadAction(track.uri, download, s)
    // Refs may carry only a name (the last-session placeholder): nothing to open then.
    track.album?.takeIf { it.uri.isNotBlank() }?.let { album ->
        SheetAction(Icons.Rounded.Album, stringResource(R.string.shell_action_go_to_album)) { s.go(Route.Album(album.uri)) }
    }
    val artistPages = track.artists.filter { it.uri.isNotBlank() }
    if (artistPages.isNotEmpty()) {
        SheetAction(
            Icons.Rounded.Person,
            stringResource(if (artistPages.size > 1) R.string.shell_action_go_to_artists else R.string.shell_action_go_to_artist),
        ) { s.goToArtists(artistPages) }
    }
    SheetAction(Icons.Rounded.Radio, stringResource(R.string.shell_action_start_radio)) { s.radio(track.uri) }
    SheetAction(Icons.Rounded.Share, stringResource(R.string.shell_action_share)) { s.share(track.uri, track.name) }
}

@Composable
private fun EpisodeActions(t: MediaActionTarget.EpisodeTarget, s: ActionScope) {
    val episode = t.episode
    val saved by remember(episode.uri) { s.graph.library.isSaved(episode.uri) }.collectAsStateWithLifecycle(null)
    val download by remember(episode.uri) { s.graph.downloads.state(episode.uri) }.collectAsStateWithLifecycle(null)
    // As its rows show it: this phone's point, else Spotify's state the episode carries.
    val progress by s.graph.episodeProgress.version.collectAsStateWithLifecycle()
    val played = remember(episode, progress) { s.graph.episodeProgress.overlay(episode).fullyPlayed == true }
    SheetHeader(
        imageUrl = episode.images.best(160) ?: episode.show?.images?.best(160),
        title = episode.name,
        subtitle = episode.show?.name,
        placeholder = Icons.Rounded.Podcasts,
    )
    SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.shell_action_add_to_queue)) {
        s.dismiss()
        s.runner.addToQueue(listOf(episode.uri))
    }
    SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.shell_action_add_to_playlist)) {
        s.dismissNow()
        s.navigator?.addToPlaylist(listOf(episode.uri))
    }
    SavedAction(
        saved = saved,
        icon = { if (it) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd },
        label = { if (it) R.string.shell_action_remove_from_episodes else R.string.shell_action_save_to_episodes },
        s = s,
    ) { shown ->
        s.runner.setSaved(episode.uri, !shown, R.string.shell_msg_saved_episode, R.string.shell_msg_removed_episode)
    }
    ItemDownloadAction(episode.uri, download, s)
    SheetAction(
        if (played) Icons.Rounded.RemoveDone else Icons.Rounded.CheckCircleOutline,
        stringResource(if (played) R.string.shell_action_mark_unplayed else R.string.shell_action_mark_played),
    ) {
        s.dismiss()
        s.graph.episodeProgress.markPlayed(episode.uri, played = !played)
        s.runner.message(if (played) R.string.shell_msg_marked_unplayed else R.string.shell_msg_marked_played)
    }
    episode.show?.takeIf { it.uri.isNotBlank() }?.let { show ->
        SheetAction(Icons.Rounded.Podcasts, stringResource(R.string.shell_action_go_to_show)) { s.go(Route.Show(show.uri)) }
    }
    SheetAction(Icons.Rounded.Info, stringResource(R.string.shell_action_view_episode)) { s.go(Route.Episode(episode.uri)) }
    SheetAction(Icons.Rounded.Share, stringResource(R.string.shell_action_share)) { s.share(episode.uri, episode.name) }
}

@Composable
private fun AlbumActions(t: MediaActionTarget.AlbumTarget, s: ActionScope) {
    val album = t.album
    val saved by remember(album.uri) { s.graph.library.isSaved(album.uri) }.collectAsStateWithLifecycle(null)
    val downloaded by remember(album.uri) { s.graph.downloads.isCollectionDownloaded(album.uri) }.collectAsStateWithLifecycle(false)
    SheetHeader(
        imageUrl = album.images.best(160),
        title = album.name,
        subtitle = album.artists.joinToString { it.name },
        placeholder = Icons.Rounded.Album,
    )
    SheetAction(Icons.Rounded.PlayArrow, stringResource(R.string.shell_action_play)) {
        s.dismiss()
        s.graph.player.playContext(album.uri)
    }
    SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.shell_action_add_to_queue)) {
        s.dismiss()
        s.runner.addCollectionToQueue(album.uri) {
            s.graph.catalog.album(album.uri).awaitData()?.tracks.orEmpty().filter { it.playable }.map { it.uri }
        }
    }
    SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.shell_action_add_to_playlist)) {
        s.dismiss()
        // All its tracks, as Spotify adds an album (one it can't play here shows dimmed there too).
        s.runner.pickPlaylistFor {
            s.graph.catalog.album(album.uri).awaitData()?.tracks.orEmpty().map { it.uri }.filter(SpotifyUris::isPlayableItem)
        }
    }
    SavedAction(
        saved = saved,
        icon = { if (it) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd },
        label = { if (it) R.string.shell_action_remove_from_library else R.string.shell_action_save_to_library },
        s = s,
    ) { shown ->
        s.runner.setSaved(album.uri, !shown, R.string.shell_msg_saved_to_library, R.string.shell_msg_removed_from_library)
    }
    CollectionDownloadAction(downloaded, s) {
        CollectionRef(album.uri, CollectionType.ALBUM, album.name, album.images.best(300))
    }
    if (album.artists.isNotEmpty()) {
        SheetAction(
            Icons.Rounded.Person,
            stringResource(if (album.artists.size > 1) R.string.shell_action_go_to_artists else R.string.shell_action_go_to_artist),
        ) { s.goToArtists(album.artists) }
    }
    SheetAction(Icons.Rounded.Radio, stringResource(R.string.shell_action_start_radio)) { s.radio(album.uri) }
    SheetAction(Icons.Rounded.Share, stringResource(R.string.shell_action_share)) { s.share(album.uri, album.name) }
}

@Composable
private fun ArtistActions(t: MediaActionTarget.ArtistTarget, s: ActionScope) {
    val artist = t.artist
    val following by remember(artist.uri) { s.graph.library.isSaved(artist.uri) }.collectAsStateWithLifecycle(null)
    SheetHeader(
        imageUrl = artist.images.best(160),
        title = artist.name,
        subtitle = stringResource(R.string.shell_type_artist),
        shape = CircleShape,
        placeholder = Icons.Rounded.Person,
    )
    SavedAction(
        saved = following,
        icon = { if (it) Icons.Rounded.PersonRemove else Icons.Rounded.PersonAdd },
        label = { if (it) R.string.shell_action_unfollow else R.string.shell_action_follow },
        s = s,
        accent = false,
    ) { shown ->
        s.runner.setSaved(artist.uri, !shown, R.string.shell_msg_following, R.string.shell_msg_unfollowed)
    }
    SheetAction(Icons.Rounded.Person, stringResource(R.string.shell_action_view_artist)) { s.go(Route.Artist(artist.uri)) }
    SheetAction(Icons.Rounded.Radio, stringResource(R.string.shell_action_start_radio)) { s.radio(artist.uri) }
    SheetAction(Icons.Rounded.Share, stringResource(R.string.shell_action_share)) { s.share(artist.uri, artist.name) }
}

@Composable
private fun PlaylistActions(t: MediaActionTarget.PlaylistTarget, s: ActionScope) {
    val playlist = t.playlist
    val following by remember(playlist.uri) { s.graph.library.isSaved(playlist.uri) }.collectAsStateWithLifecycle(null)
    val downloaded by remember(playlist.uri) { s.graph.downloads.isCollectionDownloaded(playlist.uri) }.collectAsStateWithLifecycle(false)
    val owner = playlist.owner?.let { it.displayName ?: it.username }
    SheetHeader(
        imageUrl = playlist.images.best(160),
        title = playlist.name,
        subtitle = if (owner != null) stringResource(R.string.shell_playlist_by, owner) else stringResource(R.string.shell_type_playlist),
        placeholder = Icons.AutoMirrored.Rounded.QueueMusic,
    )
    SheetAction(Icons.Rounded.PlayArrow, stringResource(R.string.shell_action_play)) {
        s.dismiss()
        s.graph.player.playContext(playlist.uri)
    }
    SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.shell_action_add_to_queue)) {
        s.dismiss()
        s.runner.addCollectionToQueue(playlist.uri) { s.graph.catalog.playlistItemUris(playlist.uri) }
    }
    SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.shell_action_add_to_other_playlist)) {
        s.dismiss()
        s.runner.pickPlaylistFor(excludeUri = playlist.uri) { s.graph.catalog.playlistItemUrisToAdd(playlist.uri) }
    }
    if (t.isOwned) {
        SheetAction(Icons.Rounded.Edit, stringResource(R.string.shell_action_rename)) { s.setPage(SheetPage.Rename) }
        PlaylistPrivacyActions(playlist.uri, s)
        SheetAction(Icons.Rounded.Delete, stringResource(R.string.shell_action_delete_playlist)) { s.setPage(SheetPage.ConfirmDelete) }
    } else {
        SavedAction(
            saved = following,
            icon = { if (it) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd },
            label = { if (it) R.string.shell_action_remove_from_library else R.string.shell_action_save_to_library },
            s = s,
        ) { shown ->
            s.runner.launch(if (shown) R.string.shell_msg_removed_from_library else R.string.shell_msg_saved_to_library) {
                if (shown) s.graph.playlists.unfollow(playlist.uri) else s.graph.playlists.follow(playlist.uri)
            }
        }
    }
    CollectionDownloadAction(downloaded, s) {
        CollectionRef(playlist.uri, CollectionType.PLAYLIST, playlist.name, playlist.images.best(300))
    }
    SheetAction(Icons.Rounded.Share, stringResource(R.string.shell_action_share)) { s.share(playlist.uri, playlist.name) }
}

@Composable
private fun ShowActions(t: MediaActionTarget.ShowTarget, s: ActionScope) {
    val show = t.show
    val following by remember(show.uri) { s.graph.library.isSaved(show.uri) }.collectAsStateWithLifecycle(null)
    SheetHeader(
        imageUrl = show.images.best(160),
        title = show.name,
        subtitle = show.publisher ?: stringResource(R.string.shell_type_podcast),
        placeholder = Icons.Rounded.Podcasts,
    )
    SavedAction(
        saved = following,
        icon = { if (it) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd },
        label = { if (it) R.string.shell_action_unfollow else R.string.shell_action_follow },
        s = s,
    ) { shown ->
        s.runner.setSaved(show.uri, !shown, R.string.shell_msg_following, R.string.shell_msg_unfollowed)
    }
    SheetAction(Icons.Rounded.Podcasts, stringResource(R.string.shell_action_go_to_show)) { s.go(Route.Show(show.uri)) }
    SheetAction(Icons.Rounded.Share, stringResource(R.string.shell_action_share)) { s.share(show.uri, show.name) }
}

/**
 * "Make public / private" and "Make collaborative / non-collaborative" for an owned playlist, from
 * its state in the library (rootlist). A collaborative playlist is private (as in Spotify): it
 * offers no "Make public". Nothing is offered until the state is known.
 */
@Composable
private fun PlaylistPrivacyActions(uri: String, s: ActionScope) {
    val entry by remember(uri) {
        s.graph.library.playlists().map { resource -> resource.dataOrNull?.flatPlaylists()?.firstOrNull { it.uri == uri } }
    }.collectAsStateWithLifecycle(null)
    val current = entry ?: return
    val isPublic = current.isPublic
    if (isPublic != null && !current.collaborative) {
        SheetAction(
            icon = if (isPublic) Icons.Rounded.Lock else Icons.Rounded.Public,
            label = stringResource(if (isPublic) R.string.shell_action_make_private else R.string.shell_action_make_public),
        ) {
            s.dismiss()
            s.runner.launch(if (isPublic) R.string.shell_msg_playlist_private else R.string.shell_msg_playlist_public) {
                s.graph.playlists.setPublic(uri, !isPublic)
            }
        }
    }
    SheetAction(
        icon = if (current.collaborative) Icons.Rounded.PersonRemove else Icons.Rounded.GroupAdd,
        label = stringResource(if (current.collaborative) R.string.shell_action_make_not_collaborative else R.string.shell_action_make_collaborative),
    ) {
        s.dismiss()
        s.runner.launch(if (current.collaborative) R.string.shell_msg_playlist_not_collaborative else R.string.shell_msg_playlist_collaborative) {
            s.graph.playlists.setCollaborative(uri, !current.collaborative)
        }
    }
}

// ---- Shared action rows -------------------------------------------------------------------------

@Composable
private fun LikeAction(liked: Boolean?, s: ActionScope, toggle: (shown: Boolean) -> Unit) {
    SavedAction(
        saved = liked,
        icon = { if (it) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder },
        label = { if (it) R.string.shell_action_unlike else R.string.shell_action_like },
        s = s,
        toggle = toggle,
    )
}

/**
 * A save/like/follow row. [toggle] gets the state the row showed and writes its opposite. While
 * [saved] is unknown (null: lookup pending, or it failed offline and is retried online) the row
 * shows the "not saved" action disabled: a tap must not act on a guess.
 */
@Composable
private fun SavedAction(
    saved: Boolean?,
    icon: (Boolean) -> ImageVector,
    label: (Boolean) -> Int,
    s: ActionScope,
    accent: Boolean = true,
    toggle: (shown: Boolean) -> Unit,
) {
    val shown = saved == true
    SheetAction(
        icon = icon(shown),
        label = stringResource(label(shown)),
        tint = if (shown && accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        enabled = saved != null,
    ) {
        if (saved == null) return@SheetAction
        s.dismiss()
        toggle(saved)
    }
}

@Composable
private fun ItemDownloadAction(uri: String, state: DownloadState?, s: ActionScope) {
    val active = state == DownloadState.COMPLETED || state == DownloadState.QUEUED ||
        state == DownloadState.PREPARING || state == DownloadState.DOWNLOADING
    SheetAction(
        icon = if (active) Icons.Rounded.DownloadForOffline else Icons.Rounded.Download,
        label = stringResource(if (active) R.string.shell_action_remove_download else R.string.shell_action_download),
        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        s.dismiss()
        if (active) {
            s.runner.launch(R.string.shell_msg_download_removed) { s.graph.downloads.removeItems(listOf(uri)) }
        } else {
            // Says what really happened: nothing starts for a download on the chosen SD card while it is away.
            s.runner.launch {
                val request = s.graph.downloads.downloadItems(listOf(uri))
                withContext(Dispatchers.Main) { s.runner.message(request.notice ?: R.string.shell_msg_download_started) }
            }
        }
    }
}

@Composable
private fun CollectionDownloadAction(downloaded: Boolean, s: ActionScope, ref: () -> CollectionRef) {
    SheetAction(
        icon = if (downloaded) Icons.Rounded.DownloadForOffline else Icons.Rounded.Download,
        label = stringResource(if (downloaded) R.string.shell_action_remove_download else R.string.shell_action_download),
        tint = if (downloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        s.dismiss()
        val collection = ref()
        if (downloaded) {
            s.runner.launch(R.string.shell_msg_download_removed) { s.graph.downloads.removeCollection(collection.uri) }
        } else {
            s.runner.launch {
                val request = s.graph.downloads.downloadCollection(collection)
                withContext(Dispatchers.Main) { s.runner.message(request.notice ?: R.string.shell_msg_download_started) }
            }
        }
    }
}

@Composable
private fun ArtistPicker(artists: List<ArtistRef>, s: ActionScope) {
    SubPageTitle(stringResource(R.string.shell_choose_artist)) { s.setPage(SheetPage.Main) }
    artists.forEach { artist ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { s.go(Route.Artist(artist.uri)) }
                .heightIn(min = 64.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(artist.images.best(120), null, Modifier.size(48.dp), CircleShape, Icons.Rounded.Person)
            Spacer(Modifier.width(16.dp))
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RenamePlaylist(t: MediaActionTarget.PlaylistTarget, s: ActionScope) {
    var name by rememberSaveable(t.playlist.uri) { mutableStateOf(t.playlist.name) }
    val focus = remember { FocusRequester() }
    val canSave = name.isNotBlank() && name.trim() != t.playlist.name
    val save = {
        if (canSave) {
            val newName = name.trim()
            s.dismiss()
            s.runner.launch(R.string.shell_msg_playlist_renamed) {
                s.graph.playlists.updateDetails(t.playlist.uri, name = newName)
            }
        }
    }
    SubPageTitle(stringResource(R.string.shell_rename_playlist)) { s.setPage(SheetPage.Main) }
    Column(Modifier.padding(horizontal = 20.dp).imePadding()) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(stringResource(R.string.shell_playlist_name)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = { s.setPage(SheetPage.Main) }) { Text(stringResource(R.string.shell_cancel)) }
            Button(onClick = save, enabled = canSave) { Text(stringResource(R.string.shell_save)) }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun ConfirmDeletePlaylist(t: MediaActionTarget.PlaylistTarget, s: ActionScope) {
    SubPageTitle(stringResource(R.string.shell_delete_playlist_title)) { s.setPage(SheetPage.Main) }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(R.string.shell_delete_playlist_message, t.playlist.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = { s.setPage(SheetPage.Main) }) { Text(stringResource(R.string.shell_cancel)) }
            Button(
                onClick = {
                    s.dismiss()
                    (s.navigator as? MainNavigator)?.leavePlaylist(t.playlist.uri)
                    s.runner.launch(R.string.shell_msg_playlist_deleted) { s.graph.playlists.delete(t.playlist.uri) }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(R.string.shell_delete)) }
        }
    }
}

// ---- Building blocks ------------------------------------------------------------------------

@Composable
private fun SheetHeader(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    shape: Shape = RoundedCornerShape(4.dp),
    placeholder: ImageVector = Icons.Rounded.Album,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(imageUrl, null, Modifier.size(56.dp), shape, placeholder)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SubPageTitle(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.shell_cd_back))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Material's disabled content alpha. */
private const val DISABLED_ALPHA = 0.38f

// ---- Add to playlist ------------------------------------------------------------------------

private sealed interface PickerEntry {
    val key: String

    data class Folder(val name: String, val depth: Int, override val key: String) : PickerEntry
    data class Playlist(val entry: RootlistEntry, val uri: String, val depth: Int, override val key: String) : PickerEntry
}

/**
 * Flattens the rootlist into folder headers + playlists the user can add to: those marked
 * [RootlistEntry.canEdit], plus collaborative ones. While the engine reports canEdit for none of
 * them (not populated yet), playlists owned by the current user (or with an unknown owner) stand
 * in; the server rejects anything else and the error is shown.
 */
private fun buildPickerEntries(rootlist: Rootlist?, me: String?, excludeUri: String? = null): List<PickerEntry> {
    if (rootlist == null) return emptyList()
    val seen = HashSet<String>()
    val canEditKnown = rootlist.flatPlaylists().any { it.canEdit }
    fun owned(e: RootlistEntry): Boolean =
        me == null || e.owner == null || e.owner.username.equals(me, ignoreCase = true)
    fun editable(e: RootlistEntry): Boolean = e.canEdit || e.collaborative || (!canEditKnown && owned(e))

    fun walk(entries: List<RootlistEntry>, depth: Int, path: String): List<PickerEntry> = buildList {
        entries.forEachIndexed { i, e ->
            when (e.type) {
                RootlistEntryType.FOLDER -> {
                    val children = walk(e.children, depth + 1, "$path/$i")
                    if (children.isNotEmpty()) {
                        add(PickerEntry.Folder(e.name, depth, "f:$path/$i"))
                        addAll(children)
                    }
                }
                RootlistEntryType.PLAYLIST -> {
                    val uri = e.uri
                    if (uri != null && uri != excludeUri && editable(e) && seen.add(uri)) add(PickerEntry.Playlist(e, uri, depth, "p:$uri"))
                }
            }
        }
    }
    return walk(rootlist.items, 0, "")
}

/**
 * Pick (or create) a playlist to add [uris] to. Only editable playlists are listed, [excludeUri]
 * (the playlist they come from) not. Picking one checks it for items already in it ("Already
 * added", [MediaActionRunner.addToPlaylist]).
 */
@Composable
fun AddToPlaylistSheet(uris: List<String>, onDismiss: () -> Unit, excludeUri: String? = null) {
    val graph = rememberAppGraph()
    val navigator = LocalOptionalAppNavigator.current
    val context = LocalContext.current
    val runner = remember(graph, navigator) { MediaActionRunner(graph, navigator, context) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val animatedDismiss: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    val rootlist by remember { graph.library.playlists() }.collectAsStateWithLifecycle(Resource.Loading())
    val me by graph.engine.user.collectAsStateWithLifecycle()
    val entries = remember(rootlist, me, excludeUri) { buildPickerEntries(rootlist.dataOrNull, me?.username, excludeUri) }
    var showCreate by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = stringResource(R.string.shell_add_to_playlist_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .semantics { heading() },
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            item(key = "new") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { showCreate = true }
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.shell_new_playlist),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (entries.isEmpty()) {
                item(key = "state") {
                    when (val r = rootlist) {
                        is Resource.Loading -> Box(
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(Modifier.size(32.dp)) }
                        is Resource.Error -> ErrorState(
                            message = friendlyErrorMessage(context, r.error),
                            onRetry = null,
                            modifier = Modifier.height(200.dp),
                        )
                        is Resource.Success -> Text(
                            text = stringResource(R.string.shell_no_editable_playlists),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is PickerEntry.Folder -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp + (entry.depth * 16).dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    is PickerEntry.Playlist -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                animatedDismiss()
                                runner.addToPlaylist(entry.uri, entry.entry.name, uris)
                            }
                            .heightIn(min = 64.dp)
                            .padding(start = 20.dp + (entry.depth * 16).dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(entry.entry.images.best(120), null, Modifier.size(48.dp), placeholderIcon = Icons.AutoMirrored.Rounded.QueueMusic)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            entry.entry.owner?.let { owner ->
                                Text(
                                    text = owner.displayName ?: owner.username,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onDismiss = { showCreate = false },
            onCreated = {
                runner.message(R.string.shell_msg_added_to_new_playlist)
                animatedDismiss()
            },
            initialUris = uris,
        )
    }
}

/**
 * Spotify's "Already added": some of the items of an add are in the playlist already. One item, or
 * none new: "Add anyway" / "Don't add"; some new: "Add new ones" / "Add anyway" / "Cancel". When
 * the playlist couldn't be checked: "Add anyway" / "Cancel".
 */
@Composable
fun AlreadyAddedDialog(prompt: PlaylistAddPrompt, onDismiss: () -> Unit) {
    val graph = rememberAppGraph()
    val navigator = LocalOptionalAppNavigator.current
    val context = LocalContext.current
    val runner = remember(graph, navigator) { MediaActionRunner(graph, navigator, context) }
    val choose: (PlaylistAddChoice) -> Unit = { choice ->
        onDismiss()
        runner.addToPlaylist(prompt, choice)
    }
    val message = when {
        prompt.unchecked -> R.string.shell_add_unchecked_message
        prompt.offersNewOnes -> R.string.shell_already_added_some
        prompt.plan.requested.size == 1 -> R.string.shell_already_added_one
        else -> R.string.shell_already_added_all
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (prompt.unchecked) R.string.shell_add_unchecked_title else R.string.shell_already_added_title)) },
        text = { Text(stringResource(message, prompt.playlistName)) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { choose(PlaylistAddChoice.ALL) }) { Text(stringResource(R.string.shell_action_add_anyway)) }
                if (prompt.offersNewOnes) {
                    TextButton(onClick = { choose(PlaylistAddChoice.NEW_ONES) }) { Text(stringResource(R.string.shell_action_add_new_ones)) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (prompt.offersNewOnes || prompt.unchecked) R.string.shell_cancel else R.string.shell_action_dont_add))
            }
        },
    )
}

/**
 * Asks for a name and creates a playlist (optionally with [initialUris]). Calls [onCreated] with
 * the new playlist URI, then [onDismiss]. The creation completes even if the dialog goes away.
 */
@Composable
fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit, initialUris: List<String> = emptyList()) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val defaultName = stringResource(R.string.shell_new_playlist_default_name)
    var name by rememberSaveable { mutableStateOf("") }
    // As Spotify: new playlists show on the profile unless the user says otherwise.
    var public by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    val create: () -> Unit = {
        if (!busy) {
            val finalName = name.trim().ifEmpty { defaultName }
            busy = true
            error = null
            scope.launch {
                val result = withContext(NonCancellable) {
                    runCatching { graph.playlists.create(finalName, public = public, initialUris = initialUris) }
                }
                busy = false
                result
                    .onSuccess { uri ->
                        onCreated(uri)
                        onDismiss()
                    }
                    .onFailure { error = friendlyErrorMessage(context, it) }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shell_create_playlist_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    singleLine = true,
                    enabled = !busy,
                    placeholder = { Text(defaultName) },
                    label = { Text(stringResource(R.string.shell_playlist_name)) },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = public, enabled = !busy, role = Role.Switch, onValueChange = { public = it })
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.shell_playlist_public), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = public, onCheckedChange = null, enabled = !busy)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = create, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.shell_create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.shell_cancel)) }
        },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
