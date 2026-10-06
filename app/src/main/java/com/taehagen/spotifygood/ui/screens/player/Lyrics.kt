package com.taehagen.spotifygood.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.Lyrics
import com.taehagen.spotifygood.model.LyricsSyncType
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.ErrorState
import com.taehagen.spotifygood.ui.components.LoadingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** User scrolling pauses auto-scroll for this long. */
private const val AUTO_SCROLL_RESUME_MS = 5_000L

/** Colours of a lyrics surface: Spotify's colours when provided, else the artwork colour. */
@Immutable
internal data class LyricsPalette(
    val background: Color,
    val highlight: Color,
    val upcoming: Color,
    val past: Color,
) {
    fun colorFor(index: Int, currentIndex: Int, synced: Boolean): Color = when {
        !synced -> highlight
        index == currentIndex -> highlight
        index < currentIndex -> past
        else -> upcoming
    }
}

@Composable
internal fun rememberLyricsPalette(lyrics: Lyrics?, fallback: Color): LyricsPalette {
    val colors = lyrics?.colors
    val background = colors?.background?.let { Color(opaqueArgb(it)) } ?: fallback.toned(maxLightness = 0.32f, minLightness = 0.14f)
    val highlight = colors?.highlightText?.let { Color(opaqueArgb(it)) } ?: Color.White
    val upcoming = colors?.text?.let { Color(opaqueArgb(it)) } ?: Color.White.copy(alpha = 0.6f)
    return remember(background, highlight, upcoming) {
        LyricsPalette(background = background, highlight = highlight, upcoming = upcoming, past = highlight.copy(alpha = 0.45f))
    }
}

