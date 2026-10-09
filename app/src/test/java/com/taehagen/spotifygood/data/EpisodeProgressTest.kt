package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.connect.DevicesRepository
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.Show
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.playback.PlayerController
import com.taehagen.spotifygood.playback.ResumeLoad
import com.taehagen.spotifygood.playback.ResumeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
private val FINISHED = PlayedPoint(0, true)

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

    @Test
    fun anUnchangedStateIsNeverNewsWhateverTheMark() {
        val phone = ResumePoint(25 * MIN, false, 20, byPhone = true)
        for (ref in SpotifyRef.entries) {
            val kept = EpisodeResume(phone, SpotifySeen(FINISHED, 15, ref))
            assertEquals(ref.name, phone, observeSpotify(kept, FINISHED, 30)?.point)
        }
        // A remote point too: Spotify still says what it said before the speaker played.
        val remote = EpisodeResume(ResumePoint(40 * MIN, false, 20), SpotifySeen(played(10), 25, SpotifyRef.NEWER_NEXT))
        assertEquals(remote.point, observeSpotify(remote, played(10), 30)?.point)
    }

    @Test
    fun withNoStateEverSeenAFinishDoesNotEndARelistenHere() {
        // Played here (offline, or the reference was lost): Spotify's "finished" may be from before.
        val phone = EpisodeResume(ResumePoint(25 * MIN, false, 20, byPhone = true))
        assertEquals(phone.point, observeSpotify(phone, FINISHED, 30)?.point)
        assertEquals("further on is still news", ResumePoint(50 * MIN, false, 30), observeSpotify(phone, played(50), 30)?.point)
        // A followed speaker's point with no reference: its device may have finished it.
        val remote = EpisodeResume(ResumePoint(25 * MIN, false, 20), SpotifySeen(null, 20, SpotifyRef.NEWER_NEXT))
        assertEquals(ResumePoint(0, true, 30), observeSpotify(remote, FINISHED, 30)?.point)
        // With a reference that changed, a finish is news over the phone's progress too.
        val seen = EpisodeResume(phone.point, SpotifySeen(played(10), 15, SpotifyRef.ADOPT_NEXT))
        assertEquals(ResumePoint(0, true, 30), observeSpotify(seen, FINISHED, 30)?.point)
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
        val request = PlayerController.withEpisodeResume(PlayRequest(contextUri = SHOW, startUri = EP)) { uri, _ -> store.resumeMs(uri) }
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
    fun anEpisodePlayRequestResumesWhereItWasLeft() = runTest {
        val resume: suspend (String, StoredPosition?) -> Long? = { uri, _ -> if (uri == EP) 50 * MIN else null }
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

    private fun EpisodeProgressStore.tracker(
        seeks: MutableList<Long> = mutableListOf(),
        lookups: MutableList<String> = mutableListOf(),
    ) = EpisodeProgressTracker(object : ResumeSink {
        override fun record(uri: String, positionMs: Long, durationMs: Long, elsewhere: Boolean, stateAt: Long?) =
            this@tracker.record(uri, positionMs, durationMs, elsewhere, stateAt)
        override fun shouldLookUp(uri: String) = this@tracker.shouldLookUp(uri)
        override fun continuedHere(uri: String) = this@tracker.continuedHere(uri)
        override fun resumeMs(uri: String) = this@tracker.resumeMs(uri)
        override fun pointMs(uri: String) = this@tracker.overlay(Episode(uri = uri, name = "")).resumePositionMs
        override fun seek(positionMs: Long) {
            seeks += positionMs
        }
        override fun lookUp(uri: String) {
            lookups += uri
        }
    })

    @Test
    fun progressAfterATransferToThePhoneIsNotOverwrittenByTheOtherDevicesOlderPoint() = runTest {
        val (store, scope) = store()
        store.fresh(NOT_STARTED, at = 100) // E not started; nothing kept
        store.record(EP, 1 * MIN, 2 * HOUR) // an earlier phone session (reference: not started)
        store.fresh(NOT_STARTED, at = 150)
        val tracker = store.tracker()
        // The speaker plays E (this phone is its remote and follows it); Spotify's point becomes 50:00.
        now = 200
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 10 * MIN, at = 200), 200)
        assertEquals("the phone keeps what it sees the speaker play", 10 * MIN, store.resumeMs(EP))
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
    fun anEpisodePlayedOnASpeakerThisPhoneControlsIsKept() = runTest {
        val (store, scope) = store()
        store.fresh(NOT_STARTED, at = 50) // the show page: not started, nothing kept
        val tracker = store.tracker()
        now = 100
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 1_000, at = 100), 100)
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 1_000, at = 100), 100 + 30 * MIN) // the 15 s saves
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 30 * MIN, at = 200), 200)
        assertEquals(30 * MIN, store.resumeMs(EP))
        // Tapping it again (from the show page, Downloads, Auto) resumes 30:00 on the speaker.
        assertEquals(30 * MIN, PlayerController.withEpisodeResume(PlayRequest(contextUri = SHOW, startUri = EP)) { uri, _ -> store.resumeMs(uri) }.positionMs)
        // A librespot speaker reports nothing: a later "not started" answer doesn't wipe it ...
        store.fresh(NOT_STARTED, at = 1_000)
        assertEquals(30 * MIN, store.resumeMs(EP))
        // ... a real later point does replace it.
        store.fresh(played(45), at = 2_000)
        assertEquals(45 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    /** The speaker plays E from [from] to [to] (minutes) while this phone follows it, then pauses. */
    private fun EpisodeProgressTracker.followSpeaker(from: Long, to: Long, at: Long) {
        onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = from * MIN, at = at), at)
        onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = to * MIN, at = at + 1), at + 1)
    }

    @Test
    fun aSpeakerThatReportsNothingKeepsItsProgressAgainstSpotifysOlderPoint() = runTest {
        val (store, scope) = store()
        store.fresh(played(10), at = 100) // the desktop left it at 10:00 (the pre-play lookup saw it)
        now = 200
        store.tracker().followSpeaker(10, 40, at = 200) // a librespot receiver plays on to 40:00
        assertEquals(40 * MIN, store.resumeMs(EP))
        store.fresh(played(10), at = 1_000) // Spotify still says 10:00
        assertEquals(40 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun notStartedAfterARemoteSaveChangesNothing() = runTest {
        val (store, scope) = store()
        store.fresh(played(10), at = 100)
        now = 200
        store.tracker().followSpeaker(10, 40, at = 200)
        store.fresh(NOT_STARTED, at = 1_000)
        assertEquals(40 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun anEpisodeTheSpeakerFinishedStaysFinished() = runTest {
        val (store, scope) = store()
        store.fresh(played(10), at = 100)
        now = 200
        val tracker = store.tracker()
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 10 * MIN, at = 200), 200)
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 2 * HOUR - 10_000, at = 300), 300)
        assertEquals(true, store.overlay(episode()).fullyPlayed)
        store.fresh(played(10), at = 1_000)
        assertEquals(true, store.overlay(episode()).fullyPlayed)
        scope.cancel()
    }

    @Test
    fun aLaterPointFromAReportingSpeakerOrElsewhereStillWins() = runTest {
        val (store, scope) = store()
        store.fresh(played(10), at = 100)
        now = 200
        store.tracker().followSpeaker(10, 40, at = 200)
        store.fresh(played(55), at = 1_000)
        assertEquals(55 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun continuingHereWhatTheSpeakerPlayedKeepsThePhonesNewerProgress() = runTest {
        val (store, scope) = store()
        now = 100
        store.tracker().followSpeaker(1, 40, at = 100) // an official device: it reports 40:00 to Spotify
        // Next morning (process restarted: a new tracker): resumed here at 40:00, played to 55:00.
        val tracker = store.tracker()
        now = 1_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 40 * MIN, at = 1_000), 1_000)
        now = 2_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 55 * MIN, at = 2_000), 2_000)
        store.fresh(played(40), at = 3_000) // the speaker's 40:00
        assertEquals(55 * MIN, store.resumeMs(EP))
        store.fresh(played(40), at = 4_000)
        assertEquals(55 * MIN, store.resumeMs(EP))
        // Played further elsewhere afterwards, or finished: news.
        store.fresh(played(70), at = 5_000)
        assertEquals(70 * MIN, store.resumeMs(EP))
        store.fresh(PlayedPoint(0, true), at = 6_000)
        assertEquals(true, store.overlay(episode()).fullyPlayed)
        scope.cancel()
    }

    @Test
    fun continuingHereAfterTheSpeakerMovedOnToMusicKeepsThePhonesProgress() = runTest {
        val (store, scope) = store()
        val tracker = store.tracker()
        now = 100
        tracker.followSpeaker(1, 30, at = 100)
        // The speaker then plays a song (no takeover seen any more), and this phone continues E.
        tracker.onSnapshot(
            snapshot(uri = "spotify:track:t", source = PlaybackSource.REMOTE, positionMs = 1_000, at = 200).let { it.copy(track = it.track!!.copy(isEpisode = false)) },
            200,
        )
        now = 1_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 30 * MIN, at = 1_000), 1_000)
        now = 2_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 55 * MIN, at = 2_000), 2_000)
        store.fresh(played(30), at = 3_000)
        assertEquals(55 * MIN, store.resumeMs(EP))
        store.fresh(played(70), at = 4_000)
        assertEquals(70 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aPlayOfAnEpisodeLastPlayedOnAFollowedSpeakerLooksUpFirst() = runTest {
        val (store, scope) = store()
        now = 100
        store.tracker().followSpeaker(1, 30, at = 100)
        now = 10 * HOUR // much later: the speaker may have played on after the phone stopped following
        var calls = 0
        val position = store.resumeOrLookUp(EP, online = { true }) {
            calls++
            store.fresh(played(50), at = now)
        }
        assertEquals(1, calls)
        assertEquals(50 * MIN, position)
        scope.cancel()
    }

    @Test
    fun aRemoteRestartNearTheStartDoesNotReplaceAPointFurtherOn() = runTest {
        val (store, scope) = store()
        store.record(EP, 30 * MIN, 2 * HOUR)
        val tracker = store.tracker()
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 800, at = 100), 100)
        assertEquals(30 * MIN, store.resumeMs(EP))
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
    fun nextWhilePausedSeeksOnlyOnceTheLoadSettled() = runTest {
        val (store, scope) = store()
        val e2 = "spotify:episode:e2"
        store.record(e2, 35 * MIN, 2 * HOUR)
        val seeks = mutableListOf<Long>()
        val tracker = store.tracker(seeks)
        // Paused on a 20 min E1, then Next: Spirc loads E2 paused, still with E1's duration (a
        // seek to 35:00 now would be dropped against it).
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 5 * MIN, at = 1_000), 1_000)
        val loading = snapshot(uri = e2, source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 0, at = 2_000)
            .copy(loading = true, durationMs = 20 * MIN)
        tracker.onSnapshot(loading, 2_000)
        assertTrue("no seek during the load", seeks.isEmpty())
        assertEquals("nothing recorded with the previous item's duration", 35 * MIN, store.resumeMs(e2))
        // settled paused at 0:00 with its own duration: the resume seek goes out now
        tracker.onSnapshot(snapshot(uri = e2, source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 0, at = 3_000), 3_000)
        assertEquals(listOf(35 * MIN), seeks)
        assertEquals(35 * MIN, store.resumeMs(e2))
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
    fun arrivingAtAnEpisodeWithNoPointLooksItUpFirst() = runTest {
        val (store, scope) = store()
        val seeks = mutableListOf<Long>()
        val lookups = mutableListOf<String>()
        val tracker = store.tracker(seeks, lookups)
        // Auto-advance (or a show context load) into E, nothing known about it here.
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 300, at = 1_000), 1_000)
        assertEquals(listOf(EP), lookups)
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 300, at = 1_000), 2_500)
        assertNull("nothing saved while the lookup runs", store.resumeMs(EP))
        // The lookup's fresh answer: Spotify has it at 30:00.
        store.fresh(played(30), at = 1_000)
        tracker.onLookedUp(EP, 3_000)
        assertEquals(listOf(30 * MIN), seeks)
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 30 * MIN, at = 3_500), 3_500)
        assertEquals(30 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aLookupThatBringsNothingLetsSavingResume() = runTest {
        val (store, scope) = store()
        val seeks = mutableListOf<Long>()
        val tracker = store.tracker(seeks)
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 300, at = 1_000), 1_000)
        tracker.onLookedUp(EP, 2_000) // not started on Spotify either
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 300, at = 1_000), 16_000)
        assertTrue(seeks.isEmpty())
        assertEquals(15_300L, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aLookupAnsweringAfterTheUserMovedOnDoesNotSeek() = runTest {
        val (store, scope) = store()
        val seeks = mutableListOf<Long>()
        val tracker = store.tracker(seeks)
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 300, at = 1_000), 1_000)
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 20 * MIN, at = 2_000), 2_000) // the user seeked
        store.fresh(played(30), at = 1_000)
        tracker.onLookedUp(EP, 3_000)
        assertTrue(seeks.isEmpty())
        scope.cancel()
    }

    // ---- lookups before a play ---------------------------------------------------------------

    @Test
    fun aPlayOfAnUnknownEpisodeLooksSpotifysPointUp() = runTest {
        val (store, scope) = store()
        var calls = 0
        val lookUp: suspend (String) -> Unit = { uri ->
            calls++
            store.fresh(played(30), at = now, uri = uri)
        }
        // A Your Episodes tap: a track list starting at E, no position.
        val request = PlayerController.withEpisodeResume(PlayRequest(trackUris = listOf(EP), startIndex = 0)) { uri, stored ->
            store.resumeOrLookUp(uri, online = { true }, stored, lookUp)
        }
        assertEquals(30 * MIN, request.positionMs)
        assertEquals(1, calls)
        scope.cancel()
    }

    @Test
    fun aLookupThatTimesOutPlaysFromTheStartWithinTheBound() = runTest {
        val (store, scope) = store()
        val started = testScheduler.currentTime
        val position = store.resumeOrLookUp(EP, online = { true }) { awaitCancellation() }
        assertNull(position)
        assertEquals(EpisodeProgressStore.LOOKUP_TIMEOUT_MS, testScheduler.currentTime - started)
        scope.cancel()
    }

    @Test
    fun noLookupWhenThePointIsKnownOfflineOrJustSeen() = runTest {
        val (store, scope) = store()
        var calls = 0
        val lookUp: suspend (String) -> Unit = { calls++ }
        store.record(EP, 20 * MIN, 2 * HOUR)
        assertEquals(20 * MIN, store.resumeOrLookUp(EP, { true }, lookUp = lookUp))
        assertNull(store.resumeOrLookUp("spotify:episode:offline", { false }, lookUp = lookUp))
        store.fresh(NOT_STARTED, at = now, uri = "spotify:episode:seen") // a fresh page just said "not started"
        assertNull(store.resumeOrLookUp("spotify:episode:seen", { true }, lookUp = lookUp))
        assertEquals(0, calls)
        scope.cancel()
    }

    @Test
    fun aPhoneSaveNewerThanTheLookupsRequestWins() = runTest {
        val (store, scope) = store()
        store.fresh(played(10), at = 100)
        now = 5_000
        store.record(EP, 40 * MIN, 2 * HOUR)
        // A lookup requested before that save answers afterwards with a changed state.
        store.fresh(played(25), at = 4_000)
        assertEquals(40 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun withoutAReferenceTheFurthestPointWins() = runTest {
        val (store, scope) = store()
        // Started here at 0 without knowing Spotify's point (a cached page, a failed lookup).
        store.record(EP, 15_000, 2 * HOUR)
        store.fresh(played(30), at = 2_000)
        assertEquals(30 * MIN, store.resumeMs(EP))
        // Played offline beyond Spotify's: the phone's stays.
        store.record("spotify:episode:b", 50 * MIN, 2 * HOUR)
        store.fresh(played(30), at = 3_000, uri = "spotify:episode:b")
        assertEquals(50 * MIN, store.resumeMs("spotify:episode:b"))
        scope.cancel()
    }

    // ---- a remote device sitting paused (round 15, item 2) ----------------------------------------

    @Test
    fun aRemoteDeviceSittingPausedSinceBeforeThePhonesSaveChangesNothing() = runTest {
        val (store, scope) = store()
        now = 2_000
        store.record(EP, 55 * MIN, 2 * HOUR) // played here offline, paused
        // The engine starts later; the desktop is still the active device, paused at 40:00 since t=1000.
        now = 3_000
        store.tracker().onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 40 * MIN, at = 1_000), 3_000)
        assertEquals(55 * MIN, store.resumeMs(EP))
        now = 3_000 + EpisodeProgressStore.OBSERVED_FRESH_MS
        assertFalse("no followed-remote mark either", store.shouldLookUp(EP))
        // A fresh answer (still the desktop's 40:00) doesn't change it.
        store.fresh(played(40), at = now)
        assertEquals(55 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aRemoteDevicePausedAfterThePhonesSaveWinsStampedWhenItPaused() = runTest {
        val (store, scope) = store()
        now = 2_000
        store.record(EP, 55 * MIN, 2 * HOUR)
        now = 3_000
        store.tracker().onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 40 * MIN, at = 2_500), 3_000)
        assertEquals("newer: it was played (back) there afterwards", 40 * MIN, store.resumeMs(EP))
        // Stamped 2500, not 3000: a pause from 2400 seen later is older, one from 2600 newer.
        store.tracker().onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 45 * MIN, at = 2_400), 3_500)
        assertEquals(40 * MIN, store.resumeMs(EP))
        store.tracker().onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 45 * MIN, at = 2_600), 3_600)
        assertEquals(45 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aRemoteDevicePlayingNowWinsWhateverItsTimestamp() = runTest {
        val (store, scope) = store()
        now = 2_000
        store.record(EP, 55 * MIN, 2 * HOUR)
        now = 3_000
        // Playing since t=1000 at 40:00 (the 15 s saves re-emit that snapshot): it plays now.
        store.tracker().onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 40 * MIN, at = 1_000), 3_000)
        assertEquals(40 * MIN + 2_000, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aRemotePauseWithNoTimestampOnlyCountsWhenItWasSeenPlaying() = runTest {
        val (store, scope) = store()
        now = 2_000
        store.record(EP, 55 * MIN, 2 * HOUR)
        now = 3_000
        val tracker = store.tracker()
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 40 * MIN, at = 0), 3_000)
        assertEquals("when it paused is unknown: the phone's point stays", 55 * MIN, store.resumeMs(EP))
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 40 * MIN, at = 3_000), 3_000)
        now = 4_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 41 * MIN, at = 0), 4_000)
        assertEquals(41 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    // ---- a re-listen of an episode Spotify has as finished (round 15, item 3) --------------------

    @Test
    fun aRelistenHereKeepsItsProgressAgainstSpotifysUnchangedFinish() = runTest {
        val (store, scope) = store()
        store.fresh(FINISHED, at = 100) // finished on the desktop months ago: nothing kept
        now = 200
        store.record(EP, 25 * MIN, 2 * HOUR) // played again here from 0:00 to 25:00
        store.fresh(FINISHED, at = 300) // Your Episodes again: still "finished"
        assertEquals(25 * MIN, store.resumeMs(EP))
        assertEquals(false, store.overlay(episode(state = FINISHED)).fullyPlayed)
        // Played elsewhere afterwards: news.
        store.fresh(played(60), at = 400)
        assertEquals(60 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aRelistenThroughTheTrackerKeepsItsProgress() = runTest {
        val (store, scope) = store()
        now = 100
        store.fresh(FINISHED, at = 100)
        val lookups = mutableListOf<String>()
        val tracker = store.tracker(lookups = lookups)
        // Tapped: it starts at 0:00 (fully played), plays to 25:00 and is paused.
        now = 1_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 300, at = 1_000), 1_000)
        assertTrue("just seen: no lookup", lookups.isEmpty())
        now = 2_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 25 * MIN, at = 2_000), 2_000)
        store.fresh(FINISHED, at = 3_000)
        assertEquals(25 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun aTakeoverOfAFinishedEpisodeKeepsThePhonesProgressAgainstTheUnchangedFinish() = runTest {
        val (store, scope) = store()
        store.fresh(FINISHED, at = 100)
        now = 200
        val tracker = store.tracker()
        // Another device re-plays it (it reports nothing); handed to this phone at 30:00, played to 45:00.
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 30 * MIN, at = 200), 200)
        now = 1_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 30 * MIN, at = 1_000), 1_000)
        now = 2_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 45 * MIN, at = 2_000), 2_000)
        store.fresh(FINISHED, at = 3_000)
        assertEquals(45 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun forgettingTheAccountForgetsTheStatesSeenToo() = runTest {
        val (store, scope) = store()
        store.fresh(played(10), at = 100) // the previous account's
        store.fresh(NOT_STARTED, at = 100, uri = "spotify:episode:b")
        store.clear()
        now = 200
        store.record(EP, 40 * MIN, 2 * HOUR) // the next account's progress here, no reference
        // Not "changed from 10:00" (another account's): the furthest point wins.
        store.fresh(played(20), at = 300)
        assertEquals(40 * MIN, store.resumeMs(EP))
        assertTrue("nothing counts as just seen", store.shouldLookUp("spotify:episode:b"))
        scope.cancel()
    }

    // ---- arrival at an episode a followed speaker left ------------------------------------------

    @Test
    fun arrivingAtAnEpisodeAFollowedSpeakerLeftLooksItUpBeforeSeeking() = runTest {
        val (store, scope) = store()
        now = 100
        store.record(EP, 30 * MIN, 2 * HOUR, elsewhere = true)
        now = 100 + EpisodeProgressStore.OBSERVED_FRESH_MS
        val seeks = mutableListOf<Long>()
        val lookups = mutableListOf<String>()
        val tracker = store.tracker(seeks, lookups)
        // Auto-advance into E.
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 300, at = now), now)
        assertEquals(listOf(EP), lookups)
        assertTrue(seeks.isEmpty())
        store.fresh(played(50), at = now) // the speaker played on to 50:00
        tracker.onLookedUp(EP, now + 1_000)
        assertEquals(listOf(50 * MIN), seeks)
        scope.cancel()
    }

    // ---- "Mark as played" / "Mark as unplayed" (round 16) ----------------------------------------

    @Test
    fun aPlayedMarkSurvivesAnUnchangedStateAndAChangedOneReplacesIt() = runTest {
        val (store, scope) = store()
        store.fresh(played(30), at = 100)
        now = 200
        store.markPlayed(EP, played = true)
        assertEquals(true, store.overlay(episode(state = played(30))).fullyPlayed)
        assertNull("restarts from the beginning", store.resumeMs(EP))
        store.fresh(played(30), at = 300) // nothing is reported to Spotify: still 30:00
        assertEquals(true, store.overlay(episode()).fullyPlayed)
        // An answer requested before the mark, delivered after it, means nothing.
        store.fresh(played(45), at = 150)
        assertEquals(true, store.overlay(episode()).fullyPlayed)
        // Played further elsewhere afterwards: news.
        store.fresh(played(50), at = 400)
        assertEquals(50 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun anUnplayedMarkClearsTheProgressAndNeedsNoLookup() = runTest {
        val (store, scope) = store()
        store.fresh(NOT_STARTED, at = 100)
        now = 200
        store.record(EP, 12 * MIN, 2 * HOUR) // played here to 12:00, then skipped
        now = 300
        store.markPlayed(EP, played = false)
        assertNull(store.resumeMs(EP))
        assertEquals(0L, store.overlay(episode(state = played(12))).resumePositionMs)
        assertEquals(false, store.overlay(episode()).fullyPlayed)
        now += EpisodeProgressStore.OBSERVED_FRESH_MS + 1
        assertFalse("kept as an entry: no lookup brings an old point back", store.shouldLookUp(EP))
        store.fresh(NOT_STARTED, at = now)
        assertNull(store.resumeMs(EP))
        // Arriving at it (auto-advance) seeks nowhere, nor looks it up.
        val seeks = mutableListOf<Long>()
        val lookups = mutableListOf<String>()
        store.tracker(seeks, lookups).onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 300, at = now), now)
        assertTrue(seeks.isEmpty())
        assertTrue(lookups.isEmpty())
        scope.cancel()
    }

    @Test
    fun aMarkEndsAFollowedSpeakersMark() = runTest {
        val (store, scope) = store()
        now = 100
        store.record(EP, 30 * MIN, 2 * HOUR, elsewhere = true)
        now = 200
        store.markPlayed(EP, played = false)
        now += EpisodeProgressStore.OBSERVED_FRESH_MS + 1
        assertFalse(store.shouldLookUp(EP))
        scope.cancel()
    }

    @Test
    fun theEpisodePlayingKeepsItsMarkUntilASeekOrItsEnd() = runTest {
        val (store, scope) = store()
        val tracker = store.tracker()
        now = 1_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 12 * MIN, at = 1_000), 1_000)
        store.markPlayed(EP, played = true)
        tracker.onMarked(EP)
        // The 15 s saves and the pause don't overwrite it.
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 12 * MIN, at = 1_000), 16_000)
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, positionMs = 12 * MIN + 19_000, at = 20_000), 20_000)
        assertEquals(true, store.overlay(episode()).fullyPlayed)
        // Resumed: still kept.
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 12 * MIN + 19_000, at = 21_000), 21_000)
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 12 * MIN + 19_000, at = 21_000), 36_000)
        assertEquals(true, store.overlay(episode()).fullyPlayed)
        // A seek: the user plays it again from there.
        now = 30_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 5 * MIN, at = 30_000), 30_000)
        assertEquals(5 * MIN, store.resumeMs(EP))
        scope.cancel()
    }

    @Test
    fun anUnplayedMarkOfTheEpisodePlayingEndsWithIt() = runTest {
        val (store, scope) = store()
        val tracker = store.tracker()
        val next = "spotify:episode:next"
        now = 1_000
        tracker.onSnapshot(snapshot(source = PlaybackSource.LOCAL, positionMs = 12 * MIN, at = 1_000), 1_000)
        store.markPlayed(EP, played = false)
        tracker.onMarked(EP)
        // Skipped to the next episode: leaving it doesn't save it, the next one records again.
        now = 5_000
        tracker.onSnapshot(snapshot(uri = next, source = PlaybackSource.LOCAL, positionMs = 10 * MIN, at = 5_000), 5_000)
        assertNull(store.resumeMs(EP))
        assertEquals(10 * MIN, store.resumeMs(next))
        // Marked unplayed near its end, and listened to the end after all: it is played.
        val last = "spotify:episode:last"
        now = 20_000
        tracker.onSnapshot(snapshot(uri = last, source = PlaybackSource.LOCAL, positionMs = 2 * HOUR - 60_000, at = 20_000), 20_000)
        store.markPlayed(last, played = false)
        tracker.onMarked(last)
        tracker.onSnapshot(snapshot(uri = last, source = PlaybackSource.LOCAL, positionMs = 2 * HOUR - 60_000, at = 20_000), 35_000)
        assertNull(store.resumeMs(last))
        tracker.onSnapshot(snapshot(uri = last, source = PlaybackSource.LOCAL, status = PlaybackStatus.STOPPED, positionMs = 0, at = 81_000), 81_000)
        assertEquals(true, store.overlay(episode(uri = last)).fullyPlayed)
        scope.cancel()
    }

    @Test
    fun aMarkReachesTheTrackerFollowingPlayback() = runTest {
        val (store, scope) = store()
        val snapshots = MutableStateFlow(PlaybackSnapshot())
        val job = backgroundScope.launch { store.recordFrom(snapshots, now = { testScheduler.currentTime + 1_000 }) }
        now = 1_000
        snapshots.value = snapshot(source = PlaybackSource.LOCAL, positionMs = 12 * MIN, at = testScheduler.currentTime + 1_000)
        runCurrent()
        assertEquals(12 * MIN, store.resumeMs(EP))
        store.markPlayed(EP, played = true)
        runCurrent()
        advanceTimeBy(EpisodeProgressStore.SAVE_INTERVAL_MS * 2 + 1)
        runCurrent()
        assertEquals("the 15 s saves keep the mark", true, store.overlay(episode()).fullyPlayed)
        job.cancel()
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
    /** When each recorded position dates from (null: now). */
    private val stamps = mutableListOf<Long?>()
    private val marks = mutableListOf<String>()
    private val tracker = EpisodeProgressTracker(object : ResumeSink {
        override fun record(uri: String, positionMs: Long, durationMs: Long, elsewhere: Boolean, stateAt: Long?) {
            recorded += Triple(uri, positionMs, durationMs)
            stamps += stateAt
        }
        override fun shouldLookUp(uri: String) = true
        override fun continuedHere(uri: String) {
            marks += "here:$uri"
        }
        override fun resumeMs(uri: String): Long? = null
        override fun pointMs(uri: String): Long? = null
        override fun seek(positionMs: Long) = Unit
        override fun lookUp(uri: String) = Unit
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
        tracker.onSnapshot(snapshot(positionMs = 10_000, at = 1_000), nowMs = 1_000)
        tracker.onSnapshot(snapshot(positionMs = 10_000, at = 1_000), nowMs = 16_000) // the 15 s save
        tracker.onSnapshot(snapshot(status = PlaybackStatus.PAUSED, positionMs = 30_000, at = 21_000), nowMs = 21_000)
        assertEquals(listOf(Triple(EP, 10_000L, 2 * HOUR), Triple(EP, 25_000L, 2 * HOUR), Triple(EP, 30_000L, 2 * HOUR)), recorded)
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
    fun playbackLeavingThePhoneSavesTheEpisodeThenFollowsTheOtherDevice() {
        tracker.onSnapshot(snapshot(status = PlaybackStatus.PAUSED, positionMs = 4_000), nowMs = 0)
        recorded.clear()
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 6_000), nowMs = 5_000)
        assertEquals("the phone's own, then the speaker's", listOf(Triple(EP, 4_000L, 2 * HOUR), Triple(EP, 6_000L, 2 * HOUR)), recorded)
        recorded.clear()
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 90_000), nowMs = 9_000)
        assertEquals(listOf(Triple(EP, 90_000L, 2 * HOUR)), recorded)
        assertTrue(marks.isEmpty())
    }

    @Test
    fun takingAnEpisodeOverFromAnotherDeviceMarksIt() {
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 600_000), nowMs = 0)
        tracker.onSnapshot(snapshot(positionMs = 600_000, at = 1_000), nowMs = 1_000)
        assertEquals(listOf("here:$EP"), marks)
    }

    @Test
    fun arrivingFarFromTheKeptPointIsATakeover() {
        // A transfer received while the app wasn't following the other device.
        tracker.onSnapshot(snapshot(positionMs = 50 * MIN, at = 1_000), nowMs = 1_000)
        assertEquals(listOf("here:$EP"), marks)
    }

    @Test
    fun remoteSavesDateFromWhenTheDevicesPositionDoes() {
        // Playing (also the 15 s re-emits of an old snapshot): now.
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 60_000, at = 1_000), nowMs = 1_000)
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, positionMs = 60_000, at = 1_000), nowMs = 16_000)
        // Paused: when it paused.
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 80_000, at = 20_000), nowMs = 21_000)
        // Paused without a timestamp: now, having seen it play ...
        tracker.onSnapshot(snapshot(source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 80_000, at = 0), nowMs = 22_000)
        // ... "long ago" for another episode it was never seen playing.
        tracker.onSnapshot(snapshot(uri = "spotify:episode:b", source = PlaybackSource.REMOTE, status = PlaybackStatus.PAUSED, positionMs = 90_000, at = 0), nowMs = 23_000)
        assertEquals(listOf(null, null, 20_000L, null, null, 0L), stamps)
        assertEquals(listOf(60_000L, 75_000L, 80_000L, 80_000L, 80_000L, 90_000L), recorded.map { it.second })
        // Local saves are now.
        stamps.clear()
        tracker.onSnapshot(snapshot(positionMs = 100_000, at = 30_000), nowMs = 30_000)
        assertEquals(listOf(0L, null), stamps)
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

