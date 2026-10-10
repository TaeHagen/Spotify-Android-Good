package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.transitionFactory
import coil3.transition.CrossfadeTransition
import coil3.transition.Transition
import coil3.transition.TransitionTarget
import com.taehagen.spotifygood.ui.components.imageData
import kotlin.math.roundToInt

/** The two anchors of the expanding player. */
internal enum class PlayerSheetValue { Collapsed, Expanded }

/** The docked card: margins in the bottom stack, corners, elevation, and its default height. */
internal val PlayerCardHorizontalMargin = 8.dp
internal val PlayerCardVerticalMargin = 4.dp
private val PlayerCardCorner = 8.dp
private val PlayerCardElevation = 4.dp
/** The card at font scale 1: a 56 dp row and the 2 dp progress line (MiniPlayerBar). */
private val PlayerCardDefaultHeight = 58.dp

/** The mini player's thumbnail inside the card (MiniPlayerBar lays it out this way). */
internal val MiniArtworkSize = 40.dp
internal val MiniArtworkStartInset = 8.dp
internal val MiniProgressLineHeight = 2.dp
internal val MiniArtworkCorner = 4.dp

/** Now Playing's art. */
internal val LargeArtworkCorner = 8.dp
internal val LargeArtworkElevation = 24.dp

/** Releases at least this fast follow their direction (slower ones settle at the nearer anchor). */
private val SettleVelocity = 300.dp

/** Critically damped: lands on the anchor without bouncing past it. */
private val SettleSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = 0.5f,
)

/**
 * State of the expanding player (docs §9.9). An anchored draggable with two anchors whose offset is
 * the surface's top edge: the docked card's top when collapsed, 0 when expanded, so that edge stays
 * under the finger. [progress] (p) is derived from it and everything else is drawn from p. Also
 * holds the measured geometry the transition interpolates between.
 *
 * The expanded state itself lives in the navigator (saved across recreation); [onSettle] reports
 * the target a gesture chose, [animateTo] follows the navigator.
 */
@Stable
class PlayerSheetState internal constructor(initiallyExpanded: Boolean, density: Density) {
    internal val draggable = AnchoredDraggableState(
        if (initiallyExpanded) PlayerSheetValue.Expanded else PlayerSheetValue.Collapsed,
    )

    /** Drags of the surface itself (not the nested scrolls), to compose Now Playing as one starts. */
    internal val interactions = MutableInteractionSource()

    private val velocityThreshold = with(density) { SettleVelocity.toPx() }
    private val miniArtworkSize = with(density) { MiniArtworkSize.toPx() }
    private val miniArtworkStart = with(density) { MiniArtworkStartInset.toPx() }
    private val miniProgressLine = with(density) { MiniProgressLineHeight.toPx() }

    /** Where the player is going or rests: changed by a settle, [animateTo] and the navigator. */
    var isExpanded: Boolean by mutableStateOf(initiallyExpanded)
        private set

    /** Told the target a drag, fling or scroll settles at (the shell updates its saved flag). */
    internal var onSettle: (expanded: Boolean) -> Unit = {}

    private var windowInRoot by mutableStateOf(Rect.Zero)
    private var dockInRoot by mutableStateOf(Rect.Zero)
    private var largeArtworkInRoot by mutableStateOf(Rect.Zero)
    internal var isRtl by mutableStateOf(false)

    /** Natural height of the mini player's content; the shell's dock reserves this much. */
    internal var miniHeight by mutableIntStateOf(0)

    /** The player's own bounds (the window), in player coordinates. */
    internal val fullBounds: Rect get() = Rect(Offset.Zero, windowInRoot.size)

    /** The docked mini player card (Rect.Zero until the shell's dock is laid out). */
    internal val collapsedBounds: Rect
        get() = if (dockInRoot.isEmpty) Rect.Zero else dockInRoot.translate(-windowInRoot.topLeft)

