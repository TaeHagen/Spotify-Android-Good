package com.taehagen.spotifygood.ui.screens.search

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Nightlife
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.RecentSearch
import com.taehagen.spotifygood.data.SearchType
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.EpisodeRow
import com.taehagen.spotifygood.ui.components.ErrorState
import com.taehagen.spotifygood.ui.components.MediaRow
import com.taehagen.spotifygood.ui.components.PlayFab
import com.taehagen.spotifygood.ui.components.TrackRow
import com.taehagen.spotifygood.ui.screens.library.LoadMoreEffect
import com.taehagen.spotifygood.ui.screens.library.NowPlaying
import com.taehagen.spotifygood.ui.screens.library.PagedState
import com.taehagen.spotifygood.ui.screens.library.messageRes
import com.taehagen.spotifygood.ui.screens.library.toBrowseError
import com.taehagen.spotifygood.ui.screens.library.typedSubtitle

// Building blocks of the search screens.

@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.browse_search_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = {
                    onClear()
                    focusRequester.requestFocus()
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.browse_search_clear))
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(8.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
            unfocusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
            focusedTextColor = MaterialTheme.colorScheme.inverseOnSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.inverseOnSurface,
            focusedPlaceholderColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
            focusedLeadingIconColor = MaterialTheme.colorScheme.inverseOnSurface,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.inverseOnSurface,
            focusedTrailingIconColor = MaterialTheme.colorScheme.inverseOnSurface,
            unfocusedTrailingIconColor = MaterialTheme.colorScheme.inverseOnSurface,
            cursorColor = MaterialTheme.colorScheme.inversePrimary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
}

@Composable
internal fun searchFilterLabel(filter: SearchFilter): String = stringResource(
    when (filter) {
        SearchFilter.TOP -> R.string.browse_search_filter_top
        SearchFilter.SONGS -> R.string.browse_search_filter_songs
        SearchFilter.ARTISTS -> R.string.browse_search_filter_artists
        SearchFilter.ALBUMS -> R.string.browse_search_filter_albums
        SearchFilter.PLAYLISTS -> R.string.browse_search_filter_playlists
        SearchFilter.PODCASTS -> R.string.browse_search_filter_podcasts
        SearchFilter.EPISODES -> R.string.browse_search_filter_episodes
    },
)

@Composable
internal fun searchTypeLabel(type: SearchType): String = searchFilterLabel(
    SearchFilter.entries.first { it.type == type },
)

// ---------------------------------------------------------------------------------------------
// Browse tiles
// ---------------------------------------------------------------------------------------------

@Immutable
internal data class BrowseCategory(
    val id: String,
    @StringRes val label: Int,
    val colors: List<Color>,
    val icon: ImageVector,
    val filter: SearchFilter = SearchFilter.TOP,
)

/** Genre and mood shortcuts (original palette). Tapping one searches for its label. */
internal val BrowseCategories: List<BrowseCategory> = listOf(
    BrowseCategory("pop", R.string.browse_category_pop, listOf(Color(0xFFE0306E), Color(0xFFF58A42)), Icons.Rounded.Star),
    BrowseCategory("hiphop", R.string.browse_category_hiphop, listOf(Color(0xFF5B2BD1), Color(0xFFB63FD9)), Icons.Rounded.Mic),
    BrowseCategory("rock", R.string.browse_category_rock, listOf(Color(0xFF9E1020), Color(0xFF3D0B12)), Icons.Rounded.ElectricBolt),
    BrowseCategory("chill", R.string.browse_category_chill, listOf(Color(0xFF16707C), Color(0xFF6CC9B6)), Icons.Rounded.Spa),
    BrowseCategory("workout", R.string.browse_category_workout, listOf(Color(0xFFE5501A), Color(0xFFF2B632)), Icons.Rounded.FitnessCenter),
    BrowseCategory("focus", R.string.browse_category_focus, listOf(Color(0xFF2A4597), Color(0xFF5B9FE3)), Icons.Rounded.Lightbulb),
    BrowseCategory("sleep", R.string.browse_category_sleep, listOf(Color(0xFF1A1F4D), Color(0xFF5D4A9E)), Icons.Rounded.Bedtime),
    BrowseCategory("party", R.string.browse_category_party, listOf(Color(0xFFD61F80), Color(0xFF6D2AE0)), Icons.Rounded.Celebration),
    BrowseCategory("podcasts", R.string.browse_category_podcasts, listOf(Color(0xFF0D6149), Color(0xFF2AAE67)), Icons.Rounded.Podcasts, SearchFilter.PODCASTS),
    BrowseCategory("electronic", R.string.browse_category_electronic, listOf(Color(0xFF087F94), Color(0xFF283A92)), Icons.Rounded.GraphicEq),
    BrowseCategory("jazz", R.string.browse_category_jazz, listOf(Color(0xFF7A4C17), Color(0xFFCF9A3A)), Icons.Rounded.Piano),
    BrowseCategory("classical", R.string.browse_category_classical, listOf(Color(0xFF5E4536), Color(0xFFB2977C)), Icons.Rounded.MusicNote),
    BrowseCategory("rnb", R.string.browse_category_rnb, listOf(Color(0xFF6E1B52), Color(0xFFD9607F)), Icons.Rounded.Nightlife),
    BrowseCategory("indie", R.string.browse_category_indie, listOf(Color(0xFF466D23), Color(0xFFB3C944)), Icons.Rounded.Headphones),
    BrowseCategory("latin", R.string.browse_category_latin, listOf(Color(0xFFD5461A), Color(0xFFF0BE3E)), Icons.Rounded.WbSunny),
    BrowseCategory("mood", R.string.browse_category_mood, listOf(Color(0xFF34508A), Color(0xFFDC67A6)), Icons.Rounded.Mood),
    BrowseCategory("playlists", R.string.browse_category_playlists, listOf(Color(0xFF3C3C46), Color(0xFF7D7F8C)), Icons.AutoMirrored.Rounded.QueueMusic, SearchFilter.PLAYLISTS),
)

