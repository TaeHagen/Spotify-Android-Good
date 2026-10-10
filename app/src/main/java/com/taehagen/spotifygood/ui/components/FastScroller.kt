package com.taehagen.spotifygood.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

// A fast scroller for long lists (docs/ARCHITECTURE.md §9.9): a pill on the right edge that
// seeks the whole list, rows not loaded yet included.

/** Lists shorter than this many screens have no fast scroller. */
internal const val FAST_SCROLL_MIN_SCREENS = 3f

/** How long the scroller stays after the list stopped moving. */
internal const val FAST_SCROLL_HIDE_MS = 1_500L

private val TouchWidth = 48.dp
private val TrackWidth = 3.dp
private val ThumbWidth = 6.dp
private val ThumbWidthDragging = 10.dp
private val MinThumbHeight = 48.dp
/** The thumb grabs touches this far above and below it, too (within its 48 dp strip). */
private val ThumbHitMargin = 24.dp
private val RailEndGap = 6.dp
private val RailWidth = 10.dp
private val DefaultRowHeight = 64.dp

/** A laid-out list item: its lazy index, offset and size along the list. */
internal data class FastScrollItem(val index: Int, val offset: Int, val size: Int)

/** Where to scroll for a fast-scroll position: a lazy item and how far into it. */
internal data class FastScrollTarget(val index: Int, val offset: Int)

/**
 * A list as the fast scroller sees it: [headerSizes] (one per item before the rows: header,
 * notices, filter), [contentCount] rows of about [rowPx] each (the whole list, rows not loaded
 * yet included), then [trailingPx] of footer. Sizes are measured items (remembered after they
 * scrolled away, [FastScrollSizes]); a row's is the average of those on screen, so the thumb
 * moves evenly with the rows and a position maps to the same row both ways.
 */
internal class FastScrollGeometry(
    val headerSizes: List<Int>,
    val rowPx: Float,
    val contentCount: Int,
    val trailingPx: Float,
    val viewportPx: Int,
    val paddingPx: Int,
) {
    val contentStart: Int get() = headerSizes.size
    val headerPx: Float = headerSizes.sum().toFloat()
    val totalPx: Float = headerPx + rowPx * contentCount + trailingPx + paddingPx

    /** How far the list scrolls. */
    val rangePx: Float = (totalPx - viewportPx).coerceAtLeast(0f)

    /** The share of the list on screen: the thumb's share of the track. */
    val visibleRatio: Float = if (totalPx <= 0f) 1f else (viewportPx / totalPx).coerceIn(0f, 1f)

    /** Long enough for a fast scroller ([FAST_SCROLL_MIN_SCREENS]). */
    val isLong: Boolean = viewportPx > 0 && contentCount > 0 && totalPx >= viewportPx * FAST_SCROLL_MIN_SCREENS

    /** Scroll offset of the start of lazy item [index]. */
    fun offsetOf(index: Int): Float {
        if (index < contentStart) return headerSizes.take(index).sum().toFloat()
        val row = index - contentStart
        return headerPx + rowPx * minOf(row, contentCount)
    }

    /**
     * The scrolled fraction (0 top, 1 end) with lazy item [firstIndex] first on screen, scrolled
     * [firstOffset] into it. [atTop] / [atBottom]: the list can't scroll further that way.
     */
    fun fractionOf(firstIndex: Int, firstOffset: Int, atTop: Boolean = false, atBottom: Boolean = false): Float = when {
        atTop || rangePx <= 0f -> 0f
        atBottom -> 1f
        else -> ((offsetOf(firstIndex) + firstOffset) / rangePx).coerceIn(0f, 1f)
    }

    /** Where to scroll to show [fraction] of the list (the inverse of [fractionOf]). */
    fun target(fraction: Float): FastScrollTarget {
        val f = fraction.coerceIn(0f, 1f)
        val lastIndex = contentStart + contentCount - 1
        // The end: the last row on top, which the list pulls down to its end.
        if (f >= 1f && contentCount > 0) return FastScrollTarget(lastIndex, 0)
        val offset = f * rangePx
        var start = 0f
        for ((index, size) in headerSizes.withIndex()) {
            if (offset < start + size) return FastScrollTarget(index, (offset - start).toInt())
            start += size
        }
        if (contentCount == 0 || rowPx <= 0f) return FastScrollTarget(contentStart, 0)
        val rows = (offset - headerPx) / rowPx
        val row = floor(rows).toInt().coerceIn(0, contentCount - 1)
        val into = ((rows - row) * rowPx).coerceIn(0f, rowPx * contentCount).toInt()
        return FastScrollTarget(contentStart + row, into)
    }

    /** The row at the top of the screen at [fraction]: what the bubble names. */
    fun rowAt(fraction: Float): Int {
        if (contentCount == 0) return 0
        if (rowPx <= 0f) return 0
        val rows = (fraction.coerceIn(0f, 1f) * rangePx - headerPx) / rowPx
        return floor(rows).toInt().coerceIn(0, contentCount - 1)
    }
}

