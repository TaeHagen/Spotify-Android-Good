package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.QueueMetadataEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackRepositoryTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    private val wallStart = 1_700_000_000_000L

    private fun TestScope.repository(events: NativeEvents): PlaybackRepository =
        PlaybackRepository(backgroundScope, events).also { repo ->
            repo.wallClock = { wallStart + testScheduler.currentTime }
        }

    private fun NativeEvents.snapshot(s: PlaybackSnapshot) = dispatch("playback", json.encodeToString(s))

    private val fullTrack = Track(
        uri = "spotify:track:a",
        name = "Song A",
        artists = listOf(ArtistRef("spotify:artist:1", "Artist")),
        album = AlbumRef("spotify:album:1", "Album", images = listOf(Image("https://i.scdn.co/image/ab", 300, 300))),
        durationMs = 200_000,
    )

    @Test
    fun mergesQueueMetadataIntoSnapshots() = runTest {
        val events = NativeEvents(json)
        val repo = repository(events)
        runCurrent()

        events.snapshot(
            PlaybackSnapshot(
                track = PlaybackTrack(uri = "spotify:track:a", uid = "u1"),
                nextTracks = listOf(PlaybackTrack(uri = "spotify:track:b", uid = "u2")),
                status = PlaybackStatus.PAUSED,
            ),
        )
        runCurrent()
        assertEquals(null, repo.snapshot.value.track?.name)

        events.dispatch("queueMetadata", json.encodeToString(QueueMetadataEvent(tracks = listOf(fullTrack))))
        runCurrent()
        val merged = repo.snapshot.value
        assertEquals("Song A", merged.track?.name)
        assertEquals("Artist", merged.track?.artistLine)
        assertEquals("https://i.scdn.co/image/ab", merged.track?.imageUrl)
        assertEquals(200_000L, merged.durationMs)
        assertEquals("u1", merged.track?.uid)
        assertEquals(null, merged.nextTracks.single().name)

        // A later snapshot without metadata keeps the cached names.
        events.snapshot(PlaybackSnapshot(track = PlaybackTrack(uri = "spotify:track:a", uid = "u1"), status = PlaybackStatus.PLAYING))
        runCurrent()
        assertEquals("Song A", repo.snapshot.value.track?.name)
    }

    @Test
    fun positionIsExtrapolatedOnlyWhilePlaying() = runTest {
        val events = NativeEvents(json)
        val repo = repository(events)
        runCurrent()
        val playing = PlaybackSnapshot(
            track = PlaybackTrack(uri = "spotify:track:a"),
            status = PlaybackStatus.PLAYING,
            positionMs = 10_000,
            positionTimestampMs = wallStart,
            durationMs = 60_000,
        )
        events.snapshot(playing)
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(15_000L, repo.positionMs())
        advanceTimeBy(100_000)
        assertEquals(60_000L, repo.positionMs()) // clamped to the duration

        events.snapshot(playing.copy(status = PlaybackStatus.PAUSED, positionMs = 20_000))
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(20_000L, repo.positionMs())
    }

    @Test
    fun tickerEmitsOnceWhilePausedAndTicksWhilePlaying() = runTest {
        val events = NativeEvents(json)
        val repo = repository(events)
        runCurrent()
        events.snapshot(
            PlaybackSnapshot(
                track = PlaybackTrack(uri = "spotify:track:a"),
                status = PlaybackStatus.PAUSED,
                positionMs = 1_000,
                positionTimestampMs = wallStart,
                durationMs = 60_000,
            ),
        )
        runCurrent()

        val values = mutableListOf<Long>()
        val job = launch { repo.positionTicker(500).toList(values) }
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(listOf(1_000L), values)

        events.snapshot(repo.snapshot.value.copy(status = PlaybackStatus.PLAYING, positionTimestampMs = wallStart + testScheduler.currentTime))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(1_000L, 1_500L, 2_000L, 2_500L, 3_000L), values)
        job.cancel()
    }

    @Test
    fun metadataCacheEvictsLeastRecentlyUsedAndKeepsCompleteEntriesUntouched() {
        val cache = PlaybackMetadataCache(capacity = 2)
        cache.addAll(QueueMetadataEvent(tracks = listOf(fullTrack, fullTrack.copy(uri = "spotify:track:b"))))
        cache["spotify:track:a"] // touch a
        cache.addAll(QueueMetadataEvent(tracks = listOf(fullTrack.copy(uri = "spotify:track:c"))))
        assertEquals(2, cache.size)
        assertEquals(null, cache["spotify:track:b"])
        assertEquals("Song A", cache["spotify:track:a"]?.name)

        val complete = PlaybackSnapshot(track = fullTrack.toTemplate().copy(uid = "x"), durationMs = 200_000)
        assertSame(complete, cache.merge(complete))
        val bare = PlaybackSnapshot(track = PlaybackTrack(uri = "spotify:track:c"))
        assertNotSame(bare, cache.merge(bare))
    }
}
