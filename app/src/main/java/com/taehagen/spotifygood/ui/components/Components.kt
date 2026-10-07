package com.taehagen.spotifygood.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.navigation.LocalOptionalAppNavigator
import com.taehagen.spotifygood.ui.theme.AppColors
import com.taehagen.spotifygood.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import java.io.File

// Shared UI building blocks. Signatures are a contract used by every screen; implementations are
// owned by the UI shell (see docs/ARCHITECTURE.md §9.9). Keep parameters stable.

private const val DISABLED_ALPHA = 0.38f

/** Space below the status bar kept free for the transparent back-button bar of detail pages. */
private val OVERLAID_TOP_BAR_SPACE = 56.dp

/**
 * Coil data for an image "url": an absolute path (a downloaded cover, used offline) is loaded as
 * a local [File], anything else as the URL it is.
 */
internal fun imageData(url: String): Any = if (url.startsWith('/')) File(url) else url

/** Cover art / artist image with placeholder, crossfade and Coil caching. [url] may be a local path. */
@Composable
fun Artwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    placeholderIcon: ImageVector = Icons.Rounded.MusicNote,
) {
    val context = LocalPlatformContext.current
    val request = remember(url, context) {
        url?.takeIf { it.isNotBlank() }?.let { ImageRequest.Builder(context).data(imageData(it)).crossfade(true).build() }
    }
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics {
                        this.contentDescription = contentDescription
                        role = Role.Image
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxSize(0.42f),
            )
        }
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onState = { state -> loaded = state is AsyncImagePainter.State.Success },
            )
        }
    }
}

