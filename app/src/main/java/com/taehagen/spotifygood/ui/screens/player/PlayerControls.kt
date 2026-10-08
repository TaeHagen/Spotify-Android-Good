package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.LaptopChromebook
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.DeviceType
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.RepeatMode
import com.taehagen.spotifygood.playback.OutputKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// ---------------------------------------------------------------------------------------------
// Transport buttons
// ---------------------------------------------------------------------------------------------

/** Play/pause; a white disc when [filled] (now playing), a plain icon otherwise (mini player). */
@Composable
internal fun PlayPauseButton(
    status: PlaybackStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 64.dp,
    filled: Boolean = true,
    containerColor: Color = Color.White,
    iconColor: Color = Color.Black,
) {
    val playing = status == PlaybackStatus.PLAYING
    val loading = status == PlaybackStatus.LOADING
    val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    val state = stringResource(
        when {
            loading -> R.string.player_state_loading
            playing -> R.string.player_state_playing
            else -> R.string.player_state_paused
        },
    )
    val contentColor = if (filled) iconColor else LocalContentColor.current
    val alpha = if (enabled) 1f else 0.38f
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(containerColor.copy(alpha = containerColor.alpha * alpha)) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * if (filled) 0.42f else 0.5f),
                color = contentColor.copy(alpha = alpha),
                strokeWidth = 2.5.dp,
            )
        } else {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = contentColor.copy(alpha = alpha),
                modifier = Modifier.size(size * if (filled) 0.56f else 0.6f),
            )
        }
    }
}

@Composable
internal fun SkipButton(next: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, iconSize: Dp = 36.dp) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(maxOf(iconSize + 12.dp, 48.dp))) {
        Icon(
            imageVector = if (next) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious,
            contentDescription = stringResource(if (next) R.string.player_next else R.string.player_previous),
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Podcast skip back / forward by [SEEK_STEP_MS]: a circular arrow (mirrored for forward) with the
 * step in seconds inside (Material has no 15 s icon).
 */
@Composable
internal fun SeekStepButton(forward: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, iconSize: Dp = 36.dp) {
    val seconds = (SEEK_STEP_MS / 1000).toInt()
    val label = stringResource(if (forward) R.string.player_seek_forward_step else R.string.player_seek_back_step, seconds)
    // Sized with the icon, independent of the font scale (the number must fit inside the arrow).
    val numberSize = with(LocalDensity.current) { (iconSize * 0.3f).toSp() }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(maxOf(iconSize + 12.dp, 48.dp))
            .semantics { contentDescription = label },
    ) {
        Box(Modifier.size(iconSize).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Replay,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { if (forward) scaleX = -1f },
            )
            Text(
                text = seconds.toString(),
                fontSize = numberSize,
                fontWeight = FontWeight.Bold,
                color = LocalContentColor.current,
                // The arrow's circle is centred slightly below the icon's centre.
                modifier = Modifier.offset(y = iconSize / 24),
            )
        }
    }
}

/** Shuffle with three states: off, shuffle, smart shuffle (sparkle badge). */
@Composable
internal fun ShuffleButton(
    shuffle: Boolean,
    smart: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.player_shuffle)
    val state = stringResource(
        when {
            smart -> R.string.player_shuffle_state_smart
            shuffle -> R.string.player_shuffle_state_on
            else -> R.string.player_shuffle_state_off
        },
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
    ) {
        ModeIcon(Icons.Rounded.Shuffle, active = shuffle || smart, enabled = enabled, sparkle = smart)
    }
}

/** Repeat with three states: off, context, track ("1" glyph). */
@Composable
internal fun RepeatButton(mode: RepeatMode, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.player_repeat)
    val state = stringResource(
        when (mode) {
            RepeatMode.OFF -> R.string.player_repeat_state_off
            RepeatMode.CONTEXT -> R.string.player_repeat_state_all
            RepeatMode.TRACK -> R.string.player_repeat_state_one
        },
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
    ) {
        ModeIcon(
            icon = if (mode == RepeatMode.TRACK) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            active = mode != RepeatMode.OFF,
            enabled = enabled,
        )
    }
}

@Composable
private fun ModeIcon(icon: ImageVector, active: Boolean, enabled: Boolean, sparkle: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
    Box(contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (active) accent else LocalContentColor.current)
        if (sparkle) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(12.dp),
            )
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 8.dp)
                    .size(4.dp)
                    .background(accent, CircleShape),
            )
        }
    }
}

/**
 * Heart toggle (Liked Songs) with a small bounce when liked. [liked] null (not known, e.g.
 * the lookup failed offline) shows an empty heart, disabled: a tap must not act on a guess.
 */
