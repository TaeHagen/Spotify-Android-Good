package com.taehagen.spotifygood.ui.components

import com.taehagen.spotifygood.data.settings.LibrarySort
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.screens.album.RichText
import com.taehagen.spotifygood.ui.screens.library.LibraryItem
import com.taehagen.spotifygood.ui.screens.library.LibraryItemKind
import com.taehagen.spotifygood.ui.screens.library.TrackSort
import com.taehagen.spotifygood.ui.screens.library.libraryScrollLabel
import com.taehagen.spotifygood.ui.screens.library.likedPlaceholders
import com.taehagen.spotifygood.ui.screens.library.likedScrollLabel
import com.taehagen.spotifygood.ui.screens.playlist.PlaylistData
import com.taehagen.spotifygood.ui.screens.playlist.PlaylistListUi
import com.taehagen.spotifygood.ui.screens.playlist.PlaylistRow
import com.taehagen.spotifygood.ui.screens.playlist.playlistPlaceholders
import com.taehagen.spotifygood.ui.screens.playlist.playlistScrollLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

class FastScrollTest {
    /** A playlist page: header 300 px, filter 100 px, 5,000 rows of 64 px, a 64 px footer. */
    private val geometry = FastScrollGeometry(
        headerSizes = listOf(300, 100),
        rowPx = 64f,
        contentCount = 5_000,
        trailingPx = 64f,
        viewportPx = 2_000,
        paddingPx = 0,
    )

    @Test
    fun theWholeListIsMeasuredRowsNotLoadedIncluded() {
        assertEquals(400f + 64f * 5_000 + 64f, geometry.totalPx, 0.01f)
        assertEquals(geometry.totalPx - 2_000f, geometry.rangePx, 0.01f)
        assertEquals(2_000f / geometry.totalPx, geometry.visibleRatio, 0.0001f)
        assertTrue(geometry.isLong)
    }

    @Test
    fun aShortListHasNoScroller() {
        val short = FastScrollGeometry(listOf(300), 64f, contentCount = 30, trailingPx = 0f, viewportPx = 2_000, paddingPx = 0)
        assertFalse(short.isLong)
        val empty = FastScrollGeometry(emptyList(), 64f, contentCount = 0, trailingPx = 0f, viewportPx = 2_000, paddingPx = 0)
        assertFalse(empty.isLong)
    }

    @Test
    fun fractionAndTargetAreInverses() {
        for (row in listOf(0, 1, 37, 1_234, 2_500, 4_000)) {
            for (into in listOf(10, 40)) {
                val fraction = geometry.fractionOf(geometry.contentStart + row, into)
                val target = geometry.target(fraction)
                assertEquals("row $row +$into", geometry.contentStart + row, target.index)
                assertTrue("row $row +$into: ${target.offset}", abs(target.offset - into) <= 1)
            }
        }
    }

    @Test
    fun theThumbsPositionMapsToTheRowOnTop() {
        // Half way down: the row whose top is at half the scroll range.
        val half = geometry.rowAt(0.5f)
        assertEquals(((geometry.rangePx * 0.5f - 400f) / 64f).toInt(), half)
        assertEquals(0, geometry.rowAt(0f))
        // The end shows the last screen: its top row, not the last one.
        assertEquals(((geometry.rangePx - 400f) / 64f).toInt(), geometry.rowAt(1f))
        assertTrue(geometry.rowAt(1f) < 5_000 - 25)
    }

    @Test
    fun theEdgesAndTheHeader() {
        assertEquals(0f, geometry.fractionOf(0, 0, atTop = true), 0f)
        assertEquals(1f, geometry.fractionOf(4_990, 0, atBottom = true), 0f)
        assertEquals(FastScrollTarget(0, 0), geometry.target(0f))
        // The end: the last row, which the list pulls to its end.
        assertEquals(FastScrollTarget(geometry.contentStart + 4_999, 0), geometry.target(1f))
        // Within the header: the header item and the offset into it.
        val inHeader = geometry.target(350f / geometry.rangePx)
        assertEquals(1, inHeader.index)
        assertTrue(abs(inHeader.offset - 50) <= 1)
        assertEquals(300f / geometry.rangePx, geometry.fractionOf(1, 0), 0.00001f)
    }

