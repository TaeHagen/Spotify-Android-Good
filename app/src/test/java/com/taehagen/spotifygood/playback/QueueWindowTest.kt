package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.TrackProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** Repeat-all: Spirc refills the next tracks with the context again, same uids. */
    private fun repeating(): QueueWindow {
        val pass = (1..12).map { track(it, uid = "u$it") }
        return QueueWindow.build(PlaybackSnapshot(track = pass[4], nextTracks = pass.drop(5) + pass + pass))
    }

    @Test
    fun repeatedPassesKeepUniqueMedia3UidsButSendTheirConnectUid() {
        val w = repeating()
        val uids = w.entries.map { it.uid }
        assertEquals(uids.size, uids.toSet().size)
        // The second pass: t5 (the current track's copy) and t8 carry made-up Media3 uids...
        val secondT8 = w.entries.indices.filter { w.entries[it].track.uri == "spotify:track:t8" }[1]
        assertEquals("u8~1", w.entries[secondT8].uid)
        // ...but the commands use the uid the engine knows.
        assertEquals("u8", w.entries[secondT8].connectUid)
        assertEquals(QueueWindow.Seek.SkipTo("u8"), w.seekPlan(secondT8))
        val copyOfCurrent = w.entries.indexOfFirst { it.uid == "u5~1" }
        assertEquals(QueueWindow.Seek.SkipTo("u5"), w.seekPlan(copyOfCurrent))
        // A remove over both copies of a uid sends it once; a move sends the Connect uid.
        val firstT8 = w.entries.indexOfFirst { it.track.uri == "spotify:track:t8" }
        assertEquals(listOf("u8", "u9", "u10", "u11", "u12", "u1", "u2", "u3", "u4", "u5", "u6", "u7"), w.removeUids(firstT8, secondT8 + 1))
        assertEquals("u8", w.moveUid(secondT8))
        assertNull(w.moveUid(w.currentIndex))
        assertEquals(QueueWindow.Seek.Unsupported, w.seekPlan(w.currentIndex))
    }

    @Test
    fun entriesWithoutAConnectUidAreSteppedToOrLeftAlone() {
        val plain = (1..4).map { PlaybackTrack(uri = "spotify:track:p$it") }
        val w = QueueWindow.build(PlaybackSnapshot(track = track(0), nextTracks = plain))
        // Never a uri-derived id: plain context entries are reached with "next".
        assertEquals(QueueWindow.Seek.Next(3), w.seekPlan(3))
        assertTrue(w.removeUids(1, 5).isEmpty())
        assertNull(w.moveUid(2))
        // Through a queued entry (it would be skipped and dropped) or too far: nothing is sent.
        val queued = QueueWindow.build(
            PlaybackSnapshot(
                track = track(0),
                nextTracks = listOf(PlaybackTrack(uri = "spotify:track:q", provider = TrackProvider.QUEUE)) + plain,
            ),
        )
        assertEquals(QueueWindow.Seek.Unsupported, queued.seekPlan(3))
        val far = QueueWindow.build(PlaybackSnapshot(track = track(0), nextTracks = (1..20).map { PlaybackTrack(uri = "spotify:track:f$it") }))
        assertEquals(QueueWindow.Seek.Next(QueueWindow.MAX_NEXT_STEPS), far.seekPlan(QueueWindow.MAX_NEXT_STEPS))
        assertEquals(QueueWindow.Seek.Unsupported, far.seekPlan(QueueWindow.MAX_NEXT_STEPS + 1))
    }
}
