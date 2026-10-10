package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.download.CollectionDownloadStatus
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.ErrorState
import com.taehagen.spotifygood.ui.components.FastScroller
import com.taehagen.spotifygood.ui.components.PlaceholderTrackRow
import com.taehagen.spotifygood.ui.components.VisibleRowsEffect
import com.taehagen.spotifygood.ui.components.rememberFastScrollDatePattern
import com.taehagen.spotifygood.ui.components.OfflineBanner
import com.taehagen.spotifygood.ui.components.PartialContentNotice
import com.taehagen.spotifygood.ui.components.PlayFab
import com.taehagen.spotifygood.ui.components.TrackRow
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.screens.album.canStartNow

@Composable
internal fun LikedSongsContent(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { graph -> LikedSongsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val resources = LocalResources.current
    val listState = rememberLazyListState()
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val likedName = stringResource(R.string.browse_liked_songs)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { navigator.showMessage(resources.getString(it.messageRes())) }
    }
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    // Lazy items before the songs (header, actions, filter, banners, a state message) and the
    // songs: the loaded ones, then placeholders to the end (loaded by window as they come on screen).
    val stateItem = state.isInitialLoading || (state.tracks.isEmpty() && !(state.filter.isNotEmpty() && state.canLoadMore))
    val headerItems = 3 + (if (state.partial) 1 else 0) + (if (state.offline) 1 else 0) + (if (stateItem) 1 else 0)
    val rowCount = state.tracks.size + state.placeholders
    // Loads the next page near the loaded end, or the windows of rows further down.
    VisibleRowsEffect(listState, contentStart = headerItems, contentCount = rowCount, onVisible = viewModel::onRowsVisible)
    val datePattern = rememberFastScrollDatePattern()
    val label: (Int) -> String? = remember(state.tracks, state.windows, state.sort, rowCount, datePattern) {
        val tracks = state.tracks
        val windows = state.windows
        val sort = state.sort
        val labelFor: (Int) -> String? = { index ->
            val saved = if (index < tracks.size) null else windows[index]
            val track = tracks.getOrNull(index) ?: saved?.track
            likedScrollLabel(track, saved?.addedAt ?: track?.uri?.let(viewModel::likedAt), index, rowCount, sort, datePattern)
        }
        labelFor
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(state = listState, contentPadding = contentPaddingWith(contentPadding), modifier = Modifier.fillMaxSize()) {
                item(key = "header", contentType = "header") {
                    LikedHeader(total = state.total)
                }
                item(key = "actions", contentType = "actions") {
                    LikedActions(
                        state = state,
                        onToggleDownload = {
                            if (state.isDownloaded) confirmRemove = true else viewModel.setDownloaded(true, likedName)
                        },
                        onShuffle = viewModel::shuffle,
                        onPlay = viewModel::playOrToggle,
                    )
                }
                item(key = "filter", contentType = "filter") {
                    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterField(
                                value = viewModel.filterText,
                                onValueChange = viewModel::onFilterChange,
                                modifier = Modifier.weight(1f),
                            )
                            TrackSortButton(sort = state.sort, options = TrackSort.LIKED_SONGS, onSort = viewModel::setSort)
                        }
                        if (state.loadingAll) {
                            LoadingAllProgress(
                                loaded = state.loadedCount,
                                total = state.total,
                                modifier = Modifier.padding(top = 8.dp, end = 12.dp),
                            )
                        }
                    }
                }
                if (state.partial) {
                    item(key = "partial", contentType = "banner") {
                        PartialContentNotice(onRetry = viewModel::refresh)
                    }
                }
                if (state.offline) {
                    item(key = "offline", contentType = "banner") {
                        OfflineBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                val error = state.error
                when {
                    state.isInitialLoading -> item(key = "loading", contentType = "state") {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.tracks.isEmpty() && error != null && state.filter.isEmpty() -> item(key = "error", contentType = "state") {
                        ErrorState(
                            message = stringResource(error.messageRes()),
                            onRetry = viewModel::retry,
                            modifier = Modifier.padding(vertical = 48.dp),
                        )
                    }
                    state.tracks.isEmpty() && state.filter.isNotEmpty() && !state.canLoadMore -> item(key = "noMatch", contentType = "state") {
                        EmptyState(
                            title = stringResource(R.string.browse_library_no_match_title, state.filter),
                            message = stringResource(R.string.browse_library_no_match_message),
                            icon = Icons.Rounded.Search,
                            modifier = Modifier.padding(vertical = 48.dp),
                        )
                    }
                    state.tracks.isEmpty() && state.filter.isEmpty() -> item(key = "empty", contentType = "state") {
                        EmptyState(
                            title = stringResource(
                                if (state.offline || state.fromDownload) R.string.browse_liked_offline_empty_title else R.string.browse_liked_empty_title,
                            ),
                            message = stringResource(
                                if (state.offline || state.fromDownload) R.string.browse_liked_offline_empty_message else R.string.browse_liked_empty_message,
                            ),
                            icon = Icons.Rounded.Favorite,
                            modifier = Modifier.padding(vertical = 48.dp),
                        )
                    }
                }
                items(state.tracks, key = { "track:${it.uri}" }, contentType = { "track" }) { track ->
                    LikedTrackRow(track, state, onPlay = viewModel::playTrack, onActions = navigator::showActions)
                }
                if (state.placeholders > 0) {
                    val loaded = state.tracks.size
                    // Keyed by position: a row keeps its slot when its window (or the next page) arrives.
                    items(count = state.placeholders, key = { "pos:${loaded + it}" }, contentType = { "track" }) { offset ->
                        val track = state.windows[loaded + offset]?.track
                        if (track != null) {
                            LikedTrackRow(track, state, onPlay = viewModel::playTrack, onActions = navigator::showActions)
                        } else {
                            PlaceholderTrackRow()
                        }
                    }
                }
                if (state.isLoadingMore) {
                    item(key = "more", contentType = "footer") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                        }
                    }
                } else if (error != null && state.tracks.isNotEmpty()) {
                    item(key = "moreError", contentType = "footer") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(error.messageRes()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.browse_retry)) }
                        }
                    }
                }
            }
        }
        FastScroller(
            listState = listState,
            contentStart = headerItems,
            contentCount = rowCount,
            // Below the collapsing bar, above the mini player.
            topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
            bottomPadding = contentPadding.calculateBottomPadding(),
            label = label,
        )
        CollapsingBar(
            title = likedName,
            collapsed = collapsed,
            onBack = navigator::back,
        )
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.browse_liked_remove_download_title),
            text = stringResource(R.string.browse_liked_remove_download_message),
            confirmLabel = stringResource(R.string.browse_remove),
            onConfirm = { viewModel.setDownloaded(false, likedName) },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun LikedTrackRow(
    track: Track,
    state: LikedSongsUiState,
    onPlay: (Track) -> Unit,
    onActions: (MediaActionTarget) -> Unit,
) {
    val target = remember(track, state.contextUri) { MediaActionTarget.TrackTarget(track, contextUri = state.contextUri) }
    TrackRow(
        track = track,
        onClick = { onPlay(track) },
        isCurrent = state.nowPlaying.isCurrent(track.uri),
        isPlaying = state.nowPlaying.isPlaying,
        downloadState = state.downloadStates[track.uri],
        onMoreClick = { onActions(target) },
        onLongClick = { onActions(target) },
        // Not downloaded while the session isn't online: it can't start (as on playlists).
        enabled = canStartNow(track.playable, state.online, state.downloadStates[track.uri]),
    )
}

