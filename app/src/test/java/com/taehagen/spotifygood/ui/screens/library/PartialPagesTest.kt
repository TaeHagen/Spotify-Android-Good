package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.components.isPlaceholder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialPagesTest {
    @Test
    fun aPartialPageFlagsTheListUntilItLoadsCompletely() {
        val pages = PartialPages()
        pages.record(0, partial = false)
        assertFalse(pages.partial.value)
        pages.record(100, partial = true)
        assertTrue(pages.partial.value)
        pages.record(200, partial = false)
        assertTrue(pages.partial.value)
        // The same window fetched again (e.g. paging back over it) and complete now.
        pages.record(100, partial = false)
        assertFalse(pages.partial.value)
    }

    @Test
    fun aReloadFromTheStartForgetsOlderPages() {
        val pages = PartialPages()
        pages.record(0, partial = false)
        pages.record(100, partial = true)
        // Retry = reload: only the first page is loaded again.
        pages.record(0, partial = false)
        assertFalse(pages.partial.value)
        pages.record(0, partial = true)
        assertTrue(pages.partial.value)
    }

    @Test
    fun placeholdersAreTheNamelessItems() {
        // docs §6.5: a slot whose metadata failed carries only its uri (empty name, not playable).
        assertTrue(Track(uri = "spotify:track:x", name = "", playable = false).isPlaceholder)
        assertTrue(Episode(uri = "spotify:episode:x", name = "").isPlaceholder)
        assertFalse(Track(uri = "spotify:track:y", name = "Song", playable = false).isPlaceholder)
    }
}
