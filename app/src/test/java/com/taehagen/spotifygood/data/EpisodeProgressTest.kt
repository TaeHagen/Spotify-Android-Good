package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val EP = "spotify:episode:a"
private const val HOUR = 3_600_000L

class MergeProgressTest {
    private val local = EpisodeProgress(positionMs = 50 * 60_000L, fullyPlayed = false, updatedAt = 1)

    @Test
    fun noLocalProgressShowsTheServers() {
        assertEquals(ProgressMerge.Server(dropLocal = false), mergeProgress(PlayedPoint(10, false), null))
        assertEquals(ProgressMerge.Server(dropLocal = false), mergeProgress(null, null))
    }

    @Test
    fun localProgressWinsWithoutAServerState() {
        assertEquals(ProgressMerge.Local(local), mergeProgress(null, local))
    }

    @Test
    fun playedOfflineBeforeAnyServerStateWasSeenWinsAndAdoptsIt() {
        val server = PlayedPoint(0, false)
        assertEquals(ProgressMerge.Local(local, baseline = server), mergeProgress(server, local))
    }

    @Test
    fun unchangedServerStateMeansThisPhoneIsNewer() {
        val server = PlayedPoint(10 * 60_000L, false)
        assertEquals(ProgressMerge.Local(local.copy(server = server)), mergeProgress(server, local.copy(server = server)))
    }