/** Every play path resumes through the controller's one decision ([PlayerController.episodeResume]). */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeResumeOnPlayTest {
    private var now = 1_000L
    private var online = true
    private var lookups = 0
    /** What the lookup's fresh answer says (null: it never answers). */
    private var spotifySays: PlayedPoint? = null

    /** The stored session (ResumeStore) the in-app Play fallback loads. */
    private var lastSession: ResumeState? = null
    /** `player.play` fails as with no active device (a cold start): the stored session is loaded. */
    private var noActiveDevice = false

    private class Fixture(val store: EpisodeProgressStore, val controller: PlayerController, val loads: MutableList<JsonObject>)

    private fun TestScope.fixture(): Fixture {
        val store = EpisodeProgressStore(null, backgroundScope, clock = { now }, io = StandardTestDispatcher(testScheduler))
        val loads = mutableListOf<JsonObject>()
        val controller = PlayerController(
            scope = backgroundScope,
            transport = { method, args ->
                if (method == "player.play" && noActiveDevice) throw NativeException(NativeErrorInfo(NativeErrorCode.NOT_ACTIVE_DEVICE, "inactive"))
                if (method == "player.load") loads += args
                JsonObject(emptyMap())
            },
            json = Json,
            snapshot = MutableStateFlow(PlaybackSnapshot()),
            lastSession = { lastSession },
        )
        // As the app graph installs it.
        controller.episodeResume = { uri, stored ->
            store.resumeOrLookUp(uri, { online }, stored) { episode ->
                lookups++
                val state = spotifySays ?: awaitCancellation()
                store.observe(listOf(Episode(uri = episode, name = "E", durationMs = 2 * HOUR, resumePositionMs = state.positionMs, fullyPlayed = state.fullyPlayed)), now)
            }
        }
        return Fixture(store, controller, loads)
    }

    private fun JsonObject.position() = this["positionMs"]!!.jsonPrimitive.content.toLong()

    /** The speaker left E at 30:00 while this phone followed it; much later it may have played on. */
    private fun Fixture.followedSpeakerLeft(min: Long) {
        store.record(EP, min * MIN, 2 * HOUR, elsewhere = true)
        now += EpisodeProgressStore.OBSERVED_FRESH_MS + 1
    }

    @Test
    fun aPlayOfAnEpisodeAFollowedSpeakerLeftLooksSpotifysPointUpFirst() = runTest {
        val f = fixture()
        f.followedSpeakerLeft(30)
        spotifySays = played(50)
        // A Your Episodes / Downloads tap (in-app).
        f.controller.playTracks(listOf(EP))
        runCurrent()
        assertEquals(50 * MIN, f.loads.single().position())
        assertEquals(1, lookups)
    }

    @Test
    fun aMediaSessionLoadAndAShowPageTapLookItUpToo() = runTest {
        val f = fixture()
        f.followedSpeakerLeft(30)
        spotifySays = played(50)
        // Android Auto / Assistant: through the media session, on this phone.
        assertTrue(f.controller.playAsync(PlayRequest(trackUris = listOf(EP), startIndex = 0), onThisPhone = true).await())
        assertEquals(50 * MIN, f.loads.last().position())
        // Another one, a speaker's point again: the show page's row names no position either.
        f.followedSpeakerLeft(30)
        spotifySays = played(70)
        f.controller.play(PlayRequest(contextUri = SHOW, startUri = EP))
        runCurrent()
        assertEquals(70 * MIN, f.loads.last().position())
        assertEquals(2, lookups)
    }

    @Test
    fun theKeptPointPlaysWhenTheLookupTimesOutOrIsOffline() = runTest {
        val f = fixture()
        f.followedSpeakerLeft(30)
        spotifySays = null // never answers
        assertTrue(f.controller.playAsync(PlayRequest(trackUris = listOf(EP), startIndex = 0)).await())
        assertEquals(30 * MIN, f.loads.last().position())
        online = false
        assertTrue(f.controller.playAsync(PlayRequest(trackUris = listOf(EP), startIndex = 0)).await())
        assertEquals(30 * MIN, f.loads.last().position())
        assertEquals("offline: no lookup", 1, lookups)
    }

    @Test
    fun thePhonesOwnPointPlaysAtOnceWithoutALookup() = runTest {
        val f = fixture()
        f.store.record(EP, 40 * MIN, 2 * HOUR)
        now += EpisodeProgressStore.OBSERVED_FRESH_MS + 1
        spotifySays = played(10)
        assertTrue(f.controller.playAsync(PlayRequest(trackUris = listOf(EP), startIndex = 0)).await())
        assertEquals(40 * MIN, f.loads.single().position())
        assertEquals(0, lookups)
    }

    @Test
    fun aRequestNamingAPositionKeepsIt() = runTest {
        val f = fixture()
        f.followedSpeakerLeft(30)
        spotifySays = played(50)
        assertTrue(f.controller.playAsync(PlayRequest(trackUris = listOf(EP), positionMs = 5_000)).await())
        assertEquals(5_000L, f.loads.single().position())
        assertEquals(0, lookups)
    }

    // ---- the stored session's resumes (round 16) ------------------------------------------------

    /** A stored session of E at [min] minutes, its position dating from [at]. */
    private fun storedSession(min: Long, at: Long?) = ResumeState(
        contextUri = SHOW, trackUri = EP, positionMs = min * MIN, title = "E", artist = null, album = null,
        artworkUrl = null, durationMs = 2 * HOUR, isEpisode = true, positionAt = at,
    )

    /** What the session player sends for a resume item (Bluetooth / Auto resumption, Tap to resume, "play something"). */
    private fun resumeItemLoad(state: ResumeState) = state.resumeLoad.applyTo(
        PlayRequest(contextUri = SHOW, startUri = EP, positionMs = state.positionMs),
    )

    @Test
    fun aResumptionOlderThanThePhonesProgressStartsAtThePhonesPoint() = runTest {
        val f = fixture()
        now = 2_000
        f.store.record(EP, 55 * MIN, 2 * HOUR) // played here offline, after the desktop paused at 40:00
        // The stored session took the desktop sitting paused at 40:00 since t=1000.
        val stored = storedSession(40, at = 1_000)
        assertTrue(f.controller.playAsync(resumeItemLoad(stored), onThisPhone = true).await())
        assertEquals(55 * MIN, f.loads.last().position())
        // A state stored by an older version (no time): the point wins as well.
        assertTrue(f.controller.playAsync(resumeItemLoad(storedSession(40, at = null)), onThisPhone = true).await())
        assertEquals(55 * MIN, f.loads.last().position())
        assertEquals(0, lookups)
    }

    @Test
    fun theInAppPlayFallbackResumesTheStoredSessionAtThePhonesNewerPoint() = runTest {
        val f = fixture()
        now = 2_000
        f.store.record(EP, 55 * MIN, 2 * HOUR)
        // Nothing active (a cold start): Play loads the stored session.
        lastSession = storedSession(40, at = 1_000)
        noActiveDevice = true
        assertTrue(f.controller.resumeAsync().await())
        assertEquals(55 * MIN, f.loads.single().position())
    }

    @Test
    fun aResumptionNewerThanThePointKeepsItsPosition() = runTest {
        val f = fixture()
        now = 2_000
        f.store.record(EP, 55 * MIN, 2 * HOUR)
        assertTrue(f.controller.playAsync(resumeItemLoad(storedSession(40, at = 3_000)), onThisPhone = true).await())
        assertEquals(40 * MIN, f.loads.last().position())
        // With no point kept at all (Spotify knows none either), the stored position plays.
        spotifySays = NOT_STARTED
        assertTrue(f.controller.playAsync(storedSession(40, at = 3_000).copy(trackUri = "spotify:episode:other").toPlayRequest(), onThisPhone = true).await())
        assertEquals(40 * MIN, f.loads.last().position())
    }

    @Test
    fun aResumptionOfAnEpisodeAFollowedSpeakerLeftLooksItUpFirst() = runTest {
        val f = fixture()
        f.followedSpeakerLeft(30) // the stored session followed the speaker too
        spotifySays = played(70) // it played on to 1:10:00
        assertTrue(f.controller.playAsync(resumeItemLoad(storedSession(30, at = 1_000)), onThisPhone = true).await())
        assertEquals(70 * MIN, f.loads.last().position())
        assertEquals(1, lookups)
    }

    @Test
    fun aResumptionOfAnEpisodeMarkedPlayedSinceStartsOver() = runTest {
        val f = fixture()
        now = 2_000
        f.store.markPlayed(EP, played = true)
        assertTrue(f.controller.playAsync(resumeItemLoad(storedSession(40, at = 1_000)), onThisPhone = true).await())
        assertEquals(0L, f.loads.last().position())
    }

    @Test
    fun aStoredSessionsPositionDatesFromWhenItPlayedOrPaused() {
        val playing = PlaybackSnapshot(
            source = PlaybackSource.REMOTE, status = PlaybackStatus.PLAYING, positionMs = 10 * MIN, positionTimestampMs = 500,
            durationMs = 2 * HOUR, track = PlaybackTrack(uri = EP, isEpisode = true),
        )
        assertEquals(9_000L, ResumeState.from(playing, 10 * MIN, nowMs = 9_000)?.positionAt)
        val paused = playing.copy(status = PlaybackStatus.PAUSED)
        assertEquals("a device sitting paused: when it paused", 500L, ResumeState.from(paused, 10 * MIN, nowMs = 9_000)?.positionAt)
        assertNull(ResumeState.from(paused.copy(positionTimestampMs = 0), 10 * MIN, nowMs = 9_000)?.positionAt)
        // Its loads carry it; any other request names none.
        assertEquals(500L, ResumeState.from(paused, 10 * MIN, nowMs = 9_000)?.toPlayRequest()?.positionAt)
        assertEquals(0L, storedSession(40, at = null).toPlayRequest().positionAt)
        assertEquals(1_000L, resumeItemLoad(storedSession(40, at = 1_000)).positionAt)
        assertNull(ResumeLoad(null, shuffle = false, smartShuffle = false, repeat = com.taehagen.spotifygood.model.RepeatMode.OFF).applyTo(PlayRequest(trackUris = listOf(EP))).positionAt)
        assertNull(PlayRequest(trackUris = listOf(EP), positionMs = 5_000).positionAt)
    }

    @Test
    fun aTransferOfTheStoredSessionStartsItsEpisodeAtTheNewestPoint() = runTest {
        val f = fixture()
        val resolve = checkNotNull(f.controller.episodeResume) // the same decision the app graph installs on both
        now = 2_000
        f.store.record(EP, 55 * MIN, 2 * HOUR)
        // Nothing plays anywhere; the stored session took the desktop's old pause: the speaker gets 55:00.
        assertTrue(DevicesRepository.resumeMayStart(PlaybackSnapshot()))
        val resolved = DevicesRepository.withEpisodeStart(storedSession(40, at = 1_000), resolve)
        assertEquals(55 * MIN, resolved.positionMs)
        assertEquals(55 * MIN, DevicesRepository.transferArgs("speaker", play = true, resume = resolved)["resume"]!!.jsonObject["positionMs"]!!.jsonPrimitive.content.toLong())
        // Newer than the point: as stored.
        assertEquals(40 * MIN, DevicesRepository.withEpisodeStart(storedSession(40, at = 3_000), resolve).positionMs)
        assertEquals(0, lookups)
        // A followed speaker's point: looked up first.
        f.followedSpeakerLeft(30)
        spotifySays = played(70)
        assertEquals(70 * MIN, DevicesRepository.withEpisodeStart(storedSession(30, at = 1_000), resolve).positionMs)
        assertEquals(1, lookups)
        // A lookup that never answers delays the transfer by its bound at most.
        f.followedSpeakerLeft(30)
        spotifySays = null
        val started = testScheduler.currentTime
        assertEquals(30 * MIN, DevicesRepository.withEpisodeStart(storedSession(30, at = 1_000), resolve).positionMs)
        assertEquals(EpisodeProgressStore.LOOKUP_TIMEOUT_MS, testScheduler.currentTime - started)
        // Music: as stored, nothing asked.
        val song = storedSession(40, at = 1_000).copy(contextUri = "spotify:album:a", trackUri = "spotify:track:t", isEpisode = false)
        assertEquals(song, DevicesRepository.withEpisodeStart(song) { _, _ -> error("not asked") })
        // Something loaded somewhere: the engine transfers that, the stored session isn't resolved.
        val paused = PlaybackSnapshot(status = PlaybackStatus.PAUSED, track = PlaybackTrack(uri = EP, isEpisode = true))
        assertFalse(DevicesRepository.resumeMayStart(paused))
    }
}
