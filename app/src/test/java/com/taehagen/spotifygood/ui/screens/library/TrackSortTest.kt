package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.TrackProvider
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackSnapshot
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
        val key = ListSortStore.LIKED_SONGS
        val sent = SortedPlays.Entry(key, listOf("spotify:track:a"), generation = 0)
        fun current(track: String, context: String?) =
            isListPlaying(key, "spotify:user:me:collection", ListPlayback(trackUri = track, contextUri = context), sent)
        assertTrue(current("spotify:track:a", null))
        assertTrue(current("spotify:track:a", "spotify:internal:tracks"))
        assertFalse("played from its album", current("spotify:track:a", "spotify:album:x"))
        assertFalse("another user's collection", current("spotify:track:a", "spotify:user:other:collection"))
        assertFalse(current("spotify:track:z", null))
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

    // ---- round 22: one precise "is this list playing" for the Play/Pause button -----------------

    private val playlistKey = ListSortStore.playlist("spotify:playlist:p")
    private val order = listOf("t1", "t2", "t3", "t4")
    private val entry = SortedPlays.Entry(playlistKey, order, generation = 0)

    private fun playing(track: String, context: String? = null, next: String? = null, previous: String? = null, shuffle: Boolean = false) =
        ListPlayback(trackUri = track, contextUri = context, isPlaying = true, shuffle = shuffle, next = next, previous = previous)

    @Test
    fun theListsOwnContextIsItInAnyOrder() {
        // Shuffle, started before the sort, from another device: the playlist context plays.
        assertTrue(isListPlaying(playlistKey, "spotify:playlist:p", playing("x", "spotify:playlist:p", shuffle = true), last = null))
        assertTrue(isListPlaying(ListSortStore.LIKED_SONGS, "spotify:user:me:collection", playing("x", "spotify:user:me:collection"), null))
        assertFalse(isListPlaying(playlistKey, "spotify:playlist:p", playing("t2", "spotify:album:a"), entry))
    }

    @Test
    fun theTrackListStartedForItWhileStillThatLoad() {
        assertTrue(isListPlaying(playlistKey, "spotify:playlist:p", playing("t2", next = "t3", previous = "t1"), entry))
        // A track list's context placeholder (spotify:web-api) is no catalog context.
        assertTrue(isListPlaying(playlistKey, "spotify:playlist:p", playing("t2", "spotify:web-api", next = "t3"), entry))
        // The end of the window: autoplay next (not a context track), compare the previous one.
        assertTrue(isListPlaying(playlistKey, "spotify:playlist:p", playing("t4", previous = "t3"), entry))
        // Repeat-all wraps to the first.
        assertTrue(isListPlaying(playlistKey, "spotify:playlist:p", playing("t4", next = "t1"), entry))
        // Shuffled: the next is any of its tracks.
        assertTrue(isListPlaying(playlistKey, "spotify:playlist:p", playing("t2", next = "t4", shuffle = true), entry))
    }

    @Test
    fun anotherContextLessPlayOfOneOfItsSongsIsNotIt() {
        // Downloads > Songs played t2 within its own section: other neighbours.
        assertFalse(isListPlaying(playlistKey, "spotify:playlist:p", playing("t2", next = "d9", previous = "d1"), entry))
        // A fresh page, nothing recorded: no fallback to "any shown song".
        assertFalse(isListPlaying(playlistKey, "spotify:playlist:p", playing("t2", next = "t3"), last = null))
        // The last such play was another list's.
        assertFalse(isListPlaying(ListSortStore.LIKED_SONGS, null, playing("t2", next = "t3"), entry))
    }

    @Test
    fun offlineLikedSongsDownloadsCountAsLikedSongs() {
        // Default order offline: the downloads go as a track list (no context), recorded for Liked Songs.
        val downloads = SortedPlays.Entry(ListSortStore.LIKED_SONGS, listOf("a", "b", "c"), generation = 0)
        assertTrue(isListPlaying(ListSortStore.LIKED_SONGS, "spotify:user:me:collection", playing("b", next = "c", previous = "a"), downloads))
    }

    @Test
    fun theRecordIsDroppedOncePlaybackMovesToAnythingElse() {
        // Sent, but the previous playback still shows: kept, not landed.
        val sent = entry.after(playing("old", "spotify:album:x"))
        assertEquals(entry, sent)
        val landed = sent!!.after(playing("t1", next = "t2"))!!
        assertTrue(landed.landed)
        assertEquals("a stop or a load on its way keeps it", landed, landed.after(ListPlayback()))
        assertEquals(landed, landed.after(playing("t2", next = "t3")))
        assertNull("another list", landed.after(playing("t2", next = "zz", previous = "yy")))
        assertNull("a context", landed.after(playing("t3", "spotify:album:a")))
    }

    @Test
    fun theNextAndPreviousAreTheContextsNotTheQueue() {
        fun t(uri: String, provider: TrackProvider = TrackProvider.CONTEXT) = PlaybackTrack(uri = uri, provider = provider)
        val snapshot = PlaybackSnapshot(
            status = PlaybackStatus.PLAYING,
            track = t("t2"),
            nextTracks = listOf(t("q", TrackProvider.QUEUE), t("t3")),
            prevTracks = listOf(t("t0"), t("t1")),
        )
        val playback = snapshot.toListPlayback()
        assertEquals("t3", playback.next)
        assertEquals("t1", playback.previous)
        assertEquals(ListPlayback(), PlaybackSnapshot().toListPlayback())
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
