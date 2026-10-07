package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.download.CollectionRef
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadedCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineLoadsTest {
    private val playlist = "spotify:playlist:p"
    private val album = "spotify:album:a"
    private val liked = "spotify:user:me:collection"
    private fun t(n: Int) = "spotify:track:$n"

    private fun collection(uri: String, type: CollectionType, vararg items: String) =
        DownloadedCollection(CollectionRef(uri, type, "name", null), items.toList(), addedAt = 0)

    private fun load(plan: OfflineLoads.Plan): PlayRequest = (plan as OfflineLoads.Plan.Load).request

    @Test
    fun contextKinds() {
        assertEquals(OfflineLoads.ContextKind.PLAYLIST, OfflineLoads.kindOf(playlist))
        assertEquals(OfflineLoads.ContextKind.PLAYLIST, OfflineLoads.kindOf("spotify:user:me:playlist:p"))
        assertEquals(OfflineLoads.ContextKind.LIKED_SONGS, OfflineLoads.kindOf(liked))
        assertEquals(OfflineLoads.ContextKind.LIKED_SONGS, OfflineLoads.kindOf("spotify:collection:tracks"))
        assertEquals(OfflineLoads.ContextKind.ALBUM, OfflineLoads.kindOf(album))
        assertEquals(OfflineLoads.ContextKind.SHOW, OfflineLoads.kindOf("spotify:show:s"))
        assertNull(OfflineLoads.kindOf("spotify:artist:x"))
        assertNull(OfflineLoads.kindOf(t(1)))
        assertNull(OfflineLoads.kindOf("spotify:user:me:collection:artist"))
    }

    @Test
    fun downloadedCollectionGivesItsOrder() {
        val members = OfflineLoads.members(
            playlist, null,
            listOf(collection(playlist, CollectionType.PLAYLIST, t(3), t(1), t(2))),
            completed = setOf(t(1), t(3)),
        ) { error("metadata not needed") }
        assertEquals(OfflineMembers(listOf(t(3), t(1), t(2)), setOf(t(1), t(3))), members)
    }

    @Test
    fun likedSongsMatchByTypeWhateverTheUriSpelling() {
        val members = OfflineLoads.members(
            "spotify:collection:tracks", null,
            listOf(collection(liked, CollectionType.LIKED_SONGS, t(1), t(2))),
            completed = setOf(t(2)),
        ) { emptyList() }
        assertEquals(listOf(t(1), t(2)), members?.order)
    }

    @Test
    fun albumNotDownloadedAsAWholeUsesTrackNumbers() {
        val entries = listOf(
            DownloadedEntry(t(3), albumUri = album, discNumber = 1, trackNumber = 3),
            DownloadedEntry(t(9), albumUri = "spotify:album:other", discNumber = 1, trackNumber = 1),
            DownloadedEntry(t(5), albumUri = album, discNumber = 2, trackNumber = 1),
            DownloadedEntry(t(1), albumUri = album, discNumber = 1, trackNumber = 1),
        )
        val members = OfflineLoads.members(album, null, emptyList(), setOf(t(1), t(3), t(5), t(9))) { entries }
        assertEquals(listOf(t(1), t(3), t(5)), members?.order)
    }

    @Test
    fun showNotDownloadedAsAWholeIsNewestFirst() {
        val show = "spotify:show:s"
        val entries = listOf(
            DownloadedEntry("spotify:episode:old", showUri = show, releaseDate = "2024-01-01"),
            DownloadedEntry("spotify:episode:new", showUri = show, releaseDate = "2025-06-01"),
        )
        val members = OfflineLoads.members(show, null, emptyList(), entries.map { it.uri }.toSet()) { entries }
        assertEquals(listOf("spotify:episode:new", "spotify:episode:old"), members?.order)
    }

    @Test
    fun playlistNotDownloadedOnlyKnowsTheTappedTrack() {
        val members = OfflineLoads.members(playlist, t(2), emptyList(), setOf(t(2))) { error("not for playlists") }
        assertEquals(listOf(t(2)), members?.order)
        assertEquals(OfflineMembers(emptyList(), setOf(t(2))), OfflineLoads.members(playlist, null, emptyList(), setOf(t(2))) { emptyList() })
        // Contexts the engine resolves: only which items are downloaded.
        assertEquals(
            OfflineMembers(emptyList(), setOf(t(1))),
            OfflineLoads.members("spotify:artist:x", null, emptyList(), setOf(t(1))) { error("not for artists") },
        )
    }

    @Test
    fun onlineAndTrackLoadsAreLeftAlone() {
        val members = OfflineMembers(listOf(t(1)), setOf(t(1)))
        val context = PlayRequest(contextUri = playlist)
        assertEquals(OfflineLoads.Plan.Unchanged, OfflineLoads.plan(context, members, EngineReach.ONLINE))
        val tracks = PlayRequest(contextUri = playlist, trackUris = listOf(t(1)))
        assertEquals(OfflineLoads.Plan.Unchanged, OfflineLoads.plan(tracks, members, EngineReach.OFFLINE))
        assertEquals(OfflineLoads.Plan.Unchanged, OfflineLoads.plan(PlayRequest(trackUris = listOf(t(1))), members, EngineReach.OFFLINE))
        assertEquals(OfflineLoads.Plan.Unchanged, OfflineLoads.plan(context, null, EngineReach.OFFLINE))
    }

    @Test
    fun contextLoadBecomesItsDownloadsKeepingTheContext() {
        val members = OfflineMembers(listOf(t(1), t(2), t(3), t(4)), setOf(t(1), t(3), t(4)))
        val request = PlayRequest(contextUri = playlist, startUri = t(3), startIndex = 2, startUid = "abc123", positionMs = 5_000, shuffle = false)
        val converted = load(OfflineLoads.plan(request, members, EngineReach.OFFLINE))
        assertEquals(listOf(t(1), t(3), t(4)), converted.trackUris)
        assertEquals(1, converted.startIndex)
        assertEquals(playlist, converted.contextUri)
        assertEquals(t(3), converted.startUri)
        assertEquals("abc123", converted.startUid)
        assertEquals(5_000L, converted.positionMs)
        assertEquals(false, converted.shuffle)
    }

    @Test
    fun undownloadedStartMovesToTheNextDownloadFromItsStart() {
        val members = OfflineMembers(listOf(t(1), t(2), t(3)), setOf(t(1), t(3)))
        val converted = load(OfflineLoads.plan(PlayRequest(contextUri = playlist, startUri = t(2), positionMs = 9_000), members, EngineReach.OFFLINE))
        assertEquals(listOf(t(1), t(3)), converted.trackUris)
        assertEquals(1, converted.startIndex)
        assertEquals("position belonged to the other track", 0L, converted.positionMs)
        // Nothing downloaded after the requested start: from the top.
        val last = load(OfflineLoads.plan(PlayRequest(contextUri = playlist, startIndex = 5), members, EngineReach.OFFLINE))
        assertEquals(0, last.startIndex)
    }

    @Test
    fun positionIsDroppedWhenTheRequestedTrackIsNotAmongTheDownloads() {
        // Resume of album track 7 at 2:13; only tracks 1 and 2 are downloaded (7 was streamed).
        val members = OfflineMembers(listOf(t(1), t(2)), setOf(t(1), t(2)))
        val converted = load(OfflineLoads.plan(PlayRequest(contextUri = "spotify:album:a", startUri = t(7), positionMs = 133_000), members, EngineReach.OFFLINE))
        assertEquals(0, converted.startIndex)
        assertEquals(0L, converted.positionMs)
        // A load without any start keeps its position (it applies to the first item it asked for).
        val noStart = load(OfflineLoads.plan(PlayRequest(contextUri = playlist, positionMs = 5_000), members, EngineReach.OFFLINE))
        assertEquals(5_000L, noStart.positionMs)
    }

    @Test
    fun indexOnlyStartNamesTheStartTrackForSpirc() {
        val members = OfflineMembers(listOf(t(1), t(2), t(3)), setOf(t(2), t(3)))
        val converted = load(OfflineLoads.plan(PlayRequest(contextUri = playlist, startIndex = 2), members, EngineReach.CONNECTING))
        assertEquals(listOf(t(2), t(3)), converted.trackUris)
        assertEquals(1, converted.startIndex)
        assertEquals(t(3), converted.startUri)
    }

    @Test
    fun noStartStaysUnset() {
        val members = OfflineMembers(listOf(t(1), t(2)), setOf(t(1), t(2)))
        val converted = load(OfflineLoads.plan(PlayRequest(contextUri = liked, shuffle = true), members, EngineReach.OFFLINE))
        assertNull(converted.startIndex)
        assertNull(converted.startUri)
        assertEquals(true, converted.shuffle)
    }

    @Test
    fun offlineQueueUidsAreDropped() {
        val members = OfflineMembers(listOf(t(1)), setOf(t(1)))
        val converted = load(OfflineLoads.plan(PlayRequest(contextUri = playlist, startUid = "o7"), members, EngineReach.OFFLINE))
        assertNull(converted.startUid)
    }

    @Test
    fun nothingDownloadedFailsOnlyWhenReallyOffline() {
        val none = OfflineMembers(listOf(t(1)), emptySet())
        val request = PlayRequest(contextUri = playlist)
        assertEquals(OfflineLoads.Plan.NotDownloaded, OfflineLoads.plan(request, none, EngineReach.OFFLINE))
        // Still connecting: the engine may come online and play the context itself.
        assertEquals(OfflineLoads.Plan.Unchanged, OfflineLoads.plan(request, none, EngineReach.CONNECTING))
    }

    @Test
    fun offlineSmartShuffleBecomesAPlainShuffle() {
        val smart = PlayRequest(contextUri = playlist, smartShuffle = true)
        assertEquals(smart.copy(shuffle = true, smartShuffle = null), OfflineLoads.withoutSmartShuffle(smart, EngineReach.OFFLINE))
        assertEquals(smart, OfflineLoads.withoutSmartShuffle(smart, EngineReach.CONNECTING))
        assertEquals(smart, OfflineLoads.withoutSmartShuffle(smart, EngineReach.ONLINE))
        val off = PlayRequest(contextUri = playlist, shuffle = false, smartShuffle = false)
        assertEquals(off, OfflineLoads.withoutSmartShuffle(off, EngineReach.OFFLINE))
    }

    @Test
    fun connectingNeverSwapsInAnotherDownloadForTheRequestedItem() {
        // Only track 2 of the album is downloaded; the user asked for track 5.
        val members = OfflineMembers(listOf(t(2)), setOf(t(2)))
        val request = PlayRequest(contextUri = album, startUri = t(5), startUid = "u5", positionMs = 3_000)
        val connecting = load(OfflineLoads.plan(request, members, EngineReach.CONNECTING))
        assertEquals(album, connecting.contextUri) // online, Spirc plays the album from track 5
        assertEquals(listOf(t(5)), connecting.trackUris) // offline, "Not available offline"
        assertEquals(t(5), connecting.startUri)
        assertEquals("u5", connecting.startUid)
        assertNull(connecting.startIndex)
        assertEquals(3_000L, connecting.positionMs)
        // Really offline the next download plays (resumptions and media-session loads rely on it).
        assertEquals(listOf(t(2)), load(OfflineLoads.plan(request, members, EngineReach.OFFLINE)).trackUris)
        // A downloaded start item still gets the context's downloads.
        val downloaded = load(OfflineLoads.plan(request.copy(startUri = t(2)), members, EngineReach.CONNECTING))
        assertEquals(listOf(t(2)), downloaded.trackUris)
        assertEquals(3_000L, downloaded.positionMs)
    }

    @Test
    fun connectingIndexOnlyStartThatIsNotDownloadedIsLoadedAlone() {
        val members = OfflineMembers(listOf(t(1), t(2), t(3)), setOf(t(1), t(3)))
        val converted = load(OfflineLoads.plan(PlayRequest(contextUri = playlist, startIndex = 1, startUid = "o1"), members, EngineReach.CONNECTING))
        assertEquals(playlist, converted.contextUri)
        assertEquals(listOf(t(2)), converted.trackUris)
        assertEquals(t(2), converted.startUri)
        assertEquals(0, converted.startIndex)
        assertNull(converted.startUid) // an offline queue uid means nothing to Spirc
    }

    @Test
    fun connectingArtistLoadsNameOnlyAStartItemThatIsNotDownloaded() {
        val artist = "spotify:artist:x"
        val members = checkNotNull(OfflineLoads.members(artist, t(9), emptyList(), setOf(t(1))) { emptyList() })
        val notDownloaded = PlayRequest(contextUri = artist, startUri = t(9))
        val converted = load(OfflineLoads.plan(notDownloaded, members, EngineReach.CONNECTING))
        assertEquals(artist, converted.contextUri)
        assertEquals(listOf(t(9)), converted.trackUris)
        // A downloaded start, a load without a start, or offline: the engine resolves the artist.
        assertEquals(OfflineLoads.Plan.Unchanged, OfflineLoads.plan(notDownloaded.copy(startUri = t(1)), members, EngineReach.CONNECTING))
        assertEquals(OfflineLoads.Plan.Unchanged, OfflineLoads.plan(PlayRequest(contextUri = artist), members, EngineReach.CONNECTING))
        assertEquals(OfflineLoads.Plan.Unchanged, OfflineLoads.plan(notDownloaded, members, EngineReach.OFFLINE))
        assertEquals(OfflineLoads.Plan.Unchanged, OfflineLoads.plan(notDownloaded, members, EngineReach.ONLINE))
    }
}