@Composable
internal fun LikeButton(liked: Boolean?, onToggle: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val accent = MaterialTheme.colorScheme.primary
    val isLiked = liked == true
    val label = stringResource(R.string.player_like)
    val state = stringResource(if (isLiked) R.string.player_like_state_on else R.string.player_like_state_off)
    val scale = remember { Animatable(1f) }
    val previous = remember { booleanArrayOf(isLiked) }
    LaunchedEffect(isLiked) {
        if (isLiked && !previous[0]) {
            scale.snapTo(0.7f)
            scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
        }
        previous[0] = isLiked
    }
    IconToggleButton(
        checked = isLiked,
        onCheckedChange = { onToggle() },
        enabled = enabled && liked != null,
        modifier = modifier
            .size(48.dp)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
    ) {
        Icon(
            imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = null,
            tint = if (isLiked) accent else LocalContentColor.current,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Seek bar / volume
// ---------------------------------------------------------------------------------------------

/**
 * Seek bar fed by a position ticker state (collected with lifecycle by the caller, so it stops when
 * the screen is not visible). Dragging previews the target time; the seek is committed on release,
 * and the thumb holds the target until the engine reports it.
 */
@Composable
internal fun PlayerSeekBar(
    position: State<Long>,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    trackKey: String? = null,
    showTimes: Boolean = true,
) {
    val positionMs by position
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var pendingSeekMs by remember(trackKey) { mutableStateOf<Long?>(null) }
    val pending = pendingSeekMs
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(2_500)
            pendingSeekMs = null
        }
    }
    // Hold the committed target until the reported position gets close to it (or the timeout).
    val heldMs = pending?.takeIf { abs(positionMs - it) >= 1_500 }
    val duration = durationMs.coerceAtLeast(0)
    val shownMs = when (val drag = dragFraction) {
        null -> heldMs ?: positionMs
        else -> (drag * duration).toLong()
    }.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
    val fraction = if (duration > 0) (shownMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val seekEnabled = enabled && duration > 0
    val elapsedText = formatPlaybackTime(shownMs)
    val durationText = formatPlaybackTime(duration)
    val label = stringResource(R.string.player_seek)
    val state = stringResource(R.string.player_seek_state, elapsedText, durationText)

    Column(modifier) {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { f ->
                    val target = (f * duration).toLong()
                    pendingSeekMs = target
                    onSeek(target)
                }
                dragFraction = null
            },
            enabled = seekEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = state
                },
            thumb = { SeekThumb(seekEnabled, dragging = dragFraction != null) },
            track = { sliderState -> ThinTrack(sliderState, seekEnabled) },
        )
        if (showTimes) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .offset(y = (-8).dp)
                    .clearAndSetSemantics {},
            ) {
                val timeColor = LocalContentColor.current.copy(alpha = 0.7f)
                Text(elapsedText, style = MaterialTheme.typography.labelSmall, color = timeColor)
                Spacer(Modifier.weight(1f))
                Text(durationText, style = MaterialTheme.typography.labelSmall, color = timeColor)
            }
        }
    }
}

@Composable
private fun SeekThumb(enabled: Boolean, dragging: Boolean) {
    val size = if (dragging) 16.dp else 12.dp
    Box(
        Modifier
            .size(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) {
            Box(
                Modifier
                    .size(size)
                    .background(LocalContentColor.current, CircleShape),
            )
        }
    }
}

