package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.ActiveDeviceRef
import com.taehagen.spotifygood.model.PlaybackContext
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeSaverTest {
    private val interval = 15_000L
    /** Wall clock of the test: virtual time on top of a fixed base. */
    private val base = 1_000_000L

    private fun TestScope.playing(positionMs: Long = 10_000) = PlaybackSnapshot(
        source = PlaybackSource.LOCAL,
        status = PlaybackStatus.PLAYING,
        positionMs = positionMs,
        positionTimestampMs = base + testScheduler.currentTime,
        durationMs = 600_000,
        context = PlaybackContext("spotify:album:a"),
        track = PlaybackTrack(uri = "spotify:track:t"),
    )

    private class Run(scope: TestScope, initial: PlaybackSnapshot, loggedIn: Boolean, interval: Long, base: Long) {
        val snapshots = MutableStateFlow(initial)
        val loggedIn = MutableStateFlow(loggedIn)
        val actions = mutableListOf<ResumeSaver.Action>()

        init {
            scope.backgroundScope.launch {
                ResumeSaver.actions(snapshots, this@Run.loggedIn, interval) { base + scope.testScheduler.currentTime }
                    .collect { actions += it }
            }
            scope.runCurrent()
        }

        fun saves() = actions.filterIsInstance<ResumeSaver.Action.Save>().map { it.state }
    }

    @Test
    fun theLocalSessionIsSavedAndThenEvery15sWhilePlaying() = runTest {
        val run = Run(this, playing(), loggedIn = true, interval, base)
        assertEquals(listOf(10_000L), run.saves().map { it.positionMs })
        advanceTimeBy(interval * 2 + 1)
        runCurrent()
        assertEquals(listOf(10_000L, 25_000L, 40_000L), run.saves().map { it.positionMs })
        // Paused: saved once, no more periodic saves; a mode change while paused is saved.
        val paused = playing(positionMs = 41_000).copy(status = PlaybackStatus.PAUSED)
        run.snapshots.value = paused
        runCurrent()
        advanceTimeBy(interval * 3)
        runCurrent()
        run.snapshots.value = paused.copy(repeat = RepeatMode.TRACK)
        runCurrent()
        assertEquals(listOf(41_000L, 41_000L), run.saves().drop(3).map { it.positionMs })
        assertEquals(RepeatMode.TRACK, run.saves().last().repeat)
    }

    @Test
    fun theStoredSessionFreezesWhenPlaybackLeavesThePhone() = runTest {
        for (left in listOf(
            // A transfer, or another device taking over.
            PlaybackSnapshot(
                source = PlaybackSource.REMOTE,
                status = PlaybackStatus.PLAYING,
                activeDevice = ActiveDeviceRef("tv", "TV"),
                track = PlaybackTrack(uri = "spotify:track:remote"),
            ),
            // The engine's reset / the Spirc letting go.
            PlaybackSnapshot.EMPTY,
        )) {
            val run = Run(this, playing(), loggedIn = true, interval, base)
            advanceTimeBy(5_000)
            runCurrent()
            run.snapshots.value = left
            runCurrent()
            // One last save at the hand-over (5 s later than the start), then nothing.
            assertEquals(listOf(10_000L, 15_000L), run.saves().map { it.positionMs })
            advanceTimeBy(interval * 4)
            runCurrent()
            run.snapshots.value = left.copy(positionMs = 99_000)
            runCurrent()
            assertEquals(2, run.saves().size)
            assertTrue(run.saves().all { it.trackUri == "spotify:track:t" })
        }
    }

    @Test
    fun loggingOutClearsAndNothingIsSavedAfterwards() = runTest {
        val run = Run(this, playing(), loggedIn = true, interval, base)
        run.loggedIn.value = false
        runCurrent()
        assertEquals(ResumeSaver.Action.Clear, run.actions.last())
        val after = run.actions.size
        // The engine resets its snapshot after the logout; time passes.
        run.snapshots.value = PlaybackSnapshot.EMPTY
        runCurrent()
        run.snapshots.value = playing(positionMs = 50_000)
        runCurrent()
        advanceTimeBy(interval * 4)
        runCurrent()
        assertEquals(after, run.actions.size)
        // The next account's session is saved again (playing since, 60 s ago).
        run.loggedIn.value = true
        runCurrent()
        assertEquals(110_000L, (run.actions.last() as ResumeSaver.Action.Save).state.positionMs)
    }

    @Test
    fun aColdStartBeforeTheLoginIsKnownDoesNotClear() = runTest {
        // isLoggedIn reads false until the stored credentials are read.
        val run = Run(this, PlaybackSnapshot.EMPTY, loggedIn = false, interval, base)
        run.loggedIn.value = true
        runCurrent()
        assertTrue(run.actions.isEmpty())
    }
}
