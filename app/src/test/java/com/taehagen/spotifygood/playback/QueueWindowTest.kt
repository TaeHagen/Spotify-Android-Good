package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueWindowTest {
    private fun track(n: Int, uid: String = "uid$n") = PlaybackTrack(uri = "spotify:track:t$n", uid = uid)

    @Test
    fun emptyWithoutCurrentTrack() {
        val window = QueueWindow.build(PlaybackSnapshot(nextTracks = listOf(track(1))))
        assertTrue(window.isEmpty)
        assertEquals(-1, window.currentIndex)
    }

    @Test
    fun keepsTenPreviousAndFiftyNextInPlayOrder() {
        val prev = (1..15).map { track(it) }
        val next = (100..179).map { track(it) }
        val window = QueueWindow.build(PlaybackSnapshot(track = track(50), prevTracks = prev, nextTracks = next))

        assertEquals(10 + 1 + 50, window.entries.size)
        assertEquals(10, window.currentIndex)
        assertEquals("spotify:track:t50", window.current?.track?.uri)
        // The most recent previous tracks are kept, nearest last.
        assertEquals((6..15).map { "spotify:track:t$it" }, window.entries.take(10).map { it.track.uri })
        assertEquals((100..149).map { "spotify:track:t$it" }, window.entries.drop(11).map { it.track.uri })
    }

    @Test
    fun dropsDelimitersAndMetaEntries() {
        val window = QueueWindow.build(
            PlaybackSnapshot(
                track = track(1),
                prevTracks = listOf(PlaybackTrack(uri = "spotify:delimiter", uid = "d")),
                nextTracks = listOf(PlaybackTrack(uri = "spotify:meta:page:1", uid = "m"), track(2)),
            ),
        )
        assertEquals(listOf("spotify:track:t1", "spotify:track:t2"), window.entries.map { it.track.uri })
        assertEquals(0, window.currentIndex)
    }

    @Test
    fun uidsAreUniqueEvenForRepeatedTracksAndDuplicateUids() {
        val same = PlaybackTrack(uri = "spotify:track:x")
        val window = QueueWindow.build(
            PlaybackSnapshot(
                track = same,
                prevTracks = listOf(same),
                nextTracks = listOf(same, track(1, uid = "dup"), track(2, uid = "dup")),
            ),
        )
        val uids = window.entries.map { it.uid }
        assertEquals(uids.size, uids.toSet().size)
        // The current entry keeps the plain derived uid.
        assertEquals("spotify:track:x", window.current?.uid)
    }

    @Test
    fun currentUidIsStableWhenTheWindowShifts() {
        val before = QueueWindow.build(PlaybackSnapshot(track = track(2), prevTracks = listOf(track(1)), nextTracks = listOf(track(3), track(4))))
        val after = QueueWindow.build(PlaybackSnapshot(track = track(3), prevTracks = listOf(track(1), track(2)), nextTracks = listOf(track(4))))
        assertEquals(before.entries[2].uid, after.current?.uid)
        assertEquals(before.current?.uid, after.entries[1].uid)
    }
}