/**
 * Sizes the scroller remembers of a list's items around its rows: header and footer items keep
 * their last measured size once scrolled away; rows count as the average of those on screen
 * (else the last average, else [defaultRowPx]).
 */
internal class FastScrollSizes(private val defaultRowPx: Float) {
    private val header = HashMap<Int, Int>()
    private val trailing = HashMap<Int, Int>()
    private var rowPx = defaultRowPx

    /** Measures [items] (on screen) and returns the geometry of the whole list. */
    fun geometry(
        items: List<FastScrollItem>,
        contentStart: Int,
        contentCount: Int,
        totalItems: Int,
        viewportPx: Int,
        paddingPx: Int,
    ): FastScrollGeometry {
        val contentEnd = contentStart + contentCount
        var sum = 0L
        var rows = 0
        for (item in items) {
            when {
                item.index < contentStart -> header[item.index] = item.size
                item.index >= contentEnd -> trailing[item.index - contentEnd] = item.size
                else -> {
                    sum += item.size
                    rows++
                }
            }
        }
        if (rows > 0) rowPx = sum.toFloat() / rows
        if (rowPx <= 0f) rowPx = defaultRowPx
        val trailingCount = (totalItems - contentEnd).coerceAtLeast(0)
        val trailingPx = (0 until trailingCount).sumOf { trailing[it] ?: 0 }.toFloat()
        return FastScrollGeometry(
            headerSizes = List(contentStart) { header[it] ?: 0 },
            rowPx = rowPx,
            contentCount = contentCount,
            trailingPx = trailingPx,
            viewportPx = viewportPx,
            paddingPx = paddingPx,
        )
    }
}

/** The thumb's top on a track of [trackPx] with a thumb of [thumbPx], at [fraction]. */
internal fun thumbTop(fraction: Float, trackPx: Float, thumbPx: Float): Float =
    ((trackPx - thumbPx).coerceAtLeast(0f) * fraction.coerceIn(0f, 1f))

/**
 * The thumb dragged [moved] px from where it was grabbed at [start]: it follows the finger 1:1 over
 * its [travel] (track minus thumb), held at the ends.
 */
internal fun draggedFraction(start: Float, moved: Float, travel: Float): Float =
    if (travel <= 0f) start.coerceIn(0f, 1f) else (start + moved / travel).coerceIn(0f, 1f)

/**
 * The part of the track (y, top to bottom) whose touches grab the scroller: the thumb and
 * [marginPx] above and below it, within the track. Its touch node is only this big, so a touch
 * anywhere else on the strip (a row's overflow button, a tap, a long press, a drag that scrolls
 * the list) goes to the rows underneath.
 */
internal fun thumbHitArea(thumbTop: Float, thumbHeight: Float, marginPx: Float, trackPx: Float): ClosedFloatingPointRange<Float> =
    (thumbTop - marginPx).coerceIn(0f, trackPx.coerceAtLeast(0f))..(thumbTop + thumbHeight + marginPx).coerceIn(0f, trackPx.coerceAtLeast(0f))

/** Whether a touch at [y] on the strip grabs the scroller ([thumbHitArea]); otherwise it passes through. */
internal fun fastScrollGrabs(y: Float, thumbTop: Float, thumbHeight: Float, marginPx: Float, trackPx: Float): Boolean =
    y in thumbHitArea(thumbTop, thumbHeight, marginPx, trackPx)

/** The thumb's height: the share of the list on screen, at least [minPx], at most the track. */
internal fun thumbHeight(visibleRatio: Float, trackPx: Float, minPx: Float): Float =
    (trackPx * visibleRatio).coerceAtLeast(minPx).coerceAtMost(trackPx.coerceAtLeast(0f))

