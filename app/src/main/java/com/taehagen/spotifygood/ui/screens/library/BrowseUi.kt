package com.taehagen.spotifygood.ui.screens.library

import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.components.Artwork
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

// Compose helpers shared by the browse screens (home, search, library, profile).

/** Original gradients of the browse area (dark-first, see docs §9.9). */
internal object BrowsePalette {
    val likedSongs = listOf(Color(0xFF3B1FA8), Color(0xFF6E5BF0), Color(0xFFA9D8EC))
    val downloads = listOf(Color(0xFF07492F), Color(0xFF13945A), Color(0xFF39E08F))
    val episodes = listOf(Color(0xFF6E1F0A), Color(0xFFC4501B), Color(0xFFF4A259))
    val folder = listOf(Color(0xFF2B2F36), Color(0xFF454C57))
}

@StringRes
internal fun BrowseError.messageRes(search: Boolean = false): Int = when (this) {
    BrowseError.OFFLINE -> if (search) R.string.browse_error_search_offline else R.string.browse_error_offline
    BrowseError.RATE_LIMITED -> R.string.browse_error_rate_limited
    BrowseError.NOT_FOUND -> R.string.browse_error_not_found
    BrowseError.GENERIC -> R.string.browse_error_generic
}

@Composable
internal fun mediaTypeLabel(type: MediaType): String = stringResource(
    when (type) {
        MediaType.TRACK -> R.string.browse_type_song
        MediaType.ALBUM -> R.string.browse_type_album
        MediaType.ARTIST -> R.string.browse_type_artist
        MediaType.PLAYLIST -> R.string.browse_type_playlist
        MediaType.SHOW -> R.string.browse_type_podcast
        MediaType.EPISODE -> R.string.browse_type_episode
        MediaType.COLLECTION -> R.string.browse_type_collection
    },
)

/** "Type • detail" subtitle line. */
@Composable
internal fun typedSubtitle(type: MediaType, detail: String?): String {
    val label = mediaTypeLabel(type)
    return if (detail.isNullOrBlank()) label else stringResource(R.string.browse_subtitle_separator, label, detail)
}

/** [base] (the host's bottom insets) plus extra spacing for scrolling content. */
@Composable
internal fun contentPaddingWith(
    base: PaddingValues,
    horizontal: Dp = 0.dp,
    top: Dp = 0.dp,
    bottom: Dp = 16.dp,
): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = base.calculateStartPadding(direction) + horizontal,
        end = base.calculateEndPadding(direction) + horizontal,
        top = base.calculateTopPadding() + top,
        bottom = base.calculateBottomPadding() + bottom,
    )
}

@Composable
internal fun BrowseTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_back))
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor),
        modifier = modifier,
    )
}

/** Horizontally scrolling single-selection chips. */
@Composable
internal fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options, key = { it.toString() }) { option ->
            val isSelected = option == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = { Text(label(option), maxLines = 1) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                ),
                border = null,
            )
        }
    }
}

/** Square gradient artwork with a centred icon (Liked Songs, Downloads, Your Episodes, folders). */
@Composable
internal fun GradientTile(
    colors: List<Color>,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    text: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.browse_cancel)) }
        },
    )
}

/** Pulsing placeholder block for skeleton loading states. */
@Composable
internal fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier
            .alpha(alpha)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** Calls [onLoadMore] when the last visible item is within [threshold] of the end. */
@Composable
internal fun LoadMoreEffect(state: LazyListState, enabled: Boolean, threshold: Int = 15, onLoadMore: () -> Unit) {
    val callback by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 - threshold
        }.distinctUntilChanged().filter { it }.collect { callback() }
    }
}

@Composable
internal fun LoadMoreEffect(state: LazyGridState, enabled: Boolean, threshold: Int = 15, onLoadMore: () -> Unit) {
    val callback by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 - threshold
        }.distinctUntilChanged().filter { it }.collect { callback() }
    }
}

/**
 * Runs [scrollToTop] when [key] changes (e.g. another folder or filter), but not when the screen
 * re-enters composition with the same key, so returning from a detail page keeps the position.
 */
@Composable
internal fun ScrollToTopOnChange(key: String, scrollToTop: suspend () -> Unit) {
    var lastKey by rememberSaveable { mutableStateOf<String?>(null) }
    val action by rememberUpdatedState(scrollToTop)
    LaunchedEffect(key) {
        if (lastKey != null && lastKey != key) action()
        lastKey = key
    }
}

/** The user's round avatar as a 48 dp button. */
@Composable
internal fun AvatarButton(user: User?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    IconButton(onClick = onClick, modifier = modifier) {
        Artwork(
            url = user?.images?.best(96),
            contentDescription = stringResource(R.string.browse_profile_open),
            shape = CircleShape,
            placeholderIcon = Icons.Rounded.Person,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * Centred full-size wrapper for loading/error/empty states that are not part of a list. It is
 * vertically scrollable so a surrounding pull-to-refresh still receives the gesture.
 */
@Composable
internal fun StateBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
