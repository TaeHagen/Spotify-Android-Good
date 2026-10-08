package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.Show
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
    private val now = 99L

    @Test
    fun withoutALiveStateWhatIsKeptStays() {
        assertNull(mergeProgress(null, null, now))
        assertEquals(local, mergeProgress(null, local, now))
    }

    @Test
    fun aPartlyPlayedLiveStateIsKeptWhenNothingIs() {
        assertEquals(
            EpisodeProgress(10 * 60_000L, fullyPlayed = false, updatedAt = now, server = PlayedPoint(10 * 60_000L, false)),
            mergeProgress(PlayedPoint(10 * 60_000L, false), null, now),
        )
        // Not started and finished ones would only crowd out this phone's progress.
        assertNull(mergeProgress(PlayedPoint(0, false), null, now))
        assertNull(mergeProgress(PlayedPoint(0, true), null, now))
    }

    @Test
    fun playedOfflineBeforeAnyLiveStateWasSeenWinsAndAdoptsIt() {
        val server = PlayedPoint(0, false)
        assertEquals(local.copy(server = server), mergeProgress(server, local, now))
    }

    @Test
    fun anUnchangedLiveStateMeansThisPhoneIsNewer() {
        val server = PlayedPoint(10 * 60_000L, false)
        assertEquals(local.copy(server = server), mergeProgress(server, local.copy(server = server), now))
    }

    @Test
    fun aLiveStateThatChangedSinceBecomesThisPhonesResumePoint() {
        val seen = PlayedPoint(10 * 60_000L, false)
        val newer = PlayedPoint(70 * 60_000L, false)
        assertEquals(EpisodeProgress(70 * 60_000L, false, now, newer), mergeProgress(newer, local.copy(server = seen), now))
        val finished = PlayedPoint(0, true)
        assertEquals(EpisodeProgress(0, true, now, finished), mergeProgress(finished, local.copy(server = seen), now))
        // Marked unplayed elsewhere: nothing to resume.
        assertNull(mergeProgress(PlayedPoint(0, false), local.copy(server = seen), now))
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
        // Later the desktop got to 70 min: Spotify's state changed since, it wins and becomes the
        // phone's resume point.
        val versionBefore = store.version.value
        assertEquals(70 * 60_000L, store.merge(episode(resume = 70 * 60_000L, played = false)).resumePositionMs)
        assertEquals(70 * 60_000L, store.resumeMs(EP))
        assertTrue(store.version.value > versionBefore)
        scope.cancel()
    }

    @Test
    fun spotifysNewerPointIsUsedByTheDownloadsOffline() = runTest {
        val (store, scope) = store()
        store.merge(episode(resume = 0, played = false)) // the show page: not started
        store.record(EP, 20 * 60_000L, 2 * HOUR) // played here to 20 min
        store.merge(episode(resume = 50 * 60_000L, played = false)) // later the desktop got to 50 min
        // The download (no played state, offline) resumes Spotify's point.
        assertEquals(50 * 60_000L, store.merge(episode()).resumePositionMs)
        assertEquals(50 * 60_000L, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun copiesWithoutALiveStateNeitherDropNorRebaseProgress() = runTest {
        val (store, scope) = store()
        val live = 10 * 60_000L
        store.merge(episode(resume = live, played = false)) // a fresh page
        store.record(EP, 40 * 60_000L, 2 * HOUR)
        // Downloads and cached pages carry no played state: they show the phone's 40 min.
        val download = episode(resume = 0, played = false).withoutPlayedState()
        assertEquals(40 * 60_000L, store.merge(download).resumePositionMs)
        // The next fresh page with the same live state keeps the phone's progress.
        assertEquals(40 * 60_000L, store.merge(episode(resume = live, played = false)).resumePositionMs)
        assertEquals(40 * 60_000L, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aCachedShowPageThenTheFreshOneKeepsTheProgress() = runTest {
        val (store, scope) = store()
        val s1 = 20 * 60_000L
        store.merge(episode(resume = s1, played = false)) // the episode page, live
        store.record(EP, 55 * 60_000L, 2 * HOUR)
        // Monday's cached show page (E not started) is emitted first, then the fresh one.
        val monday = Show(uri = "spotify:show:s", name = "Show", episodes = listOf(episode(resume = 0, played = false)))
        for (resource in listOf<Resource<Show>>(Resource.Loading(monday), Resource.Success(monday, fromCache = true), Resource.Error(Exception(), monday))) {
            val shown = resource.withoutCachedPlayedState().dataOrNull!!.episodes.map(store::merge)
            assertEquals(55 * 60_000L, shown.single().resumePositionMs)
        }
        val fresh: Resource<Show> = Resource.Success(monday.copy(episodes = listOf(episode(resume = s1, played = false))))
        assertEquals(55 * 60_000L, fresh.withoutCachedPlayedState().dataOrNull!!.episodes.map(store::merge).single().resumePositionMs)
        assertEquals(55 * 60_000L, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun anOfflineCachedPageBeforeAnOfflinePlayDoesNotLetTheFirstLivePageDropIt() = runTest {
        val (store, scope) = store()
        // Offline: the cached page (stripped) and a play from the downloads.
        val cached = Show(uri = "spotify:show:s", name = "Show", episodes = listOf(episode(resume = 0, played = false)))
        Resource.Error<Show>(Exception(), cached).withoutCachedPlayedState().dataOrNull!!.episodes.forEach { store.merge(it) }
        store.record(EP, 30 * 60_000L, 2 * HOUR)
        // Online again: the first live page (Spotify still at 20 min from the desktop) adopts it as
        // the reference and keeps this phone's newer progress.
        assertEquals(30 * 60_000L, store.merge(episode(resume = 20 * 60_000L, played = false)).resumePositionMs)
        assertEquals(30 * 60_000L, store.resumeMs(EP))
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

class DownloadPlayedStateTest {
    @Test
    fun downloadMetadataNeverCarriesAFrozenPlayedState() {
        // Rows written before played state was stripped at download time still hold one.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val stored = """{"uri":"$EP","name":"E","durationMs":7200000,"resumePositionMs":0,"fullyPlayed":false}"""
        val decoded = com.taehagen.spotifygood.ui.screens.library.decodeDownloadMetadata(json, EP, stored)
            as com.taehagen.spotifygood.ui.screens.library.DownloadMetadata.OfEpisode
        assertNull(decoded.episode.resumePositionMs)
        assertNull(decoded.episode.fullyPlayed)
    }
}
