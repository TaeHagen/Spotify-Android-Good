package com.taehagen.spotifygood.ui.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.PlaylistOwner
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.DetailHeader
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.PartialContentNotice
import com.taehagen.spotifygood.ui.components.EpisodeRow
import com.taehagen.spotifygood.ui.components.PlayFab
import com.taehagen.spotifygood.ui.components.TrackRow
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MainNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.album.AddedButton
import com.taehagen.spotifygood.ui.screens.album.CollectionDownloadButton
import com.taehagen.spotifygood.ui.screens.album.DetailActionRow
import com.taehagen.spotifygood.ui.screens.album.DownloadedCopyNotice
import com.taehagen.spotifygood.ui.screens.album.DetailScaffold
import com.taehagen.spotifygood.ui.screens.album.ExpandableText
import com.taehagen.spotifygood.ui.screens.album.HeaderMetaText
import com.taehagen.spotifygood.ui.screens.album.LoadMoreEffect
import com.taehagen.spotifygood.ui.screens.album.LoadState
import com.taehagen.spotifygood.ui.screens.album.LoadStateContent
import com.taehagen.spotifygood.ui.screens.album.MoreButton
import com.taehagen.spotifygood.ui.screens.album.PagingFooter
import com.taehagen.spotifygood.ui.screens.album.ShuffleButton
import com.taehagen.spotifygood.ui.screens.album.SmartShuffleButton
import com.taehagen.spotifygood.ui.screens.album.dataOrNull
import com.taehagen.spotifygood.ui.screens.album.detailTopInset
import com.taehagen.spotifygood.ui.screens.album.isRefreshing
import com.taehagen.spotifygood.ui.screens.album.rememberRichText
import com.taehagen.spotifygood.ui.screens.album.songsAndDuration