    /** Now Playing's art slot (Rect.Zero while Now Playing isn't laid out). */
    internal val largeArtwork: Rect
        get() = if (largeArtworkInRoot.isEmpty) Rect.Zero else largeArtworkInRoot.translate(-windowInRoot.topLeft)

    /** The mini player's thumbnail, derived from the card (see [miniArtworkBounds]). */
    internal val miniArtwork: Rect
        get() = miniArtworkBounds(collapsedBounds, miniArtworkSize, miniArtworkStart, miniProgressLine, isRtl)

    /**
     * p: 0 docked, 1 full screen. Read it only in layout, draw and graphicsLayer lambdas (or
     * through derivedStateOf): it changes every frame of a drag.
     */
    val progress: Float
        get() {
            val distance = collapsedBounds.top
            val offset = draggable.offset
            if (offset.isNaN() || distance <= 0f) return if (isExpanded) 1f else 0f
            return (1f - offset / distance).coerceIn(0f, 1f)
        }

    internal fun surfaceBounds(p: Float): Rect = surfaceBoundsAt(p, collapsedBounds, fullBounds)

    internal fun artworkBounds(p: Float): Rect = artworkBoundsAt(p, miniArtwork, largeArtwork)

    /** How far the art's bottom edge at [p] is below (or above, negative) its Now Playing slot's. */
    internal fun artworkBottomShift(p: Float): Float {
        val large = largeArtwork
        return if (large.isEmpty) 0f else artworkBounds(p).bottom - large.bottom
    }

    internal fun updateWindow(coordinates: LayoutCoordinates) {
        val bounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        if (bounds != windowInRoot) {
            windowInRoot = bounds
            syncAnchors()
        }
    }

    /** The shell's dock (where the card rests), unclipped. */
    internal fun updateDock(coordinates: LayoutCoordinates) {
        val bounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        if (bounds != dockInRoot) {
            dockInRoot = bounds
            syncAnchors()
        }
    }

    internal fun updateLargeArtwork(coordinates: LayoutCoordinates?) {
        largeArtworkInRoot = coordinates?.let { Rect(it.positionInRoot(), it.size.toSize()) } ?: Rect.Zero
    }

    private fun syncAnchors() {
        val top = collapsedBounds.top
        if (top <= 0f) return
        draggable.updateAnchors(
            DraggableAnchors {
                PlayerSheetValue.Collapsed at top
                PlayerSheetValue.Expanded at 0f
            },
            if (isExpanded) PlayerSheetValue.Expanded else PlayerSheetValue.Collapsed,
        )
    }

    /** Springs to the anchor of [expanded], starting at [velocity] (px/s, positive toward collapsed). */
    suspend fun animateTo(expanded: Boolean, velocity: Float = 0f) {
        isExpanded = expanded
        val target = if (expanded) PlayerSheetValue.Expanded else PlayerSheetValue.Collapsed
        draggable.anchoredDrag(target) { anchors, latest ->
            val to = anchors.positionOf(latest)
            if (to.isNaN()) return@anchoredDrag
            val from = draggable.offset.takeUnless { it.isNaN() } ?: to
            animateOffset(from, to, velocity) { dragTo(it) }
        }
    }

    /** A released gesture: picks the anchor ([settlesExpanded]), reports it and springs there. */
    internal suspend fun settle(velocity: Float) {
        val expand = decide(velocity)
        animateTo(expand, velocity)
    }

    private fun decide(velocity: Float): Boolean {
        val expand = settlesExpanded(progress, -velocity, velocityThreshold)
        isExpanded = expand
        onSettle(expand)
        return expand
    }

    /** Predictive back in progress: the player shrinks toward the mini player with the gesture. */
    internal fun previewBack(backProgress: Float) {
        val distance = collapsedBounds.top
        val offset = draggable.offset
        if (distance <= 0f || offset.isNaN()) return
        draggable.dispatchRawDelta((1f - backPreviewProgress(backProgress)) * distance - offset)
    }

