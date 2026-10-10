package com.taehagen.spotifygood.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.settings.LibrarySort
import com.taehagen.spotifygood.data.settings.LibraryView
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.CreatePlaylistDialog
import com.taehagen.spotifygood.ui.components.DownloadIndicator
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.EpisodeRow
import com.taehagen.spotifygood.ui.components.ErrorState
import com.taehagen.spotifygood.ui.components.LoadingState
import com.taehagen.spotifygood.ui.components.MediaCard
import com.taehagen.spotifygood.ui.components.MediaRow
import com.taehagen.spotifygood.ui.components.OfflineBanner
import com.taehagen.spotifygood.ui.components.PartialContentNotice
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import kotlinx.coroutines.flow.Flow
import com.taehagen.spotifygood.ui.components.FastScroller
import com.taehagen.spotifygood.ui.components.fastScrollDate
import com.taehagen.spotifygood.ui.components.fastScrollLetter
import com.taehagen.spotifygood.ui.components.fastScrollPosition
import com.taehagen.spotifygood.ui.components.rememberFastScrollDatePattern

@Composable
fun LibraryScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { graph -> LibraryViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var showCreate by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = state.showEpisodes || state.folders.isNotEmpty() || state.searchActive) {
        viewModel.navigateUp()
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LibraryHeader(
            user = state.user,
            searchActive = state.searchActive,
            onProfile = { navigator.navigate(Route.Profile()) },
            onToggleSearch = { viewModel.setSearchActive(!state.searchActive) },
            onCreate = { showCreate = true },
        )
        if (state.searchActive) {
            LibrarySearchField(
                value = viewModel.searchText,
                onValueChange = viewModel::onSearchTextChange,
                onClose = { viewModel.setSearchActive(false) },
            )
        }
        ChipRow(
            options = LibraryFilter.entries,
            selected = state.filter,
            onSelect = viewModel::selectFilter,
            label = { libraryFilterLabel(it) },
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (state.offline) OfflineBanner(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (!state.showEpisodes) {
            SortViewRow(
                sort = state.sort,
                view = state.view,
                onSort = viewModel::setSort,
                onToggleView = viewModel::toggleView,
            )
        }
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            val error = state.error
            when {
                state.showEpisodes -> YourEpisodesList(
                    state = state,
                    contentPadding = contentPadding,
                    onBack = { viewModel.navigateUp() },
                    onPlay = viewModel::playEpisode,
                    onActions = { navigator.showActions(MediaActionTarget.EpisodeTarget(it)) },
                    onLoadMore = viewModel::loadMoreEpisodes,
                    onRetry = viewModel::openEpisodes,
                    onRetryPartial = viewModel::retryEpisodes,
                )
                state.isInitialLoading -> LoadingState()
                error != null && state.items.isEmpty() -> StateBox {
                    ErrorState(message = stringResource(error.messageRes()), onRetry = viewModel::retry)
                }
                state.view == LibraryView.GRID -> LibraryGrid(
                    state = state,
                    contentPadding = contentPadding,
                    navigator = navigator,
                    onOpenFolder = viewModel::openFolder,
                    onBack = { viewModel.navigateUp() },
                    onOpenEpisodes = viewModel::openEpisodes,
                    downloadState = viewModel::downloadState,
                )
                else -> LibraryList(
                    state = state,
                    contentPadding = contentPadding,
                    navigator = navigator,
                    onOpenFolder = viewModel::openFolder,
                    onBack = { viewModel.navigateUp() },
                    onOpenEpisodes = viewModel::openEpisodes,
                    downloadState = viewModel::downloadState,
                )
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onDismiss = { showCreate = false },
            onCreated = { uri ->
                showCreate = false
                navigator.navigate(Route.Playlist(uri))
            },
        )
    }
}

@Composable
fun LikedSongsScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    LikedSongsContent(contentPadding = contentPadding, modifier = modifier)
}

@Composable
fun DownloadsScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    DownloadsContent(contentPadding = contentPadding, modifier = modifier)
}

// ---------------------------------------------------------------------------------------------
// Header, search, chips, sort
// ---------------------------------------------------------------------------------------------

