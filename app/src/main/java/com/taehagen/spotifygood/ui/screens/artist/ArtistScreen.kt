package com.taehagen.spotifygood.ui.screens.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.MediaCard
import com.taehagen.spotifygood.ui.components.MediaCarousel
import com.taehagen.spotifygood.ui.components.MediaRow
import com.taehagen.spotifygood.ui.components.PartialContentNotice
import com.taehagen.spotifygood.ui.components.PlayFab
import com.taehagen.spotifygood.ui.components.SectionHeader
import com.taehagen.spotifygood.ui.components.TrackRow
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.album.canStartNow
import com.taehagen.spotifygood.ui.screens.album.DetailActionRow
import com.taehagen.spotifygood.ui.screens.album.DetailScaffold
import com.taehagen.spotifygood.ui.screens.album.ExpandableText
import com.taehagen.spotifygood.ui.screens.album.FollowButton
import com.taehagen.spotifygood.ui.screens.album.LoadStateContent
import com.taehagen.spotifygood.ui.screens.album.MoreButton
import com.taehagen.spotifygood.ui.screens.album.ShuffleButton
import com.taehagen.spotifygood.ui.screens.album.dataOrNull
import com.taehagen.spotifygood.ui.screens.album.detailTopInset
import com.taehagen.spotifygood.ui.screens.album.isRefreshing
import com.taehagen.spotifygood.ui.screens.album.rememberReleaseRefs
import com.taehagen.spotifygood.ui.screens.album.rememberRichText
import com.taehagen.spotifygood.ui.screens.album.withTop

private val HeroHeight = 300.dp

@Composable
fun ArtistScreen(uri: String, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = uri) { graph -> ArtistViewModel(graph, uri) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    DetailScaffold(
        title = state.load.dataOrNull()?.artist?.name.orEmpty(),
        showTitle = showTitle,
        onBack = navigator::back,
        refreshing = state.load.isRefreshing,
        modifier = modifier,
    ) {
        LoadStateContent(state.load, offline = state.offline, onRetry = viewModel::retry) { content ->
            ArtistList(
                uri = uri,
                content = content,
                state = state,
                listState = listState,
                contentPadding = contentPadding,
                navigator = navigator,
                onPlay = viewModel::playContext,
                onShuffle = viewModel::shuffleContext,
                onRadio = viewModel::startRadio,
                onToggleFollow = viewModel::toggleFollow,
                onPlayTrack = viewModel::playTrack,
                onRetry = viewModel::retry,
            )
        }
    }
}

