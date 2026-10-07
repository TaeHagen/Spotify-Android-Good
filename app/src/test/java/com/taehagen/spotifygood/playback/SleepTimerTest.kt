package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.nativebridge.NativeEvents
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    private class FakeWakeups : SleepWakeups {
        val scheduled = mutableListOf<Long>()
        var cancels = 0
        val holds = mutableListOf<Long>()
        var releases = 0
        override fun schedule(endsAtElapsedMs: Long) { scheduled += endsAtElapsedMs }
        override fun cancel() { cancels++ }
        override fun holdAwake(ms: Long) { holds += ms }
        override fun release() { releases++ }
    }

    private inner class Harness(scope: TestScope) {
        val events = NativeEvents(json)
        val repo = PlaybackRepository(scope.backgroundScope, events)
        val calls = mutableListOf<String>()
        val controller = PlayerController(
            scope = scope.backgroundScope,
            transport = { method, _ -> calls += method; JsonObject(emptyMap()) as JsonElement },
            json = Json,
            snapshot = repo.snapshot,
            lastSession = { null },
        )
        val wakeups = FakeWakeups()
        /** Elapsed realtime: advanced by hand to model time passing while the CPU is suspended. */
        var now = 5_000_000L
        val timer = SleepTimer(scope.backgroundScope, controller, repo).also {
            it.clock = { now }
            it.wakeups = wakeups
        }

        fun snapshot(s: PlaybackSnapshot) = events.dispatch("playback", json.encodeToString(s))
    }

    private val remotePlaying = PlaybackSnapshot(
        source = PlaybackSource.REMOTE,
        status = PlaybackStatus.PLAYING,
        track = PlaybackTrack(uri = "spotify:track:1", uid = "u1"),
        durationMs = 200_000,
        positionMs = 50_000,
    )

    @Test
    fun theWakeUpAlarmPausesOnTimeAlthoughNoDelayElapsedWhileTheCpuSlept() = runTest {
        val h = Harness(this)
        h.snapshot(remotePlaying)
        runCurrent()
        h.timer.start(30)
        runCurrent()
        val endsAt = h.now + 30 * 60_000L
        assertEquals(listOf(endsAt), h.wakeups.scheduled)
        // 30 minutes pass in deep sleep: the monotonic delay did not advance at all.
        h.now += 30 * 60_000L
        runCurrent()
        assertTrue("nothing happens without a wake-up", h.calls.isEmpty())
        h.timer.onWakeupAlarm()
        runCurrent()
        assertEquals(listOf("player.pause"), h.calls)
        assertTrue("the CPU is kept up for the pause request", h.wakeups.holds.isNotEmpty())
        // Finished: the alarm is disarmed and the wake lock released.
        h.snapshot(remotePlaying.copy(status = PlaybackStatus.PAUSED))
        runCurrent()
        assertEquals(SleepTimerState.Off, h.timer.state.value)
        assertTrue(h.wakeups.cancels > 0 && h.wakeups.releases > 0)
    }

    @Test
    fun anEarlyAlarmKeepsTheCpuAwakeUntilTheEnd() = runTest {
        val h = Harness(this)
        h.timer.start(30)
        runCurrent()
        // The inexact window fired 6 minutes early.
        h.now += 24 * 60_000L
        h.timer.onWakeupAlarm()
        runCurrent()
        assertEquals(listOf(6 * 60_000L + SleepSchedule.PAUSE_SLACK_MS), h.wakeups.holds)
        assertTrue("not paused yet", h.calls.isEmpty())
    }

    @Test
    fun cancelAndReplaceDisarmTheRightAlarm() = runTest {
        val h = Harness(this)
        h.timer.start(10)
        runCurrent()
        h.timer.start(20)
        runCurrent()
        assertEquals(listOf(h.now + 10 * 60_000L, h.now + 20 * 60_000L), h.wakeups.scheduled)
        assertEquals("the replaced timer must not cancel the new alarm", 0, h.wakeups.cancels)
        h.timer.cancel()
        runCurrent()
        assertEquals(1, h.wakeups.cancels)
        assertEquals(SleepTimerState.Off, h.timer.state.value)
        // A late alarm after the cancel does nothing.
        h.timer.onWakeupAlarm()
        assertTrue(h.wakeups.holds.isEmpty())
    }

    @Test
    fun endOfTrackOnARemoteDeviceArmsTheTrackEndAndFollowsTheSnapshot() = runTest {
        val h = Harness(this)
        h.snapshot(remotePlaying)
        runCurrent()
        h.timer.endOfTrack()
        runCurrent()
        assertEquals(listOf(h.now + 200_000 - 50_000 - SleepTimer.END_MARGIN_MS), h.wakeups.scheduled)
        // A seek on the remote device moves the end.
        h.snapshot(remotePlaying.copy(positionMs = 120_000))
        runCurrent()
        assertEquals(h.now + 200_000 - 120_000 - SleepTimer.END_MARGIN_MS, h.wakeups.scheduled.last())
        // Paused on the device: no wake-up until it plays again.
        val cancelsBefore = h.wakeups.cancels
        h.snapshot(remotePlaying.copy(positionMs = 120_000, status = PlaybackStatus.PAUSED))
        runCurrent()
        assertEquals(cancelsBefore + 1, h.wakeups.cancels)
        // The track ends while the CPU slept: the alarm pauses at the end.
        h.snapshot(remotePlaying.copy(positionMs = 190_000))
        runCurrent()
        h.now += 10_000
        h.timer.onWakeupAlarm()
        runCurrent()
        assertEquals(listOf("player.pause"), h.calls)
    }
}
