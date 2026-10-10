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