    /** Release of a drag on the surface: the same settle, driven through the draggable's scroll scope. */
    internal val flingBehavior: FlingBehavior = object : FlingBehavior {
        override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
            val expand = decide(initialVelocity)
            val to = draggable.anchors.positionOf(if (expand) PlayerSheetValue.Expanded else PlayerSheetValue.Collapsed)
            val from = draggable.offset
            if (!to.isNaN() && !from.isNaN()) {
                animateOffset(from, to, initialVelocity) { value -> scrollBy(value - draggable.offset) }
            }
            return 0f
        }
    }

    /**
     * Now Playing's scrolling content first: a downward drag collapses the player only once the
     * content is at its top, and while the player is partly collapsed an upward drag expands it
     * before the content scrolls. A gesture that moved the player settles it on release.
     */
    internal val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        private var movedPlayer = false

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y >= 0f || progress >= 1f) return Offset.Zero
            return Offset(0f, drag(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
            return Offset(0f, drag(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!movedPlayer) return Velocity.Zero
            movedPlayer = false
            settle(available.y)
            return available
        }

        private fun drag(delta: Float): Float {
            if (draggable.offset.isNaN()) return 0f
            val used = draggable.dispatchRawDelta(delta)
            if (used != 0f) movedPlayer = true
            return used
        }
    }
}

/** Springs the offset from [from] to [to], stopping on the anchor rather than passing it. */
private suspend fun animateOffset(from: Float, to: Float, velocity: Float, apply: (Float) -> Unit) {
    if (from == to) return
    AnimationState(initialValue = from, initialVelocity = velocity).animateTo(to, SettleSpring) {
        val reached = if (to > from) value >= to else value <= to
        apply(if (reached) to else value)
        if (reached) cancelAnimation()
    }
}

@Composable
internal fun rememberPlayerSheetStateInternal(initiallyExpanded: Boolean): PlayerSheetState {
    val density = LocalDensity.current
    return remember { PlayerSheetState(initiallyExpanded, density) }
}

/** The sheet of the expanding player around the surfaces (null outside it: previews, standalone). */
internal val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetState?> { null }

/**
 * The place the docked card takes in the shell's bottom stack (above the navigation bar). The card
 * itself is drawn by [ExpandingPlayerLayout] above everything; this reserves its room and tells the
 * sheet where it rests.
 */
@Composable
internal fun PlayerDockSpace(sheet: PlayerSheetState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val height = with(density) { sheet.miniHeight.takeIf { it > 0 }?.toDp() } ?: PlayerCardDefaultHeight
    Spacer(
        modifier
            .padding(horizontal = PlayerCardHorizontalMargin, vertical = PlayerCardVerticalMargin)
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned(sheet::updateDock),
    )
}

/**
 * The expanding player: one surface that grows from the docked card to the full window as p goes
 * from 0 to 1, driven by the finger (anchored draggable on the surface, nested scrolling inside Now
 * Playing). It is a full-window layer whose clip outline is the interpolated card, so touches
 * outside the card reach the app below. Every per-frame change is a draw, layer or placement
 * update reading p; Now Playing itself recomposes only when a threshold flips.
 *
 * - [mini]: the mini player content (laid out at the card's width, moves with the art's top edge
 *   and fades out over [0, MINI_FADE_END]); placed only while visible.
 * - [nowPlaying]: full-window Now Playing, composed once the player moves; its parts fade and
 *   slide in themselves (see [playerTopBarMotion], [playerDetailsMotion]).
 * - The art is one element flying between the mini player's thumbnail and Now Playing's slot.
 */
