package com.taehagen.spotifygood.ui.screens.show

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.DetailHeader
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.EpisodeRow
import com.taehagen.spotifygood.ui.components.PartialContentNotice
import com.taehagen.spotifygood.ui.components.PlayFab
import com.taehagen.spotifygood.ui.components.SectionHeader
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.album.AddedButton
import com.taehagen.spotifygood.ui.screens.album.CollectionDownloadButton
import com.taehagen.spotifygood.ui.screens.album.DownloadedCopyNotice
import com.taehagen.spotifygood.ui.screens.album.DetailActionRow
import com.taehagen.spotifygood.ui.screens.album.DetailScaffold
import com.taehagen.spotifygood.ui.screens.album.ExpandableText
import com.taehagen.spotifygood.ui.screens.album.FollowButton
import com.taehagen.spotifygood.ui.screens.album.HeaderMetaText
import com.taehagen.spotifygood.ui.screens.album.LoadMoreEffect
import com.taehagen.spotifygood.ui.screens.album.LoadState
import com.taehagen.spotifygood.ui.screens.album.LoadStateContent
import com.taehagen.spotifygood.ui.screens.album.MoreButton
import com.taehagen.spotifygood.ui.screens.album.PagingFooter
import com.taehagen.spotifygood.ui.screens.album.RemoveDownloadDialog
import com.taehagen.spotifygood.ui.screens.album.currentLocale
import com.taehagen.spotifygood.ui.screens.album.dataOrNull
import com.taehagen.spotifygood.ui.screens.album.durationText
import com.taehagen.spotifygood.ui.screens.album.formatShortDate
import com.taehagen.spotifygood.ui.screens.album.isRefreshing
import com.taehagen.spotifygood.ui.screens.album.rememberRichText
import com.taehagen.spotifygood.ui.screens.album.shareUrl
import java.time.LocalDate

@Composable
fun ShowScreen(uri: String, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = uri) { graph -> ShowViewModel(graph, uri) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    LoadMoreEffect(
        listState = listState,
        enabled = state.load is LoadState.Ready && !state.list.endReached && !state.list.failed,
        onLoadMore = viewModel::loadMore,
    )

    DetailScaffold(
        title = state.load.dataOrNull()?.show?.name.orEmpty(),
        showTitle = showTitle,
        onBack = navigator::back,
        refreshing = state.load.isRefreshing,
        modifier = modifier,
    ) {
        LoadStateContent(state.load, offline = state.offline, onRetry = viewModel::retry) { header ->
            ShowList(
                header = header,
                state = state,
                listState = listState,
                contentPadding = contentPadding,
                navigator = navigator,
                onPlay = viewModel::playContext,
                onToggleFollow = viewModel::toggleFollow,
                onDownload = viewModel::download,
                onRemoveDownload = { viewModel.removeCollectionDownload() },
                onPlayEpisode = viewModel::playEpisode,
                onSort = viewModel::setSort,
                onRetryPage = viewModel::loadMore,
                onRetryPartial = viewModel::retryPartial,
                onRetry = viewModel::retry,
            )
        }
    }
}

