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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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

/** The scroll to the next line, full screen and in Now Playing's preview. */
private val LyricsScrollSpec = tween<Float>(durationMillis = 650, easing = FastOutSlowInEasing)

/** A line's fade between past, current and upcoming. */
private const val LYRICS_LINE_COLOR_MS = 300

/** Space above and below each line of the preview. */
private val PreviewLinePadding = 3.dp

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
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    val artworkColor by rememberArtworkColor(artwork)
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
                listState.animateScrollBy(visible.offset.toFloat(), LyricsScrollSpec)
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
    val animated by animateColorAsState(color, animationSpec = tween(durationMillis = LYRICS_LINE_COLOR_MS), label = "lyricLine")
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

/**
 * The synced lines of Now Playing's lyrics card: a clipped viewport of [lyricsPreviewRows]
 * single-line rows (so the card keeps its size) over the song's lines. The current line keeps the
 * row the window gives it ([lyricsPreviewTopLine]); as it advances the lines scroll up with the
 * full screen's motion, and each line's colour fades between past, current and upcoming. Larger
 * moves snap ([lyricsPreviewMove]); another song starts in place, with nothing carried over.
 *
 * The position is followed only while the lines can be seen: Now Playing shown (its details fade
 * in from [PlayerMotion.DETAILS_FADE_START]) and the viewport on screen (it sits below the first
 * screen of the scrolling layout). Otherwise nothing reads the position or moves, and on return
 * the lines snap or scroll to the line playing. In the background the position stops (it is
 * collected with the lifecycle), and so do frames.
 *
 * The viewport takes no touches: a tap reaches the card (the full lyrics), drags reach Now
 * Playing's scroll and the sheet. TalkBack reads the [lyricsPreviewWindow] lines through the card.
 */
@Composable
internal fun LyricsPreviewLines(lyrics: Lyrics, position: State<Long>, palette: LyricsPalette) {
    val lines = lyrics.lines
    val rows = lyricsPreviewRows(lines.size)
    if (rows == 0) return
    val sheet = LocalPlayerSheet.current
    val nowPlayingShown = remember(sheet) {
        derivedStateOf { sheet == null || sheet.progress > PlayerMotion.DETAILS_FADE_START }
    }
    val onScreen = remember { mutableStateOf(false) }
    val currentIndex = remember(lines, position, nowPlayingShown) {
        // Starts at the line playing (read without subscribing the composition to the position);
        // while the lines can't be seen it keeps the last one and reads nothing.
        var last = Snapshot.withoutReadObservation { lyricsLineIndexAt(lines, position.value) }
        derivedStateOf {
            if (nowPlayingShown.value && onScreen.value) lyricsLineIndexAt(lines, position.value).also { last = it } else last
        }
    }
    val index = currentIndex.value
    val topLine = lyricsPreviewTopLine(lines.size, index)
    val window = lyricsPreviewWindow(lines.size, index)
    val instrumental = stringResource(R.string.player_lyrics_instrumental)
    val spoken = remember(lines, window, instrumental) {
        window.map { AnnotatedString(lines[it].words.ifBlank { instrumental }) }
    }
    val typography = MaterialTheme.typography
    val style = remember(typography) { typography.titleLarge.copy(fontWeight = FontWeight.Bold) }
    // The lines use only these colours; the background can animate with the artwork.
    val linePalette = remember(palette.highlight, palette.upcoming, palette.past) {
        palette.copy(background = Color.Unspecified)
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 1)
    // One row of text, and a row of the viewport: the text with its padding.
    val rowText = remember(measurer, style) { measurer.measure("A", style).size.height }
    val rowHeight = rowText + 2 * with(density) { PreviewLinePadding.roundToPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(with(density) { (rowHeight * rows).toDp() })
            .onVisibilityChanged(minFractionVisible = 0f) { onScreen.value = it }
            // The window's lines, as the card read them before it scrolled (no list, no scroll actions).
            .clearAndSetSemantics { this[SemanticsProperties.Text] = spoken },
    ) {
        key(lines) {
            val listState = remember { LazyListState(firstVisibleItemIndex = topLine) }
            // The line the list rests on or is scrolling to.
            val target = remember { intArrayOf(topLine) }
            LaunchedEffect(topLine) {
                val from = target[0]
                target[0] = topLine
                when (lyricsPreviewMove(from, topLine)) {
                    LyricsPreviewMove.STAY -> Unit
                    LyricsPreviewMove.SNAP -> listState.scrollToItem(topLine)
                    LyricsPreviewMove.SCROLL -> {
                        val info = listState.layoutInfo
                        val line = info.visibleItemsInfo.firstOrNull { it.index == topLine }
                        if (line == null) {
                            listState.animateScrollToItem(topLine)
                        } else {
                            val last = info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }
                            val distance = lyricsPreviewScrollDistance(
                                lineTop = line.offset,
                                contentBottom = last?.let { it.offset + it.size },
                                viewportHeight = info.viewportSize.height,
                            )
                            if (distance != 0) listState.animateScrollBy(distance.toFloat(), LyricsScrollSpec)
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                overscrollEffect = null,
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(lines, key = { i, _ -> i }, contentType = { _, _ -> "line" }) { i, line ->
                    LyricsPreviewLine(
                        text = line.words.ifBlank { instrumental },
                        index = i,
                        currentIndex = currentIndex,
                        palette = linePalette,
                        style = style,
                        rowText = rowText,
                        rowHeight = rowHeight,
                    )
                }
            }
        }
    }
}

/**
 * One preview line, [lyricsPreviewLineRows] rows of [rowHeight] tall with its text centred (a
 * single row: [PreviewLinePadding] above and below). Its colour is read when drawing: a fade
 * redraws the line, nothing more.
 */
@Composable
private fun LyricsPreviewLine(
    text: String,
    index: Int,
    currentIndex: State<Int>,
    palette: LyricsPalette,
    style: TextStyle,
    rowText: Int,
    rowHeight: Int,
) {
    // Recomposes only when this line becomes current or past, not on every line change.
    val target by remember(index, currentIndex, palette) {
        derivedStateOf { palette.colorFor(index, currentIndex.value, synced = true) }
    }
    val color = animateColorAsState(target, animationSpec = tween(durationMillis = LYRICS_LINE_COLOR_MS), label = "lyricsPreviewLine")
    BasicText(
        text = text,
        style = style,
        color = { color.value },
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val height = maxOf(placeable.height, lyricsPreviewLineRows(placeable.height, rowText) * rowHeight)
            layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
        },
    )
}
