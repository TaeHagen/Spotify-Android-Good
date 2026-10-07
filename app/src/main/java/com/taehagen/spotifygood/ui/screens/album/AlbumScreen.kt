package com.taehagen.spotifygood.ui.screens.album

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.DetailHeader
import com.taehagen.spotifygood.ui.components.MediaCarousel
import com.taehagen.spotifygood.ui.components.PartialContentNotice
import com.taehagen.spotifygood.ui.components.PlayFab
import com.taehagen.spotifygood.ui.components.TrackRow
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route

@Composable
fun AlbumScreen(uri: String, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = uri) { graph -> AlbumViewModel(graph, uri) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    DetailScaffold(
        title = state.load.dataOrNull()?.album?.name.orEmpty(),
        showTitle = showTitle,
        onBack = navigator::back,
        refreshing = state.load.isRefreshing,
        modifier = modifier,
    ) {
        LoadStateContent(state.load, offline = state.offline, onRetry = viewModel::retry) { content ->
            AlbumList(
                uri = uri,
                content = content,
                state = state,
                listState = listState,
                contentPadding = contentPadding,
                navigator = navigator,
                onPlay = viewModel::playContext,
                onShuffle = viewModel::shuffleContext,
                onPlayTrack = viewModel::playTrack,
                onToggleSaved = { viewModel.toggleSaved(state.saved) },
                onDownload = viewModel::download,
                onRemoveDownload = { viewModel.removeCollectionDownload() },
                onRetry = viewModel::refetch,
            )
        }
    }
}

@Composable
private fun AlbumList(
    uri: String,
    content: AlbumContent,
    state: AlbumUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onToggleSaved: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onRetry: () -> Unit,
) {
    val album = content.album
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "header", contentType = "header") {
            AlbumHeader(
                content = content,
                state = state,
                onArtistClick = { navigator.navigate(Route.Artist(it.uri)) },
                onPlay = onPlay,
                onShuffle = onShuffle,
                onToggleSaved = onToggleSaved,
                onDownload = onDownload,
                onRemoveDownload = onRemoveDownload,
                onMore = { navigator.showActions(MediaActionTarget.AlbumTarget(album.toRef())) },
            )
        }
        if (content.downloadedCopy) {
            item(key = "downloaded", contentType = "notice") {
                DownloadedCopyNotice(
                    stringResource(R.string.detail_showing_downloaded_tracks),
                    onRetry = onRetry.takeUnless { state.offline },
                )
            }
        }
        if (album.partial) {
            // Some tracks are placeholders (their metadata failed right now).
            item(key = "partial", contentType = "notice") { PartialContentNotice(onRetry = onRetry) }
        }
        content.discs.forEach { disc ->
            if (content.multiDisc) {
                item(key = "disc-${disc.disc}", contentType = "disc") {
                    SubsectionTitle(stringResource(R.string.detail_disc, disc.disc))
                }
            }
            items(disc.tracks, key = { "t-${it.index}" }, contentType = { "track" }) { indexed ->
                val track = indexed.track
                val downloadState = state.rowDownloads[track.uri]
                val playable = canStartNow(track.playable, state.online, downloadState, track.explicit, state.filterExplicit)
                val showActions = { navigator.showActions(MediaActionTarget.TrackTarget(track, contextUri = uri)) }
                TrackRow(
                    track = track,
                    onClick = { onPlayTrack(track) },
                    isCurrent = state.playback.isCurrent(track.uri),
                    isPlaying = state.playback.isPlayingItem(track.uri),
                    showArtwork = false,
                    index = track.trackNumber ?: (indexed.index + 1),
                    subtitleOverride = indexed.artistLine,
                    downloadState = downloadState,
                    onMoreClick = showActions,
                    onLongClick = showActions,
                    // Unplayable (or not downloaded while the session isn't online): dimmed, actions still in the overflow.
                    enabled = playable,
                )
            }
        }
        item(key = "footer", contentType = "footer") {
            AlbumFooter(content)
        }
        if (state.moreBy.isNotEmpty()) {
            item(key = "more-by", contentType = "carousel") {
                MoreByCarousel(content, state.moreBy, navigator)
            }
        }
    }
}

@Composable
private fun AlbumHeader(
    content: AlbumContent,
    state: AlbumUiState,
    onArtistClick: (ArtistRef) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSaved: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onMore: () -> Unit,
) {
    val album = content.album
    val type = albumTypeLabel(album.albumType)
    val subtitle = content.year?.let { stringResource(R.string.detail_separator, type, it.toString()) } ?: type
    val isContext = state.playback.isContext(album.uri)
    DetailHeader(
        title = album.name,
        imageUrl = album.images.best(640),
        subtitle = subtitle,
        modifier = Modifier.fillMaxWidth(),
        actions = {
            Column(Modifier.fillMaxWidth()) {
                ArtistLinks(album.artists, onArtistClick = onArtistClick)
                HeaderMetaText(songsAndDuration(album.tracks.size, content.totalDurationMs, withSeconds = true))
                DetailActionRow(
                    leading = {
                        HeartButton(saved = state.saved == true, onClick = onToggleSaved)
                        CollectionDownloadButton(ui = state.download, onDownload = onDownload, onRemove = onRemoveDownload)
                        MoreButton(onClick = onMore, label = album.name)
                    },
                    trailing = {
                        ShuffleButton(active = isContext && state.playback.shuffle, onClick = onShuffle)
                        PlayFab(isPlaying = state.playback.isPlayingContext(album.uri), onClick = onPlay)
                    },
                )
            }
        },
    )
}

@Composable
private fun AlbumFooter(content: AlbumContent) {
    val album = content.album
    val locale = currentLocale()
    val releaseDate = remember(album.releaseDate, album.releaseDatePrecision, locale) {
        formatReleaseDate(album.releaseDate, album.releaseDatePrecision, locale)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        if (releaseDate != null) {
            Text(releaseDate, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            songsAndDuration(album.tracks.size, content.totalDurationMs, withSeconds = true),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        album.label?.takeIf { it.isNotBlank() }?.let { label ->
            Text(
                stringResource(R.string.detail_label, label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        album.copyrights.forEach { copyright ->
            Text(
                copyright,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun MoreByCarousel(content: AlbumContent, moreBy: List<AlbumRef>, navigator: AppNavigator) {
    val artist = content.album.artists.firstOrNull() ?: return
    val refs = rememberReleaseRefs(moreBy, showType = false)
    MediaCarousel(
        title = stringResource(R.string.detail_more_by, artist.name),
        items = refs,
        onItemClick = { navigator.navigate(Route.Album(it.uri)) },
        onItemLongClick = { ref ->
            moreBy.firstOrNull { it.uri == ref.uri }?.let { navigator.showActions(MediaActionTarget.AlbumTarget(it)) }
        },
        onSeeAll = { navigator.navigate(Route.ArtistDiscography(artist.uri, "albums")) },
        modifier = Modifier.padding(bottom = 24.dp),
    )
}