@Composable
internal fun ExpandingPlayerLayout(
    sheet: PlayerSheetState,
    artwork: String?,
    artworkColor: State<Color>,
    modifier: Modifier = Modifier,
    mini: @Composable () -> Unit,
    nowPlaying: @Composable () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    SideEffect { sheet.isRtl = rtl }
    val dragged by sheet.interactions.collectIsDraggedAsState()
    val moving by remember(sheet) { derivedStateOf { sheet.progress > 0f || sheet.isExpanded } }
    val nowPlayingHidden by remember(sheet) {
        derivedStateOf { sheet.progress < PlayerMotion.NOW_PLAYING_ACCESSIBLE }
    }
    CompositionLocalProvider(LocalPlayerSheet provides sheet) {
        Box(
            modifier
                .fillMaxSize()
                .onGloballyPositioned(sheet::updateWindow),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = sheet.progress
                        shape = BoundsShape(sheet.surfaceBounds(p), surfaceCornerAt(p, PlayerCardCorner.toPx()))
                        clip = true
                        shadowElevation = PlayerCardElevation.toPx() * (1f - p)
                    }
                    .drawBehind { drawSurfaceBackground(sheet, artworkColor.value) }
                    .anchoredDraggable(
                        state = sheet.draggable,
                        orientation = Orientation.Vertical,
                        interactionSource = sheet.interactions,
                        flingBehavior = sheet.flingBehavior,
                    )
                    .nestedScroll(sheet.nestedScrollConnection),
            ) {
                if (moving || dragged) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .ignoreTapsWhile { sheet.progress < PlayerMotion.NOW_PLAYING_INTERACTIVE }
                            .then(if (nowPlayingHidden) Modifier.clearAndSetSemantics {} else Modifier),
                    ) {
                        nowPlaying()
                    }
                }
                MiniPlayerLayer(sheet, content = mini)
                FlyingArtwork(sheet, artwork)
            }
        }
    }
}

/**
 * The surface's background: the mini player's art-toned card colour, with Now Playing's
 * art-coloured gradient (over the surface's current bounds) fading in with p.
 */
private fun DrawScope.drawSurfaceBackground(sheet: PlayerSheetState, artworkColor: Color) {
    val p = sheet.progress
    if (p < 1f) drawRect(artworkColor.toned(maxLightness = 0.24f, minLightness = 0.12f))
    if (p > 0f) {
        val bounds = sheet.surfaceBounds(p)
        val top = artworkColor.toned(maxLightness = 0.36f, minLightness = 0.16f)
        drawRect(PlayerDefaults.Background, alpha = p)
        drawRect(
            Brush.verticalGradient(0f to top, 0.85f to PlayerDefaults.Background, startY = bounds.top, endY = bounds.bottom),
            alpha = p,
        )
    }
}

/**
 * The mini player at the card, fading out early. It moves with the art's top edge, so the title
 * stays beside the growing art (which pushes it aside, [miniTextMotion]) instead of leaving it.
 */
@Composable
private fun MiniPlayerLayer(sheet: PlayerSheetState, content: @Composable () -> Unit) {
    val visible by remember(sheet) { derivedStateOf { sheet.progress < PlayerMotion.MINI_FADE_END } }
    val fallbackMargin = with(LocalDensity.current) { (PlayerCardHorizontalMargin * 2).roundToPx() }
    Box(
        Modifier
            .layout { measurable, constraints ->
                val card = sheet.collapsedBounds
                // Measured even while hidden: the dock keeps the card's height.
                val width = if (card.isEmpty) constraints.maxWidth - fallbackMargin else card.width.roundToInt()
                val placeable = measurable.measure(Constraints.fixedWidth(width.coerceAtLeast(0)))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (visible && !card.isEmpty) {
                        placeable.placeWithLayer(card.left.roundToInt(), card.top.roundToInt()) {
                            val p = sheet.progress
                            alpha = fadeOut(p, 0f, PlayerMotion.MINI_FADE_END)
                            translationY = sheet.artworkBounds(p).top - sheet.miniArtwork.top
                        }
                    }
                }
            }
            .onSizeChanged { sheet.miniHeight = it.height }
            .ignoreTapsWhile { sheet.progress > 0f },
    ) {
        content()
    }
}

