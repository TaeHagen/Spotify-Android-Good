package com.taehagen.spotifygood.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.Track

// Shared UI building blocks. Signatures are a contract used by every screen; implementations are
// owned by the UI shell (see docs/ARCHITECTURE.md §9.9). Keep parameters stable.

/** Cover art / artist image with placeholder, crossfade and Coil caching. */
@Composable
fun Artwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    placeholderIcon: ImageVector = Icons.Rounded.MusicNote,
) {
    Box(modifier)
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
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier) { Text(track.name) }
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
    onMoreClick: (() -> Unit)? = null,
) {
    Row(modifier) { Text(episode.name) }
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
    Row(modifier) { Text(ref.name) }
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
    Column(modifier) { Text(ref.name) }
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
    Column(modifier) { Text(title) }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Text(title, modifier)
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Text(message, modifier)
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Text(title, modifier)
}

@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Text("E", modifier)
}

/** Download state indicator (nothing when null / not downloaded). */
@Composable
fun DownloadIndicator(state: DownloadState?, modifier: Modifier = Modifier) {
    Box(modifier.size(16.dp))
}

/** Banner shown while offline (no network or offline mode). */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Text("Offline", modifier)
}

/**
 * Collapsing header used by album/playlist/artist/show pages: large artwork (or artist image),
 * title, subtitle lines, palette gradient background, and an action row (play/shuffle/download/
 * like/more) supplied by the caller.
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
    Column(modifier) { Text(title) }
}

/** Round play/pause button (green) used on detail headers. */
@Composable
fun PlayFab(isPlaying: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier)
}