// -- Bubble labels ------------------------------------------------------------------------------

/**
 * The bubble letter for a name sorted by it: its first character, accents dropped and upper case
 * ("élan" → E); a digit or a symbol is "#" (they sort first); blank → null.
 */
fun fastScrollLetter(name: String?, locale: Locale = Locale.getDefault()): String? {
    val text = name?.trim().orEmpty()
    if (text.isEmpty()) return null
    val first = String(Character.toChars(text.codePointAt(0)))
    val base = Normalizer.normalize(first, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    val code = if (base.isEmpty()) text.codePointAt(0) else base.codePointAt(0)
    return if (Character.isLetter(code)) String(Character.toChars(code)).uppercase(locale) else "#"
}

/** "1,234 / 5,000": the row [index] (from 0) of [total]. */
fun fastScrollPosition(index: Int, total: Int, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getIntegerInstance(locale)
    return "${format.format(index + 1L)} / ${format.format(total.toLong())}"
}

/** The month a row was added ([epochMs]), e.g. "Mar 2024" for the pattern "MMM yyyy". */
fun fastScrollDate(epochMs: Long, pattern: String, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** The month pattern of [locale] (e.g. "MMM yyyy", "yyyy年M月"). */
@Composable
fun rememberFastScrollDatePattern(): String {
    val locale = Locale.getDefault()
    return remember(locale) { android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMyyyy") }
}

// -- Composables --------------------------------------------------------------------------------

/**
 * A fast scroller over [listState]'s list, drawn in the parent's box (put it after the list): a
 * slim track and a pill thumb on the right edge between [topPadding] and [bottomPadding] (keep it
 * clear of the top bar, the mini player and the navigation bar). The rows are the lazy items
 * from [contentStart] on, [contentCount] of them (-1: all of them; count rows not loaded yet when
 * the list shows placeholders for them, so the thumb spans the whole list). It shows while the
 * list scrolls and fades [FAST_SCROLL_HIDE_MS] after; lists shorter than
 * [FAST_SCROLL_MIN_SCREENS] screens have none. Dragging the thumb seeks 1:1 with a bubble naming
 * the row on top ([label], null: none). Only the thumb, with [ThumbHitMargin] above and below it
 * in its 48 dp strip, takes touches, and only while it shows ([thumbHitArea]): every other touch
 * goes to the rows (overflow buttons, taps, long presses, list drags). TalkBack gets it as an
 * adjustable control with "scroll to top / bottom" actions.
 */
@Composable
fun FastScroller(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentStart: Int = 0,
    contentCount: Int = -1,
    enabled: Boolean = true,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    label: ((Int) -> String?)? = null,
) {
    FastScroller(listState, modifier, contentStart, contentCount, enabled, topPadding, bottomPadding, label, previewShown = false, previewDrag = null)
}

/**
 * [FastScroller] with its state set for screenshots: [previewShown] keeps it shown, [previewDrag]
 * holds the thumb at that fraction (the bubble shows).
 */
@Composable
internal fun FastScroller(
    listState: LazyListState,
    modifier: Modifier,
    contentStart: Int,
    contentCount: Int,
    enabled: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    label: ((Int) -> String?)?,
    previewShown: Boolean,
    previewDrag: Float?,
) {
    val density = LocalDensity.current
    val sizes = remember(listState) { FastScrollSizes(with(density) { DefaultRowHeight.toPx() }) }
    val start by rememberUpdatedState(contentStart)
    val count by rememberUpdatedState(contentCount)
    val geometry: State<FastScrollGeometry> = remember(listState, sizes) {
        derivedStateOf {
            val info = listState.layoutInfo
            val rows = if (count >= 0) count else (info.totalItemsCount - start).coerceAtLeast(0)
            sizes.geometry(
                items = info.visibleItemsInfo.map(LazyListItemInfo::toFastScrollItem),
                contentStart = start,
                contentCount = rows,
                totalItems = info.totalItemsCount,
                viewportPx = info.viewportSize.height,
                paddingPx = info.beforeContentPadding + info.afterContentPadding,
            )
        }
    }
    val listFraction: State<Float> = remember(listState, geometry) {
        derivedStateOf {
            geometry.value.fractionOf(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                atTop = !listState.canScrollBackward,
                atBottom = !listState.canScrollForward,
            )
        }
    }
    val long by remember(geometry) { derivedStateOf { geometry.value.isLong } }
    if (!enabled || !long) return

    var dragFraction by remember { mutableStateOf(previewDrag) }
    val dragging = dragFraction != null
    var trackPx by remember { mutableIntStateOf(0) }
    val minThumbPx = with(density) { MinThumbHeight.toPx() }
    val thumbPx: State<Float> = remember(geometry) {
        derivedStateOf { thumbHeight(geometry.value.visibleRatio, trackPx.toFloat(), minThumbPx) }
    }
    val fraction: () -> Float = { dragFraction ?: listFraction.value }
    val thumbTopPx: () -> Float = { thumbTop(fraction(), trackPx.toFloat(), thumbPx.value) }
    val hitMarginPx = with(density) { ThumbHitMargin.toPx() }

    // Shown while the list moves or the thumb is held, then fades out.
    var shown by remember { mutableStateOf(previewShown || previewDrag != null) }
    val active = listState.isScrollInProgress || dragging || previewShown
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(FAST_SCROLL_HIDE_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 120 else 300), label = "fastScrollAlpha")
    val thumbWidth by animateDpAsState(if (dragging) ThumbWidthDragging else ThumbWidth, tween(120), label = "fastScrollThumb")

    // The list follows the thumb (conflated: one scroll per frame at most).
    LaunchedEffect(listState) {
        snapshotFlow { dragFraction }.filterNotNull().collectLatest { f ->
            val target = geometry.value.target(f)
            listState.scrollToItem(target.index, target.offset)
        }
    }
    val scope = rememberCoroutineScope()
    val jump: (Float) -> Unit = { f ->
        shown = true
        scope.launch {
            val target = geometry.value.target(f)
            listState.scrollToItem(target.index, target.offset)
        }
    }

    // Bubble: the row on top while dragging; a soft tick when its letter changes.
    val bubbleRow by remember(geometry) { derivedStateOf { dragFraction?.let { geometry.value.rowAt(it) } } }
    val bubbleText = bubbleRow?.let { row -> label?.invoke(row) }
    val haptics = LocalHapticFeedback.current
    val lastLetter = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(bubbleText, dragging) {
        val letter = bubbleText?.takeIf { dragging && it.length <= 2 }
        val previous = lastLetter[0]
        if (letter != null && previous != null && letter != previous) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
        lastLetter[0] = letter
    }

    val description = stringResource(R.string.shell_fast_scroll)
    val toTop = stringResource(R.string.shell_fast_scroll_top)
    val toBottom = stringResource(R.string.shell_fast_scroll_bottom)
    // Coarse (5 %) for accessibility: the node isn't rebuilt on every frame of a scroll.
    val a11yFraction by remember(listFraction) { derivedStateOf { (listFraction.value * 20).roundToInt() / 20f } }
    val colors = MaterialTheme.colorScheme
    val thumbColor = if (dragging) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.85f)
    val trackColor = colors.onSurface.copy(alpha = 0.10f)

    Box(modifier.fillMaxSize().padding(top = topPadding, bottom = bottomPadding)) {
        // The strip lays out the track; it takes no touches itself (no pointer input).
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(TouchWidth)
                .onSizeChanged { trackPx = it.height },
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = RailEndGap)
                    .width(RailWidth)
                    .fillMaxHeight()
                    .graphicsLayer { this.alpha = alpha },
            ) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(TrackWidth)
                        .fillMaxHeight()
                        .background(trackColor, CircleShape),
                )
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .layout { measurable, _ ->
                            val w = thumbWidth.roundToPx()
                            val h = thumbPx.value.roundToInt().coerceAtLeast(0)
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(placeable.width, placeable.height) { placeable.place(0, thumbTopPx().roundToInt()) }
                        }
                        .background(thumbColor, CircleShape)
                        .semantics {
                            contentDescription = description
                            progressBarRangeInfo = ProgressBarRangeInfo(a11yFraction, 0f..1f)
                            setProgress { value ->
                                jump(value)
                                true
                            }
                            customActions = listOf(
                                CustomAccessibilityAction(toTop) { jump(0f); true },
                                CustomAccessibilityAction(toBottom) { jump(1f); true },
                            )
                        },
                )
            }
            // What grabs the scroller: the thumb and a margin around it, while it shows. Placed
            // over the thumb (it moves with it), so touches elsewhere on the strip reach the rows.
            if (shown || dragging) {
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val area = thumbHitArea(thumbTopPx(), thumbPx.value, hitMarginPx, trackPx.toFloat())
                            val height = (area.endInclusive - area.start).roundToInt().coerceAtLeast(0)
                            val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
                            layout(placeable.width, placeable.height) { placeable.place(0, area.start.roundToInt()) }
                        }
                        .pointerInput(listState) {
                            fastScrollDrag(
                                fraction = fraction,
                                travel = { (trackPx - thumbPx.value).coerceAtLeast(0f) },
                                onDrag = { dragFraction = it },
                                onDragEnd = { dragFraction = null },
                            )
                        },
                )
            }
        }
        // The bubble beside the thumb, kept on the track.
        Layout(
            content = {
                AnimatedVisibility(
                    visible = dragging && bubbleText != null,
                    enter = fadeIn(tween(100)) + scaleIn(initialScale = 0.8f),
                    exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.8f),
                ) {
                    FastScrollBubble(bubbleText.orEmpty())
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .padding(end = TouchWidth + 4.dp)
                .clearAndSetSemantics { },
        ) { measurables, constraints ->
            val placeable = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(placeable?.width ?: 0, constraints.maxHeight) {
                if (placeable != null) {
                    val center = thumbTopPx() + thumbPx.value / 2f
                    val y = (center - placeable.height / 2f).roundToInt().coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                    placeable.place(0, y)
                }
            }
        }
    }
}

