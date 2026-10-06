package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import kotlinx.coroutines.flow.Flow

/** Mini player card: artwork colour background, swipe to skip, tap to expand. */
@Composable
internal fun MiniPlayerContent(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { graph -> PlayerViewModel(graph) }
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val liked by viewModel.isLiked.collectAsStateWithLifecycle()
    val indicator by viewModel.indicator.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                PlayerMessage.LIKE_FAILED -> navigator.showMessage(context.getString(R.string.player_like_failed))
            }
        }
    }

    val track = snapshot.track ?: return
    // Read only in the draw phase: the colour animates on every track change.
    val artworkColor = rememberArtworkColor(track.imageUrl)
    val restrictions = snapshot.restrictions
    val title = track.name ?: stringResource(R.string.player_track_loading)
    val artists = track.artistLine
    val accent = MaterialTheme.colorScheme.primary
    val nextLabel = stringResource(R.string.player_next)
    val previousLabel = stringResource(R.string.player_previous)
    val expandLabel = stringResource(R.string.player_open_now_playing)

    PlayerSurfaceTheme {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            contentColor = PlayerDefaults.PrimaryText,
            shadowElevation = 4.dp,
            modifier = modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier.drawBehind {
                    drawRect(artworkColor.value.toned(maxLightness = 0.24f, minLightness = 0.12f))
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    SwipeToSkipBox(
                        canNext = restrictions.canSkipNext,
                        canPrevious = restrictions.canSkipPrev,
                        onNext = viewModel::next,
                        onPrevious = viewModel::previous,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClickLabel = expandLabel, onClick = onExpand)
                            .semantics(mergeDescendants = true) {
                                customActions = buildList {
                                    if (restrictions.canSkipNext) add(CustomAccessibilityAction(nextLabel) { viewModel.next(); true })
                                    if (restrictions.canSkipPrev) add(CustomAccessibilityAction(previousLabel) { viewModel.previous(); true })
                                }
                            },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(
                                url = track.imageUrl,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            MiniPlayerText(title = title, artists = artists, indicator = indicator, accent = accent)
                        }
                    }
                    IconButton(onClick = navigator::openDevices) {
                        Icon(
                            imageVector = indicator.icon(),
                            contentDescription = stringResource(R.string.player_devices),
                            tint = if (indicator is DeviceIndicator.None) PlayerDefaults.PrimaryText else accent,
                        )
                    }
                    LikeButton(liked = liked, onToggle = viewModel::toggleLike)
                    PlayPauseButton(
                        status = snapshot.status,
                        onClick = viewModel::togglePlayPause,
                        enabled = !(snapshot.status == PlaybackStatus.PLAYING && !restrictions.canPause),
                        size = 48.dp,
                        filled = false,
                    )
                }
                MiniProgressLine(
                    position = viewModel.position,
                    initialPositionMs = viewModel::positionNow,
                    durationMs = snapshot.effectiveDurationMs(),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerText(title: String, artists: String, indicator: DeviceIndicator, accent: Color) {
    val indicatorText = when (indicator) {
        is DeviceIndicator.Remote -> stringResource(R.string.player_playing_on, indicator.name)
        is DeviceIndicator.LocalOutput -> indicator.name
        DeviceIndicator.None -> null
    }
    Column {
        if (indicatorText == null) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2_000),
            )
            if (artists.isNotEmpty()) {
                Text(
                    text = artists,
                    style = MaterialTheme.typography.bodySmall,
                    color = PlayerDefaults.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            val line = remember(title, artists) {
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
                    if (artists.isNotEmpty()) {
                        withStyle(SpanStyle(color = PlayerDefaults.SecondaryText)) { append(" • "); append(artists) }
                    }
                }
            }
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2_000),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(indicator.icon(), contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = indicatorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 2dp progress line; the position is only read in the draw phase (no recomposition per tick). */
@Composable
private fun MiniProgressLine(
    position: Flow<Long>,
    initialPositionMs: () -> Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val positionState = position.collectAsStateWithLifecycle(initialValue = remember(position) { initialPositionMs() })
    Canvas(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .clearAndSetSemantics {},
    ) {
        drawRect(PlayerDefaults.TrackInactive)
        if (durationMs > 0) {
            val fraction = (positionState.value.toFloat() / durationMs).coerceIn(0f, 1f)
            val width = size.width * fraction
            val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
            drawRect(Color.White, topLeft = Offset(left, 0f), size = Size(width, size.height))
        }
    }
}