    @Test
    fun theThumbFollowsTheFingerOneToOne() {
        val track = 1_600f
        val thumb = thumbHeight(geometry.visibleRatio, track, minPx = 120f)
        // 2,000 px of 320,000: below the minimum.
        assertEquals(120f, thumb, 0f)
        for (f in listOf(0f, 0.25f, 0.5f, 0.999f, 1f)) {
            assertEquals(f, fractionAtThumb(thumbTop(f, track, thumb), track, thumb), 0.0001f)
        }
        assertEquals(0f, fractionAtThumb(-40f, track, thumb), 0f)
        assertEquals(1f, fractionAtThumb(track + 40f, track, thumb), 0f)
        // A long viewport: the thumb is its share, never longer than the track.
        assertEquals(800f, thumbHeight(0.5f, track, 120f), 0f)
        assertEquals(track, thumbHeight(1f, track, 2_000f), 0f)
    }

    @Test
    fun sizesAreRememberedAfterScrollingAway() {
        val sizes = FastScrollSizes(defaultRowPx = 64f)
        // At the top: header, filter and rows on screen.
        sizes.geometry(
            items = listOf(FastScrollItem(0, 0, 300), FastScrollItem(1, 300, 100)) + (2 until 30).map { FastScrollItem(it, 400 + (it - 2) * 70, 70) },
            contentStart = 2, contentCount = 5_000, totalItems = 5_003, viewportPx = 2_000, paddingPx = 0,
        )
        // Far down: only rows.
        val far = sizes.geometry(
            items = (3_000 until 3_030).map { FastScrollItem(it, (it - 3_000) * 70, 70) },
            contentStart = 2, contentCount = 5_000, totalItems = 5_003, viewportPx = 2_000, paddingPx = 0,
        )
        assertEquals(listOf(300, 100), far.headerSizes)
        assertEquals(70f, far.rowPx, 0f)
        // Nothing measured yet: the default row height.
        val fresh = FastScrollSizes(64f).geometry(emptyList(), 0, 100, 100, 2_000, 0)
        assertEquals(64f, fresh.rowPx, 0f)
    }

    @Test
    fun visibleRowsAreCountedFromTheFirstRow() {
        val items = (0 until 12).map { FastScrollItem(it, it * 64, 64) }
        assertEquals(0 to 8, visibleRows(items, contentStart = 3, contentCount = 100))
        // Only the header on screen: no rows.
        assertNull(visibleRows(items.take(3), contentStart = 3, contentCount = 100))
        // The footer after the rows doesn't count.
        assertEquals(0 to 5, visibleRows(items, contentStart = 3, contentCount = 6))
    }

    // -- Bubble labels ------------------------------------------------------------------------------

    @Test
    fun lettersIgnoreCaseAndAccents() {
        assertEquals("E", fastScrollLetter("élan", Locale.US))
        assertEquals("A", fastScrollLetter("  abba", Locale.US))
        assertEquals("T", fastScrollLetter("The Beatles", Locale.US))
        assertEquals("Ø", fastScrollLetter("øystein", Locale.US))
        assertEquals("日", fastScrollLetter("日本", Locale.US))
        // Digits and symbols sort first: one "#".
        assertEquals("#", fastScrollLetter("1999", Locale.US))
        assertEquals("#", fastScrollLetter("(What's the Story)", Locale.US))
        assertNull(fastScrollLetter("", Locale.US))
        assertNull(fastScrollLetter("   ", Locale.US))
        assertNull(fastScrollLetter(null, Locale.US))
    }

    @Test
    fun positionsAndMonths() {
        assertEquals("1,234 / 5,000", fastScrollPosition(1_233, 5_000, Locale.US))
        assertEquals("1 / 7", fastScrollPosition(0, 7, Locale.US))
        assertEquals("1.234 / 5.000", fastScrollPosition(1_233, 5_000, Locale.GERMANY))
        // 2024-03-01T00:00:00Z
        assertEquals("Mar 2024", fastScrollDate(1_709_251_200_000L, "MMM yyyy", Locale.US, ZoneOffset.UTC))
    }

    private val track = Track(
        uri = "spotify:track:1",
        name = "Creep",
        artists = listOf(ArtistRef("spotify:artist:r", "Radiohead")),
        album = AlbumRef("spotify:album:p", "Pablo Honey"),
    )