/** A track list row: artwork (optional) or index, title, artists, explicit/download badges, overflow. */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    showArtwork: Boolean = true,
    index: Int? = null,
    subtitleOverride: String? = null,
    downloadState: DownloadState? = null,
    isSuggestion: Boolean = false,
    onMoreClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /**
     * False: dimmed and not clickable; the overflow button ([onMoreClick]) stays usable so the
     * item's actions remain reachable. Kept before [trailing] so trailing-lambda calls still work.
     */
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val titleColor = if (isCurrent) colors.primary else colors.onSurface
    val dimmed = !enabled || !track.playable
    val subtitle = subtitleOverride ?: remember(track.artists) { track.artists.joinToString { it.name } }
    val nowPlayingLabel = stringResource(R.string.shell_state_now_playing)
    val unavailableLabel = stringResource(R.string.shell_state_unavailable)
    val moreLabel = stringResource(R.string.shell_cd_more_options_for, track.name)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = { if (track.playable) onClick() },
                onLongClick = onLongClick ?: onMoreClick,
                onLongClickLabel = if (onLongClick != null || onMoreClick != null) moreLabel else null,
            )
            .semantics {
                when {
                    !track.playable -> stateDescription = unavailableLabel
                    isCurrent -> stateDescription = nowPlayingLabel
                }
            }
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = if (onMoreClick != null) 4.dp else 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .alpha(if (dimmed) DISABLED_ALPHA else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                showArtwork -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Artwork(
                        url = track.album?.images?.best(120),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                    )
                    if (isCurrent && isPlaying) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            NowPlayingBars(isPlaying = true, color = AppColors.Brand)
                        }
                    }
                }
                index != null -> Box(Modifier.widthIn(min = 32.dp), contentAlignment = Alignment.Center) {
                    if (isCurrent && isPlaying) {
                        NowPlayingBars(isPlaying = true)
                    } else {
                        Text(
                            text = index.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) colors.primary else colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (showArtwork || index != null) Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrent && isPlaying && !showArtwork && index == null) {
                        NowPlayingBars(isPlaying = true, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = track.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isSuggestion) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = stringResource(R.string.shell_cd_suggested),
                            tint = colors.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (track.explicit) ExplicitBadge()
                    DownloadIndicator(downloadState)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Trailing slot sits at the END of the row (queue remove / drag handle / suggestion actions).
        if (trailing != null) {
            Box(Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA), contentAlignment = Alignment.Center) { trailing() }
        }
        if (onMoreClick != null) {
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Rounded.MoreVert, contentDescription = moreLabel, tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** A podcast episode row with date, duration, progress and description excerpt. */
@Composable
fun EpisodeRow(
    episode: Episode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    downloadState: DownloadState? = null,
    onLongClick: (() -> Unit)? = null,
    /**
     * False: dimmed and not clickable; the overflow button ([onMoreClick]) stays usable. Both new
     * parameters precede [onMoreClick] for trailing-lambda calls.
     */
    enabled: Boolean = true,
    onMoreClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val description = remember(episode.description) { stripHtml(episode.description) }
    val resume = episode.resumePositionMs ?: 0L
    val played = episode.fullyPlayed == true
    val meta = remember(episode.releaseDate, episode.durationMs, resume, played) {
        buildList {
            formatReleaseDate(episode.releaseDate)?.let(::add)
            when {
                played -> add(context.getString(R.string.shell_episode_played))
                resume > 0 && episode.durationMs > resume ->
                    add(context.getString(R.string.shell_episode_time_left, formatLongDuration(context, episode.durationMs - resume)))
                episode.durationMs > 0 -> add(formatLongDuration(context, episode.durationMs))
            }
        }.joinToString(" • ")
    }
    val moreLabel = stringResource(R.string.shell_cd_more_options_for, episode.name)
    val nowPlayingLabel = stringResource(R.string.shell_state_now_playing)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = { if (episode.playable) onClick() },
                onLongClick = onLongClick ?: onMoreClick,
                onLongClickLabel = if (onLongClick != null || onMoreClick != null) moreLabel else null,
            )
            .semantics { if (isCurrent) stateDescription = nowPlayingLabel }
            .padding(start = 16.dp, end = if (onMoreClick != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(Modifier.weight(1f).alpha(if (enabled && episode.playable) 1f else DISABLED_ALPHA)) {
            Artwork(
                url = episode.images.best(160) ?: episode.show?.images?.best(160),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(8.dp),
                placeholderIcon = Icons.Rounded.Podcasts,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrent) colors.primary else colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.show?.name?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (description.isNotEmpty()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isCurrent) NowPlayingBars(isPlaying = isPlaying, modifier = Modifier.size(14.dp))
                    if (played) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                    }
                    if (episode.explicit) ExplicitBadge()
                    DownloadIndicator(downloadState)
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!played && resume > 0 && episode.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { (resume.toFloat() / episode.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth(0.5f)
                            .height(3.dp)
                            .clip(CircleShape),
                        color = colors.primary,
                        trackColor = colors.surfaceVariant,
                        drawStopIndicator = {},
                    )
                }
            }
        }
        if (onMoreClick != null) {
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Rounded.MoreVert, contentDescription = moreLabel, tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** Placeholder icon and shape for a media type. */
private fun MediaType.placeholderIcon(): ImageVector = when (this) {
    MediaType.ARTIST -> Icons.Rounded.Person
    MediaType.ALBUM -> Icons.Rounded.Album
    MediaType.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
    MediaType.SHOW, MediaType.EPISODE -> Icons.Rounded.Podcasts
    MediaType.COLLECTION -> Icons.Rounded.Favorite
    MediaType.TRACK -> Icons.Rounded.MusicNote
}

/** Artwork for a [MediaRef]: circle for artists, gradient heart tile for Liked Songs without art. */
@Composable
fun MediaThumbnail(ref: MediaRef, modifier: Modifier = Modifier, cornerRadius: Dp = 4.dp) {
    val url = ref.images.best(300)
    when {
        ref.type == MediaType.COLLECTION && url == null -> LikedSongsTile(modifier, RoundedCornerShape(cornerRadius))
        ref.type == MediaType.ARTIST -> Artwork(url, null, modifier, CircleShape, Icons.Rounded.Person)
        else -> Artwork(url, null, modifier, RoundedCornerShape(cornerRadius), ref.type.placeholderIcon())
    }
}

/** Original Liked Songs tile (gradient + heart). */
@Composable
fun LikedSongsTile(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp)) {
    Box(modifier.clip(shape).background(AppColors.LikedGradient), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize(0.42f))
    }
}

/** A list row for an album/artist/playlist/show (artists get a circular image). */
@Composable
fun MediaRow(
    ref: MediaRef,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = ref.subtitle,
    onMoreClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val moreLabel = stringResource(R.string.shell_cd_more_options_for, ref.name)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick ?: onMoreClick,
                onLongClickLabel = if (onLongClick != null || onMoreClick != null) moreLabel else null,
            )
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = if (onMoreClick != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(ref, Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = ref.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        if (onMoreClick != null) {
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Rounded.MoreVert, contentDescription = moreLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A vertical tile (artwork + title + subtitle) for horizontal carousels and grids. */
@Composable
fun MediaCard(
    ref: MediaRef,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val isArtist = ref.type == MediaType.ARTIST
    val moreLabel = stringResource(R.string.shell_cd_more_options_for, ref.name)
    Column(
        modifier = modifier
            .width(size)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) moreLabel else null,
            )
            .padding(bottom = 6.dp),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        MediaThumbnail(
            ref = ref,
            modifier = Modifier
                .size(size)
                .then(if (isArtist) Modifier else Modifier.shadow(6.dp, RoundedCornerShape(6.dp))),
            cornerRadius = 6.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = ref.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
        if (!ref.subtitle.isNullOrBlank()) {
            Text(
                text = ref.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

/** A titled horizontally scrolling row of [MediaCard]s. */
@Composable
fun MediaCarousel(
    title: String,
    items: List<MediaRef>,
    onItemClick: (MediaRef) -> Unit,
    modifier: Modifier = Modifier,
    onItemLongClick: ((MediaRef) -> Unit)? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    val distinct = remember(items) { items.distinctBy { it.uri } }
    Column(modifier.fillMaxWidth()) {
        SectionHeader(
            title = title,
            action = onSeeAll?.let { seeAll ->
                {
                    TextButton(onClick = seeAll) {
                        Text(stringResource(R.string.shell_see_all), style = MaterialTheme.typography.labelLarge)
                    }
                }
            },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(distinct, key = { it.uri }, contentType = { it.type }) { ref ->
                MediaCard(
                    ref = ref,
                    onClick = { onItemClick(ref) },
                    onLongClick = onItemLongClick?.let { longClick -> { longClick(ref) } },
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = if (action != null) 8.dp else 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        action?.invoke()
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    // Short delay so fast (cached) loads do not flash a spinner.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(180)
        visible = true
    }
    val label = stringResource(R.string.shell_loading)
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(220))) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(32.dp)
                    .size(36.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.shell_retry))
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.shell_cd_explicit)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f))
            .padding(horizontal = 4.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "E",
            color = MaterialTheme.colorScheme.surface,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Download state indicator (nothing when null / not downloaded). */
@Composable
fun DownloadIndicator(state: DownloadState?, modifier: Modifier = Modifier) {
    DownloadIndicator(state = state, progress = null, modifier = modifier)
}

/** Download state indicator with optional determinate [progress] (0..1) while downloading. */
@Composable
fun DownloadIndicator(state: DownloadState?, progress: Float?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    when (state) {
        null, DownloadState.CANCELLED -> Unit
        DownloadState.QUEUED -> Icon(
            imageVector = Icons.Rounded.Schedule,
            contentDescription = stringResource(R.string.shell_cd_download_queued),
            tint = colors.onSurfaceVariant,
            modifier = modifier.size(16.dp),
        )
        DownloadState.PREPARING, DownloadState.DOWNLOADING -> {
            val label = stringResource(R.string.shell_cd_downloading)
            val m = modifier
                .size(16.dp)
                .padding(1.dp)
                .semantics { contentDescription = label }
            if (progress != null && state == DownloadState.DOWNLOADING) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = m,
                    color = colors.primary,
                    strokeWidth = 2.dp,
                    trackColor = colors.surfaceVariant,
                )
            } else {
                CircularProgressIndicator(modifier = m, color = colors.primary, strokeWidth = 2.dp)
            }
        }
        DownloadState.COMPLETED -> Icon(
            imageVector = Icons.Rounded.DownloadForOffline,
            contentDescription = stringResource(R.string.shell_cd_downloaded),
            tint = colors.primary,
            modifier = modifier.size(16.dp),
        )
        DownloadState.FAILED -> Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = stringResource(R.string.shell_cd_download_failed),
            tint = colors.error,
            modifier = modifier.size(16.dp),
        )
    }
}

/** Banner shown while offline (no network or offline mode). */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.shell_offline_banner), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Collapsing header used by album/playlist/artist/show pages: large artwork (or artist image),
 * title, subtitle lines, palette gradient background, and an action row (play/shuffle/download/
 * like/more) supplied by the caller.
 *
 * Edge-to-edge: place it as the first item of a full-window list. The gradient is drawn behind
 * the status bar (and beyond, for overscroll) while the content is padded by the status-bar inset
 * plus room for a transparent overlaid top bar; pair with [DetailTopBar] to show the title in the
 * bar once the header scrolls away.
 */
@Composable
fun DetailHeader(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    description: String? = null,
    circularImage: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalIsDarkTheme.current
    val dominant = rememberDominantColor(imageUrl)
    val tint by animateColorAsState(
        targetValue = (dominant ?: colors.surfaceContainerHighest).copy(alpha = if (dark) 0.9f else 0.45f),
        animationSpec = tween(600),
        label = "headerTint",
    )
    val background = colors.background
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val extra = 360.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to tint,
                        0.55f to tint.copy(alpha = tint.alpha * 0.55f),
                        1f to background,
                        startY = -extra,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, -extra),
                    size = Size(size.width, size.height + extra),
                )
            }
            // Edge-to-edge: the gradient (drawn above) fills the status-bar area; the content is
            // pushed below the status bar and the transparent back-button bar screens overlay.
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp)
            .padding(top = OVERLAID_TOP_BAR_SPACE, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = if (circularImage) CircleShape else RoundedCornerShape(6.dp)
        Artwork(
            url = imageUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .widthIn(max = 300.dp)
                .aspectRatio(1f)
                .shadow(elevation = 18.dp, shape = shape),
            shape = shape,
            placeholderIcon = if (circularImage) Icons.Rounded.Person else Icons.Rounded.MusicNote,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = if (title.length > 28) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (circularImage) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() },
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = if (circularImage) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            ExpandableDescription(description, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth()) { actions() }
    }
}