@Composable
internal fun BrowseTile(category: BrowseCategory, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(category.label)
    Box(
        modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(category.colors))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
    ) {
        Icon(
            imageVector = category.icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 12.dp, y = 8.dp)
                .rotate(20f)
                .size(64.dp),
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Recent searches
// ---------------------------------------------------------------------------------------------

@Composable
internal fun RecentSearchRow(item: RecentSearch, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val remove: @Composable () -> Unit = {
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.browse_search_remove_recent))
        }
    }
    when (item) {
        is RecentSearch.Item -> MediaRow(
            ref = item.ref,
            onClick = onClick,
            subtitle = typedSubtitle(item.ref.type, item.ref.subtitle),
            trailing = remove,
            modifier = modifier,
        )
        is RecentSearch.Query -> Row(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = item.query,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            remove()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------------------------

@Composable
internal fun TopResultCard(
    ref: MediaRef,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: (() -> Unit)?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** A song / episode that cannot be played here: dimmed, "Unavailable" for accessibility. */
    unavailable: Boolean = false,
) {
    val unavailableLabel = stringResource(R.string.shell_state_unavailable)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = stringResource(R.string.browse_more_options),
                )
                .semantics { if (unavailable) stateDescription = unavailableLabel },
        ) {
            Column(Modifier.padding(16.dp).alpha(if (unavailable) 0.38f else 1f)) {
                Artwork(
                    url = ref.images.best(300),
                    contentDescription = null,
                    shape = if (ref.type == MediaType.ARTIST) CircleShape else RoundedCornerShape(6.dp),
                    modifier = Modifier.size(96.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = ref.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = if (onPlay != null) 64.dp else 0.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = typedSubtitle(ref.type, ref.subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = if (onPlay != null) 64.dp else 0.dp),
                )
            }
            if (onPlay != null) {
                PlayFab(
                    isPlaying = isPlaying,
                    onClick = onPlay,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
    }
}

/** Row for one result, playing songs and opening everything else. */
@Composable
internal fun SearchItemRow(
    item: SearchItem,
    nowPlaying: NowPlaying,
    onClick: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is SearchItem.Song -> TrackRow(
            track = item.track,
            onClick = onClick,
            isCurrent = nowPlaying.isCurrent(item.track.uri),
            isPlaying = nowPlaying.isPlaying,
            onMoreClick = onActions,
            onLongClick = onActions,
            modifier = modifier,
        )
        is SearchItem.EpisodeItem -> EpisodeRow(
            episode = item.episode,
            onClick = onClick,
            isCurrent = nowPlaying.isCurrent(item.episode.uri),
            isPlaying = nowPlaying.isPlaying,
            onLongClick = onActions,
            onMoreClick = onActions,
            modifier = modifier,
        )
        else -> MediaRow(
            ref = item.ref,
            onClick = onClick,
            subtitle = typedSubtitle(item.ref.type, item.ref.subtitle),
            onMoreClick = onActions,
            onLongClick = onActions,
            modifier = modifier,
        )
    }
}

/** Paged single-type result list with load-more footer. */
@Composable
internal fun SearchItemsList(
    paged: PagedState<SearchItem>,
    nowPlaying: NowPlaying,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onItemClick: (SearchItem) -> Unit,
    onItemActions: (SearchItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    emptyContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LoadMoreEffect(listState, enabled = paged.canLoadMore && paged.items.isNotEmpty(), onLoadMore = onLoadMore)
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = modifier.fillMaxSize()) {
        items(paged.items, key = { it.key }, contentType = { it::class }) { item ->
            SearchItemRow(
                item = item,
                nowPlaying = nowPlaying,
                onClick = { onItemClick(item) },
                onActions = { onItemActions(item) },
            )
        }
        when {
            paged.isLoading -> item(key = "footer:loading", contentType = "footer") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                }
            }
            paged.error != null -> item(key = "footer:error", contentType = "footer") {
                if (paged.items.isEmpty()) {
                    ErrorState(
                        message = stringResource(paged.error.toBrowseError().messageRes(search = true)),
                        onRetry = onRetry,
                        modifier = Modifier.fillParentMaxHeight(0.7f),
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(paged.error.toBrowseError().messageRes(search = true)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.browse_retry)) }
                    }
                }
            }
            paged.items.isEmpty() && paged.endReached -> item(key = "footer:empty", contentType = "footer") {
                Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    emptyContent()
                }
            }
        }
    }
}
