package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadActivity
import com.taehagen.spotifygood.download.DownloadItem
import com.taehagen.spotifygood.download.DownloadPause
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsLogicTest {
    private fun item(uri: String, state: DownloadState, bytes: Long = 0, total: Long = 0) =
        DownloadItem(uri, state, bytes, total, metadataJson = null, imagePath = null, error = null)

    private fun collection(uri: String, type: CollectionType, items: List<String>, addedAt: Long = 0) =
        DownloadedCollection(uri, type, uri.substringAfterLast(':'), null, items, addedAt)

    private val metadata: (DownloadItem) -> DownloadMetadata? = { item ->
        if (item.uri.startsWith("spotify:episode:")) {
            DownloadMetadata.OfEpisode(Episode(item.uri, "Episode ${item.uri.last()}"))
        } else {
            DownloadMetadata.OfTrack(Track(item.uri, "Track ${item.uri.last()}"))
        }
    }

    @Test
    fun resumeIsOfferedOnlyWhenAStoppedRunLeftItemsWaiting() {
        val stopped = DownloadActivity(running = false, lastError = "Storage is full")
        assertTrue(canResumeDownloads(stopped, pendingCount = 3))
        // Nothing waits, the run is still going, or it did not stop early (waiting for Wi-Fi).
        assertFalse(canResumeDownloads(stopped, pendingCount = 0))
        assertFalse(canResumeDownloads(stopped.copy(running = true), pendingCount = 3))
        assertFalse(canResumeDownloads(DownloadActivity(running = false), pendingCount = 3))
    }

    @Test
    fun theHeaderSaysWhenSpotifysKeyLimitLetsTheQueueGoOn() {
        val now = 1_000_000L
        val paced = DownloadActivity(running = true, pause = DownloadPause(DownloadPause.Reason.PACING, now + 90_000L))
        assertEquals(DownloadPauseNotice(DownloadPause.Reason.PACING, 2), downloadPauseNotice(paced, pendingCount = 40, now = now))
        // After a throttle the run was handed back to the system: still said while nothing runs.
        val limited = DownloadActivity(running = false, pause = DownloadPause(DownloadPause.Reason.LIMITED, now + 10 * 60_000L))
        assertEquals(DownloadPauseNotice(DownloadPause.Reason.LIMITED, 10), downloadPauseNotice(limited, pendingCount = 40, now = now))
        // The pause is over but the resume has not run the queue yet: "continuing shortly" ...
        assertEquals(0, downloadPauseNotice(limited, pendingCount = 40, now = now + 10 * 60_000L + 30_000L)?.minutes)
        // ... for a minute: a resume that waits for its network doesn't keep the promise up.
        assertNull(downloadPauseNotice(limited, pendingCount = 40, now = now + 10 * 60_000L + PAUSE_NOTICE_GRACE_MS + 1))
        // While a run waits inline the pause is shown as long as it lasts.
        assertEquals(0, downloadPauseNotice(limited.copy(running = true), pendingCount = 40, now = now + 20 * 60_000L)?.minutes)
        // Nothing waits, or nothing is paused: no note (not a failure either).
        assertNull(downloadPauseNotice(limited, pendingCount = 0, now = now))
        assertNull(downloadPauseNotice(DownloadActivity(running = true), pendingCount = 40, now = now))
        assertFalse(canResumeDownloads(limited, pendingCount = 40))
    }

    @Test
    fun groupsCollectionsAndIndividualItems() {
        val items = listOf(
            item("spotify:track:1", DownloadState.COMPLETED),
            item("spotify:track:2", DownloadState.COMPLETED),
            item("spotify:track:3", DownloadState.DOWNLOADING, bytes = 50, total = 200),
            item("spotify:episode:4", DownloadState.FAILED),
            item("spotify:track:5", DownloadState.QUEUED),
            item("spotify:track:6", DownloadState.CANCELLED),
        )
        val collections = listOf(
            collection("spotify:playlist:p", CollectionType.PLAYLIST, listOf("spotify:track:1", "spotify:track:3")),
            collection("spotify:album:a", CollectionType.ALBUM, listOf("spotify:track:2")),
            collection("spotify:user:me:collection", CollectionType.LIKED_SONGS, listOf("spotify:track:1")),
        )
        val content = buildDownloadsContent(items, collections, metadata)

        assertEquals(listOf(CollectionType.LIKED_SONGS, CollectionType.PLAYLIST), content.playlists.map { it.type })
        assertEquals(listOf("spotify:album:a"), content.albums.map { it.uri })
        assertTrue(content.podcasts.isEmpty())
        // Newest first; collection items and cancelled items are not listed individually.
        assertEquals(listOf("spotify:track:5"), content.songs.map { it.uri })
        assertEquals(listOf("spotify:episode:4"), content.episodes.map { it.uri })
        assertEquals("Episode 4", content.episodes.single().episode?.name)
        assertEquals(2, content.completedCount)
        assertEquals(1, content.failedCount)
        assertEquals(2, content.pendingCount)
        assertEquals(setOf("spotify:track:1", "spotify:track:2"), content.completed)
        // The transferring item is reported even when it belongs to a collection.
        assertEquals("spotify:track:3", content.active?.uri)
        assertEquals(0.25f, content.active?.progress)
        assertEquals("Track 3", content.active?.track?.name)
    }

    @Test
    fun activeItemFollowsTheDownloader() {
        val items = listOf(
            // Left DOWNLOADING by a killed run; the downloader works on track 2 meanwhile.
            item("spotify:track:1", DownloadState.DOWNLOADING, bytes = 10, total = 100),
            item("spotify:track:2", DownloadState.PREPARING),
            item("spotify:track:3", DownloadState.COMPLETED),
        )
        val collections = listOf(collection("spotify:album:a", CollectionType.ALBUM, listOf("spotify:track:2")))
        assertEquals("spotify:track:2", buildDownloadsContent(items, collections, metadata, "spotify:track:2").active?.uri)
        // A finished item is no longer active even if the downloader has not moved on yet.
        assertEquals(null, buildDownloadsContent(items, collections, metadata, "spotify:track:3").active)
        assertEquals("spotify:track:1", buildDownloadsContent(items, collections, metadata).active?.uri)
    }

    @Test
    fun emptyContent() {
        val content = buildDownloadsContent(emptyList(), emptyList(), metadata)
        assertTrue(content.isEmpty)
        assertEquals(null, content.active)
    }

    @Test
    fun offlinePlaybackUsesOnlyCompletedItems() {
        val c = collection("spotify:playlist:p", CollectionType.PLAYLIST, listOf("a", "b", "c"))
        assertEquals(listOf("a", "c"), c.playableUris(setOf("a", "c"), offline = true))
        assertEquals(listOf("a", "b", "c"), c.playableUris(setOf("a"), offline = false))
    }

    private fun entry(uri: String, state: DownloadState, explicit: Boolean = false) =
        DownloadEntry(uri, state, 0, 0, null, Track(uri, uri, explicit = explicit), null)

    private val a = entry("spotify:track:a", DownloadState.COMPLETED)
    private val b = entry("spotify:track:b", DownloadState.FAILED)
    private val c = entry("spotify:track:c", DownloadState.COMPLETED)
    private val e = entry("spotify:track:e", DownloadState.COMPLETED, explicit = true)

    @Test
    fun offlineATapOnAnEntryThatIsntDownloadedLoadsNothing() {
        // The offline queue would skip b and start c.
        assertEquals(EntryPlay.NotDownloaded, planEntryPlay(b, listOf(a, b, c), online = false, filterExplicit = false))
        assertEquals(EntryPlay.Tracks(listOf(a.uri, c.uri), 1), planEntryPlay(c, listOf(a, b, c), online = false, filterExplicit = false))
        assertEquals(EntryPlay.Tracks(listOf(a.uri, b.uri, c.uri), 1), planEntryPlay(b, listOf(a, b, c), online = true, filterExplicit = false))
    }

    @Test
    fun whileConnectingAnEntryThatIsntDownloadedIsSentAlone() {
        // Alone it waits for the session (or the engine says it isn't available offline); in the
        // section the offline queue would start c instead.
        assertEquals(EntryPlay.Tracks(listOf(b.uri), 0), planEntryPlay(b, listOf(a, b, c), online = false, filterExplicit = false, connecting = true))
        // A downloaded one still starts among the downloads.
        assertEquals(EntryPlay.Tracks(listOf(a.uri, c.uri), 1), planEntryPlay(c, listOf(a, b, c), online = false, filterExplicit = false, connecting = true))
    }

    @Test
    fun explicitEntriesDontStartWhileFiltered() {
        assertEquals(EntryPlay.Unavailable, planEntryPlay(e, listOf(a, e, c), online = true, filterExplicit = true))
        assertEquals(EntryPlay.Tracks(listOf(a.uri, c.uri), 1), planEntryPlay(c, listOf(a, e, c), online = true, filterExplicit = true))
        assertEquals(EntryPlay.Tracks(listOf(a.uri, e.uri, c.uri), 1), planEntryPlay(e, listOf(a, e, c), online = true, filterExplicit = false))
        val shown = DownloadsContent(songs = listOf(a, e)).withExplicitFilter(true)
        assertEquals(listOf(true, false), shown.songs.map { it.track!!.playable })
    }
}
