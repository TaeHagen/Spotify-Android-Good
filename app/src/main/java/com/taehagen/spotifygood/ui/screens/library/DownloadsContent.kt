package com.taehagen.spotifygood.ui.screens.library

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.download.CollectionDownloadStatus
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.DownloadIndicator
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.EpisodeRow
import com.taehagen.spotifygood.ui.components.LoadingState
import com.taehagen.spotifygood.ui.components.MediaRow
import com.taehagen.spotifygood.ui.components.OfflineBanner
import com.taehagen.spotifygood.ui.components.SectionHeader
import com.taehagen.spotifygood.ui.components.TrackRow
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import kotlinx.coroutines.flow.Flow

private sealed interface PendingRemoval {
    data class Collection(val uri: String, val name: String) : PendingRemoval
    data class Item(val uri: String, val title: String) : PendingRemoval
    data object All : PendingRemoval
}

private sealed interface SheetTarget {
    data class OfCollection(val collection: DownloadedCollection) : SheetTarget
    data class OfEntry(val entry: DownloadEntry) : SheetTarget
}

private data class SheetAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
internal fun DownloadsContent(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { graph -> DownloadsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val resources = LocalResources.current
    var menuOpen by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<PendingRemoval?>(null) }
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { navigator.showMessage(resources.getString(it.messageRes())) }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BrowseTopBar(
            title = stringResource(R.string.browse_library_downloads),
            onBack = navigator::back,
            actions = {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.browse_more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (state.content.failedCount > 0) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browse_downloads_retry_failed)) },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.retryFailed()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_downloads_remove_all)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            enabled = !state.content.isEmpty || state.usedBytes > 0,
                            onClick = {
                                menuOpen = false
                                pendingRemoval = PendingRemoval.All
                            },
                        )
                    }
                }
            },
        )
        when {
            state.isLoading -> LoadingState()
            state.content.isEmpty -> StateBox {
                EmptyState(
                    title = stringResource(R.string.browse_downloads_empty_title),
                    message = stringResource(R.string.browse_downloads_empty_message),
                    icon = Icons.Rounded.DownloadForOffline,
                    action = {
                        Button(onClick = { navigator.navigate(Route.Library) }) {
                            Text(stringResource(R.string.browse_downloads_browse_library))
                        }
                    },
                )
            }
            else -> DownloadsList(
                state = state,
                contentPadding = contentPadding,
                statusOf = viewModel::collectionStatus,
                onPlayCollection = viewModel::playCollection,
                onCollectionMore = { sheet = SheetTarget.OfCollection(it) },
                onPlayEntry = viewModel::playEntry,
                onEntryMore = { sheet = SheetTarget.OfEntry(it) },
                onRetryEntry = { viewModel.retryItem(it.uri) },
                onRetryFailed = viewModel::retryFailed,
            )
        }
    }

    when (val target = sheet) {
        is SheetTarget.OfCollection -> {
            val c = target.collection
            val name = collectionName(c)
            DownloadSheet(
                title = name,
                onDismiss = { sheet = null },
                actions = listOf(
                    SheetAction(Icons.Rounded.PlayArrow, stringResource(R.string.browse_play)) { viewModel.playCollection(c) },
                    SheetAction(Icons.AutoMirrored.Rounded.OpenInNew, goToLabel(c.type)) { navigator.openCollection(c) },
                    SheetAction(Icons.Rounded.Delete, stringResource(R.string.browse_remove_download)) {
                        pendingRemoval = PendingRemoval.Collection(c.uri, name)
                    },
                ),
            )
        }
        is SheetTarget.OfEntry -> {
            val e = target.entry
            val title = e.track?.name ?: e.episode?.name ?: e.uri
            val actions = buildList {
                add(SheetAction(Icons.Rounded.PlayArrow, stringResource(R.string.browse_play)) { viewModel.playEntry(e) })
                if (e.state == DownloadState.FAILED) {
                    add(SheetAction(Icons.Rounded.Refresh, stringResource(R.string.browse_retry_download)) { viewModel.retryItem(e.uri) })
                }
                add(SheetAction(Icons.Rounded.Delete, stringResource(R.string.browse_remove_download)) {
                    pendingRemoval = PendingRemoval.Item(e.uri, title)
                })
                val actionTarget = e.track?.let { MediaActionTarget.TrackTarget(it) } ?: e.episode?.let { MediaActionTarget.EpisodeTarget(it) }
                if (actionTarget != null) {
                    add(SheetAction(Icons.Rounded.MoreHoriz, stringResource(R.string.browse_more_options)) { navigator.showActions(actionTarget) })
                }
            }
            DownloadSheet(title = title, onDismiss = { sheet = null }, actions = actions)
        }
        null -> Unit
    }

    pendingRemoval?.let { removal ->
        when (removal) {
            is PendingRemoval.Collection -> ConfirmDialog(
                title = stringResource(R.string.browse_downloads_remove_item_title, removal.name),
                text = stringResource(R.string.browse_downloads_remove_item_message),
                confirmLabel = stringResource(R.string.browse_remove),
                onConfirm = { viewModel.removeCollection(removal.uri) },
                onDismiss = { pendingRemoval = null },
            )
            is PendingRemoval.Item -> ConfirmDialog(
                title = stringResource(R.string.browse_downloads_remove_item_title, removal.title),
                text = stringResource(R.string.browse_downloads_remove_item_message),
                confirmLabel = stringResource(R.string.browse_remove),
                onConfirm = { viewModel.removeItem(removal.uri) },
                onDismiss = { pendingRemoval = null },
            )
            PendingRemoval.All -> ConfirmDialog(
                title = stringResource(R.string.browse_downloads_remove_all_title),
                text = stringResource(R.string.browse_downloads_remove_all_message),
                confirmLabel = stringResource(R.string.browse_remove_all),
                onConfirm = viewModel::removeAll,
                onDismiss = { pendingRemoval = null },
            )
        }
    }
}