/**
 * The art while the player moves (0 < p < 1): laid out once at Now Playing's art size and moved,
 * scaled and rounded by its layer, so no frame relayouts it. At rest the mini player's thumbnail
 * (p = 0) or Now Playing's own art (p = 1) shows instead. It stays composed (and loads each new
 * cover) while hidden, so it already holds the cover when it appears; one loaded out of sight shows
 * without a fade ([PlayerArtwork]).
 */
@Composable
private fun FlyingArtwork(sheet: PlayerSheetState, artwork: String?) {
    val flying by remember(sheet) { derivedStateOf { sheet.progress.let { it > 0f && it < 1f } } }
    Box(
        Modifier
            .layout { measurable, constraints ->
                val base = sheet.largeArtwork.takeUnless { it.isEmpty } ?: sheet.miniArtwork
                val side = base.width.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(side, side))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (flying && !base.isEmpty) {
                        placeable.placeWithLayer(0, 0) {
                            val p = sheet.progress
                            val bounds = sheet.artworkBounds(p)
                            val scale = (bounds.width / side).coerceAtLeast(0.001f)
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scale
                            scaleY = scale
                            translationX = bounds.left
                            translationY = bounds.top
                            // Corners are in layer pixels, before the scale.
                            shape = RoundedCornerShape(lerp(MiniArtworkCorner.toPx(), LargeArtworkCorner.toPx(), p) / scale)
                            clip = true
                            shadowElevation = LargeArtworkElevation.toPx() * p
                        }
                    }
                }
            }
            .clearAndSetSemantics {},
    ) {
        PlayerSurfaceTheme {
            PlayerArtwork(
                url = artwork,
                contentDescription = null,
                shape = null,
                shown = { flying },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A rounded [bounds] inside the layer (the surface's clip outline, which also bounds its touches). */
private data class BoundsShape(val bounds: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(bounds, CornerRadius(radius)))
}

/**
 * Taps don't land while [blocked] (a player in motion): downs are consumed before the children
 * see them, so buttons don't fire. Drags still start (they catch the moving player), and their
 * ups are left alone so a release keeps its fling.
 */
internal fun Modifier.ignoreTapsWhile(blocked: () -> Boolean): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (blocked()) event.changes.forEach { if (it.changedToDownIgnoreConsumed()) it.consume() }
        }
    }
}

/**
 * Vertical motion that starts on a slider stays with it: Now Playing neither scrolls nor collapses
 * from the seek bar or the volume slider. A drag that crosses the vertical touch slop before the
 * slider took it sideways is held here until the finger lifts; being deeper than Now Playing's
 * scroll and the sheet, this sees each move before them, so they never get one past their slop.
 * Everything else is left to the slider: it takes sideways drags at its own slop (those within 30°
 * of horizontal; it leaves steeper ones to the vertical draggables above it, so they end up held
 * here), and a tap that jitters a little still lands (consuming a move earlier would cancel both).
 */
internal fun Modifier.keepDragsLocal(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val vertical = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            ?: return@awaitEachGesture
        drag(vertical.id) { it.consume() }
    }
}

/**
 * Now Playing's top bar (collapse chevron, context): the head of the growing surface, it rides the
 * surface's top edge and fades in late.
 */
internal fun Modifier.playerTopBarMotion(sheet: PlayerSheetState?): Modifier =
    if (sheet == null) {
        this
    } else {
        graphicsLayer {
            val p = sheet.progress
            alpha = fadeIn(p, PlayerMotion.TOP_BAR_FADE_START, 1f)
            translationY = sheet.surfaceBounds(p).top
        }
    }

/**
 * Now Playing's title, seek bar, controls and actions: fade in over [DETAILS_FADE_START, 1] and ride
 * just below the moving art, or, beside it ([besideArtwork], the wide layout), the surface's top
 * edge like the top bar.
 */