@Composable
private fun ArtistList(
    uri: String,
    content: ArtistContent,
    state: ArtistUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRadio: () -> Unit,
    onToggleFollow: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onRetry: () -> Unit,
) {
    val artist = content.artist
    var popularExpanded by rememberSaveable { mutableStateOf(false) }
    val popular = if (popularExpanded) content.topTracks else content.topTracks.take(ArtistViewModel.COLLAPSED_TOP_TRACKS)

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "hero", contentType = "hero") {
            ArtistHero(name = artist.name, imageUrl = content.heroImageUrl)
        }
        item(key = "actions", contentType = "actions") {
            DetailActionRow(
                modifier = Modifier.padding(horizontal = 12.dp),
                leading = {
                    FollowButton(following = state.following == true, onClick = onToggleFollow, modifier = Modifier.padding(start = 4.dp, end = 4.dp))
                    MoreButton(
                        onClick = { navigator.showActions(MediaActionTarget.ArtistTarget(ArtistRef(artist.uri, artist.name, artist.images))) },
                        label = artist.name,
                    )
                    IconButton(onClick = onRadio) {
                        Icon(
                            Icons.Rounded.Radio,
                            contentDescription = stringResource(R.string.detail_start_radio),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailing = {
                    ShuffleButton(active = state.playback.isContext(uri) && state.playback.shuffle, onClick = onShuffle)
                    PlayFab(isPlaying = state.playback.isPlayingContext(uri), onClick = onPlay)
                },
            )
        }
        if (artist.partial) {
            // Some top tracks are placeholders, or releases / related artists are missing right now.
            item(key = "partial", contentType = "notice") { PartialContentNotice(onRetry = onRetry) }
        }
        if (popular.isNotEmpty()) {
            item(key = "popular-title", contentType = "section") {
                SectionHeader(stringResource(R.string.detail_popular), Modifier.padding(top = 16.dp))
            }
            itemsIndexed(popular, key = { index, track -> "pop-$index-${track.uri}" }, contentType = { _, _ -> "track" }) { index, track ->
                val downloadState = state.rowDownloads[track.uri]
                val playable = canStartNow(track.playable, state.online, downloadState)
                val showActions = { navigator.showActions(MediaActionTarget.TrackTarget(track, contextUri = uri)) }
                TrackRow(
                    track = track,
                    onClick = { onPlayTrack(track) },
                    isCurrent = state.playback.isCurrent(track.uri),
                    isPlaying = state.playback.isPlayingItem(track.uri),
                    showArtwork = true,
                    index = index + 1,
                    subtitleOverride = track.album?.name,
                    downloadState = downloadState,
                    onMoreClick = showActions,
                    onLongClick = showActions,
                    // Unplayable (or not downloaded while the session isn't online): dimmed, actions still in the overflow.
                    enabled = playable,
                )
            }
            if (content.topTracks.size > ArtistViewModel.COLLAPSED_TOP_TRACKS) {
                item(key = "popular-toggle", contentType = "toggle") {
                    TextButton(
                        onClick = { popularExpanded = !popularExpanded },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            stringResource(if (popularExpanded) R.string.detail_show_less else R.string.detail_see_more),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        content.groups.forEach { releaseGroup ->
            item(key = "group-${releaseGroup.group.key}", contentType = "carousel") {
                ReleaseCarousel(uri, releaseGroup, navigator)
            }
        }
        if (content.related.isNotEmpty()) {
            item(key = "related", contentType = "carousel") {
                MediaCarousel(
                    title = stringResource(R.string.detail_fans_also_like),
                    items = content.related,
                    onItemClick = { navigator.navigate(Route.Artist(it.uri)) },
                    onItemLongClick = { ref ->
                        navigator.showActions(MediaActionTarget.ArtistTarget(ArtistRef(ref.uri, ref.name, ref.images)))
                    },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        if (!content.biography.isEmpty) {
            item(key = "about", contentType = "about") {
                AboutSection(content)
            }
        }
    }
}

@Composable
private fun ArtistHero(name: String, imageUrl: String?) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val background = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .height(HeroHeight + top),
    ) {
        Artwork(
            url = imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            placeholderIcon = Icons.Rounded.Person,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.3f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to background,
                    ),
                ),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun ReleaseCarousel(uri: String, releaseGroup: ReleaseGroup, navigator: AppNavigator) {
    val refs = rememberReleaseRefs(releaseGroup.releases)
    MediaCarousel(
        title = stringResource(releaseGroup.group.title),
        items = refs,
        onItemClick = { navigator.navigate(Route.Album(it.uri)) },
        onItemLongClick = { ref -> releaseActions(ref, releaseGroup.releases, navigator) },
        onSeeAll = { navigator.navigate(Route.ArtistDiscography(uri, releaseGroup.group.key)) },
        modifier = Modifier.padding(top = 16.dp),
    )
}

private fun releaseActions(ref: MediaRef, releases: List<AlbumRef>, navigator: AppNavigator) {
    val album = releases.firstOrNull { it.uri == ref.uri } ?: AlbumRef(ref.uri, ref.name, ref.images)
    navigator.showActions(MediaActionTarget.AlbumTarget(album))
}

@Composable
private fun AboutSection(content: ArtistContent) {
    val biography = rememberRichText(content.biography)
    Column(Modifier.padding(top = 24.dp, bottom = 24.dp)) {
        SectionHeader(stringResource(R.string.detail_about))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
        ) {
            Column {
                if (content.aboutImageUrl != null) {
                    Artwork(
                        url = content.aboutImageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10f),
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        placeholderIcon = Icons.Rounded.Person,
                    )
                }
                ExpandableText(
                    text = biography,
                    collapsedLines = 4,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Discography
// ---------------------------------------------------------------------------------------------

/** [group] = "albums" | "singles" | "compilations" | "appears_on". */
@Composable
fun ArtistDiscographyScreen(uri: String, group: String, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = uri) { graph ->
        ArtistDiscographyViewModel(graph, uri, DiscographyGroup.fromKey(group))
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var grid by rememberSaveable { mutableStateOf(false) }

    DetailScaffold(
        title = state.load.dataOrNull()?.artistName.orEmpty(),
        showTitle = true,
        onBack = navigator::back,
        refreshing = state.load.isRefreshing,
        modifier = modifier,
        actions = {
            IconButton(onClick = { grid = !grid }) {
                Icon(
                    if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                    contentDescription = stringResource(if (grid) R.string.detail_list_view else R.string.detail_grid_view),
                )
            }
        },
    ) {
        LoadStateContent(state.load, offline = state.offline, onRetry = viewModel::retry) { content ->
            val padding = contentPadding.withTop(detailTopInset())
            if (grid) {
                DiscographyGrid(content, padding, navigator, onSelect = viewModel::select)
            } else {
                DiscographyList(content, padding, navigator, onSelect = viewModel::select)
            }
        }
    }
}

@Composable
private fun GroupChips(content: DiscographyContent, onSelect: (DiscographyGroup) -> Unit) {
    if (content.available.size <= 1) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        items(content.available, key = { it.key }) { group ->
            FilterChip(
                selected = group == content.selected,
                onClick = { onSelect(group) },
                label = { Text(stringResource(group.title)) },
            )
        }
    }
}

@Composable
private fun DiscographyList(
    content: DiscographyContent,
    padding: PaddingValues,
    navigator: AppNavigator,
    onSelect: (DiscographyGroup) -> Unit,
) {
    val refs = rememberReleaseRefs(content.releases)
    LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
        item(key = "chips", contentType = "chips") { GroupChips(content, onSelect) }
        if (refs.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(title = stringResource(R.string.detail_discography_empty), modifier = Modifier.padding(top = 48.dp))
            }
        }
        items(refs, key = { it.uri }, contentType = { "release" }) { ref ->
            MediaRow(
                ref = ref,
                onClick = { navigator.navigate(Route.Album(ref.uri)) },
                onMoreClick = { releaseActions(ref, content.releases, navigator) },
                onLongClick = { releaseActions(ref, content.releases, navigator) },
            )
        }
    }
}

@Composable
private fun DiscographyGrid(
    content: DiscographyContent,
    padding: PaddingValues,
    navigator: AppNavigator,
    onSelect: (DiscographyGroup) -> Unit,
) {
    val refs = rememberReleaseRefs(content.releases)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val spacing = 12.dp
        val columns = ((maxWidth - spacing) / (MinGridCell + spacing)).toInt().coerceAtLeast(2)
        val cell = (maxWidth - spacing * (columns + 1)) / columns
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing),
        ) {
            item(key = "chips", span = { GridItemSpan(maxLineSpan) }, contentType = "chips") {
                GroupChips(content, onSelect)
            }
            if (refs.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    EmptyState(title = stringResource(R.string.detail_discography_empty), modifier = Modifier.padding(top = 48.dp))
                }
            }
            items(refs, key = { it.uri }, contentType = { "release" }) { ref ->
                MediaCard(
                    ref = ref,
                    onClick = { navigator.navigate(Route.Album(ref.uri)) },
                    size = cell,
                    onLongClick = { releaseActions(ref, content.releases, navigator) },
                )
            }
        }
    }
}

private val MinGridCell = 150.dp