@Composable
private fun LibraryHeader(
    user: User?,
    searchActive: Boolean,
    onProfile: () -> Unit,
    onToggleSearch: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarButton(user = user, onClick = onProfile)
        Text(
            text = stringResource(R.string.browse_library_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconButton(onClick = onToggleSearch) {
            Icon(
                imageVector = if (searchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = stringResource(
                    if (searchActive) R.string.browse_library_close_search else R.string.browse_library_search,
                ),
            )
        }
        IconButton(onClick = onCreate) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.browse_library_create))
        }
    }
}

@Composable
private fun LibrarySearchField(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.browse_library_search), maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.browse_library_close_search))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(8.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
    )
}

@Composable
private fun libraryFilterLabel(filter: LibraryFilter): String = stringResource(
    when (filter) {
        LibraryFilter.PLAYLISTS -> R.string.browse_library_filter_playlists
        LibraryFilter.ALBUMS -> R.string.browse_library_filter_albums
        LibraryFilter.ARTISTS -> R.string.browse_library_filter_artists
        LibraryFilter.PODCASTS -> R.string.browse_library_filter_podcasts
        LibraryFilter.DOWNLOADED -> R.string.browse_library_filter_downloaded
    },
)

@Composable
private fun sortLabel(sort: LibrarySort): String = stringResource(
    when (sort) {
        LibrarySort.RECENT -> R.string.browse_library_sort_recent
        LibrarySort.RECENTLY_ADDED -> R.string.browse_library_sort_recently_added
        LibrarySort.ALPHABETICAL -> R.string.browse_library_sort_alphabetical
        LibrarySort.CREATOR -> R.string.browse_library_sort_creator
    },
)