/** HTML-aware description, two lines until tapped; links open in-app when possible. */
@Composable
fun ExpandableDescription(html: String, modifier: Modifier = Modifier, collapsedLines: Int = 2) {
    val navigator = LocalOptionalAppNavigator.current
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.onSurface
    val text = remember(html, navigator, linkColor) {
        AnnotatedString.fromHtml(
            htmlString = html,
            linkStyles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)),
            linkInteractionListener = { link ->
                val url = (link as? LinkAnnotation.Url)?.url ?: return@fromHtml
                if (navigator?.openUri(url) != true) runCatching { uriHandler.openUri(url) }
            },
        )
    }
    var expanded by rememberSaveable(html) { mutableStateOf(false) }
    var overflowing by remember(html) { mutableStateOf(false) }
    val toggleLabel = stringResource(if (expanded) R.string.shell_show_less else R.string.shell_show_more)
    Column(modifier.animateContentSize()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        )
        if (overflowing || expanded) {
            Text(
                text = toggleLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/**
 * Top bar for detail pages: transparent over the [DetailHeader], fading to [containerColor] with
 * the [title] once the header has scrolled away.
 */
@Composable
fun DetailTopBar(
    title: String,
    listState: LazyListState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val thresholdPx = with(LocalDensity.current) { 220.dp.toPx() }
    val collapsed by remember(listState, thresholdPx) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > thresholdPx }
    }
    val progress by animateFloatAsState(if (collapsed) 1f else 0f, tween(220), label = "topBarCollapse")
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alpha(progress),
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.shell_cd_back))
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor.copy(alpha = containerColor.alpha * progress),
            scrolledContainerColor = containerColor,
        ),
    )
}

/** Round play/pause button (green) used on detail headers. */
@Composable
fun PlayFab(isPlaying: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(if (isPlaying) R.string.shell_cd_pause else R.string.shell_cd_play)
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(56.dp)
            .semantics { contentDescription = label },
        shape = CircleShape,
        color = AppColors.Brand,
        contentColor = AppColors.OnBrand,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Crossfade(targetState = isPlaying, animationSpec = tween(150), label = "playFab") { playing ->
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}