    @Test
    fun theBubbleFollowsTheSort() {
        val item = PlaylistItem(addedAt = 1_709_251_200_000L, track = track)
        assertEquals("C", playlistScrollLabel(item, 3, 100, TrackSort.TITLE, "MMM yyyy"))
        assertEquals("R", playlistScrollLabel(item, 3, 100, TrackSort.ARTIST, "MMM yyyy"))
        assertEquals("P", playlistScrollLabel(item, 3, 100, TrackSort.ALBUM, "MMM yyyy"))
        assertEquals(fastScrollDate(1_709_251_200_000L, "MMM yyyy"), playlistScrollLabel(item, 3, 100, TrackSort.RECENTLY_ADDED, "MMM yyyy"))
        assertEquals(fastScrollPosition(3, 100), playlistScrollLabel(item, 3, 100, TrackSort.CUSTOM, "MMM yyyy"))
        // A row not loaded yet: its position in the own order, nothing for a letter.
        assertEquals(fastScrollPosition(4_000, 5_000), playlistScrollLabel(null, 4_000, 5_000, TrackSort.CUSTOM, "MMM yyyy"))
        assertNull(playlistScrollLabel(null, 4_000, 5_000, TrackSort.TITLE, "MMM yyyy"))

        assertEquals("C", likedScrollLabel(track, null, 0, 10, TrackSort.TITLE, "MMM yyyy"))
        assertEquals(fastScrollDate(1L, "MMM yyyy"), likedScrollLabel(track, 1L, 0, 10, TrackSort.RECENTLY_ADDED, "MMM yyyy"))
        assertEquals(fastScrollPosition(4, 10), likedScrollLabel(null, null, 4, 10, TrackSort.RECENTLY_ADDED, "MMM yyyy"))

        val folder = LibraryItem(LibraryItemKind.PLAYLIST, "spotify:playlist:x", "zebra", creator = "Ann", addedAt = 1L)
        assertEquals("Z", libraryScrollLabel(folder, 0, 1, LibrarySort.ALPHABETICAL, "MMM yyyy"))
        assertEquals("A", libraryScrollLabel(folder, 0, 1, LibrarySort.CREATOR, "MMM yyyy"))
        assertEquals(fastScrollPosition(0, 1), libraryScrollLabel(folder, 0, 1, LibrarySort.RECENT, "MMM yyyy"))
    }

    @Test
    fun placeholdersOnlyInTheOwnOrderOnline() {
        val playlist = PlaylistData(
            meta = Playlist(uri = "spotify:playlist:x", name = "x"),
            description = RichText.EMPTY,
            rows = (0 until 100).map { PlaylistRow("k$it", PlaylistItem(track = track.copy(uri = "spotify:track:$it"))) },
            total = 5_000,
            revision = "r",
        )
        val own = PlaylistListUi()
        assertEquals(4_900, playlistPlaceholders(playlist, 100, own, editMode = false, online = true))
        assertEquals(0, playlistPlaceholders(playlist, 100, own, editMode = true, online = true))
        assertEquals(0, playlistPlaceholders(playlist, 100, own, editMode = false, online = false))
        assertEquals(0, playlistPlaceholders(playlist, 100, own.copy(sortActive = true), editMode = false, online = true))
        assertEquals(0, playlistPlaceholders(playlist, 100, own.copy(filterActive = true), editMode = false, online = true))
        assertEquals(0, playlistPlaceholders(playlist.copy(downloadedCopy = true), 100, own, editMode = false, online = true))

        assertEquals(4_900, likedPlaceholders(100, 5_000, fromDownload = false, filter = "", sort = TrackSort.RECENTLY_ADDED, online = true))
        assertEquals(0, likedPlaceholders(100, 5_000, fromDownload = false, filter = "x", sort = TrackSort.RECENTLY_ADDED, online = true))
        assertEquals(0, likedPlaceholders(100, 5_000, fromDownload = false, filter = "", sort = TrackSort.TITLE, online = true))
        assertEquals(0, likedPlaceholders(100, 5_000, fromDownload = true, filter = "", sort = TrackSort.RECENTLY_ADDED, online = true))
        assertEquals(0, likedPlaceholders(100, null, fromDownload = false, filter = "", sort = TrackSort.RECENTLY_ADDED, online = true))
    }
}
