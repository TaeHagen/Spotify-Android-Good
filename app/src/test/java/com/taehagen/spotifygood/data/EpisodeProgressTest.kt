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
private const val SHOW = "spotify:show:s"
private const val HOUR = 3_600_000L
private const val MIN = 60_000L

private fun played(min: Long) = PlayedPoint(min * MIN, false)
private val NOT_STARTED = PlayedPoint(0, false)

class ObserveSpotifyTest {
    @Test
    fun aFirstStateIsKeptOnlyWhenPartlyPlayed() {
        assertEquals(EpisodeResume(ResumePoint(35 * MIN, false, 10), SpotifySeen(played(35), 10)), observeSpotify(null, played(35), 10))
        assertNull("never played: nothing to keep", observeSpotify(null, NOT_STARTED, 10))
        assertNull("finished: would only crowd out real points", observeSpotify(null, PlayedPoint(0, true), 10))
    }

    @Test
    fun theFirstStateAfterAnOfflinePlayOnlyBecomesTheReference() {
        val phone = EpisodeResume(ResumePoint(40 * MIN, false, 5))
        assertEquals(EpisodeResume(phone.point, SpotifySeen(played(10), 10)), observeSpotify(phone, played(10), 10))
    }

    @Test
    fun anUnchangedStateKeepsThePhonesNewerPoint() {
        val kept = EpisodeResume(ResumePoint(40 * MIN, false, 20), SpotifySeen(played(10), 10))
        assertEquals(EpisodeResume(kept.point, SpotifySeen(played(10), 30)), observeSpotify(kept, played(10), 30))
    }

    @Test
    fun aChangedStateIsNewsAndTheNewestPointWins() {
        val kept = EpisodeResume(ResumePoint(40 * MIN, false, 20), SpotifySeen(played(10), 10))
        assertEquals(EpisodeResume(ResumePoint(70 * MIN, false, 30), SpotifySeen(played(70), 30)), observeSpotify(kept, played(70), 30))
        // Saved here after that request started: the phone's is newer.
        assertEquals(kept.point, observeSpotify(kept, played(70), 15)?.point)
        // Finished elsewhere / marked unplayed elsewhere.
        assertEquals(ResumePoint(0, true, 30), observeSpotify(kept, PlayedPoint(0, true), 30)?.point)
        assertNull(observeSpotify(kept, NOT_STARTED, 30)?.point)
    }

    @Test
    fun anAnswerRequestedBeforeTheLastOneSeenIsIgnored() {
        val kept = EpisodeResume(ResumePoint(30 * MIN, false, 20), SpotifySeen(played(30), 20))
        assertEquals(kept, observeSpotify(kept, NOT_STARTED, 10))
        assertEquals(kept, observeSpotify(kept, NOT_STARTED, 20))
    }

