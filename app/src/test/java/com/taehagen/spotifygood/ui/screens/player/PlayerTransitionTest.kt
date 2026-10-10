package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTransitionTest {
    // A 1080x2340 px window; the docked card above an 80 dp (240 px) navigation bar, 8 dp margins.
    private val window = Rect(0f, 0f, 1080f, 2340f)
    private val card = Rect(24f, 1914f, 1056f, 2088f)
    private val thumbnail = Rect(48f, 1938f, 168f, 2058f)
    private val slot = Rect(72f, 312f, 1008f, 1248f)

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.01f)
        assertEquals(expected.top, actual.top, 0.01f)
        assertEquals(expected.right, actual.right, 0.01f)
        assertEquals(expected.bottom, actual.bottom, 0.01f)
    }

    @Test
    fun theSurfaceGrowsFromTheCardToTheWindow() {
        assertRect(card, surfaceBoundsAt(0f, card, window))
        assertRect(window, surfaceBoundsAt(1f, card, window))
        assertRect(Rect(12f, 957f, 1068f, 2214f), surfaceBoundsAt(0.5f, card, window))
        // Out-of-range progress (a spring's last frame) stays at the anchors.
        assertRect(window, surfaceBoundsAt(1.2f, card, window))
        assertRect(card, surfaceBoundsAt(-0.1f, card, window))
    }

    @Test
    fun theSurfaceAlwaysContainsTheCardAndGrowsSteadily() {
        var previous = surfaceBoundsAt(0f, card, window)
        for (step in 1..100) {
            val bounds = surfaceBoundsAt(step / 100f, card, window)
            assertTrue(bounds.top <= card.top && bounds.bottom >= card.bottom)
            assertTrue(bounds.left <= card.left && bounds.right >= card.right)
            assertTrue(bounds.top <= previous.top && bounds.bottom >= previous.bottom)
            previous = bounds
        }
    }

    @Test
    fun withoutAMeasuredCardTheSurfaceGrowsFromTheBottomEdge() {
        assertTrue(surfaceBoundsAt(0f, Rect.Zero, window).isEmpty)
        assertEquals(2340f, surfaceBoundsAt(0f, Rect.Zero, window).top, 0.01f)
        assertRect(Rect(0f, 1170f, 1080f, 2340f), surfaceBoundsAt(0.5f, Rect.Zero, window))
        assertRect(window, surfaceBoundsAt(1f, Rect.Zero, window))
    }

    @Test
    fun cornersSquareOffAsTheSurfaceFillsTheWindow() {
        assertEquals(24f, surfaceCornerAt(0f, 24f), 0.001f)
        assertEquals(12f, surfaceCornerAt(0.5f, 24f), 0.001f)
        assertEquals(0f, surfaceCornerAt(1f, 24f), 0.001f)
    }

    @Test
    fun theArtMovesFromTheThumbnailToTheSlot() {
        assertRect(thumbnail, artworkBoundsAt(0f, thumbnail, slot))
        assertRect(slot, artworkBoundsAt(1f, thumbnail, slot))
        assertRect(Rect(60f, 1125f, 588f, 1653f), artworkBoundsAt(0.5f, thumbnail, slot))
        // Square all the way (the art is never stretched).
        for (step in 0..10) {
            val bounds = artworkBoundsAt(step / 10f, thumbnail, slot)
            assertEquals(bounds.width, bounds.height, 0.01f)
        }
    }

    @Test
    fun theArtStaysOnWhatIsKnown() {
        // Now Playing not laid out yet: the art doesn't jump anywhere.
        assertRect(thumbnail, artworkBoundsAt(0.4f, thumbnail, Rect.Zero))
        // Restored expanded, the card not measured yet.
        assertRect(slot, artworkBoundsAt(0.4f, Rect.Zero, slot))
    }

    @Test
    fun theThumbnailSitsAtTheCardsStartCentredAboveTheProgressLine() {
        assertRect(thumbnail, miniArtworkBounds(card, size = 120f, startInset = 24f, bottomLine = 6f, rtl = false))
        assertRect(
            Rect(912f, 1938f, 1032f, 2058f),
            miniArtworkBounds(card, size = 120f, startInset = 24f, bottomLine = 6f, rtl = true),
        )
        assertTrue(miniArtworkBounds(Rect.Zero, 120f, 24f, 6f, rtl = false).isEmpty)
    }

    @Test
    fun fadeWindows() {
        assertEquals(0f, fadeIn(0.2f, 0.4f, 1f), 0.001f)
        assertEquals(0f, fadeIn(0.4f, 0.4f, 1f), 0.001f)
        assertEquals(0.5f, fadeIn(0.7f, 0.4f, 1f), 0.001f)
        assertEquals(1f, fadeIn(1f, 0.4f, 1f), 0.001f)
        assertEquals(1f, fadeOut(0f, 0f, 0.3f), 0.001f)
        assertEquals(0.5f, fadeOut(0.15f, 0f, 0.3f), 0.001f)
        assertEquals(0f, fadeOut(0.3f, 0f, 0.3f), 0.001f)
        assertEquals(0f, fadeOut(0.9f, 0f, 0.3f), 0.001f)
        // A degenerate window is a step at its end.
        assertEquals(0f, fadeIn(0.49f, 0.5f, 0.5f), 0.001f)
        assertEquals(1f, fadeIn(0.5f, 0.5f, 0.5f), 0.001f)
    }

    @Test
    fun theMiniPlayerIsGoneBeforeNowPlayingAppears() {
        assertTrue(PlayerMotion.MINI_FADE_END < PlayerMotion.DETAILS_FADE_START)
        assertTrue(PlayerMotion.DETAILS_FADE_START <= PlayerMotion.TOP_BAR_FADE_START)
        for (step in 0..100) {
            val p = step / 100f
            val mini = fadeOut(p, 0f, PlayerMotion.MINI_FADE_END)
            val details = fadeIn(p, PlayerMotion.DETAILS_FADE_START, 1f)
            assertTrue("both visible at $p", mini == 0f || details == 0f)
        }
        // Now Playing is interactive and accessible only once it is (almost) all there.
        assertTrue(PlayerMotion.NOW_PLAYING_INTERACTIVE > PlayerMotion.NOW_PLAYING_ACCESSIBLE)
        assertTrue(PlayerMotion.NOW_PLAYING_ACCESSIBLE >= PlayerMotion.MINI_FADE_END)
    }

    @Test
    fun aSlowReleaseSettlesAtTheNearerAnchor() {
        val threshold = 900f
        assertFalse(settlesExpanded(progress = 0.3f, velocity = 0f, velocityThreshold = threshold))
        assertTrue(settlesExpanded(progress = 0.7f, velocity = 0f, velocityThreshold = threshold))
        assertTrue(settlesExpanded(progress = 0.5f, velocity = 0f, velocityThreshold = threshold))
        // Slower than the threshold either way: still the position.
        assertFalse(settlesExpanded(progress = 0.45f, velocity = 899f, velocityThreshold = threshold))
        assertTrue(settlesExpanded(progress = 0.55f, velocity = -899f, velocityThreshold = threshold))
    }

    @Test
    fun aFlingFollowsItsDirection() {
        val threshold = 900f
        assertTrue(settlesExpanded(progress = 0.05f, velocity = 900f, velocityThreshold = threshold))
        assertTrue(settlesExpanded(progress = 0.1f, velocity = 4_000f, velocityThreshold = threshold))
        assertFalse(settlesExpanded(progress = 0.95f, velocity = -900f, velocityThreshold = threshold))
        assertFalse(settlesExpanded(progress = 0.9f, velocity = -4_000f, velocityThreshold = threshold))
    }

    @Test
    fun predictiveBackShrinksTowardTheMiniPlayer() {
        assertEquals(1f, backPreviewProgress(0f), 0.001f)
        assertEquals(1f - PlayerMotion.BACK_PREVIEW_RANGE / 2f, backPreviewProgress(0.5f), 0.001f)
        assertEquals(1f - PlayerMotion.BACK_PREVIEW_RANGE, backPreviewProgress(1f), 0.001f)
        assertEquals(1f - PlayerMotion.BACK_PREVIEW_RANGE, backPreviewProgress(1.5f), 0.001f)
        // The preview never leaves Now Playing's controls invisible.
        assertTrue(backPreviewProgress(1f) > PlayerMotion.DETAILS_FADE_START)
    }

    @Test
    fun theStatusBarIconsSwitchOnceTheSurfaceReachesUnderTheStatusBar() {
        val top = card.top
        val statusBar = 72f
        assertFalse(surfaceCoversStatusBar(0f, top, statusBar))
        assertFalse(surfaceCoversStatusBar(0.9f, top, statusBar))
        // The edge 35 px down: more than half the status bar shows the player.
        assertTrue(surfaceCoversStatusBar(1f - 35f / top, top, statusBar))
        assertFalse(surfaceCoversStatusBar(1f - 40f / top, top, statusBar))
        assertTrue(surfaceCoversStatusBar(1f, top, statusBar))
        // Without a status bar (immersive), only once expanded.
        assertFalse(surfaceCoversStatusBar(0.999f, top, 0f))
        assertTrue(surfaceCoversStatusBar(1f, top, 0f))
    }
}