@Composable
private fun ShowList(
    header: ShowHeader,
    state: ShowUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    onPlay: () -> Unit,
    onToggleFollow: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onSort: (EpisodeSort) -> Unit,
    onRetryPage: () -> Unit,
    onRetryPartial: () -> Unit,
    onRetry: () -> Unit,
) {
    val show = header.show
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "header", contentType = "header") {
            DetailHeader(
                title = show.name,
                imageUrl = show.images.best(640),
                subtitle = show.publisher,
                modifier = Modifier.fillMaxWidth(),
                actions = {
                    DetailActionRow(
                        leading = {
                            FollowButton(following = state.following == true, onClick = onToggleFollow, modifier = Modifier.padding(end = 4.dp))
                            CollectionDownloadButton(ui = state.download, onDownload = onDownload, onRemove = onRemoveDownload)
                            MoreButton(
                                onClick = { navigator.showActions(MediaActionTarget.ShowTarget(show.toRef())) },
                                label = show.name,
                            )
                        },
                        trailing = {
                            PlayFab(isPlaying = state.playback.isPlayingContext(show.uri), onClick = onPlay)
                        },
                    )
                },
            )
        }
        if (!header.description.isEmpty) {
            item(key = "about", contentType = "about") {
                val description = rememberRichText(header.description)
                Column(Modifier.padding(top = 8.dp)) {
                    SectionHeader(stringResource(R.string.detail_about))
                    ExpandableText(
                        text = description,
                        collapsedLines = 3,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        item(key = "episodes-title", contentType = "section") {
            val sort = state.list.sort
            SectionHeader(
                title = stringResource(R.string.detail_all_episodes),
                modifier = Modifier.padding(top = 16.dp),
                action = {
                    TextButton(
                        onClick = { onSort(if (sort == EpisodeSort.NEWEST) EpisodeSort.OLDEST else EpisodeSort.NEWEST) },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = stringResource(R.string.detail_sort_episodes),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (sort == EpisodeSort.NEWEST) R.string.detail_sort_newest else R.string.detail_sort_oldest))
                    }
                },
            )
        }
        if (state.downloadedCopy) {
            item(key = "downloaded", contentType = "notice") {
                DownloadedCopyNotice(
                    stringResource(R.string.detail_showing_downloaded_episodes),
                    onRetry = onRetry.takeUnless { state.offline },
                )
            }
        }
        if (state.partial) {
            item(key = "partial", contentType = "notice") { PartialContentNotice(onRetry = onRetryPartial) }
        }
        items(state.list.episodes, key = { it.uri }, contentType = { "episode" }) { episode ->
            val downloadState = state.rowDownloads[episode.uri]
            val playable = episode.playable && (!state.offline || downloadState == DownloadState.COMPLETED)
            val showActions = { navigator.showActions(MediaActionTarget.EpisodeTarget(episode)) }
            EpisodeRow(
                episode = episode,
                onClick = { onPlayEpisode(episode) },
                isCurrent = state.playback.isCurrent(episode.uri),
                isPlaying = state.playback.isPlayingItem(episode.uri),
                downloadState = downloadState,
                onLongClick = showActions,
                // Unplayable (or not downloaded while offline): dimmed, actions still in the overflow.
                enabled = playable,
                onMoreClick = showActions,
            )
        }
        item(key = "footer", contentType = "footer") {
            when {
                state.list.episodes.isEmpty() && state.list.endReached && !state.list.loading -> EmptyState(
                    title = stringResource(R.string.detail_no_episodes),
                    modifier = Modifier.padding(vertical = 32.dp),
                )
                else -> PagingFooter(loading = state.list.loading, failed = state.list.failed, onRetry = onRetryPage)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Episode
// ---------------------------------------------------------------------------------------------

@Composable
fun EpisodeScreen(uri: String, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = uri) { graph -> EpisodeViewModel(graph, uri) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    DetailScaffold(
        title = state.load.dataOrNull()?.episode?.name.orEmpty(),
        showTitle = showTitle,
        onBack = navigator::back,
        modifier = modifier,
    ) {
        LoadStateContent(state.load, offline = state.offline, onRetry = viewModel::retry) { content ->
            EpisodeDetails(
                uri = uri,
                content = content,
                state = state,
                listState = listState,
                contentPadding = contentPadding,
                navigator = navigator,
                onPlayPause = viewModel::playPause,
                onToggleSaved = viewModel::toggleSaved,
                onDownload = viewModel::download,
                onRemoveDownload = viewModel::removeDownload,
            )
        }
    }
}

@Composable
private fun EpisodeDetails(
    uri: String,
    content: EpisodeContent,
    state: EpisodeUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    onPlayPause: () -> Unit,
    onToggleSaved: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    val episode = content.episode
    val context = LocalContext.current
    val shareFailed = stringResource(R.string.detail_share_failed)
    val shareTitle = stringResource(R.string.detail_share)
    val description = rememberRichText(content.description)
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "header", contentType = "header") {
            DetailHeader(
                title = episode.name,
                imageUrl = episode.images.best(640) ?: episode.show?.images?.best(640),
                modifier = Modifier.fillMaxWidth(),
                actions = {
                    Column(Modifier.fillMaxWidth()) {
                        episode.show?.let { show ->
                            Box(
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.detail_open_show)) {
                                        navigator.navigate(Route.Show(show.uri))
                                    },
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    show.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        EpisodeMeta(episode)
                        DetailActionRow(
                            leading = {
                                AddedButton(
                                    added = state.saved == true,
                                    onClick = onToggleSaved,
                                    addDescription = R.string.detail_save_episode,
                                    removeDescription = R.string.detail_remove_episode,
                                )
                                EpisodeDownloadButton(state.download, onDownload = onDownload, onRemove = onRemoveDownload)
                                IconButton(onClick = { shareLink(context, uri, shareTitle, onFailure = { navigator.showMessage(shareFailed) }) }) {
                                    Icon(
                                        Icons.Rounded.Share,
                                        contentDescription = stringResource(R.string.detail_share),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                MoreButton(
                                    onClick = { navigator.showActions(MediaActionTarget.EpisodeTarget(episode)) },
                                    label = episode.name,
                                )
                            },
                            trailing = {
                                PlayFab(isPlaying = state.playback.isPlayingItem(uri), onClick = onPlayPause)
                            },
                        )
                    }
                },
            )
        }
        if (description.isNotEmpty()) {
            item(key = "description", contentType = "description") {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** "Mar 3 • 45 min" / "Mar 3 • 12 min left" / "Mar 3 • Played", plus a progress line when started. */
@Composable
private fun EpisodeMeta(episode: Episode) {
    val locale = currentLocale()
    val date = remember(episode.releaseDate, locale) { formatShortDate(episode.releaseDate, LocalDate.now(), locale) }
    val resume = episode.resumePosition()
    val time = when {
        episode.fullyPlayed == true -> stringResource(R.string.detail_played)
        resume > 0 && episode.durationMs > resume ->
            stringResource(R.string.detail_time_left, durationText(episode.durationMs - resume))
        else -> durationText(episode.durationMs)
    }
    HeaderMetaText(if (date != null) stringResource(R.string.detail_separator, date, time) else time)
    if (episode.fullyPlayed != true && resume > 0 && episode.durationMs > 0) {
        LinearProgressIndicator(
            progress = { (resume.toFloat() / episode.durationMs).coerceIn(0f, 1f) },
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.5f),
        )
    }
}

@Composable
private fun EpisodeDownloadButton(state: DownloadState?, onDownload: () -> Unit, onRemove: () -> Unit) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(
        when (state) {
            null, DownloadState.CANCELLED -> R.string.detail_download_episode
            DownloadState.COMPLETED -> R.string.detail_remove_episode_download
            DownloadState.FAILED -> R.string.detail_episode_download_failed
            DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING -> R.string.detail_downloading_episode
        },
    )
    IconButton(
        onClick = {
            when (state) {
                null, DownloadState.CANCELLED, DownloadState.FAILED -> onDownload()
                DownloadState.COMPLETED -> confirmRemove = true
                DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING -> onRemove()
            }
        },
    ) {
        when (state) {
            DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Icon(
                    Icons.Rounded.ArrowCircleDown,
                    contentDescription = description,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            DownloadState.COMPLETED -> Icon(
                Icons.Rounded.DownloadForOffline,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            DownloadState.FAILED -> Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            null, DownloadState.CANCELLED -> Icon(
                Icons.Rounded.ArrowCircleDown,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
    if (confirmRemove) {
        RemoveDownloadDialog(
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

private fun shareLink(context: Context, uri: String, title: String, onFailure: () -> Unit) {
    val url = shareUrl(uri) ?: return onFailure()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    try {
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        onFailure()
    }
}