    @Test
    fun handoffMarksDecideWhatTheNextStateMeans() {
        val point = ResumePoint(70 * MIN, false, 20)
        val adopt = EpisodeResume(point, SpotifySeen(null, 15, SpotifyRef.ADOPT_NEXT))
        assertEquals(EpisodeResume(point, SpotifySeen(played(50), 30)), observeSpotify(adopt, played(50), 30))
        val newer = EpisodeResume(point, SpotifySeen(null, 25, SpotifyRef.NEWER_NEXT))
        assertEquals(ResumePoint(90 * MIN, false, 30), observeSpotify(newer, played(90), 30)?.point)
        // An answer requested before the handoff means nothing.
        assertEquals(newer, observeSpotify(newer, played(90), 24))
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

    private fun episode(uri: String = EP, state: PlayedPoint? = null) =
        Episode(uri = uri, name = "Episode", durationMs = 2 * HOUR, resumePositionMs = state?.positionMs, fullyPlayed = state?.fullyPlayed)

    /** A fresh answer requested at [at]. */
    private fun EpisodeProgressStore.fresh(state: PlayedPoint, at: Long, uri: String = EP) = observe(listOf(episode(uri, state)), at)

    @Test
    fun recordedProgressOverlaysTheEpisodeAndResumesPlays() = runTest {
        val (store, scope) = store()
        assertNull(store.resumeMs(EP))
        store.record(EP, 50 * MIN, 2 * HOUR)
        assertEquals(50 * MIN, store.resumeMs(EP))
        assertEquals(50 * MIN, store.overlay(episode()).resumePositionMs)
        assertEquals(false, store.overlay(episode()).fullyPlayed)
        scope.cancel()
    }

    @Test
    fun nearTheEndIsFullyPlayedAndRestarts() = runTest {
        val (store, scope) = store()
        store.record(EP, 2 * HOUR - 20_000L, 2 * HOUR)
        assertNull("restarts from the beginning", store.resumeMs(EP))
        assertEquals(true, store.overlay(episode()).fullyPlayed)
        scope.cancel()
    }

    @Test
    fun aZeroPositionDoesNotEraseTheResumePoint() = runTest {
        val (store, scope) = store()
        store.record(EP, 50 * MIN, 2 * HOUR)
        store.record(EP, 0, 2 * HOUR)
        assertEquals(50 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun overlayHasNoSideEffects() = runTest {
        val (store, scope) = store()
        store.fresh(played(30), at = 10)
        val version = store.version.value
        repeat(5) {
            store.overlay(episode(state = NOT_STARTED))
            store.overlay(episode(state = played(80)))
        }
        assertEquals(version, store.version.value)
        assertEquals(30 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun anOlderPageCanNotUndoANewerAnswer() = runTest {
        val (store, scope) = store()
        // The episode page (requested at t2) saw 30:00; the show page requested earlier (t1) saw
        // "not started" and is delivered (or re-delivered) afterwards.
        store.fresh(played(30), at = 200)
        store.fresh(NOT_STARTED, at = 100)
        assertEquals(30 * MIN, store.resumeMs(EP))
        // Both pages kept open and re-delivering: nothing changes any more.
        val version = store.version.value
        repeat(3) {
            store.fresh(NOT_STARTED, at = 100)
            store.fresh(played(30), at = 200)
        }
        assertEquals(version, store.version.value)
        assertEquals(30 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun thePhonesProgressWinsWhileSpotifysStateIsUnchanged() = runTest {
        val (store, scope) = store()
        store.fresh(played(10), at = 100) // the desktop left it at 10:00
        now = 200
        store.record(EP, 40 * MIN, 2 * HOUR) // continued here (offline)
        store.fresh(played(10), at = 300) // Spotify still says 10:00
        assertEquals(40 * MIN, store.resumeMs(EP))
        // The show page's Play takes the raw newest episode through the overlay: 40:00.
        assertEquals(40 * MIN, store.overlay(episode(state = played(10))).resumePositionMs)
        // Later the desktop got to 60:00: news, the newest point.
        store.fresh(played(60), at = 400)
        assertEquals(60 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aPointPlayedOfflineBeforeAnyStateWasSeenIsKept() = runTest {
        val (store, scope) = store()
        store.record(EP, 40 * MIN, 2 * HOUR)
        store.fresh(played(10), at = 2_000)
        assertEquals(40 * MIN, store.resumeMs(EP))
        assertEquals(40 * MIN, store.overlay(episode(state = played(10))).resumePositionMs)
        scope.cancel()
    }

    @Test
    fun spotifysPointReachesEveryPlayPath() = runTest {
        val (store, scope) = store()
        // A fresh show page (the app's, Android Auto's or the voice search's) says 35:00.
        store.fresh(played(35), at = 100)
        val request = PlayerController.withEpisodeResume(PlayRequest(contextUri = SHOW, startUri = EP), store::resumeMs)
        assertEquals(35 * MIN, request.positionMs)
        // The downloads (no played state) show it too.
        assertEquals(35 * MIN, store.overlay(episode().withoutPlayedState()).resumePositionMs)
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
        first.record(EP, 50 * MIN, 2 * HOUR)
        first.fresh(played(10), at = 900)
        advanceUntilIdle()
        assertTrue(file.exists())
        firstScope.cancel()

        val (second, secondScope) = store(file)
        advanceUntilIdle()
        assertEquals(50 * MIN, second.resumeMs(EP))
        // The reference survived too: an unchanged state keeps the phone's point.
        second.fresh(played(10), at = 5_000)
        assertEquals(50 * MIN, second.resumeMs(EP))

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
        val resume = { uri: String -> if (uri == EP) 50 * MIN else null }
        assertEquals(50 * MIN, PlayerController.withEpisodeResume(PlayRequest(trackUris = listOf(EP), startIndex = 0), resume).positionMs)
        assertEquals(50 * MIN, PlayerController.withEpisodeResume(PlayRequest(contextUri = SHOW, startUri = EP), resume).positionMs)
        assertEquals(5_000L, PlayerController.withEpisodeResume(PlayRequest(trackUris = listOf(EP), positionMs = 5_000), resume).positionMs)
        assertEquals(0L, PlayerController.withEpisodeResume(PlayRequest(trackUris = listOf("spotify:track:t")), resume).positionMs)
        assertEquals(0L, PlayerController.withEpisodeResume(PlayRequest(contextUri = SHOW), resume).positionMs)
    }

    // ---- Connect handoffs, through the tracker -------------------------------------------------

    private fun snapshot(uri: String = EP, source: PlaybackSource, status: PlaybackStatus = PlaybackStatus.PLAYING, positionMs: Long, at: Long) =
        PlaybackSnapshot(
            source = source,
            status = status,
            positionMs = positionMs,
            positionTimestampMs = at,
            durationMs = 2 * HOUR,
            track = PlaybackTrack(uri = uri, isEpisode = true),
        )

    private fun EpisodeProgressStore.tracker(seeks: MutableList<Long> = mutableListOf()) = EpisodeProgressTracker(object : ResumeSink {
        override fun record(uri: String, positionMs: Long, durationMs: Long) = this@tracker.record(uri, positionMs, durationMs)
        override fun playedElsewhere(uri: String) = this@tracker.playedElsewhere(uri)
        override fun continuedHere(uri: String) = this@tracker.continuedHere(uri)
        override fun resumeMs(uri: String) = this@tracker.resumeMs(uri)
        override fun pointMs(uri: String) = this@tracker.overlay(Episode(uri = uri, name = "")).resumePositionMs
        override fun seek(positionMs: Long) {
            seeks += positionMs
        }
    })

    @Test
    fun progressAfterATransferToThePhoneIsNotOverwrittenByTheOtherDevicesOlderPoint() = runTest {
        val (store, scope) = store()
        store.fresh(NOT_STARTED, at = 100) // E not started; nothing kept
        store.record(EP, 1 * MIN, 2 * HOUR) // an earlier phone session (reference: not started)
        store.fresh(NOT_STARTED, at = 150)
        val tracker = store.tracker()
        // The speaker plays E (this phone is its remote); Spotify's point becomes 50:00.
        now = 200
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 10 * MIN, at = 200), 200)
        // Transferred to this phone at 50:00, played on to 70:00 and paused.
        now = 1_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 50 * MIN, at = 1_000), 1_000)
        now = 2_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 70 * MIN, at = 2_000), 2_000)
        assertEquals(70 * MIN, store.resumeMs(EP))
        // Later a fresh page reports the speaker's 50:00: it only becomes the reference.
        store.fresh(played(50), at = 3_000)
        assertEquals(70 * MIN, store.resumeMs(EP))
        store.fresh(played(50), at = 4_000)
        assertEquals(70 * MIN, store.resumeMs(EP))
        // Played further elsewhere afterwards: news.
        store.fresh(played(95), at = 5_000)
        assertEquals(95 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aTransferAwayLetsTheOtherDevicesNextPointWin() = runTest {
        val (store, scope) = store()
        val tracker = store.tracker()
        now = 100
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 70 * MIN, at = 100), 100)
        // Handed to the speaker, which plays on to 90:00.
        now = 200
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 70 * MIN, at = 200), 200)
        assertEquals(70 * MIN, store.resumeMs(EP))
        store.fresh(played(90), at = 300)
        assertEquals(90 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aPlainLocalPlayKeepsItsProgressAgainstAnUnchangedState() = runTest {
        val (store, scope) = store()
        store.fresh(played(10), at = 50)
        val tracker = store.tracker()
        now = 100
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 10 * MIN, at = 100), 100)
        now = 200
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 40 * MIN, at = 200), 200)
        store.fresh(played(10), at = 300)
        assertEquals(40 * MIN, store.resumeMs(EP))
        store.fresh(played(60), at = 400)
        assertEquals(60 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun autoAdvanceIntoAPartlyPlayedEpisodeResumesItAndKeepsItsPoint() = runTest {
        val (store, scope) = store()
        val e2 = "spotify:episode:e2"
        store.record(e2, 30 * MIN, 2 * HOUR) // last week: 30 of 60 min
        val seeks = mutableListOf<Long>()
        val tracker = store.tracker(seeks)
        // E1 plays to its end; Spirc (or the offline queue) moves on to E2 at 0:00.
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 2 * HOUR - 5_000, at = 1_000), 1_000)
        tracker.onSnapshot(snapshot(uri = e2, source = PlaybackSource.LOCAL, positionMs = 300, at = 6_000), 6_000)
        assertEquals(listOf(30 * MIN), seeks)
        assertEquals("the point is not overwritten before the seek lands", 30 * MIN, store.resumeMs(e2))
        tracker.onSnapshot(snapshot(uri = e2, source = PlaybackSource.LOCAL, positionMs = 30 * MIN, at = 7_000), 7_000)
        tracker.onSnapshot(snapshot(uri = e2, source = PlaybackSource.LOCAL, positionMs = 30 * MIN, at = 7_000), 22_000)
        assertEquals(30 * MIN + 15_000, store.resumeMs(e2))
        assertEquals("seeks once", 1, seeks.size)
        scope.cancel()
    }

    @Test
    fun aResumeSeekThatNeverLandsStopsGuardingAfterAWhile() = runTest {
        val (store, scope) = store()
        store.record(EP, 30 * MIN, 2 * HOUR)
        val seeks = mutableListOf<Long>()
        val tracker = store.tracker(seeks)
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 0, at = 1_000), 1_000)
        assertEquals(1, seeks.size)
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 0, at = 1_000), 5_000)
        assertEquals(30 * MIN, store.resumeMs(EP))
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 0, at = 1_000), 1_000 + EpisodeProgressTracker.RESUME_GRACE_MS + 15_000)
        assertEquals(15_000 + EpisodeProgressTracker.RESUME_GRACE_MS, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun theAppsOwnLoadAtThePointIsNotSeekedAgain() = runTest {
        val (store, scope) = store()
        store.record(EP, 30 * MIN, 2 * HOUR)
        val seeks = mutableListOf<Long>()
        val tracker = store.tracker(seeks)
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 30 * MIN, at = 1_000), 1_000)
        assertTrue(seeks.isEmpty())
        scope.cancel()
    }
}

