package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.playback.EngineReach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TrackSortTest {
    private val collator = defaultCollator(Locale.US)

    private fun track(
        name: String,
        artist: String,
        album: String,
        disc: Int = 1,
        number: Int = 1,
        playable: Boolean = true,
    ) = Track(
        uri = "spotify:track:${name.lowercase()}",
        name = name,
        artists = listOf(ArtistRef("spotify:artist:$artist", artist)),
        album = AlbumRef("spotify:album:$album", album),
        discNumber = disc,
        trackNumber = number,
        playable = playable,
    )

    // A playlist in its own (custom) order.
    private val items = listOf(
        PlaylistItem(addedAt = 300, track = track("zebra", "Beta", "Night", number = 2)),
        PlaylistItem(addedAt = 100, track = track("Álpha", "alpha", "Day", number = 3)),
        PlaylistItem(addedAt = 200, track = track("Mango", "Beta", "Night", number = 1)),
        PlaylistItem(addedAt = 400, track = track("apple", "Gamma", "Ash", playable = false)),
        PlaylistItem(addedAt = null, track = track("Kiwi", "alpha", "Day", number = 1)),
    )
    private val keys = items.map { it.sortKey() }

    private fun names(order: List<Int>) = order.map { items[it].track!!.name }

    @Test
    fun theDefaultOrderIsTheServers() {
        assertEquals(listOf(0, 1, 2, 3, 4), sortOrder(keys, TrackSort.CUSTOM, default = TrackSort.CUSTOM, collator))
        val liked = items.map { it.track!! }
        assertTrue(liked.sortedFor(TrackSort.RECENTLY_ADDED, default = TrackSort.RECENTLY_ADDED) === liked)
    }

    @Test
    fun titleIgnoresCaseAndAccentsAndPutsUnplayableLast() {
        // "apple" can't play: last, whatever its title.
        assertEquals(listOf("Álpha", "Kiwi", "Mango", "zebra", "apple"), names(sortOrder(keys, TrackSort.TITLE, TrackSort.CUSTOM, collator)))
    }

    @Test
    fun artistThenAlbumThenTrackNumber() {
        assertEquals(listOf("Kiwi", "Álpha", "Mango", "zebra", "apple"), names(sortOrder(keys, TrackSort.ARTIST, TrackSort.CUSTOM, collator)))
    }

    @Test
    fun albumThenTrackNumber() {
        assertEquals(listOf("Kiwi", "Álpha", "Mango", "zebra", "apple"), names(sortOrder(keys, TrackSort.ALBUM, TrackSort.CUSTOM, collator)))
    }

    @Test
    fun recentlyAddedIsNewestFirstWithUnknownDatesLast() {
        assertEquals(listOf("zebra", "Mango", "Álpha", "Kiwi", "apple"), names(sortOrder(keys, TrackSort.RECENTLY_ADDED, TrackSort.CUSTOM, collator)))
    }

    @Test
    fun theOrderIsOfRowIndicesSoEditsKeepThePlaylistPosition() {
        val order = sortOrder(keys, TrackSort.TITLE, TrackSort.CUSTOM, collator)
        assertEquals(items.indices.toSet(), order.toSet())
        assertEquals(1, order.first()) // "Álpha" is row 1 of the playlist
    }

    @Test
    fun episodesSortByNameAndShow() {
        val show = ShowRef("spotify:show:s", "Show", publisher = "Publisher")
        val key = PlaylistItem(episode = Episode(uri = "spotify:episode:e", name = "Ep", show = show)).sortKey()
        assertEquals("Ep", key.title)
        assertEquals("Publisher", key.artist)
        assertEquals("Show", key.album)
    }

    @Test
    fun tiesKeepTheListOrder() {
        val same = List(5) { i -> PlaylistItem(track = track("Same", "A", "B").copy(uri = "spotify:track:$i")) }
        assertEquals(listOf(0, 1, 2, 3, 4), sortOrder(same.map { it.sortKey() }, TrackSort.TITLE, TrackSort.CUSTOM, collator))
    }

    @Test
    fun aSortedListPlaysAsATrackListInTheShownOrder() {
        val uris = listOf("c", "a", "b")
        val request = sortedPlayRequest(uris, startIndex = 1)!!
        assertEquals(uris, request.trackUris)
        assertEquals(1, request.startIndex)
        assertNull("a track list, not the context (its order is the server's)", request.contextUri)
        assertEquals(false, request.shuffle)
        assertNull(sortedPlayRequest(emptyList()))
    }

    @Test
    fun longSortedListsPlayABoundedWindowAroundTheStart() {
        val uris = (0 until 2_000).map { "spotify:track:$it" }
        val fromTop = sortedPlayRequest(uris, 0)!!
        assertEquals(MAX_SORTED_PLAY, fromTop.trackUris!!.size)
        assertEquals("spotify:track:0", fromTop.trackUris!!.first())
        assertEquals(0, fromTop.startIndex)

        val middle = sortedPlayRequest(uris, 1_000)!!
        assertEquals(MAX_SORTED_PLAY, middle.trackUris!!.size)
        assertEquals("spotify:track:1000", middle.trackUris!![middle.startIndex!!])
        assertEquals("a few before the start, for previous", 50, middle.startIndex)

        val end = sortedPlayRequest(uris, 1_990)!!
        assertEquals("spotify:track:1999", end.trackUris!!.last())
        assertEquals("spotify:track:1990", end.trackUris!![end.startIndex!!])
    }

    @Test
    fun theSortedListIsCurrentOnlyWithoutACatalogContext() {
        val sent = setOf("spotify:track:a")
        assertTrue(isSortedPlayback("spotify:track:a", null, sent))
        assertTrue(isSortedPlayback("spotify:track:a", "spotify:internal:tracks", sent))
        assertFalse("played from its album", isSortedPlayback("spotify:track:a", "spotify:album:x", sent))
        assertFalse(isSortedPlayback("spotify:track:a", "spotify:user:me:collection", sent))
        assertFalse(isSortedPlayback("spotify:track:z", null, sent))
    }

    @Test
    fun storedSortsAreRememberedForTheMostRecentLists() {
        assertEquals(TrackSort.TITLE, parseStoredSort("TITLE|123"))
        assertNull(parseStoredSort("NOPE|1"))
        val stored = mapOf("old" to "TITLE|1", "new" to "ALBUM|3", "mid" to "ARTIST|2")
        assertEquals(listOf("old"), sortsToForget(stored, max = 2))
        assertEquals(emptyList<String>(), sortsToForget(stored, max = 3))
    }

    // ---- round 21: the plain-list rule, the kept marker, like patches -------------------------

    private fun load(start: SortedStart): PlayRequestView {
        val request = (start as SortedStart.Load).request
        return PlayRequestView(request.trackUris.orEmpty(), request.startIndex ?: 0)
    }

    private data class PlayRequestView(val uris: List<String>, val index: Int)

    @Test
    fun whileConnectingATappedSongThatIsntDownloadedIsSentAlone() {
        // Only c is downloaded; tapping b must not start c (the offline queue's next download).
        val plan = planSortedPlay(listOf("a", "b", "c"), "b", EngineReach.CONNECTING, downloaded = setOf("c"))
        assertEquals(PlayRequestView(listOf("b"), 0), load(plan))
    }

    @Test
    fun aDownloadedStartPlaysTheListsDownloadsFromExactlyThere() {
        val plan = planSortedPlay(listOf("a", "b", "c", "d"), "c", EngineReach.CONNECTING, downloaded = setOf("a", "c", "d"))
        assertEquals(PlayRequestView(listOf("a", "c", "d"), 1), load(plan))
    }

    @Test
    fun offlineATappedSongThatIsntDownloadedDoesntStart() {
        assertEquals(SortedStart.NotDownloaded, planSortedPlay(listOf("a", "b"), "b", EngineReach.OFFLINE, downloaded = setOf("a")))
    }

    @Test
    fun onlineTheWholeSortedWindowPlays() {
        assertEquals(PlayRequestView(listOf("a", "b", "c"), 1), load(planSortedPlay(listOf("a", "b", "c"), "b", EngineReach.ONLINE, emptySet())))
        assertEquals(PlayRequestView(listOf("a", "b", "c"), 0), load(planSortedPlay(listOf("a", "b", "c"), null, EngineReach.ONLINE, emptySet())))
    }

    @Test
    fun playWhileNotOnlinePlaysTheListsDownloads() {
        assertEquals(PlayRequestView(listOf("b", "c"), 0), load(planSortedPlay(listOf("a", "b", "c"), null, EngineReach.OFFLINE, setOf("c", "b"))))
        // Nothing downloaded: connecting waits for the session with the list; offline nothing plays.
        assertEquals(PlayRequestView(listOf("a", "b"), 0), load(planSortedPlay(listOf("a", "b"), null, EngineReach.CONNECTING, emptySet())))
        assertEquals(SortedStart.NotDownloaded, planSortedPlay(listOf("a", "b"), null, EngineReach.OFFLINE, emptySet()))
        assertEquals(SortedStart.Nothing, planSortedPlay(emptyList(), null, EngineReach.ONLINE, emptySet()))
    }

    @Test
    fun aReopenedPageKnowsItsSortedListIsPlaying() {
        val list = ListSortStore.playlist("spotify:playlist:p")
        val sent = SortedPlays.Entry(list, setOf("spotify:track:a", "spotify:track:b"), generation = 0)
        // The page's own view model is new: what was sent comes from the kept entry.
        val uris = sortedListUris(list, sent) { error("not needed") }
        assertTrue(isSortedPlayback("spotify:track:b", null, uris))
        // Another list played sorted last: not this one.
        assertEquals(emptySet<String>(), sortedListUris(ListSortStore.LIKED_SONGS, sent) { listOf("spotify:track:b") })
        // Nothing recorded (a new process): the shown list counts.
        assertTrue(isSortedPlayback("spotify:track:b", null, sortedListUris(list, null) { listOf("spotify:track:b") }))
    }

    @Test
    fun likesPatchAFullyLoadedListWithoutPagingItAgain() {
        val a = track("A", "x", "y")
        val b = track("B", "x", "y")
        val c = track("C", "x", "y")
        val loaded = listOf(a, b, c)
        val unliked = LikedPatch().unliked(listOf(b.uri))
        assertEquals(listOf(a, c) to 2, applyLikedPatch(loaded, 3, unliked))
        val n = track("New", "x", "y")
        val liked = unliked.liked(listOf(n))
        assertEquals(listOf(n, a, c) to 3, applyLikedPatch(loaded, 3, liked))
        // Liked again: it moves to the top, the count stays.
        val again = liked.liked(listOf(c))
        assertEquals(listOf(c, n, a) to 3, applyLikedPatch(loaded, 3, again))
        assertEquals(loaded to 3, applyLikedPatch(loaded, 3, LikedPatch()))
    }
}
