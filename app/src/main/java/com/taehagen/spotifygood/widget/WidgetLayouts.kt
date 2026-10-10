package com.taehagen.spotifygood.widget

import kotlin.math.ceil
import kotlin.math.floor

/** A widget size in dp. */
internal data class WidgetSize(val width: Float, val height: Float) {
    /** Like `RemoteViews.fitsIn`: whole dp, this rounded down against [other] rounded up. */
    fun fitsIn(other: WidgetSize): Boolean = floor(width) <= ceil(other.width) && floor(height) <= ceil(other.height)

    fun squareDistance(other: WidgetSize): Float {
        val dw = width - other.width
        val dh = height - other.height
        return dw * dw + dh * dh
    }
}

/**
 * The widget's responsive layouts (docs/ARCHITECTURE.md §9.4), each with the smallest size it is
 * made for. Android 12+ gets all of them in one sized RemoteViews and the launcher shows the best
 * fit (also while resizing); below Android 12 [bestFit] picks the same one for the portrait and
 * the landscape size the launcher reports. On a phone in portrait n × m cells are about
 * (73n − 16) × (118m − 16) dp: 2x1 ≈ 130 × 102, 2x2 ≈ 130 × 220, 4x1 ≈ 276 × 102,
 * 4x2 ≈ 276 × 220; in landscape about (142n − 15) × (66m − 15).
 */
internal enum class WidgetLayout(val minSize: WidgetSize) {
    /** Art filling the widget, play/pause on it (2x1). */
    SMALL(WidgetSize(40f, 40f)),

    /** Art with play/pause below it (2x2). */
    SMALL_TALL(WidgetSize(100f, 170f)),

    /** One row: art, title and artist, play/pause (3x1). */
    ROW(WidgetSize(190f, 40f)),

    /** One row with the skips (4x1 in landscape). */
    ROW_FULL(WidgetSize(250f, 40f)),

    /** Art beside title, artist and the skips around play/pause (4x1). */
    STACKED(WidgetSize(250f, 96f)),

    /** Art across the top, title, artist, device and the controls (3x2). */
    TALL_NARROW(WidgetSize(180f, 170f)),

    /** Large art beside title, artist and device; the controls with like and shuffle below (4x2). */
    LARGE(WidgetSize(250f, 170f)),

    /** [TALL_NARROW] with like and shuffle (4x3 and taller). */
    TALL(WidgetSize(250f, 300f)),
    ;

    /** Title and artist (the small layouts carry them as the content description). */
    val showsText: Boolean get() = this != SMALL && this != SMALL_TALL

    /** Previous / next, or −15 s / +15 s for an episode. */
    val showsSkips: Boolean get() = showsText && this != ROW

    /** The large layouts: "Playing on <device>" for another Connect device and a row of controls. */
    val isLarge: Boolean get() = this == TALL_NARROW || this == LARGE || this == TALL

    /** Like and shuffle in the control row. */
    val showsExtras: Boolean get() = this == LARGE || this == TALL

    /** A one-row shape (the sign-in prompt lays out in a row there). */
    val isRow: Boolean get() = this == ROW || this == ROW_FULL || this == STACKED

    /**
     * The side (dp) of the square the artwork is drawn in at [size], from the layout's padding,
     * maximum artwork size and what sits beside or below it (layout/widget_np_*.xml). [SMALL]
     * crops it to fill the widget, so its longer side counts; the tall layouts count the text and
     * device line as at their tallest.
     */
    fun artDp(size: WidgetSize): Float = when (this) {
        SMALL -> maxOf(size.width, size.height)
        SMALL_TALL -> minOf(size.width - 16, size.height - 72)
        ROW, ROW_FULL -> minOf(size.height - 8, 72f)
        STACKED -> minOf(size.height - 16, 112f)
        LARGE -> minOf(size.height - 80, 120f)
        TALL_NARROW, TALL -> minOf(size.width - 24, size.height - 129)
    }.coerceAtLeast(0f)

    companion object {
        /** 4x2 in portrait, the widget's default size. */
        val DEFAULT_SIZE = WidgetSize(276f, 220f)

        /**
         * The layout the launcher shows at [size] (`RemoteViews.findBestFitLayout`): of those that
         * fit, the one closest to it; the smallest when none fits.
         */
        fun bestFit(size: WidgetSize): WidgetLayout =
            entries.filter { it.minSize.fitsIn(size) }.minByOrNull { it.minSize.squareDistance(size) }
                ?: entries.minBy { it.minSize.width * it.minSize.height }

        /**
         * Below Android 12: the portrait (min width × max height) and landscape (max width × min
         * height) sizes from the widget's options, or null when the launcher reported none.
         */
        fun orientationSizes(minWidth: Int, minHeight: Int, maxWidth: Int, maxHeight: Int): Pair<WidgetSize, WidgetSize>? {
            if (minWidth <= 0 || minHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) return null
            return WidgetSize(minWidth.toFloat(), maxHeight.toFloat()) to WidgetSize(maxWidth.toFloat(), minHeight.toFloat())
        }
    }
}

/**
 * How large the one artwork bitmap all widgets share is decoded (pure): for the largest artwork
 * any placed widget shows at any of its sizes, rounded up to a step so that the image cache key
 * stays the same while the sizes move a little.
 */
internal object WidgetArtSize {
    /** Square sides in px; the CDN's covers are 300 and 640 px. */
    val STEPS = listOf(256, 384, 512, 640)

    /**
     * The step for widgets of [sizes] (dp) at [density]: the smallest one covering the largest
     * artwork their layouts show ([WidgetLayout.artDp]), at most the largest step whose ARGB
     * bitmap fits [budgetBytes]; the smallest step without a widget size.
     */
    fun px(sizes: List<WidgetSize>, density: Float, budgetBytes: Long): Int {
        val needed = sizes.maxOfOrNull { WidgetLayout.bestFit(it).artDp(it) * density } ?: 0f
        val allowed = STEPS.filter { it.toLong() * it * BYTES_PER_PIXEL <= budgetBytes }.ifEmpty { STEPS.take(1) }
        return allowed.firstOrNull { it >= needed } ?: allowed.last()
    }

    /**
     * The artwork's share of what a widget update may carry in bitmaps (the system's limit is the
     * screen's w × h × 4 bytes × 1.5, for all views of an update together): a quarter of it.
     */
    fun budgetBytes(screenWidthPx: Int, screenHeightPx: Int): Long =
        screenWidthPx.toLong() * screenHeightPx * BYTES_PER_PIXEL * 3 / 2 / 4

    private const val BYTES_PER_PIXEL = 4
}
