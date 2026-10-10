package com.taehagen.spotifygood.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutsTest {
    /** n × m cells on a phone in portrait: about (73n − 16) × (118m − 16) dp. */
    private fun portrait(columns: Int, rows: Int) = WidgetSize(73f * columns - 16, 118f * rows - 16)

    /** n × m cells in landscape: about (142n − 15) × (66m − 15) dp. */
    private fun landscape(columns: Int, rows: Int) = WidgetSize(142f * columns - 15, 66f * rows - 15)

    @Test
    fun smallSizesShowArtworkAndPlayPause() {
        assertEquals(WidgetLayout.SMALL, WidgetLayout.bestFit(portrait(2, 1)))
        assertEquals(WidgetLayout.SMALL_TALL, WidgetLayout.bestFit(portrait(2, 2)))
        for (layout in listOf(WidgetLayout.SMALL, WidgetLayout.SMALL_TALL)) {
            assertFalse(layout.showsText)
            assertFalse(layout.showsSkips)
            assertFalse(layout.isLarge)
            assertFalse(layout.showsExtras)
        }
    }

    @Test
    fun mediumSizesShowTextAndTheSkips() {
        assertEquals(WidgetLayout.STACKED, WidgetLayout.bestFit(portrait(4, 1)))
        assertEquals(WidgetLayout.ROW_FULL, WidgetLayout.bestFit(landscape(4, 1)))
        assertEquals(WidgetLayout.ROW_FULL, WidgetLayout.bestFit(landscape(2, 1)))
        for (layout in listOf(WidgetLayout.STACKED, WidgetLayout.ROW_FULL)) {
            assertTrue(layout.showsText)
            assertTrue(layout.showsSkips)
            assertFalse(layout.isLarge)
        }
        // 3x1: title and artist, but no room for the skips.
        assertEquals(WidgetLayout.ROW, WidgetLayout.bestFit(portrait(3, 1)))
        assertTrue(WidgetLayout.ROW.showsText)
        assertFalse(WidgetLayout.ROW.showsSkips)
    }

    @Test
    fun largeSizesShowTheDeviceAndWhereThereIsRoomLikeAndShuffle() {
        assertEquals(WidgetLayout.LARGE, WidgetLayout.bestFit(portrait(4, 2)))
        assertEquals(WidgetLayout.LARGE, WidgetLayout.bestFit(portrait(5, 2)))
        assertEquals(WidgetLayout.TALL, WidgetLayout.bestFit(portrait(4, 3)))
        assertEquals(WidgetLayout.TALL, WidgetLayout.bestFit(portrait(5, 4)))
        for (layout in listOf(WidgetLayout.LARGE, WidgetLayout.TALL)) {
            assertTrue(layout.isLarge)
            assertTrue(layout.showsSkips)
            assertTrue(layout.showsExtras)
        }
        // 3x2: the device line and the controls, no room for like and shuffle.
        assertEquals(WidgetLayout.TALL_NARROW, WidgetLayout.bestFit(portrait(3, 2)))
        assertTrue(WidgetLayout.TALL_NARROW.isLarge)
        assertFalse(WidgetLayout.TALL_NARROW.showsExtras)
        // 4x2 turned to landscape is one row high: the 4x1 layout.
        assertEquals(WidgetLayout.STACKED, WidgetLayout.bestFit(landscape(4, 2)))
    }

    @Test
    fun everyLayoutIsShownAtItsOwnSizeAndTooSmallFallsBackToTheSmallest() {
        for (layout in WidgetLayout.entries) assertEquals(layout, WidgetLayout.bestFit(layout.minSize))
        assertEquals(WidgetLayout.entries.size, WidgetLayout.entries.map { it.minSize }.toSet().size)
        assertEquals(WidgetLayout.SMALL, WidgetLayout.bestFit(WidgetSize(20f, 20f)))
        assertEquals(WidgetLayout.LARGE, WidgetLayout.bestFit(WidgetLayout.DEFAULT_SIZE))
    }

    @Test
    fun fitsInComparesWholeDp() {
        assertTrue(WidgetSize(250.4f, 40f).fitsIn(WidgetSize(249.6f, 40f)))
        assertFalse(WidgetSize(251f, 40f).fitsIn(WidgetSize(249.5f, 40f)))
    }

    @Test
    fun theOptionsGiveThePortraitAndTheLandscapeSize() {
        val (portrait, landscape) = WidgetLayout.orientationSizes(minWidth = 276, minHeight = 117, maxWidth = 554, maxHeight = 220)!!
        assertEquals(WidgetSize(276f, 220f), portrait)
        assertEquals(WidgetSize(554f, 117f), landscape)
        assertEquals(WidgetLayout.LARGE, WidgetLayout.bestFit(portrait))
        assertEquals(WidgetLayout.STACKED, WidgetLayout.bestFit(landscape))
        // A launcher that reports no sizes: the default.
        assertNull(WidgetLayout.orientationSizes(0, 0, 0, 0))
    }
}