@Composable
private fun ThinTrack(state: SliderState, enabled: Boolean) {
    val content = LocalContentColor.current
    val active = if (enabled) content else content.copy(alpha = 0.5f)
    val inactive = content.copy(alpha = 0.24f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        val y = size.height / 2
        val stroke = size.height
        val activeWidth = size.width * state.coercedValueAsFraction
        drawLine(inactive, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
        if (activeWidth > 0f) {
            if (layoutDirection == LayoutDirection.Rtl) {
                drawLine(active, Offset(size.width, y), Offset(size.width - activeWidth, y), strokeWidth = stroke, cap = StrokeCap.Round)
            } else {
                drawLine(active, Offset(0f, y), Offset(activeWidth, y), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * Connect volume (0..65535) slider. The dragged value is shown until the device reports it back
 * (or a short timeout), so the thumb does not jump while the throttled commands catch up.
 */
@Composable
internal fun VolumeSlider(
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var heldFraction by remember { mutableStateOf<Float?>(null) }
    val held = heldFraction
    LaunchedEffect(volume, held) {
        if (held != null && volumeSettled(volume, held)) heldFraction = null
    }
    LaunchedEffect(held) {
        if (held != null) {
            delay(2_500)
            heldFraction = null
        }
    }
    val fraction = dragFraction ?: held ?: volumeToFraction(volume)
    val label = stringResource(R.string.player_volume)
    val state = stringResource(R.string.player_volume_state, volumePercent(fractionToVolume(fraction)))
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Rounded.VolumeDown,
            contentDescription = null,
            tint = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
        Slider(
            value = fraction,
            onValueChange = {
                dragFraction = it
                onVolumeChange(fractionToVolume(it))
            },
            onValueChangeFinished = {
                heldFraction = dragFraction
                dragFraction = null
            },
            enabled = enabled,
            interactionSource = interaction,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = state
                },
            thumb = { SeekThumb(enabled, dragging = dragged || pressed) },
            track = { sliderState -> ThinTrack(sliderState, enabled) },
        )
        Icon(
            Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Gestures
// ---------------------------------------------------------------------------------------------

/**
 * Horizontal swipe to skip: drag toward the start edge for next, toward the end edge for previous
 * (mirrored in RTL). Passing the threshold or flinging slides the content out and back in.
 */
@Composable
internal fun SwipeToSkipBox(
    canNext: Boolean,
    canPrevious: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val nextAllowed by rememberUpdatedState(canNext)
    val previousAllowed by rememberUpdatedState(canPrevious)
    Box(
        modifier = modifier.pointerInput(rtl) {
            val tracker = VelocityTracker()
            // Positive "forward" = toward the next item.
            fun forwardOf(x: Float) = if (rtl) x else -x
            detectHorizontalDragGesturesCompat(
                onDragStart = { tracker.resetTracking() },
                onDrag = { change, delta ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch {
                        // Read the offset inside the coroutine so queued events never lose a delta.
                        val proposed = offset.value + delta
                        val forward = forwardOf(proposed)
                        val blocked = (forward > 0 && !nextAllowed) || (forward < 0 && !previousAllowed)
                        offset.snapTo(if (blocked) offset.value + delta * 0.25f else proposed)
                    }
                },
                onDragEnd = {
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val velocity = tracker.calculateVelocity().x
                    scope.launch {
                        // Runs after the queued drag updates, so the offset is final here.
                        val x = offset.value
                        val flung = abs(velocity) > 1_000f && sign(velocity) == sign(x)
                        val passed = abs(x) > width * 0.3f || flung
                        val forward = forwardOf(x)
                        when {
                            passed && forward > 0 && nextAllowed -> {
                                offset.animateTo(sign(x) * width, tween(160))
                                next()
                                offset.snapTo(-sign(x) * width * 0.3f)
                                offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                            passed && forward < 0 && previousAllowed -> {
                                offset.animateTo(sign(x) * width, tween(160))
                                previous()
                                offset.snapTo(-sign(x) * width * 0.3f)
                                offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                            else -> offset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                        }
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f) } },
            )
        },
    ) {
        Box(
            Modifier.graphicsLayer {
                translationX = offset.value
                val width = size.width.coerceAtLeast(1f)
                alpha = 1f - (abs(offset.value) / width * 0.7f).coerceIn(0f, 0.7f)
            },
            content = content,
        )
    }
}

private suspend fun PointerInputScope.detectHorizontalDragGesturesCompat(
    onDragStart: () -> Unit,
    onDrag: (PointerInputChange, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) = detectHorizontalDragGestures(
    onDragStart = { onDragStart() },
    onDragEnd = onDragEnd,
    onDragCancel = onDragCancel,
    onHorizontalDrag = { change, delta ->
        change.consume()
        onDrag(change, delta)
    },
)

// ---------------------------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------------------------

internal fun DeviceType.icon(isGroup: Boolean = false): ImageVector = when {
    isGroup -> Icons.Rounded.SpeakerGroup
    else -> when (this) {
        DeviceType.SMARTPHONE -> Icons.Rounded.Smartphone
        DeviceType.COMPUTER -> Icons.Rounded.Computer
        DeviceType.TABLET -> Icons.Rounded.Tablet
        DeviceType.SPEAKER -> Icons.Rounded.Speaker
        DeviceType.TV -> Icons.Rounded.Tv
        DeviceType.AVR -> Icons.Rounded.SurroundSound
        DeviceType.STB -> Icons.Rounded.SettingsInputHdmi
        DeviceType.AUDIO_DONGLE -> Icons.Rounded.Speaker
        DeviceType.GAME_CONSOLE -> Icons.Rounded.SportsEsports
        DeviceType.CAST_AUDIO, DeviceType.CAST_VIDEO -> Icons.Rounded.Cast
        DeviceType.AUTOMOBILE -> Icons.Rounded.DirectionsCar
        DeviceType.SMARTWATCH -> Icons.Rounded.Watch
        DeviceType.CHROMEBOOK -> Icons.Rounded.LaptopChromebook
        DeviceType.UNKNOWN -> Icons.Rounded.DevicesOther
    }
}

internal fun OutputKind.icon(): ImageVector = when (this) {
    OutputKind.SPEAKER -> Icons.Rounded.Smartphone
    OutputKind.WIRED -> Icons.Rounded.Headphones
    OutputKind.BLUETOOTH -> Icons.Rounded.Bluetooth
    OutputKind.USB -> Icons.Rounded.Usb
    OutputKind.HDMI -> Icons.Rounded.SettingsInputHdmi
    OutputKind.CAR -> Icons.Rounded.DirectionsCar
    OutputKind.HEARING_AID -> Icons.Rounded.Hearing
    OutputKind.OTHER -> Icons.Rounded.Speaker
}

internal fun DeviceIndicator.icon(): ImageVector = when (this) {
    is DeviceIndicator.Remote -> type.icon()
    is DeviceIndicator.LocalOutput -> kind.icon()
    DeviceIndicator.None -> Icons.Rounded.Devices
}