@Composable
internal fun LyricsScreenContent(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { graph -> PlayerViewModel(graph) }
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyrics.collectAsStateWithLifecycle()
    // Single fine-grained ticker for the lines and the seek bar; stops when not visible.
    val position = viewModel.lyricsPosition.collectAsStateWithLifecycle(initialValue = remember { viewModel.positionNow() })
    val coverage = rememberWindowCoverage()
    BackHandler(enabled = coverage.coversWindow, onBack = onDismiss)

    val track = snapshot.track
    val artworkColor by rememberArtworkColor(track?.imageUrl)
    val loaded = (lyricsState as? LyricsState.Loaded)?.takeIf { it.trackUri == track?.uri }
    val palette = rememberLyricsPalette(loaded?.lyrics, artworkColor)
    val background by animateColorAsState(palette.background, animationSpec = tween(600), label = "lyricsBackground")

    PlayerSurfaceTheme {
        Column(
            modifier
                .then(coverage.modifier)
                .fillMaxSize()
                .background(background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            LyricsTopBar(track = track, onDismiss = onDismiss)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (val state = lyricsState) {
                    LyricsState.Loading -> LoadingState()
                    LyricsState.Unavailable -> EmptyState(
                        title = stringResource(R.string.player_lyrics_none),
                        message = stringResource(R.string.player_lyrics_none_message),
                        icon = Icons.Rounded.Lyrics,
                        modifier = Modifier.fillMaxSize(),
                    )
                    LyricsState.Error -> ErrorState(
                        message = stringResource(R.string.player_lyrics_error),
                        onRetry = viewModel::retryLyrics,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is LyricsState.Loaded -> if (state.lyrics.syncType == LyricsSyncType.UNSYNCED) {
                        UnsyncedLyrics(lyrics = state.lyrics, palette = palette)
                    } else {
                        SyncedLyrics(
                            lyrics = state.lyrics,
                            palette = palette,
                            position = position,
                            canSeek = snapshot.restrictions.canSeek,
                            onSeek = viewModel::seekTo,
                        )
                    }
                }
            }
            LyricsMiniControls(
                snapshot = snapshot,
                position = position,
                onSeek = viewModel::seekTo,
                onPlayPause = viewModel::togglePlayPause,
            )
        }
    }
}

@Composable
private fun LyricsTopBar(track: PlaybackTrack?, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(R.string.player_close),
                modifier = Modifier.size(32.dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { heading() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = track?.name.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2_000),
            )
            val artists = track?.artistLine.orEmpty()
            if (artists.isNotEmpty()) {
                Text(
                    text = artists,
                    style = MaterialTheme.typography.bodySmall,
                    color = PlayerDefaults.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Balances the close button so the title stays centred.
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: Lyrics,
    palette: LyricsPalette,
    position: State<Long>,
    canSeek: Boolean,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentIndex by remember(lyrics, position) {
        derivedStateOf { lyricsLineIndexAt(lyrics.lines, position.value) }
    }
    var autoScroll by remember { mutableStateOf(true) }

    // A user drag pauses auto-scroll; it resumes 5 s after the finger lifts.
    LaunchedEffect(listState) {
        var resume: Job? = null
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    resume?.cancel()
                    autoScroll = false
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    resume?.cancel()
                    resume = launch {
                        delay(AUTO_SCROLL_RESUME_MS)
                        autoScroll = true
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The current line sits at ~1/3 of the height: list items start below this padding.
        val anchor = maxHeight / 3
        val firstScroll = remember(lyrics) { booleanArrayOf(true) }
        LaunchedEffect(lyrics, currentIndex, autoScroll) {
            if (!autoScroll) return@LaunchedEffect
            val target = currentIndex.coerceAtLeast(0)
            if (firstScroll[0]) {
                firstScroll[0] = false
                listState.scrollToItem(target)
                return@LaunchedEffect
            }
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (visible != null) {
                listState.animateScrollBy(visible.offset.toFloat(), tween(durationMillis = 650, easing = FastOutSlowInEasing))
            } else {
                listState.animateScrollToItem(target)
            }
        }
        val seekLabel = stringResource(R.string.player_lyrics_seek_line)
        val instrumental = stringResource(R.string.player_lyrics_instrumental)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = anchor, bottom = maxHeight - anchor),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lyrics.lines, key = { index, _ -> index }, contentType = { _, _ -> "line" }) { index, line ->
                LyricLine(
                    text = line.words.ifBlank { instrumental },
                    color = palette.colorFor(index, currentIndex, synced = true),
                    onClickLabel = seekLabel,
                    onClick = if (canSeek) {
                        {
                            autoScroll = true
                            onSeek(line.startTimeMs)
                        }
                    } else {
                        null
                    },
                )
            }
            item(key = "provider", contentType = "footer") { ProviderFooter(lyrics.provider, palette) }
        }
        AnimatedVisibility(
            visible = !autoScroll,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        ) {
            Surface(
                onClick = { autoScroll = true },
                shape = RoundedCornerShape(50),
                color = palette.highlight,
                contentColor = palette.background,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.player_lyrics_resume_sync), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun LyricLine(text: String, color: Color, onClickLabel: String, onClick: (() -> Unit)?) {
    val animated by animateColorAsState(color, animationSpec = tween(durationMillis = 300), label = "lyricLine")
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = animated,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    )
}

@Composable
private fun UnsyncedLyrics(lyrics: Lyrics, palette: LyricsPalette) {
    val instrumental = stringResource(R.string.player_lyrics_instrumental)
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "note", contentType = "note") {
            Text(
                text = stringResource(R.string.player_lyrics_unsynced_note),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.highlight.copy(alpha = 0.75f),
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        itemsIndexed(lyrics.lines, key = { index, _ -> index }, contentType = { _, _ -> "line" }) { _, line ->
            Text(
                text = line.words.ifBlank { instrumental },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = palette.highlight,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        item(key = "provider", contentType = "footer") { ProviderFooter(lyrics.provider, palette) }
    }
}

@Composable
private fun ProviderFooter(provider: String?, palette: LyricsPalette) {
    if (provider.isNullOrBlank()) return
    Text(
        text = stringResource(R.string.player_lyrics_provided_by, provider),
        style = MaterialTheme.typography.labelMedium,
        color = palette.highlight.copy(alpha = 0.7f),
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 16.dp),
    )
}

@Composable
private fun LyricsMiniControls(
    snapshot: PlaybackSnapshot,
    position: State<Long>,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
) {
    val restrictions = snapshot.restrictions
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerSeekBar(
            position = position,
            durationMs = snapshot.effectiveDurationMs(),
            enabled = restrictions.canSeek,
            onSeek = onSeek,
            trackKey = snapshot.track?.uri,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(12.dp))
        PlayPauseButton(
            status = snapshot.status,
            onClick = onPlayPause,
            enabled = !(snapshot.status == PlaybackStatus.PLAYING && !restrictions.canPause),
            size = 56.dp,
        )
    }
}