@Composable
private fun SortViewRow(sort: LibrarySort, view: LibraryView, onSort: (LibrarySort) -> Unit, onToggleView: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    text = sortLabel(sort),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Text(
                    text = stringResource(R.string.browse_library_sort_by),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LibrarySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(option)) },
                        onClick = {
                            menuOpen = false
                            onSort(option)
                        },
                        trailingIcon = if (option == sort) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleView) {
            Icon(
                imageVector = if (view == LibraryView.LIST) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                contentDescription = stringResource(
                    if (view == LibraryView.LIST) R.string.browse_library_show_grid else R.string.browse_library_show_list,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// List / grid
// ---------------------------------------------------------------------------------------------

private fun AppNavigator.openLibraryItem(item: LibraryItem) = open(item.toMediaRef())

/**
 * The fast scroller's bubble for the library item at [index] of [count]: the first letter of the
 * name (or creator) it is sorted by, the month it was added, else its position.
 */
internal fun libraryScrollLabel(item: LibraryItem?, index: Int, count: Int, sort: LibrarySort, datePattern: String): String? = when (sort) {
    LibrarySort.ALPHABETICAL -> fastScrollLetter(item?.name)
    LibrarySort.CREATOR -> fastScrollLetter(item?.creator?.takeIf { it.isNotBlank() } ?: item?.name)
    LibrarySort.RECENTLY_ADDED -> item?.addedAt?.let { fastScrollDate(it, datePattern) } ?: fastScrollPosition(index, count)
    LibrarySort.RECENT -> fastScrollPosition(index, count)
}

@Composable
private fun LibraryList(
    state: LibraryUiState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    onOpenFolder: (LibraryItem) -> Unit,
    onBack: () -> Unit,
    onOpenEpisodes: () -> Unit,
    downloadState: (String) -> Flow<DownloadState?>,
) {
    val listState = rememberLazyListState()
    ScrollToTopOnChange("${state.folders.lastOrNull()?.id}|${state.filter}") { listState.scrollToItem(0) }

    // Before the items: the folder's back row and the pinned rows.
    val itemsStart = (if (state.folders.isNotEmpty()) 1 else 0) + state.pinned.size
    val datePattern = rememberFastScrollDatePattern()
    val items = state.items
    val sort = state.sort
    val label: (Int) -> String? = remember(items, sort, datePattern) {
        { index -> libraryScrollLabel(items.getOrNull(index), index, items.size, sort, datePattern) }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = contentPaddingWith(contentPadding), modifier = Modifier.fillMaxSize()) {
            state.folders.lastOrNull()?.let { folder ->
                item(key = "back", contentType = "back") { BackRow(title = folder.name, onClick = onBack) }
            }
            items(state.pinned, key = { it.key }, contentType = { "pinned" }) { entry ->
                PinnedRow(entry = entry, onClick = { onPinnedClick(entry, navigator, onOpenEpisodes) })
            }
            items(state.items, key = { it.key }, contentType = { if (it.kind == LibraryItemKind.FOLDER) "folder" else "item" }) { item ->
                if (item.kind == LibraryItemKind.FOLDER) {
                    FolderRow(item = item, onClick = { onOpenFolder(item) })
                } else {
                    val ref = remember(item) { item.toMediaRef() }
                    MediaRow(
                        ref = ref,
                        onClick = { navigator.openLibraryItem(item) },
                        subtitle = typedSubtitle(ref.type, item.creator),
                        onLongClick = { item.actionTarget(state.user?.username)?.let(navigator::showActions) },
                        trailing = if (item.id in state.downloaded) {
                            { CollectionDownloadBadge(item.id, downloadState) }
                        } else {
                            null
                        },
                    )
                }
            }
            if (state.items.isEmpty() && state.pinned.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    LibraryEmpty(state, Modifier.fillParentMaxHeight(0.7f))
                }
            }
        }
        FastScroller(
            listState = listState,
            contentStart = itemsStart,
            contentCount = items.size,
            bottomPadding = contentPadding.calculateBottomPadding(),
            label = label,
        )
    }
}

@Composable
private fun LibraryGrid(
    state: LibraryUiState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    onOpenFolder: (LibraryItem) -> Unit,
    onBack: () -> Unit,
    onOpenEpisodes: () -> Unit,
    downloadState: (String) -> Flow<DownloadState?>,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontal = 16.dp
        val spacing = 12.dp
        val columns = ((maxWidth - horizontal * 2 + spacing) / (MIN_GRID_CELL + spacing)).toInt().coerceAtLeast(2)
        val cell: Dp = (maxWidth - horizontal * 2 - spacing * (columns - 1)) / columns
        val gridState = rememberLazyGridState()
        ScrollToTopOnChange("${state.folders.lastOrNull()?.id}|${state.filter}") { gridState.scrollToItem(0) }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            contentPadding = contentPaddingWith(contentPadding, horizontal = horizontal),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            state.folders.lastOrNull()?.let { folder ->
                item(key = "back", span = { GridItemSpan(maxLineSpan) }, contentType = "back") {
                    BackRow(title = folder.name, onClick = onBack)
                }
            }
            items(state.pinned, key = { it.key }, contentType = { "pinned" }) { entry ->
                PinnedGridTile(entry = entry, size = cell, onClick = { onPinnedClick(entry, navigator, onOpenEpisodes) })
            }
            items(state.items, key = { it.key }, contentType = { if (it.kind == LibraryItemKind.FOLDER) "folder" else "item" }) { item ->
                if (item.kind == LibraryItemKind.FOLDER) {
                    FolderGridTile(item = item, size = cell, onClick = { onOpenFolder(item) })
                } else {
                    val ref = remember(item) { item.toMediaRef() }
                    Box {
                        MediaCard(
                            ref = ref,
                            onClick = { navigator.openLibraryItem(item) },
                            size = cell,
                            onLongClick = { item.actionTarget(state.user?.username)?.let(navigator::showActions) },
                        )
                        if (item.id in state.downloaded) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                            ) {
                                Box(Modifier.padding(4.dp)) { CollectionDownloadBadge(item.id, downloadState) }
                            }
                        }
                    }
                }
            }
            if (state.items.isEmpty() && state.pinned.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    LibraryEmpty(state, Modifier.padding(top = 64.dp))
                }
            }
        }
    }
}

private val MIN_GRID_CELL = 104.dp