private fun LazyListItemInfo.toFastScrollItem() = FastScrollItem(index, offset, size)

/**
 * The thumb's drag (its hit area only gets touches that start on or near it): grabbed at once,
 * then it follows the finger 1:1 ([draggedFraction]) from where it was. Moves are summed as
 * deltas, as the hit area moves with the thumb under the finger.
 */
private suspend fun PointerInputScope.fastScrollDrag(
    fraction: () -> Float,
    travel: () -> Float,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val start = fraction()
        var moved = 0f
        try {
            onDrag(start)
            drag(down.id) { change ->
                moved += change.positionChange().y
                change.consume()
                onDrag(draggedFraction(start, moved, travel()))
            }
        } finally {
            onDragEnd()
        }
    }
}

@Composable
private fun FastScrollBubble(text: String) {
    val letter = text.length <= 2
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
    ) {
        Box(
            Modifier
                .defaultMinSize(minWidth = 56.dp, minHeight = 48.dp)
                .padding(horizontal = if (letter) 12.dp else 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = if (letter) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * A row not loaded yet (placeholder while its page loads): a [TrackRow]'s size and layout in
 * quiet blocks, so the list keeps its length and the scroller its scale.
 */
@Composable
fun PlaceholderTrackRow(modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.surfaceContainerHigh
    val loading = stringResource(R.string.shell_loading_row)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics { contentDescription = loading }
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(block, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.55f).height(14.dp).background(block, RoundedCornerShape(4.dp)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.35f).height(12.dp).background(block.copy(alpha = 0.7f), RoundedCornerShape(4.dp)))
        }
    }
}

/**
 * Reports the rows on screen (indices from 0 at lazy item [contentStart], [contentCount] rows):
 * a paged list loads what is looked at ([onVisible] with the first and last row), also rows a
 * fast scroll jumped to.
 */
@Composable
fun VisibleRowsEffect(listState: LazyListState, contentStart: Int, contentCount: Int, onVisible: (first: Int, last: Int) -> Unit) {
    val callback by rememberUpdatedState(onVisible)
    LaunchedEffect(listState, contentStart, contentCount) {
        snapshotFlow { visibleRows(listState.layoutInfo.visibleItemsInfo.map(LazyListItemInfo::toFastScrollItem), contentStart, contentCount) }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (first, last) -> callback(first, last) }
    }
}

/** The first and last row among [items] (rows: lazy items [contentStart] on, [contentCount]). */
internal fun visibleRows(items: List<FastScrollItem>, contentStart: Int, contentCount: Int): Pair<Int, Int>? {
    val end = contentStart + contentCount
    val first = items.firstOrNull { it.index in contentStart until end }?.index ?: return null
    val last = items.lastOrNull { it.index in contentStart until end }?.index ?: return null
    return (first - contentStart) to (last - contentStart)
}
