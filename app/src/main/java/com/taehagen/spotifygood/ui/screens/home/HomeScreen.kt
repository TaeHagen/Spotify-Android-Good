package com.taehagen.spotifygood.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.HomeSection
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.ErrorState
import com.taehagen.spotifygood.ui.components.MediaCarousel
import com.taehagen.spotifygood.ui.components.PlaylistArtwork
import com.taehagen.spotifygood.ui.components.OfflineBanner
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.library.AvatarButton
import com.taehagen.spotifygood.ui.screens.library.BrowsePalette
import com.taehagen.spotifygood.ui.screens.library.ChipRow
import com.taehagen.spotifygood.ui.screens.library.GradientTile
import com.taehagen.spotifygood.ui.screens.library.SkeletonBlock
import com.taehagen.spotifygood.ui.screens.library.StateBox
import com.taehagen.spotifygood.ui.screens.library.contentPaddingWith
import com.taehagen.spotifygood.ui.screens.library.isLikedSongsUri
import com.taehagen.spotifygood.ui.screens.library.messageRes
import com.taehagen.spotifygood.ui.screens.library.toActionTarget

@Composable
fun HomeScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { graph -> HomeViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HomeTopBar(
            greeting = state.greeting,
            user = state.user,
            onProfile = { navigator.navigate(Route.Profile()) },
            onSettings = { navigator.navigate(Route.Settings) },
        )
        ChipRow(
            options = HomeFilter.entries,
            selected = state.filter,
            onSelect = viewModel::selectFilter,
            label = { filterLabel(it) },
            modifier = Modifier.padding(bottom = 8.dp),
        )
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            val error = state.error
            when {
                state.isInitialLoading -> HomeSkeleton(contentPadding)
                error != null -> StateBox {
                    ErrorState(
                        message = stringResource(error.messageRes()),
                        onRetry = viewModel::retry,
                    )
                }
                else -> HomeFeedList(
                    state = state,
                    contentPadding = contentPadding,
                    navigator = navigator,
                    onRetry = viewModel::retry,
                )
            }
        }
    }
}

@Composable
private fun filterLabel(filter: HomeFilter): String = stringResource(
    when (filter) {
        HomeFilter.ALL -> R.string.browse_filter_all
        HomeFilter.MUSIC -> R.string.browse_filter_music
        HomeFilter.PODCASTS -> R.string.browse_filter_podcasts
    },
)

@Composable
private fun HomeTopBar(greeting: Greeting, user: User?, onProfile: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarButton(user = user, onClick = onProfile)
        Text(
            text = stringResource(
                when (greeting) {
                    Greeting.MORNING -> R.string.browse_greeting_morning
                    Greeting.AFTERNOON -> R.string.browse_greeting_afternoon
                    Greeting.EVENING -> R.string.browse_greeting_evening
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.browse_settings))
        }
    }
}

@Composable
private fun HomeFeedList(
    state: HomeUiState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    val open: (MediaRef) -> Unit = { ref ->
        if (ref.type == MediaType.COLLECTION && isLikedSongsUri(ref.uri)) navigator.navigate(Route.LikedSongs) else navigator.open(ref)
    }
    val showActions: (MediaRef) -> Unit = { ref -> ref.toActionTarget(state.user?.username)?.let(navigator::showActions) }

    LazyColumn(
        state = listState,
        contentPadding = contentPaddingWith(contentPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.offline) {
            item(key = "offline", contentType = "banner") {
                OfflineBanner(Modifier.padding(horizontal = 16.dp))
            }
        }
        if (state.refreshFailed) {
            item(key = "refreshFailed", contentType = "banner") {
                RefreshFailedRow(onRetry)
            }
        }
        if (state.quickAccess.isNotEmpty()) {
            item(key = "quickAccess", contentType = "quickAccess") {
                QuickAccessGrid(
                    items = state.quickAccess,
                    onOpen = { item ->
                        when (item) {
                            QuickAccessItem.LikedSongs -> navigator.navigate(Route.LikedSongs)
                            is QuickAccessItem.Media -> open(item.ref)
                        }
                    },
                    onLongClick = { item -> (item as? QuickAccessItem.Media)?.let { showActions(it.ref) } },
                )
            }
        }
        if (state.offline && state.nothingDownloaded) {
            item(key = "offlineEmpty", contentType = "state") {
                EmptyState(
                    title = stringResource(R.string.browse_home_offline_empty_title),
                    message = stringResource(R.string.browse_home_offline_empty_message),
                    icon = Icons.Rounded.CloudOff,
                    action = {
                        Button(onClick = { navigator.navigate(Route.Downloads) }) {
                            Text(stringResource(R.string.browse_go_to_downloads))
                        }
                    },
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
        } else if (!state.offline && state.sections.isEmpty() && state.quickAccess.isEmpty()) {
            item(key = "filterEmpty", contentType = "state") {
                EmptyState(
                    title = stringResource(R.string.browse_home_filter_empty),
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
        }
        items(state.sections, key = { "section:${it.id}" }, contentType = { "carousel" }) { section ->
            MediaCarousel(
                title = sectionTitle(section),
                items = section.items,
                onItemClick = open,
                onItemLongClick = showActions,
            )
        }
    }
}

@Composable
private fun sectionTitle(section: HomeSection): String = when (section.id) {
    RECENTLY_PLAYED_SECTION_ID -> stringResource(R.string.browse_home_recently_played)
    DOWNLOADED_SECTION_ID -> stringResource(R.string.browse_home_downloaded)
    else -> section.title
}

@Composable
private fun RefreshFailedRow(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.browse_home_refresh_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.browse_retry)) }
    }
}

@Composable
private fun QuickAccessGrid(
    items: List<QuickAccessItem>,
    onOpen: (QuickAccessItem) -> Unit,
    onLongClick: (QuickAccessItem) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    QuickAccessTile(
                        item = item,
                        onClick = { onOpen(item) },
                        onLongClick = { onLongClick(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickAccessTile(
    item: QuickAccessItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (item) {
        QuickAccessItem.LikedSongs -> stringResource(R.string.browse_liked_songs)
        is QuickAccessItem.Media -> item.ref.name
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.browse_more_options))
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (item) {
                QuickAccessItem.LikedSongs -> GradientTile(
                    colors = BrowsePalette.likedSongs,
                    icon = Icons.Rounded.Favorite,
                    shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
                    modifier = Modifier.size(56.dp),
                )
                is QuickAccessItem.Media -> if (item.ref.type == MediaType.PLAYLIST) {
                    PlaylistArtwork(
                        uri = item.ref.uri,
                        imageUrl = item.ref.images.best(160),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
                        tilePx = 64,
                    )
                } else {
                    Artwork(
                        url = item.ref.images.best(160),
                        contentDescription = null,
                        shape = if (item.ref.type == MediaType.ARTIST) {
                            RoundedCornerShape(28.dp)
                        } else {
                            RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                        },
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun HomeSkeleton(contentPadding: PaddingValues) {
    val loading = stringResource(R.string.browse_loading)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPaddingWith(contentPadding, horizontal = 16.dp))
            .semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(4) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(Modifier.weight(1f).height(56.dp))
                SkeletonBlock(Modifier.weight(1f).height(56.dp))
            }
        }
        repeat(2) {
            Spacer(Modifier.height(16.dp))
            SkeletonBlock(Modifier.width(180.dp).height(22.dp))
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { SkeletonBlock(Modifier.size(140.dp)) }
            }
        }
    }
}