private fun onPinnedClick(entry: PinnedEntry, navigator: AppNavigator, onOpenEpisodes: () -> Unit) {
    when (entry) {
        is PinnedEntry.LikedSongs -> navigator.navigate(Route.LikedSongs)
        is PinnedEntry.Downloads -> navigator.navigate(Route.Downloads)
        PinnedEntry.YourEpisodes -> onOpenEpisodes()
    }
}

@Composable
private fun CollectionDownloadBadge(uri: String, downloadState: (String) -> Flow<DownloadState?>) {
    val flow = remember(uri) { downloadState(uri) }
    val state by flow.collectAsStateWithLifecycle(initialValue = DownloadState.COMPLETED)
    DownloadIndicator(state)
}

private data class PinnedVisual(val colors: List<Color>, val icon: ImageVector)

private fun PinnedEntry.visual(): PinnedVisual = when (this) {
    is PinnedEntry.LikedSongs -> PinnedVisual(BrowsePalette.likedSongs, Icons.Rounded.Favorite)
    PinnedEntry.YourEpisodes -> PinnedVisual(BrowsePalette.episodes, Icons.Rounded.Bookmark)
    is PinnedEntry.Downloads -> PinnedVisual(BrowsePalette.downloads, Icons.Rounded.DownloadForOffline)
}

@Composable
private fun pinnedTitle(entry: PinnedEntry): String = stringResource(
    when (entry) {
        is PinnedEntry.LikedSongs -> R.string.browse_liked_songs
        PinnedEntry.YourEpisodes -> R.string.browse_library_your_episodes
        is PinnedEntry.Downloads -> R.string.browse_library_downloads
    },
)

@Composable
private fun pinnedSubtitle(entry: PinnedEntry): String = when (entry) {
    is PinnedEntry.LikedSongs -> typedSubtitle(
        MediaType.PLAYLIST,
        entry.count?.let { pluralStringResource(R.plurals.browse_song_count, it, it) },
    )
    PinnedEntry.YourEpisodes -> stringResource(R.string.browse_library_your_episodes_subtitle)
    is PinnedEntry.Downloads -> pluralStringResource(R.plurals.browse_download_count, entry.count, entry.count)
}

@Composable
private fun PinnedRow(entry: PinnedEntry, onClick: () -> Unit) {
    val visual = entry.visual()
    LibraryRowLayout(
        onClick = onClick,
        leading = { GradientTile(visual.colors, visual.icon, Modifier.size(56.dp), iconSize = 26.dp) },
        title = pinnedTitle(entry),
        subtitle = pinnedSubtitle(entry),
        trailing = if (entry is PinnedEntry.LikedSongs && entry.downloaded) {
            { DownloadIndicator(DownloadState.COMPLETED) }
        } else {
            null
        },
    )
}

@Composable
private fun FolderRow(item: LibraryItem, onClick: () -> Unit) {
    val count = item.playlistCount()
    LibraryRowLayout(
        onClick = onClick,
        leading = { GradientTile(BrowsePalette.folder, Icons.Rounded.Folder, Modifier.size(56.dp), iconSize = 26.dp) },
        title = item.name,
        subtitle = stringResource(
            R.string.browse_subtitle_separator,
            stringResource(R.string.browse_library_folder),
            pluralStringResource(R.plurals.browse_playlist_count, count, count),
        ),
    )
}

