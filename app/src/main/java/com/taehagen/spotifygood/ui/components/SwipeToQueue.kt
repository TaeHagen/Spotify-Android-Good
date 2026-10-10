package com.taehagen.spotifygood.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.ui.navigation.LocalOptionalAppNavigator
import com.taehagen.spotifygood.ui.theme.AppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

// Swipe a track or episode row start→end to add it to the queue, as Spotify does
// (docs/ARCHITECTURE.md §9.9).

/** The swipe adds once it passes this share of the row's width... */
internal const val SWIPE_QUEUE_WIDTH_SHARE = 0.28f

/** ...or this many dp, whichever is smaller. */
internal const val SWIPE_QUEUE_MAX_THRESHOLD_DP = 96f

/** A fling this fast (dp/s) start→end counts with its projected distance ([swipeQueueCommits]). */
internal const val SWIPE_QUEUE_FLING_DP_PER_S = 800f

/** How far ahead a fling is projected (s). */
private const val FLING_PROJECTION_S = 0.15f

/** A fling still has to move at least this share of the threshold. */
private const val FLING_MIN_SHARE = 0.25f

/** Past the threshold the row follows the finger at this rate (it resists). */
private const val PAST_THRESHOLD_RATE = 0.35f

/** The row moves at most this share of its width. */
private const val MAX_SHARE = 0.6f

/** The distance (px) a row of [widthPx] must be swiped to add: the smaller of a share of it and [maxPx]. */
internal fun swipeQueueThreshold(widthPx: Float, maxPx: Float): Float = minOf(widthPx * SWIPE_QUEUE_WIDTH_SHARE, maxPx)

/**
 * Whether a released swipe adds to the queue: past [threshold] ([distance] px start→end), or a
 * fling start→end ([velocity] px/s, at least [flingVelocity]) whose projection reaches it after
 * moving a quarter of it. A slow or backwards release springs back with no action.
 */
internal fun swipeQueueCommits(distance: Float, velocity: Float, threshold: Float, flingVelocity: Float): Boolean {
    if (threshold <= 0f) return false
    if (distance >= threshold) return true
    return velocity >= flingVelocity && distance >= threshold * FLING_MIN_SHARE && distance + velocity * FLING_PROJECTION_S >= threshold
}

/** Where the row is drawn for a finger [distance] px start→end: 1:1 to the threshold, then resisting, at most [maxPx]. */
internal fun swipeQueueRowOffset(distance: Float, threshold: Float, maxPx: Float): Float {
    val d = distance.coerceAtLeast(0f)
    val shown = if (d <= threshold) d else threshold + (d - threshold) * PAST_THRESHOLD_RATE
    return shown.coerceAtMost(maxPx.coerceAtLeast(0f))
}

/**
 * Which rows offer the swipe: those the list allows ([allowed]: not the Queue screen, a playlist
 * in edit mode, a sheet's picker) that hold a playable item that can start now ([enabled]); not a
 * placeholder (metadata failed) or an unavailable one.
 */
internal fun swipeToQueueEligible(allowed: Boolean, placeholder: Boolean, playable: Boolean, enabled: Boolean): Boolean =
    allowed && !placeholder && playable && enabled

/**
 * Adds an item to the queue the way a row menu's "Add to queue" does ([MediaActionRunner.addToQueue]:
 * "Added to queue", a full queue, offline and unavailable items); null outside the app (previews).
 */
@Composable
internal fun rememberQueueAdd(): ((String) -> Unit)? {
    val context = LocalContext.current
    val app = context.applicationContext as? App ?: return null
    val navigator = LocalOptionalAppNavigator.current
    val activity = LocalActivity.current ?: context
    return remember(app, navigator, activity) {
        val runner = MediaActionRunner(app.graph, navigator, activity)
        val add: (String) -> Unit = { uri -> runner.addToQueue(listOf(uri)) }
        add
    }
}

/** The add of a row's [uri] to the queue when it offers the swipe ([eligible]), else null. */
@Composable
internal fun rememberRowQueueAdd(eligible: Boolean, uri: String): (() -> Unit)? {
    if (!eligible) return null
    val add = rememberQueueAdd() ?: return null
    return remember(add, uri) { { add(uri) } }
}

/** A row's swipe: how far it is drawn start→end (px) and the threshold it must pass. */
@Stable
internal class SwipeToQueueState(offset: Float = 0f) {
    var offset by mutableFloatStateOf(offset)
    var threshold by mutableFloatStateOf(0f)

    /** The swipe passed the threshold: letting go adds. */
    val past: Boolean get() = threshold > 0f && offset >= threshold
}

/**
 * A track or episode row that can be swiped start→end (mirrored right to left) to add its item to
 * the queue ([onQueue]; null: a plain row). The row ([content], given the modifier that slides it)
 * follows the finger over a green background with the queue icon on its leading side; past the
 * threshold ([swipeQueueThreshold]) the icon pops, the green deepens and a soft tick plays, and
 * letting go there (or flinging, [swipeQueueCommits]) adds and springs the row back; short of it,
 * it only springs back. The swipe locks after the touch slop: a mostly vertical move (the list
 * scrolls), a move end→start, or a press held for a long press leave the touch to the row and the
 * list, so taps and long presses work as before. The offset is read in layer and draw blocks only:
 * a swipe doesn't recompose the row.
 */