private fun AppNavigator.openCollection(collection: DownloadedCollection) {
    if (collection.type == CollectionType.LIKED_SONGS) navigate(Route.LikedSongs) else open(collection.toMediaRef())
}

@Composable
private fun collectionName(collection: DownloadedCollection): String =
    if (collection.type == CollectionType.LIKED_SONGS) stringResource(R.string.browse_liked_songs) else collection.name

@Composable
private fun goToLabel(type: CollectionType): String = stringResource(
    when (type) {
        CollectionType.PLAYLIST, CollectionType.LIKED_SONGS -> R.string.browse_go_to_playlist
        CollectionType.ALBUM -> R.string.browse_go_to_album
        CollectionType.SHOW -> R.string.browse_go_to_podcast
    },
)

@Composable
private fun DownloadsList(
    state: DownloadsUiState,
    contentPadding: PaddingValues,
    statusOf: (String) -> Flow<CollectionDownloadStatus>,
    onPlayCollection: (DownloadedCollection) -> Unit,
    onCollectionMore: (DownloadedCollection) -> Unit,
    onPlayEntry: (DownloadEntry) -> Unit,
    onEntryMore: (DownloadEntry) -> Unit,
    onRetryEntry: (DownloadEntry) -> Unit,
    onRetryFailed: () -> Unit,
) {
    val content = state.content
    LazyColumn(contentPadding = contentPaddingWith(contentPadding), modifier = Modifier.fillMaxSize()) {
        item(key = "header", contentType = "header") {
            StorageHeader(state = state, onRetryFailed = onRetryFailed)
        }
        if (state.offline) {
            item(key = "offline", contentType = "banner") {
                OfflineBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        collectionSection("playlists", R.string.browse_downloads_section_playlists, content.playlists, statusOf, onPlayCollection, onCollectionMore)
        collectionSection("albums", R.string.browse_downloads_section_albums, content.albums, statusOf, onPlayCollection, onCollectionMore)
        collectionSection("podcasts", R.string.browse_downloads_section_podcasts, content.podcasts, statusOf, onPlayCollection, onCollectionMore)
        if (content.songs.isNotEmpty()) {
            item(key = "songsHeader", contentType = "sectionHeader") {
                SectionHeader(
                    title = stringResource(R.string.browse_downloads_section_songs),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(content.songs, key = { "song:${it.uri}" }, contentType = { "song" }) { entry ->
                val track = remember(entry.uri, entry.track) { entry.track ?: Track(uri = entry.uri, name = entry.uri.substringAfterLast(':')) }
                TrackRow(
                    track = track,
                    onClick = { onPlayEntry(entry) },
                    isCurrent = state.nowPlaying.isCurrent(entry.uri),
                    isPlaying = state.nowPlaying.isPlaying,
                    subtitleOverride = entryStatusText(entry),
                    downloadState = entry.state,
                    onMoreClick = { onEntryMore(entry) },
                    onLongClick = { onEntryMore(entry) },
                    trailing = if (entry.state == DownloadState.FAILED) {
                        {
                            IconButton(onClick = { onRetryEntry(entry) }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.browse_retry_download))
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
        if (content.episodes.isNotEmpty()) {
            item(key = "episodesHeader", contentType = "sectionHeader") {
                SectionHeader(
                    title = stringResource(R.string.browse_downloads_section_episodes),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(content.episodes, key = { "episode:${it.uri}" }, contentType = { "episode" }) { entry ->
                val episode = remember(entry.uri, entry.episode) { entry.episode ?: Episode(uri = entry.uri, name = entry.uri.substringAfterLast(':')) }
                EpisodeRow(
                    episode = episode,
                    onClick = { onPlayEntry(entry) },
                    isCurrent = state.nowPlaying.isCurrent(entry.uri),
                    isPlaying = state.nowPlaying.isPlaying,
                    downloadState = entry.state,
                    onMoreClick = { onEntryMore(entry) },
                )
            }
        }
    }
}

private fun LazyListScope.collectionSection(
    id: String,
    title: Int,
    collections: List<DownloadedCollection>,
    statusOf: (String) -> Flow<CollectionDownloadStatus>,
    onPlay: (DownloadedCollection) -> Unit,
    onMore: (DownloadedCollection) -> Unit,
) {
    if (collections.isEmpty()) return
    item(key = "header:$id", contentType = "sectionHeader") {
        SectionHeader(
            title = stringResource(title),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(collections, key = { "collection:${it.uri}" }, contentType = { "collection" }) { collection ->
        CollectionRow(collection = collection, statusOf = statusOf, onClick = { onPlay(collection) }, onMore = { onMore(collection) })
    }
}

@Composable
private fun CollectionRow(
    collection: DownloadedCollection,
    statusOf: (String) -> Flow<CollectionDownloadStatus>,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val flow = remember(collection.uri) { statusOf(collection.uri) }
    val status by flow.collectAsStateWithLifecycle(initialValue = CollectionDownloadStatus.Complete)
    val subtitle = collectionStatusText(collection, status)
    val indicator = status.toIndicatorState()
    if (collection.type == CollectionType.LIKED_SONGS) {
        LibraryRowLayout(
            onClick = onClick,
            leading = { GradientTile(BrowsePalette.likedSongs, Icons.Rounded.Favorite, Modifier.size(56.dp), iconSize = 26.dp) },
            title = stringResource(R.string.browse_liked_songs),
            subtitle = subtitle,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadIndicator(indicator)
                    IconButton(onClick = onMore) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.browse_more_options))
                    }
                }
            },
        )
    } else {
        val ref = remember(collection) { collection.toMediaRef() }
        MediaRow(
            ref = ref,
            onClick = onClick,
            subtitle = subtitle,
            onMoreClick = onMore,
            onLongClick = onMore,
            trailing = { DownloadIndicator(indicator) },
        )
    }
}

@Composable
private fun collectionStatusText(collection: DownloadedCollection, status: CollectionDownloadStatus): String = when (status) {
    is CollectionDownloadStatus.InProgress -> if (status.active) {
        stringResource(R.string.browse_download_progress, status.done, status.total)
    } else {
        stringResource(R.string.browse_download_waiting_progress, status.done, status.total)
    }
    else -> {
        val count = collection.itemUris.size
        typedSubtitle(
            type = if (collection.type == CollectionType.LIKED_SONGS) MediaType.PLAYLIST else collection.mediaType,
            detail = if (collection.type == CollectionType.SHOW) {
                pluralStringResource(R.plurals.browse_episode_count, count, count)
            } else {
                pluralStringResource(R.plurals.browse_song_count, count, count)
            },
        )
    }
}

@Composable
private fun entryStatusText(entry: DownloadEntry): String? = when (entry.state) {
    DownloadState.QUEUED, DownloadState.PREPARING -> stringResource(R.string.browse_download_waiting)
    DownloadState.DOWNLOADING -> entry.progress?.let { stringResource(R.string.browse_download_downloading_percent, (it * 100).toInt()) }
        ?: stringResource(R.string.browse_download_downloading)
    DownloadState.FAILED -> stringResource(R.string.browse_download_item_failed)
    DownloadState.COMPLETED, DownloadState.CANCELLED -> null
}

@Composable
private fun StorageHeader(state: DownloadsUiState, onRetryFailed: () -> Unit) {
    val context = LocalContext.current
    val used = remember(state.usedBytes) { Formatter.formatShortFileSize(context, state.usedBytes) }
    val content = state.content
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.browse_downloads_storage, used),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = pluralStringResource(R.plurals.browse_download_count, content.completedCount, content.completedCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (content.pendingCount > 0) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = pluralStringResource(R.plurals.browse_downloads_pending, content.pendingCount, content.pendingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            content.active?.let { active ->
                val name = active.track?.name ?: active.episode?.name
                if (name != null) {
                    Text(
                        text = stringResource(R.string.browse_downloads_active, name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val progress = active.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        }
        if (content.failedCount > 0) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pluralStringResource(R.plurals.browse_downloads_failed, content.failedCount, content.failedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetryFailed) { Text(stringResource(R.string.browse_retry)) }
            }
        }
    }
}

@Composable
private fun DownloadSheet(title: String, onDismiss: () -> Unit, actions: List<SheetAction>) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            actions.forEach { action ->
                ListItem(
                    headlineContent = { Text(action.label) },
                    leadingContent = { Icon(action.icon, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        onDismiss()
                        action.onClick()
                    },
                )
            }
        }
    }
}