@Composable
internal fun LibraryRowLayout(
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun PinnedGridTile(entry: PinnedEntry, size: Dp, onClick: () -> Unit) {
    val visual = entry.visual()
    GridTileLayout(
        onClick = onClick,
        artwork = { GradientTile(visual.colors, visual.icon, Modifier.size(size), shape = RoundedCornerShape(6.dp), iconSize = size / 3) },
        title = pinnedTitle(entry),
        subtitle = pinnedSubtitle(entry),
    )
}

@Composable
private fun FolderGridTile(item: LibraryItem, size: Dp, onClick: () -> Unit) {
    val count = item.playlistCount()
    GridTileLayout(
        onClick = onClick,
        artwork = { GradientTile(BrowsePalette.folder, Icons.Rounded.Folder, Modifier.size(size), shape = RoundedCornerShape(6.dp), iconSize = size / 3) },
        title = item.name,
        subtitle = pluralStringResource(R.plurals.browse_playlist_count, count, count),
    )
}

@Composable
private fun GridTileLayout(onClick: () -> Unit, artwork: @Composable () -> Unit, title: String, subtitle: String) {
    Column(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)) {
        artwork()
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BackRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.browse_back), onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_back))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun LibraryEmpty(state: LibraryUiState, modifier: Modifier = Modifier) {
    when {
        state.query.isNotBlank() -> EmptyState(
            title = stringResource(R.string.browse_library_no_match_title, state.query),
            message = stringResource(R.string.browse_library_no_match_message),
            icon = Icons.Rounded.Search,
            modifier = modifier,
        )
        state.offline || state.filter == LibraryFilter.DOWNLOADED -> EmptyState(
            title = stringResource(R.string.browse_library_offline_empty_title),
            message = stringResource(R.string.browse_library_offline_empty_message),
            icon = if (state.offline) Icons.Rounded.CloudOff else Icons.Rounded.DownloadForOffline,
            modifier = modifier,
        )
        state.folders.isNotEmpty() -> EmptyState(
            title = stringResource(R.string.browse_library_folder_empty),
            icon = Icons.Rounded.Folder,
            modifier = modifier,
        )
        state.filter != null -> EmptyState(
            title = stringResource(R.string.browse_library_filter_empty),
            icon = Icons.AutoMirrored.Rounded.LibraryBooks,
            modifier = modifier,
        )
        else -> EmptyState(
            title = stringResource(R.string.browse_library_empty_title),
            message = stringResource(R.string.browse_library_empty_message),
            icon = Icons.AutoMirrored.Rounded.LibraryBooks,
            modifier = modifier,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Your Episodes
// ---------------------------------------------------------------------------------------------

@Composable
private fun YourEpisodesList(
    state: LibraryUiState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (Episode) -> Unit,
    onActions: (Episode) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRetryPartial: () -> Unit,
) {
    val listState = rememberLazyListState()
    val episodes = state.episodes
    LoadMoreEffect(listState, enabled = episodes.canLoadMore && episodes.items.isNotEmpty(), onLoadMore = onLoadMore)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = contentPaddingWith(contentPadding), modifier = Modifier.fillMaxSize()) {
            item(key = "back", contentType = "back") {
                BackRow(title = stringResource(R.string.browse_library_your_episodes), onClick = onBack)
            }
            if (state.episodesPartial) {
                item(key = "partial", contentType = "notice") { PartialContentNotice(onRetry = onRetryPartial) }
            }
            items(episodes.items, key = { "episode:${it.uri}" }, contentType = { "episode" }) { episode ->
                EpisodeRow(
                    episode = episode,
                    onClick = { onPlay(episode) },
                    isCurrent = state.nowPlaying.isCurrent(episode.uri),
                    isPlaying = state.nowPlaying.isPlaying,
                    // Which ones play offline (a tap of another says it isn't available offline).
                    downloadState = DownloadState.COMPLETED.takeIf { episode.uri in state.downloadedUris },
                    onLongClick = { onActions(episode) },
                    onMoreClick = { onActions(episode) },
                )
            }
            when {
                episodes.isLoading -> item(key = "loading", contentType = "footer") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                }
                episodes.error != null -> item(key = "error", contentType = "footer") {
                    ErrorState(
                        message = stringResource(episodes.error.toBrowseError().messageRes()),
                        onRetry = onRetry,
                        modifier = if (episodes.items.isEmpty()) Modifier.fillParentMaxHeight(0.6f) else Modifier,
                    )
                }
                episodes.items.isEmpty() && episodes.endReached -> item(key = "empty", contentType = "footer") {
                    EmptyState(
                        title = stringResource(R.string.browse_library_episodes_empty_title),
                        message = stringResource(R.string.browse_library_episodes_empty_message),
                        icon = Icons.Rounded.Podcasts,
                        modifier = Modifier.fillParentMaxHeight(0.6f),
                    )
                }
            }
        }
        FastScroller(listState, bottomPadding = contentPadding.calculateBottomPadding())
    }
}