internal fun Modifier.playerDetailsMotion(sheet: PlayerSheetState?, besideArtwork: Boolean = false): Modifier =
    if (sheet == null) {
        this
    } else {
        graphicsLayer {
            val p = sheet.progress
            alpha = fadeIn(p, PlayerMotion.DETAILS_FADE_START, 1f)
            translationY = if (besideArtwork) sheet.surfaceBounds(p).top else sheet.artworkBottomShift(p)
        }
    }

/** Whether Now Playing's own art is the one on screen: at rest (p = 1), or outside the player. */
internal fun PlayerSheetState?.showsPlayerArtwork(): Boolean = this == null || progress >= 1f

/** Whether the mini player's thumbnail is the art on screen: at rest (p = 0), or outside the player. */
internal fun PlayerSheetState?.showsMiniArtwork(): Boolean = this == null || progress <= 0f

/** Now Playing's own art: shown only at rest (p = 1); [FlyingArtwork] takes over while it moves. */
internal fun Modifier.playerArtworkAtRest(sheet: PlayerSheetState?): Modifier =
    if (sheet == null) this else graphicsLayer { alpha = if (sheet.showsPlayerArtwork()) 1f else 0f }

/** The mini player's thumbnail: shown only at rest (p = 0). */
internal fun Modifier.miniArtworkAtRest(sheet: PlayerSheetState?): Modifier =
    if (sheet == null) this else graphicsLayer { alpha = if (sheet.showsMiniArtwork()) 1f else 0f }

/** The mini player's title and artist: pushed aside by the growing art while they fade. */
internal fun Modifier.miniTextMotion(sheet: PlayerSheetState?): Modifier =
    if (sheet == null) {
        this
    } else {
        graphicsLayer {
            val p = sheet.progress
            if (p > 0f) {
                val mini = sheet.miniArtwork
                val art = sheet.artworkBounds(p)
                translationX = if (sheet.isRtl) art.left - mini.left else art.right - mini.right
            } else {
                translationX = 0f
            }
        }
    }

/** Fixed request size of the player's artwork (Spotify's covers are at most 640 px). */
private const val PLAYER_ARTWORK_PX = 640

/**
 * Cover art of the player surfaces. The mini player's thumbnail, the moving art and Now Playing's
 * art make this same request (same data, fixed size), so a copy composed after another has loaded
 * the cover gets that bitmap from the memory cache. A new cover reaches the composed copies at
 * once, and each may decode it before any is cached.
 *
 * A cover that loads while this copy is [shown] fades in; one that loads while it is hidden shows
 * at once, since Coil's fade starts at the painter's first draw: it would otherwise run, from the
 * grey placeholder, only when the copy appears. [shape] null: the caller clips.
 */
@Composable
internal fun PlayerArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = RoundedCornerShape(MiniArtworkCorner),
    shown: () -> Boolean = { true },
) {
    val context = LocalPlatformContext.current
    val currentShown by rememberUpdatedState(shown)
    val request = remember(url, context) {
        url?.takeIf { it.isNotBlank() }?.let {
            ImageRequest.Builder(context)
                .data(imageData(it))
                .size(PLAYER_ARTWORK_PX)
                .transitionFactory(CrossfadeWhileShown { currentShown() })
                .build()
        }
    }
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .then(if (shape != null) Modifier.clip(shape) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics {
                        this.contentDescription = contentDescription
                        role = Role.Image
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxSize(0.42f),
            )
        }
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onState = { state -> loaded = state is AsyncImagePainter.State.Success },
            )
        }
    }
}

/** Coil's crossfade for a cover that loads while [shown]; any other shows at once (see [PlayerArtwork]). */
private class CrossfadeWhileShown(private val shown: () -> Boolean) : Transition.Factory {
    override fun create(target: TransitionTarget, result: ImageResult): Transition =
        (if (shown()) PlayerArtworkCrossfade else Transition.Factory.NONE).create(target, result)
}

/** The fade the app's image loader gives every other image (a memory-cache hit never fades). */
private val PlayerArtworkCrossfade = CrossfadeTransition.Factory()