@Composable
fun SwipeToQueueRow(
    onQueue: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    SwipeToQueueRow(onQueue, modifier, previewOffset = null, content = content)
}

/** [SwipeToQueueRow] held at [previewOffset] (dp, start→end) for screenshots. */
@Composable
internal fun SwipeToQueueRow(
    onQueue: (() -> Unit)?,
    modifier: Modifier,
    previewOffset: Float?,
    content: @Composable (Modifier) -> Unit,
) {
    if (onQueue == null) {
        content(modifier)
        return
    }
    val density = LocalDensity.current
    val state = remember { SwipeToQueueState(with(density) { (previewOffset ?: 0f).dp.toPx() }) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sign = if (rtl) -1f else 1f
    val maxThresholdPx = with(density) { SWIPE_QUEUE_MAX_THRESHOLD_DP.dp.toPx() }
    val flingPx = with(density) { SWIPE_QUEUE_FLING_DP_PER_S.dp.toPx() }
    val queue by rememberUpdatedState(onQueue)
    val scope = rememberCoroutineScope()
    val springBack = remember { arrayOfNulls<Job>(1) }
    val slide = remember(state, sign) { Modifier.graphicsLayer { translationX = state.offset * sign } }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { state.threshold = swipeQueueThreshold(it.width.toFloat(), maxThresholdPx) }
            .pointerInput(sign) {
                swipeToQueueGestures(
                    state = state,
                    sign = sign,
                    flingVelocity = flingPx,
                    onStart = { springBack[0]?.cancel() },
                    onRelease = { commit ->
                        if (commit) queue()
                        springBack[0] = scope.launch {
                            animate(state.offset, 0f, animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                                state.offset = value
                            }
                        }
                    },
                )
            },
    ) {
        SwipeToQueueBackground(state, rtl, animated = previewOffset == null)
        content(slide)
    }
}

/** The green the swipe reveals, with the queue icon on the leading side, clipped to what the row uncovered. */
@Composable
private fun BoxScope.SwipeToQueueBackground(state: SwipeToQueueState, rtl: Boolean, animated: Boolean) {
    val past by remember(state) { derivedStateOf { state.past } }
    val green by animateColorAsState(
        if (past) AppColors.Brand else AppColors.Brand.copy(alpha = 0.55f),
        if (animated) tween(120) else snap(),
        label = "swipeQueueGreen",
    )
    val iconScale by animateFloatAsState(
        if (past) 1.25f else 1f,
        if (animated) spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium) else snap(),
        label = "swipeQueueIcon",
    )
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(past) {
        if (past) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
    Box(
        Modifier
            .matchParentSize()
            .drawWithContent {
                val shown = state.offset
                if (shown <= 0f) return@drawWithContent
                val left = if (rtl) size.width - shown else 0f
                clipRect(left = left, right = left + shown) {
                    drawRect(green)
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = null,
            tint = AppColors.OnBrand,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp)
                .size(26.dp)
                .graphicsLayer {
                    val reveal = if (state.threshold > 0f) (state.offset / (state.threshold * 0.7f)).coerceIn(0f, 1f) else 0f
                    alpha = reveal
                    scaleX = (0.7f + 0.3f * reveal) * iconScale
                    scaleY = scaleX
                },
        )
    }
}

/**
 * The swipe's touches: after the slop it locks to a start→end move that is mostly horizontal
 * (anything else, or no move before a long press, is left alone); the row then follows the
 * finger, and the release decides ([swipeQueueCommits]) with its distance and velocity.
 */
private suspend fun PointerInputScope.swipeToQueueGestures(
    state: SwipeToQueueState,
    sign: Float,
    flingVelocity: Float,
    onStart: () -> Unit,
    onRelease: (commit: Boolean) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var total = Offset.Zero
        val locked: Boolean? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var decided: Boolean? = null
            while (decided == null) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (change == null || change.changedToUpIgnoreConsumed() || change.isConsumed) {
                    decided = false
                } else {
                    total += change.positionChange()
                    if (total.getDistance() > slop) {
                        decided = swipeQueueLocks(total.x * sign, total.y)
                        if (decided) change.consume()
                    }
                }
            }
            decided
        }
        if (locked != true) return@awaitEachGesture
        onStart()
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)
        var distance = 0f
        val ended = drag(down.id) { change ->
            distance = (distance + change.positionChange().x * sign).coerceAtLeast(0f)
            velocity.addPosition(change.uptimeMillis, change.position)
            change.consume()
            state.offset = swipeQueueRowOffset(distance, state.threshold, size.width * MAX_SHARE)
        }
        val speed = velocity.calculateVelocity().x * sign
        onRelease(ended && swipeQueueCommits(distance, speed, state.threshold, flingVelocity))
    }
}

/** After the slop, a move locks to the swipe when it goes start→end ([forward] > 0) and is mostly horizontal. */
internal fun swipeQueueLocks(forward: Float, vertical: Float): Boolean = forward > 0f && abs(forward) > abs(vertical)
