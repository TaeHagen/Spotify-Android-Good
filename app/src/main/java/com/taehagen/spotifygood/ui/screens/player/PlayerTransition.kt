package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp

// Geometry of the expanding player (docs §9.9). One progress value p drives everything: 0 is the
// mini player docked above the navigation bar, 1 is full-screen Now Playing. All rects are in the
// player's coordinates (the window: the player is a full-window overlay). Kept free of Compose
// state so it is unit tested.

/** Where each part of the transition happens, as windows of p. */
internal object PlayerMotion {
    /** The mini player's title, artist and buttons are gone by here. */
    const val MINI_FADE_END = 0.3f
    /** Now Playing's title, seek bar, controls and actions fade and slide in from here to 1. */
    const val DETAILS_FADE_START = 0.4f
    /** Now Playing's top bar (collapse chevron, context) fades in from here to 1. */
    const val TOP_BAR_FADE_START = 0.5f
    /** Now Playing takes taps from here (a tap into a moving player does nothing). */
    const val NOW_PLAYING_INTERACTIVE = 0.95f
    /** Below this, Now Playing is hidden from accessibility services (the mini player is the target). */
    const val NOW_PLAYING_ACCESSIBLE = 0.5f
    /** A full predictive back swipe shows the player this far toward the mini player. */
    const val BACK_PREVIEW_RANGE = 0.35f
}

/** 0 up to [start], 1 from [end], linear in between: the opacity of something fading in over the window. */
internal fun fadeIn(p: Float, start: Float, end: Float): Float =
    if (end <= start) (if (p >= end) 1f else 0f) else ((p - start) / (end - start)).coerceIn(0f, 1f)

/** 1 up to [start], 0 from [end]: the opacity of something fading out over the window. */
internal fun fadeOut(p: Float, start: Float, end: Float): Float = 1f - fadeIn(p, start, end)

/**
 * The player surface at [p]: it grows from the docked card [collapsed] to [full]. While the card is
 * unknown (not measured yet) it grows from the window's bottom edge instead, so nothing shows at 0.
 */
internal fun surfaceBoundsAt(p: Float, collapsed: Rect, full: Rect): Rect {
    val start = if (collapsed.isEmpty) Rect(full.left, full.bottom, full.right, full.bottom) else collapsed
    return lerp(start, full, p.coerceIn(0f, 1f))
}

/** Corner radius of the surface: the card's at 0, square once it fills the window. */
internal fun surfaceCornerAt(p: Float, collapsedRadius: Float): Float = collapsedRadius * (1f - p.coerceIn(0f, 1f))

/**
 * The album art at [p], one element moving and scaling from the mini player's thumbnail [mini] to
 * Now Playing's art slot [large]. While one of them is unknown (Now Playing not laid out yet) the
 * art stays at the known one.
 */
internal fun artworkBoundsAt(p: Float, mini: Rect, large: Rect): Rect = when {
    large.isEmpty -> mini
    mini.isEmpty -> large
    else -> lerp(mini, large, p.coerceIn(0f, 1f))
}

/**
 * The thumbnail inside the docked card [card]: [size] square, [startInset] from the card's start
 * edge, centred in the card above its [bottomLine] (the progress line). Mirrors MiniPlayerBar's
 * layout, so the art's start rect needs no measuring through the card's moving layers.
 */
internal fun miniArtworkBounds(card: Rect, size: Float, startInset: Float, bottomLine: Float, rtl: Boolean): Rect {
    if (card.isEmpty) return Rect.Zero
    val top = card.top + (card.height - bottomLine - size) / 2f
    val left = if (rtl) card.right - startInset - size else card.left + startInset
    return Rect(left, top, left + size, top + size)
}

/**
 * Where a released drag settles: a fling at least [velocityThreshold] fast follows its direction,
 * anything slower settles at the nearer anchor (halfway counts as expanded). [velocity] is in px/s,
 * positive toward expanded.
 */
internal fun settlesExpanded(progress: Float, velocity: Float, velocityThreshold: Float): Boolean = when {
    velocity >= velocityThreshold -> true
    velocity <= -velocityThreshold -> false
    else -> progress >= 0.5f
}

/** The progress shown while a predictive back swipe is at [backProgress] (0..1): it shrinks toward the mini player. */
internal fun backPreviewProgress(backProgress: Float): Float =
    1f - PlayerMotion.BACK_PREVIEW_RANGE * backProgress.coerceIn(0f, 1f)

/**
 * The surface reaches under the status bar: its icons switch to light (the player is always dark)
 * once at most half of [statusBarHeight] still shows the page behind. [collapsedTop] is the docked
 * card's top (the surface's top edge moves from there to 0).
 */
internal fun surfaceCoversStatusBar(p: Float, collapsedTop: Float, statusBarHeight: Float): Boolean =
    p >= 1f || (1f - p) * collapsedTop <= statusBarHeight / 2f