class EpisodeProgressTrackerTest {
    private val recorded = mutableListOf<Triple<String, Long, Long>>()
    private val marks = mutableListOf<String>()
    private val tracker = EpisodeProgressTracker(object : ResumeSink {
        override fun record(uri: String, positionMs: Long, durationMs: Long) {
            recorded += Triple(uri, positionMs, durationMs)
        }
        override fun playedElsewhere(uri: String) {
            marks += "elsewhere:$uri"
        }
        override fun continuedHere(uri: String) {
            marks += "here:$uri"
        }
        override fun resumeMs(uri: String): Long? = null
        override fun pointMs(uri: String): Long? = null
        override fun seek(positionMs: Long) = Unit
    })

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
        tracker.onSnapshot(snapshot(positionMs = 3_000, at = 1_000), nowMs = 1_000)
        tracker.onSnapshot(snapshot(positionMs = 3_000, at = 1_000), nowMs = 16_000) // the 15 s save
        tracker.onSnapshot(snapshot(status = PlaybackStatus.PAUSED, positionMs = 23_000, at = 21_000), nowMs = 21_000)
        assertEquals(listOf(Triple(EP, 3_000L, 2 * HOUR), Triple(EP, 18_000L, 2 * HOUR), Triple(EP, 23_000L, 2 * HOUR)), recorded)
        assertTrue(marks.isEmpty())
    }

    @Test
    fun theOutgoingEpisodeIsSavedWhereItGotTo() {
        tracker.onSnapshot(snapshot(positionMs = 3_000, at = 1_000), nowMs = 1_000)
        recorded.clear()
        tracker.onSnapshot(snapshot(uri = "spotify:track:t", isEpisode = false, at = 10_000), nowMs = 10_000)
        assertEquals(listOf(Triple(EP, 12_000L, 2 * HOUR)), recorded)
    }

    @Test
    fun anEpisodeThatEndedCountsAsPlayedToTheEnd() {
        tracker.onSnapshot(snapshot(positionMs = 2 * HOUR - 10_000, at = 1_000), nowMs = 1_000)
        recorded.clear()
        tracker.onSnapshot(snapshot(status = PlaybackStatus.STOPPED, positionMs = 0, at = 21_000), nowMs = 21_000)
        assertEquals(listOf(Triple(EP, 2 * HOUR, 2 * HOUR)), recorded)
    }

    @Test
    fun playbackLeavingThePhoneSavesTheEpisodeAndMarksIt() {
        tracker.onSnapshot(snapshot(status = PlaybackStatus.PAUSED, positionMs = 4_000), nowMs = 0)
        recorded.clear()
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 4_000), nowMs = 5_000)
        assertEquals(listOf(Triple(EP, 4_000L, 2 * HOUR)), recorded)
        assertEquals(listOf("elsewhere:$EP"), marks)
        recorded.clear()
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 90_000), nowMs = 9_000)
        assertTrue("a remote device's playback is not recorded here", recorded.isEmpty())
        assertEquals("marked once", 1, marks.size)
    }

    @Test
    fun takingAnEpisodeOverFromAnotherDeviceMarksIt() {
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 600_000), nowMs = 0)
        tracker.onSnapshot(snapshot(positionMs = 600_000, at = 1_000), nowMs = 1_000)
        assertEquals(listOf("elsewhere:$EP", "here:$EP"), marks)
    }

    @Test
    fun arrivingFarFromTheKeptPointIsATakeover() {
        // A transfer received while the app wasn't following the other device.
        tracker.onSnapshot(snapshot(positionMs = 50 * MIN, at = 1_000), nowMs = 1_000)
        assertEquals(listOf("here:$EP"), marks)
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

    @Test
    fun cachedShowPagesCarryNoPlayedState() {
        val page = Show(uri = SHOW, name = "Show", episodes = listOf(Episode(uri = EP, name = "E", resumePositionMs = 0, fullyPlayed = false)))
        for (resource in listOf<Resource<Show>>(Resource.Loading(page), Resource.Success(page, fromCache = true), Resource.Error(Exception(), page))) {
            assertNull(resource.withoutCachedPlayedState().dataOrNull!!.episodes.single().playedPoint())
        }
        assertEquals(PlayedPoint(0, false), Resource.Success(page).withoutCachedPlayedState().dataOrNull!!.episodes.single().playedPoint())
    }
}