@Composable
fun PlaylistScreen(uri: String, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = uri) { graph -> PlaylistViewModel(graph, uri) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    var query by rememberSaveable { mutableStateOf("") }
    var showAddSongs by rememberSaveable { mutableStateOf(false) }
    var showEditDetails by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val data = state.load.dataOrNull()

    // The query survives process death (saveable) but a recreated ViewModel starts unfiltered.
    LaunchedEffect(viewModel) { if (query.isNotEmpty()) viewModel.setFilter(query) }
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                // The "deleted" message comes from the ViewModel (also when the page is gone).
                PlaylistEvent.Deleted -> if (navigator is MainNavigator) navigator.leavePlaylist(uri) else navigator.back()
            }
        }
    }
    LoadMoreEffect(
        listState = listState,
        enabled = state.load is LoadState.Ready && !state.paging.failed && !state.list.filterActive,
        onLoadMore = viewModel::loadMore,
    )
    val exitEditMode = {
        viewModel.setEditMode(false)
    }
    BackHandler(enabled = state.editMode, onBack = exitEditMode)
    val actions = remember(viewModel) {
        PlaylistActions(
            onPlay = viewModel::playContext,
            onShuffle = viewModel::shuffleContext,
            onSmartShuffle = viewModel::smartShuffleContext,
            onRetryPartial = viewModel::retryPartial,
            onToggleFollow = viewModel::toggleFollow,
            onDownload = viewModel::download,
            onRemoveDownload = { viewModel.removeCollectionDownload() },
            onPlayItem = viewModel::playItem,
            onRetryPage = viewModel::retryPage,
            onAddSongs = { showAddSongs = true },
            onEditDetails = { showEditDetails = true },
            onDelete = { confirmDelete = true },
            onRemoveItem = viewModel::removeItem,
            onMoveItem = viewModel::moveItem,
            onDragStart = viewModel::beginDrag,
            onDragMove = viewModel::previewMove,
            onDragEnd = viewModel::endDrag,
        )
    }

    DetailScaffold(
        title = data?.meta?.name.orEmpty(),
        showTitle = showTitle || state.editMode,
        onBack = { if (state.editMode) exitEditMode() else navigator.back() },
        refreshing = state.load.isRefreshing,
        modifier = modifier,
        actions = {
            when {
                state.editMode -> TextButton(onClick = exitEditMode) {
                    Text(stringResource(R.string.detail_done), fontWeight = FontWeight.Bold)
                }
                // Rows from the download are read-only (setEditMode refuses them).
                data?.meta?.canEdit == true && !data.downloadedCopy -> IconButton(
                    onClick = {
                        query = ""
                        viewModel.setEditMode(true)
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = if (showTitle) 0f else 0.4f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.detail_edit_playlist))
                }
            }
        },
    ) {
        LoadStateContent(state.load, offline = state.offline, onRetry = viewModel::retry) { playlist ->
            PlaylistList(
                uri = uri,
                playlist = playlist,
                state = state,
                query = query,
                onQueryChange = {
                    query = it
                    viewModel.setFilter(it)
                },
                listState = listState,
                contentPadding = contentPadding,
                navigator = navigator,
                actions = actions,
            )
        }
    }

    if (showAddSongs) {
        val addState by viewModel.addSongs.collectAsStateWithLifecycle()
        AddSongsSheet(
            state = addState,
            onQueryChange = viewModel::setAddQuery,
            onAdd = { viewModel.addTracks(listOf(it)) },
            onDismiss = {
                showAddSongs = false
                viewModel.setAddQuery("")
            },
        )
    }
    if (showEditDetails && data != null) {
        EditDetailsDialog(
            initialName = data.meta.name,
            initialDescription = data.description.text,
            onSave = { name, description ->
                showEditDetails = false
                viewModel.updateDetails(name, description)
            },
            onDismiss = { showEditDetails = false },
        )
    }
    if (confirmDelete && data != null) {
        DeletePlaylistDialog(
            name = data.meta.name,
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Callbacks of the playlist page (stable holder to keep parameter lists short). */
private class PlaylistActions(
    val onPlay: () -> Unit,
    val onShuffle: () -> Unit,
    val onSmartShuffle: () -> Unit,
    val onRetryPartial: () -> Unit,
    val onToggleFollow: () -> Unit,
    val onDownload: () -> Unit,
    val onRemoveDownload: () -> Unit,
    val onPlayItem: (VisibleRow) -> Unit,
    val onRetryPage: () -> Unit,
    val onAddSongs: () -> Unit,
    val onEditDetails: () -> Unit,
    val onDelete: () -> Unit,
    val onRemoveItem: (String) -> Unit,
    val onMoveItem: (Int, Int) -> Unit,
    val onDragStart: () -> Unit,
    val onDragMove: (Int, Int) -> Unit,
    val onDragEnd: (Int, Int) -> Unit,
)

@Composable
private fun PlaylistList(
    uri: String,
    playlist: PlaylistData,
    state: PlaylistUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    actions: PlaylistActions,
) {
    val meta = playlist.meta
    val rows = state.list.rows
    val currentRows by rememberUpdatedState(playlist.rows)
    val reorderState = rememberReorderState(
        listState = listState,
        indexOfKey = { key -> currentRows.indexOfFirst { it.key == key }.takeIf { it >= 0 } },
        onStart = actions.onDragStart,
        onMove = actions.onDragMove,
        onEnd = actions.onDragEnd,
        edgeTop = detailTopInset(),
        edgeBottom = contentPadding.calculateBottomPadding() +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    )

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "header", contentType = "header") {
            PlaylistHeader(playlist, state, navigator, actions)
        }
        if (playlist.downloadedCopy) {
            item(key = "downloaded", contentType = "notice") {
                DownloadedCopyNotice(stringResource(R.string.detail_showing_downloaded_tracks))
            }
        }
        if (playlist.partial && !state.editMode) {
            // Some rows are placeholders (their metadata failed right now).
            item(key = "partial", contentType = "notice") { PartialContentNotice(onRetry = actions.onRetryPartial) }
        }
        if (!state.editMode && playlist.total > 0) {
            item(key = "filter", contentType = "filter") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SearchField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = stringResource(R.string.detail_find_in_playlist),
                    )
                    if (state.paging.loadingAll) {
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        items(
            rows,
            key = { it.row.key },
            contentType = { if (it.row.item.episode != null) "episode" else "track" },
        ) { visible ->
            if (state.editMode) {
                val dragging = reorderState.draggingKey == visible.row.key
                EditableRow(
                    row = visible,
                    count = playlist.rows.size,
                    reorderState = reorderState,
                    onRemove = { actions.onRemoveItem(visible.row.key) },
                    onMove = actions.onMoveItem,
                    modifier = Modifier
                        // The dragged item follows the finger; everything else animates into place.
                        .then(if (dragging) Modifier else Modifier.animateItem())
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = reorderState.translationFor(visible.row.key) }
                        .then(
                            if (dragging) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier,
                        ),
                )
            } else {
                PlaylistItemRow(
                    uri = uri,
                    visible = visible,
                    playlist = playlist,
                    state = state,
                    navigator = navigator,
                    onPlay = actions.onPlayItem,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        item(key = "footer", contentType = "footer") {
            when {
                playlist.total == 0 && playlist.rows.isEmpty() -> EmptyState(
                    title = stringResource(R.string.detail_playlist_empty),
                    message = if (meta.canEdit) stringResource(R.string.detail_playlist_empty_owned) else null,
                    action = if (meta.canEdit) {
                        {
                            Button(onClick = actions.onAddSongs) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.detail_add_songs))
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.padding(vertical = 32.dp),
                )
                state.list.filterActive && rows.isEmpty() && !state.paging.loadingAll -> EmptyState(
                    title = stringResource(R.string.detail_no_matches, query.trim()),
                    message = stringResource(R.string.detail_no_matches_message),
                    modifier = Modifier.padding(vertical = 32.dp),
                )
                else -> PagingFooter(
                    loading = state.paging.loading,
                    failed = state.paging.failed,
                    onRetry = actions.onRetryPage,
                )
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    playlist: PlaylistData,
    state: PlaylistUiState,
    navigator: AppNavigator,
    actions: PlaylistActions,
) {
    val meta = playlist.meta
    val description = rememberRichText(playlist.description)
    val isContext = state.playback.isContext(meta.uri)
    DetailHeader(
        title = meta.name,
        imageUrl = meta.images.best(640),
        modifier = Modifier.fillMaxWidth(),
        actions = {
            Column(Modifier.fillMaxWidth()) {
                if (!playlist.description.isEmpty) {
                    ExpandableText(text = description, collapsedLines = 2)
                }
                meta.owner?.let { owner ->
                    OwnerLine(
                        owner = owner,
                        collaborative = meta.collaborative,
                        onClick = { navigator.navigate(Route.Profile(owner.username)) },
                    )
                }
                HeaderMetaText(songsAndDuration(playlist.total, state.list.totalDurationMs))
                if (state.editMode) {
                    EditToolbar(
                        isOwned = meta.isOwnedByMe,
                        onAddSongs = actions.onAddSongs,
                        onEditDetails = actions.onEditDetails,
                        onDelete = actions.onDelete,
                    )
                } else {
                    DetailActionRow(
                        leading = {
                            if (!meta.isOwnedByMe) {
                                AddedButton(
                                    added = state.following == true,
                                    onClick = actions.onToggleFollow,
                                    addDescription = R.string.detail_save_playlist,
                                    removeDescription = R.string.detail_remove_playlist,
                                )
                            }
                            CollectionDownloadButton(
                                ui = state.download,
                                onDownload = actions.onDownload,
                                onRemove = actions.onRemoveDownload,
                            )
                            MoreButton(
                                onClick = {
                                    navigator.showActions(MediaActionTarget.PlaylistTarget(meta.toRef(), isOwned = meta.isOwnedByMe))
                                },
                                label = meta.name,
                            )
                        },
                        trailing = {
                            // The engine rejects smart shuffle offline (it needs recommendations); plain shuffle works.
                            if (!state.offline) {
                                SmartShuffleButton(active = isContext && state.playback.smartShuffle, onClick = actions.onSmartShuffle)
                            }
                            ShuffleButton(
                                active = isContext && state.playback.shuffle && !state.playback.smartShuffle,
                                onClick = actions.onShuffle,
                            )
                            PlayFab(isPlaying = state.playback.isPlayingContext(meta.uri), onClick = actions.onPlay)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun OwnerLine(owner: PlaylistOwner, collaborative: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.detail_open_profile), onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        val name = owner.displayName?.takeIf { it.isNotBlank() } ?: owner.username
        Text(
            if (collaborative) stringResource(R.string.detail_owner_collaborative, name) else name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PlaylistItemRow(
    uri: String,
    visible: VisibleRow,
    playlist: PlaylistData,
    state: PlaylistUiState,
    navigator: AppNavigator,
    onPlay: (VisibleRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = visible.row.item
    val track = item.track
    val episode = item.episode
    val itemUri = item.uri ?: return
    val downloadState = state.rowDownloads[itemUri]
    // Unplayable (or not downloaded while offline): dimmed, actions still in the overflow.
    val playable = (track?.playable ?: episode?.playable ?: false) &&
        (!state.offline || downloadState == DownloadState.COMPLETED)
    val onClick = { onPlay(visible) }
    val editable = playlist.meta.canEdit && !playlist.downloadedCopy
    when {
        track != null -> {
            val showActions = {
                navigator.showActions(
                    MediaActionTarget.TrackTarget(
                        track = track,
                        contextUri = uri,
                        // Positional "remove from playlist" only with server rows (not the download's).
                        playlistUri = uri.takeIf { editable },
                        playlistIndex = visible.index.takeIf { editable },
                        playlistRevision = playlist.revision.takeIf { editable },
                    ),
                )
            }
            TrackRow(
                track = track,
                onClick = onClick,
                modifier = modifier,
                isCurrent = state.playback.isCurrent(itemUri),
                isPlaying = state.playback.isPlayingItem(itemUri),
                showArtwork = true,
                downloadState = downloadState,
                onMoreClick = showActions,
                onLongClick = showActions,
                enabled = playable,
            )
        }
        episode != null -> {
            val showActions = { navigator.showActions(MediaActionTarget.EpisodeTarget(episode)) }
            EpisodeRow(
                episode = episode,
                onClick = onClick,
                modifier = modifier,
                isCurrent = state.playback.isCurrent(itemUri),
                isPlaying = state.playback.isPlayingItem(itemUri),
                downloadState = downloadState,
                onLongClick = showActions,
                enabled = playable,
                onMoreClick = showActions,
            )
        }
    }
}
