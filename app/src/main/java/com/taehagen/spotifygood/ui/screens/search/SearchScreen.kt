package com.taehagen.spotifygood.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.RecentSearch
import com.taehagen.spotifygood.data.SearchType
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.EpisodeRow
import com.taehagen.spotifygood.ui.components.ErrorState
import com.taehagen.spotifygood.ui.components.LoadingState
import com.taehagen.spotifygood.ui.components.MediaCarousel
import com.taehagen.spotifygood.ui.components.SectionHeader
import com.taehagen.spotifygood.ui.components.TrackRow
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.library.BrowseError
import com.taehagen.spotifygood.ui.screens.library.ChipRow
import com.taehagen.spotifygood.ui.screens.library.ConfirmDialog
import com.taehagen.spotifygood.ui.screens.library.StateBox
import com.taehagen.spotifygood.ui.screens.library.contentPaddingWith
import com.taehagen.spotifygood.ui.screens.library.messageRes
import com.taehagen.spotifygood.ui.screens.library.toActionTarget

@Composable
fun SearchScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { graph -> SearchViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val queryText = viewModel.queryText
    val hideKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    BackHandler(enabled = queryText.isNotEmpty()) {
        viewModel.clearQuery()
        hideKeyboard()
    }

    val open: (MediaRef) -> Unit = { ref ->
        viewModel.onOpened(ref)
        hideKeyboard()
        navigator.open(ref)
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.browse_search_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
            )
            SearchField(
                value = queryText,
                onValueChange = viewModel::onQueryChange,
                onSearch = {
                    viewModel.submit()
                    hideKeyboard()
                },
                onClear = viewModel::clearQuery,
                focusRequester = focusRequester,
            )
        }
        if (queryText.isNotBlank()) {
            ChipRow(
                options = SearchFilter.entries,
                selected = state.filter,
                onSelect = viewModel::selectFilter,
                label = { searchFilterLabel(it) },
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                queryText.isBlank() -> SearchHome(
                    recent = state.recent,
                    contentPadding = contentPadding,
                    onRecentClick = { item ->
                        viewModel.openRecent(item)
                        if (item is RecentSearch.Item) {
                            hideKeyboard()
                            navigator.open(item.ref)
                        }
                    },
                    onRecentRemove = viewModel::removeRecent,
                    onClearRecent = viewModel::clearRecent,
                    onCategory = { label, filter ->
                        hideKeyboard()
                        viewModel.runQuery(label, filter)
                    },
                    onDrag = hideKeyboard,
                )
                state.offline -> StateBox {
                    ErrorState(message = stringResource(BrowseError.OFFLINE.messageRes(search = true)), onRetry = null)
                }
                state.filter == SearchFilter.TOP -> TopResults(
                    state = state,
                    contentPadding = contentPadding,
                    navigator = navigator,
                    onOpen = open,
                    onPlayTrack = viewModel::playTrack,
                    onPlayTop = viewModel::playTop,
                    onRetry = viewModel::retry,
                    onSeeAll = { type -> navigator.navigate(Route.SearchResults(state.query, type.wire)) },
                    onDrag = hideKeyboard,
                )
                else -> TypedResults(
                    state = state,
                    contentPadding = contentPadding,
                    onItemClick = { item ->
                        if (item is SearchItem.Song) {
                            hideKeyboard()
                            viewModel.playTrack(item.track)
                        } else {
                            open(item.ref)
                        }
                    },
                    onItemActions = { navigator.showActions(it.actionTarget(state.myUsername)) },
                    onLoadMore = viewModel::loadMore,
                    onRetry = viewModel::retry,
                    onDrag = hideKeyboard,
                )
            }
        }
    }
}

/** Hides the keyboard when the user starts dragging a list. */
@Composable
private fun HideKeyboardOnDrag(listState: LazyListState, onDrag: () -> Unit) {
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) onDrag() }
}