    @Test
    fun aServerStateThatChangedSinceMeansItWasPlayedElsewhere() {
        val seen = PlayedPoint(10 * 60_000L, false)
        assertEquals(ProgressMerge.Server(dropLocal = true), mergeProgress(PlayedPoint(70 * 60_000L, false), local.copy(server = seen)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeProgressStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = 1_000L

    private fun TestScope.store(file: File? = null, maxEntries: Int = EpisodeProgressStore.MAX_ENTRIES): Pair<EpisodeProgressStore, CoroutineScope> {
        val scope = CoroutineScope(coroutineContext + Job())
        val store = EpisodeProgressStore(file, scope, clock = { now }, io = StandardTestDispatcher(testScheduler), maxEntries = maxEntries)
        return store to scope
    }

    private fun episode(uri: String = EP, resume: Long? = null, played: Boolean? = null) =
        Episode(uri = uri, name = "Episode", durationMs = 2 * HOUR, resumePositionMs = resume, fullyPlayed = played)

    @Test
    fun recordedProgressOverlaysTheEpisodeAndResumesPlays() = runTest {
        val (store, scope) = store()
        assertNull(store.resumeMs(EP))
        store.record(EP, 50 * 60_000L, 2 * HOUR)
        assertEquals(50 * 60_000L, store.resumeMs(EP))
        val shown = store.merge(episode())
        assertEquals(50 * 60_000L, shown.resumePositionMs)
        assertEquals(false, shown.fullyPlayed)
        scope.cancel()
    }

    @Test
    fun nearTheEndIsFullyPlayedAndRestarts() = runTest {
        val (store, scope) = store()
        store.record(EP, 2 * HOUR - 20_000L, 2 * HOUR)
        assertNull("restarts from the beginning", store.resumeMs(EP))
        val shown = store.merge(episode())
        assertEquals(true, shown.fullyPlayed)
        assertEquals(0L, shown.resumePositionMs)
        scope.cancel()
    }

    @Test
    fun aZeroPositionDoesNotEraseTheResumePoint() = runTest {
        val (store, scope) = store()
        store.record(EP, 50 * 60_000L, 2 * HOUR)
        store.record(EP, 0, 2 * HOUR) // a load reporting 0 before it seeks
        assertEquals(50 * 60_000L, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun playedElsewhereLaterWinsOverThisPhone() = runTest {
        val (store, scope) = store()
        // The show page showed Spotify's 10 min, then the user listened to 50 min here.
        store.merge(episode(resume = 10 * 60_000L, played = false))
        store.record(EP, 50 * 60_000L, 2 * HOUR)
        assertEquals(50 * 60_000L, store.merge(episode(resume = 10 * 60_000L, played = false)).resumePositionMs)
        // Later the desktop got to 70 min: Spotify's state changed since, it wins.
        val versionBefore = store.version.value
        assertEquals(70 * 60_000L, store.merge(episode(resume = 70 * 60_000L, played = false)).resumePositionMs)
        assertNull(store.resumeMs(EP))
        assertTrue(store.version.value > versionBefore)
        scope.cancel()
    }

    @Test
    fun theLeastRecentlyPlayedAreForgottenFirst() = runTest {
        val (store, scope) = store(maxEntries = 2)
        store.record("spotify:episode:1", 1_000, HOUR)
        store.record("spotify:episode:2", 1_000, HOUR)
        store.record("spotify:episode:1", 2_000, HOUR)
        store.record("spotify:episode:3", 1_000, HOUR)
        assertNull(store.resumeMs("spotify:episode:2"))
        assertEquals(2_000L, store.resumeMs("spotify:episode:1"))
        assertEquals(1_000L, store.resumeMs("spotify:episode:3"))
        scope.cancel()
    }

    @Test
    fun progressSurvivesARestartAndClearWipesItOnDisk() = runTest {
        val file = File(folder.root, "episode_progress.json")
        val (first, firstScope) = store(file)
        advanceUntilIdle()
        first.record(EP, 50 * 60_000L, 2 * HOUR)
        advanceUntilIdle()
        assertTrue(file.exists())
        firstScope.cancel()

        val (second, secondScope) = store(file)
        advanceUntilIdle()
        assertEquals(50 * 60_000L, second.resumeMs(EP))

        second.clear()
        advanceUntilIdle()
        assertNull(second.resumeMs(EP))
        assertFalse(file.exists())
        secondScope.cancel()

        val (third, thirdScope) = store(file)
        advanceUntilIdle()
        assertNull("the next account starts empty", third.resumeMs(EP))
        thirdScope.cancel()
    }

    @Test
    fun anEpisodePlayRequestResumesWhereItWasLeft() {
        val resume = { uri: String -> if (uri == EP) 50 * 60_000L else null }
        // Downloads / search: a track list starting at the episode.
        assertEquals(50 * 60_000L, PlayerController.withEpisodeResume(PlayRequest(trackUris = listOf(EP), startIndex = 0), resume).positionMs)
        // Show page without a known position.
        assertEquals(
            50 * 60_000L,
            PlayerController.withEpisodeResume(PlayRequest(contextUri = "spotify:show:s", startUri = EP), resume).positionMs,
        )
        // An explicit position wins; tracks and unknown episodes are left alone.
        assertEquals(5_000L, PlayerController.withEpisodeResume(PlayRequest(trackUris = listOf(EP), positionMs = 5_000), resume).positionMs)
        assertEquals(0L, PlayerController.withEpisodeResume(PlayRequest(trackUris = listOf("spotify:track:t")), resume).positionMs)
        assertEquals(0L, PlayerController.withEpisodeResume(PlayRequest(trackUris = listOf("spotify:episode:other")), resume).positionMs)
        // Playing a whole show starts wherever the engine starts it.
        assertEquals(0L, PlayerController.withEpisodeResume(PlayRequest(contextUri = "spotify:show:s"), resume).positionMs)
    }
}

class EpisodeProgressTrackerTest {
    private val recorded = mutableListOf<Triple<String, Long, Long>>()
    private val tracker = EpisodeProgressTracker { uri, position, duration -> recorded += Triple(uri, position, duration) }

    private fun snapshot(
        uri: String? = EP,
        status: PlaybackStatus = PlaybackStatus.PLAYING,
        positionMs: Long = 0,
        at: Long = 0,
        source: PlaybackSource = PlaybackSource.LOCAL,
        isEpisode: Boolean = true,
    ) = PlaybackSnapshot(
        source = source,
        status = status,
        positionMs = positionMs,
        positionTimestampMs = at,
        durationMs = 2 * HOUR,
        track = uri?.let { PlaybackTrack(uri = it, isEpisode = isEpisode) },
    )

    @Test
    fun recordsWhilePlayingAndOnPause() {
        tracker.onSnapshot(snapshot(positionMs = 60_000, at = 1_000), nowMs = 1_000)
        tracker.onSnapshot(snapshot(positionMs = 60_000, at = 1_000), nowMs = 16_000) // the 15 s save
        tracker.onSnapshot(snapshot(status = PlaybackStatus.PAUSED, positionMs = 80_000, at = 21_000), nowMs = 21_000)
        assertEquals(listOf(Triple(EP, 60_000L, 2 * HOUR), Triple(EP, 75_000L, 2 * HOUR), Triple(EP, 80_000L, 2 * HOUR)), recorded)
    }

    @Test
    fun theOutgoingEpisodeIsSavedWhereItGotTo() {
        tracker.onSnapshot(snapshot(positionMs = 60_000, at = 1_000), nowMs = 1_000)
        recorded.clear()
        // Skipped to a song 9 s later.
        tracker.onSnapshot(snapshot(uri = "spotify:track:t", isEpisode = false, at = 10_000), nowMs = 10_000)
        assertEquals(listOf(Triple(EP, 69_000L, 2 * HOUR)), recorded)
    }

    @Test
    fun anEpisodeThatEndedCountsAsPlayedToTheEnd() {
        tracker.onSnapshot(snapshot(positionMs = 2 * HOUR - 10_000, at = 1_000), nowMs = 1_000)
        recorded.clear()
        tracker.onSnapshot(snapshot(status = PlaybackStatus.STOPPED, positionMs = 0, at = 21_000), nowMs = 21_000)
        assertEquals(listOf(Triple(EP, 2 * HOUR, 2 * HOUR)), recorded)
    }

    @Test
    fun playbackLeavingThePhoneSavesTheEpisode() {
        tracker.onSnapshot(snapshot(status = PlaybackStatus.PAUSED, positionMs = 60_000), nowMs = 0)
        recorded.clear()
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 60_000), nowMs = 5_000)
        assertEquals(listOf(Triple(EP, 60_000L, 2 * HOUR)), recorded)
        recorded.clear()
        // The remote device's playback is not recorded here.
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 90_000), nowMs = 9_000)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun loadingAndSongsRecordNothing() {
        tracker.onSnapshot(snapshot(status = PlaybackStatus.LOADING), nowMs = 0)
        tracker.onSnapshot(snapshot(uri = "spotify:track:t", isEpisode = false, positionMs = 30_000), nowMs = 0)
        assertTrue(recorded.isEmpty())
    }
}