@Composable
private fun LikedHeader(total: Int?) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(BrowsePalette.likedSongs[0], BrowsePalette.likedSongs[1].copy(alpha = 0.55f), MaterialTheme.colorScheme.background),
                ),
            ),
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 16.dp),
        ) {
            GradientTile(
                colors = BrowsePalette.likedSongs,
                icon = Icons.Rounded.Favorite,
                shape = RoundedCornerShape(8.dp),
                iconSize = 56.dp,
                modifier = Modifier.size(148.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.browse_liked_songs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            if (total != null) {
                Text(
                    text = pluralStringResource(R.plurals.browse_song_count, total, total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun LikedActions(
    state: LikedSongsUiState,
    onToggleDownload: () -> Unit,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleDownload, enabled = state.contextUri != null) {
            Icon(
                imageVector = if (state.isDownloaded) Icons.Rounded.DownloadForOffline else Icons.Rounded.Download,
                contentDescription = stringResource(
                    if (state.isDownloaded) R.string.browse_remove_download else R.string.browse_download,
                ),
                tint = if (state.isDownloaded) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        val progress = state.download
        Text(
            text = when (progress) {
                is CollectionDownloadStatus.InProgress -> stringResource(R.string.browse_download_progress, progress.done, progress.total)
                CollectionDownloadStatus.Complete -> stringResource(R.string.browse_downloaded)
                CollectionDownloadStatus.None -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onShuffle) {
            Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.browse_shuffle_play))
        }
        Spacer(Modifier.size(8.dp))
        PlayFab(
            isPlaying = state.listIsCurrent && state.nowPlaying.isPlaying,
            onClick = onPlay,
            busy = state.playPending,
        )
    }
}

@Composable
private fun FilterField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.browse_liked_filter), maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.browse_clear_filter))
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(8.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

/** Back button over the gradient that turns into a solid title bar once the header scrolls away. */
@Composable
internal fun CollapsingBar(title: String, collapsed: Boolean, onBack: () -> Unit) {
    val container by animateColorAsState(
        if (collapsed) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        label = "barColor",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (collapsed) MaterialTheme.colorScheme.onSurface else Color.White) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_back))
            }
            if (collapsed) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