@Composable
private fun SearchHome(
    recent: List<RecentSearch>,
    contentPadding: PaddingValues,
    onRecentClick: (RecentSearch) -> Unit,
    onRecentRemove: (RecentSearch) -> Unit,
    onClearRecent: () -> Unit,
    onCategory: (String, SearchFilter) -> Unit,
    onDrag: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val dragged by gridState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) onDrag() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val labels = BrowseCategories.associate { it.id to stringResource(it.label) }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = contentPaddingWith(contentPadding, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (recent.isNotEmpty()) {
            item(key = "recentHeader", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                SectionHeader(
                    title = stringResource(R.string.browse_search_recent),
                    action = {
                        TextButton(onClick = { confirmClear = true }) {
                            Text(stringResource(R.string.browse_search_clear_recent))
                        }
                    },
                )
            }
            items(recent, key = { "recent:${it.key}" }, span = { GridItemSpan(maxLineSpan) }, contentType = { it::class }) { item ->
                RecentSearchRow(item = item, onClick = { onRecentClick(item) }, onRemove = { onRecentRemove(item) })
            }
        }
        item(key = "browseHeader", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            SectionHeader(title = stringResource(R.string.browse_search_browse_all), modifier = Modifier.padding(top = 8.dp))
        }
        items(BrowseCategories, key = { "category:${it.id}" }, contentType = { "category" }) { category ->
            BrowseTile(
                category = category,
                onClick = { onCategory(labels.getValue(category.id), category.filter) },
            )
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.browse_search_clear_recent_title),
            text = null,
            confirmLabel = stringResource(R.string.browse_search_clear_recent_confirm),
            onConfirm = onClearRecent,
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun TopResults(
    state: SearchUiState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    onOpen: (MediaRef) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayTop: (MediaRef, TopSections) -> Unit,
    onRetry: () -> Unit,
    onSeeAll: (SearchType) -> Unit,
    onDrag: () -> Unit,
) {
    when (val top = state.top) {
        TopResultsState.Idle, TopResultsState.Loading -> LoadingState()
        is TopResultsState.Failed -> StateBox {
            ErrorState(message = stringResource(top.error.messageRes(search = true)), onRetry = onRetry)
        }
        is TopResultsState.Empty -> StateBox { NoResults(top.query, onRetry) }
        is TopResultsState.Ready -> {
            val listState = rememberLazyListState()
            HideKeyboardOnDrag(listState, onDrag)
            val sections = top.sections
            val actionsFor: (MediaRef) -> Unit = { ref ->
                val target = sections.byUri[ref.uri]?.actionTarget(state.myUsername) ?: ref.toActionTarget(state.myUsername)
                target?.let(navigator::showActions)
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = contentPaddingWith(contentPadding, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    sections.top?.let { ref ->
                        item(key = "top", contentType = "top") {
                            // A song / episode marked unplayable must not start (a different track
                            // would play): the card is dimmed and only offers its actions.
                            val playable = sections.byUri[ref.uri].isPlayable
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                SectionHeader(title = stringResource(R.string.browse_search_top_result))
                                TopResultCard(
                                    ref = ref,
                                    onClick = {
                                        val song = sections.byUri[ref.uri] as? SearchItem.Song
                                        when {
                                            !playable -> actionsFor(ref)
                                            song != null -> onPlayTrack(song.track)
                                            else -> onOpen(ref)
                                        }
                                    },
                                    onLongClick = { actionsFor(ref) },
                                    onPlay = if (!playable || ref.type == MediaType.SHOW || ref.type == MediaType.COLLECTION) {
                                        null
                                    } else {
                                        { onPlayTop(ref, sections) }
                                    },
                                    unavailable = !playable,
                                    isPlaying = state.nowPlaying.isPlaying &&
                                        (state.nowPlaying.contextUri == ref.uri || state.nowPlaying.trackUri == ref.uri),
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    if (sections.songs.isNotEmpty()) {
                        item(key = "songsHeader", contentType = "header") {
                            SeeAllHeader(stringResource(R.string.browse_search_filter_songs)) { onSeeAll(SearchType.TRACK) }
                        }
                        items(sections.songs, key = { "song:${it.uri}" }, contentType = { "song" }) { track ->
                            TrackRow(
                                track = track,
                                onClick = { onPlayTrack(track) },
                                isCurrent = state.nowPlaying.isCurrent(track.uri),
                                isPlaying = state.nowPlaying.isPlaying,
                                onMoreClick = { navigator.showActions(MediaActionTarget.TrackTarget(track, contextUri = track.album?.uri)) },
                                onLongClick = { navigator.showActions(MediaActionTarget.TrackTarget(track, contextUri = track.album?.uri)) },
                            )
                        }
                    }
                    carousel("artists", R.string.browse_search_filter_artists, sections.artists, SearchType.ARTIST, onOpen, actionsFor, onSeeAll)
                    carousel("albums", R.string.browse_search_filter_albums, sections.albums, SearchType.ALBUM, onOpen, actionsFor, onSeeAll)
                    carousel("playlists", R.string.browse_search_filter_playlists, sections.playlists, SearchType.PLAYLIST, onOpen, actionsFor, onSeeAll)
                    carousel("shows", R.string.browse_search_filter_podcasts, sections.shows, SearchType.SHOW, onOpen, actionsFor, onSeeAll)
                    if (sections.episodes.isNotEmpty()) {
                        item(key = "episodesHeader", contentType = "header") {
                            SeeAllHeader(stringResource(R.string.browse_search_filter_episodes)) { onSeeAll(SearchType.EPISODE) }
                        }
                        items(sections.episodes, key = { "episode:${it.uri}" }, contentType = { "episode" }) { episode ->
                            val ref = sections.byUri[episode.uri]?.ref
                            EpisodeRow(
                                episode = episode,
                                onClick = { ref?.let(onOpen) ?: navigator.navigate(Route.Episode(episode.uri)) },
                                isCurrent = state.nowPlaying.isCurrent(episode.uri),
                                isPlaying = state.nowPlaying.isPlaying,
                                onLongClick = { navigator.showActions(MediaActionTarget.EpisodeTarget(episode)) },
                                onMoreClick = { navigator.showActions(MediaActionTarget.EpisodeTarget(episode)) },
                            )
                        }
                    }
                }
                if (top.isRefreshing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }
}

private fun LazyListScope.carousel(
    id: String,
    title: Int,
    items: List<MediaRef>,
    type: SearchType,
    onOpen: (MediaRef) -> Unit,
    onActions: (MediaRef) -> Unit,
    onSeeAll: (SearchType) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "carousel:$id", contentType = "carousel") {
        MediaCarousel(
            title = stringResource(title),
            items = items,
            onItemClick = onOpen,
            onItemLongClick = onActions,
            onSeeAll = { onSeeAll(type) },
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SeeAllHeader(title: String, onSeeAll: () -> Unit) {
    SectionHeader(
        title = title,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        action = {
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.browse_search_see_all)) }
        },
    )
}

@Composable
private fun TypedResults(
    state: SearchUiState,
    contentPadding: PaddingValues,
    onItemClick: (SearchItem) -> Unit,
    onItemActions: (SearchItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onDrag: () -> Unit,
) {
    val paged = state.typed
    if (paged == null || paged.isInitialLoading) {
        LoadingState()
        return
    }
    // A fresh list per query/filter so the scroll position starts at the top.
    val listState = remember(state.query, state.filter) { LazyListState() }
    HideKeyboardOnDrag(listState, onDrag)
    SearchItemsList(
        paged = paged,
        nowPlaying = state.nowPlaying,
        listState = listState,
        contentPadding = contentPaddingWith(contentPadding, top = 4.dp),
        onItemClick = onItemClick,
        onItemActions = onItemActions,
        onLoadMore = onLoadMore,
        onRetry = onRetry,
        emptyContent = { NoResults(state.query, onRetry) },
    )
}

/**
 * No results for [query]. [onRetry] asks the engine again (a transient failure can come back as an
 * empty result; empty results are not cached).
 */
@Composable
private fun NoResults(query: String, onRetry: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.browse_search_no_results_title, query),
        message = stringResource(R.string.browse_search_no_results_message),
        icon = Icons.Rounded.Search,
        action = {
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.shell_retry))
            }
        },
    )
}

/** All results of one type for [query] ([type] = SearchType.wire). */
@Composable
fun SearchResultsScreen(query: String, type: String, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = "searchResults:$type:$query") { graph -> SearchResultsViewModel(graph, query, type) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        searchTypeLabel(state.type),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        stringResource(R.string.browse_search_results_subtitle, query),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = navigator::back) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.offline && state.paged.items.isEmpty() -> StateBox {
                    ErrorState(message = stringResource(BrowseError.OFFLINE.messageRes(search = true)), onRetry = null)
                }
                state.paged.isInitialLoading -> LoadingState()
                else -> SearchItemsList(
                    paged = state.paged,
                    nowPlaying = state.nowPlaying,
                    listState = listState,
                    contentPadding = contentPaddingWith(contentPadding),
                    onItemClick = { item ->
                        if (item is SearchItem.Song) {
                            viewModel.playTrack(item.track)
                        } else {
                            viewModel.onOpened(item.ref)
                            navigator.open(item.ref)
                        }
                    },
                    onItemActions = { navigator.showActions(it.actionTarget(state.myUsername)) },
                    onLoadMore = viewModel::loadMore,
                    onRetry = viewModel::retry,
                    emptyContent = { NoResults(query, viewModel::retry) },
                )
            }
        }
    }
}
