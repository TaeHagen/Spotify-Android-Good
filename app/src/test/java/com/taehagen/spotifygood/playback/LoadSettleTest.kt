package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.ActiveDeviceRef
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadSettleTest {
    private val cold = PlaybackSnapshot.EMPTY
    /** The engine's first snapshot: nothing loaded, the mixer volume. */
    private val none = PlaybackSnapshot(volume = 30_000)
    /** The Spirc right after activation, before its context is resolved. */
    private val activated = PlaybackSnapshot(source = PlaybackSource.LOCAL, activeDevice = ActiveDeviceRef("me", "Phone"), volume = 30_000)
    private val loading = activated.copy(status = PlaybackStatus.LOADING, track = PlaybackTrack(uri = "spotify:track:t"))

    @Test
    fun aColdLoadSettlesOnlyOnceItsTrackShows() = runTest {
        val snapshots = MutableStateFlow(cold)
        val failure = MutableStateFlow<PlaybackFailure?>(null)
        val settled = async { LoadSettle.await(cold, snapshots, failure, timeoutMs = 15_000) }
        runCurrent()
        snapshots.value = none
        runCurrent()
        assertFalse(settled.isCompleted)
        snapshots.value = activated
        runCurrent()
        assertFalse(settled.isCompleted) // Media3 would show IDLE / an empty playlist here
        snapshots.value = loading
        runCurrent()
        assertTrue(settled.isCompleted)
        assertTrue(settled.await())
    }

    /** Another device of the account, active (paused) before this phone activates. */
    private val desktop = PlaybackSnapshot(
        source = PlaybackSource.REMOTE,
        status = PlaybackStatus.PAUSED,
        activeDevice = ActiveDeviceRef("desk", "Desktop"),
        track = PlaybackTrack(uri = "spotify:track:d"),
    )

    @Test
    fun aColdPullPastAnotherActiveDeviceSettlesOnlyOnItsOwnTrack() = runTest {
        val snapshots = MutableStateFlow(cold)
        val failure = MutableStateFlow<PlaybackFailure?>(null)
        val settled = async { LoadSettle.await(cold, snapshots, failure, timeoutMs = 15_000) }
        runCurrent()
        for (s in listOf(none, desktop, activated)) {
            snapshots.value = s
            runCurrent()
            assertFalse(settled.isCompleted)
        }
        snapshots.value = loading
        runCurrent()
        assertTrue(settled.await())
    }

    @Test
    fun aFailedStartSettlesRemotePlaybackDoesNot() = runTest {
        val snapshots = MutableStateFlow(none)
        val failure = MutableStateFlow<PlaybackFailure?>(null)
        val failed = async { LoadSettle.await(cold, snapshots, failure, timeoutMs = 15_000) }
        runCurrent()
        assertFalse(failed.isCompleted)
        failure.value = PlaybackFailure(PlaybackErrorKind.UNAVAILABLE, "Not available")
        runCurrent()
        assertTrue(failed.await())

        assertFalse(LoadSettle.isSettled(cold, PlaybackSnapshot(source = PlaybackSource.REMOTE), failed = false))
        assertFalse(LoadSettle.isSettled(cold, desktop, failed = false))
        // The offline queue's snapshots are local.
        assertTrue(LoadSettle.isSettled(cold, loading.copy(offline = true), failed = false))
        // The snapshot from before the load does not count, even with a track.
        assertFalse(LoadSettle.isSettled(loading, loading, failed = false))
        // Hidden entries are not a track to show.
        assertFalse(LoadSettle.isSettled(cold, activated.copy(track = PlaybackTrack(uri = "spotify:delimiter")), failed = false))
    }

    @Test
    fun itGivesUpAfterTheTimeout() = runTest {
        val snapshots = MutableStateFlow(none)
        val settled = async { LoadSettle.await(cold, snapshots, MutableStateFlow(null), timeoutMs = 15_000) }
        advanceTimeBy(14_999)
        runCurrent()
        assertFalse(settled.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(settled.await())
    }
}
