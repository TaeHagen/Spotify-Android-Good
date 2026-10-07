package com.taehagen.spotifygood.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.taehagen.spotifygood.model.PlaybackContext
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.RepeatMode
import com.taehagen.spotifygood.model.TrackProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ResumeStoreTest {
    private val dir: File = Files.createTempDirectory("resume-store").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "playback_resume.preferences_pb") })
    private val store = ResumeStore(dataStore)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val state = ResumeState(
        contextUri = "spotify:playlist:p",
        trackUri = "spotify:track:t",
        positionMs = 61_000,
        title = "Song",
        artist = "Artist",
        album = null,
        artworkUrl = null,
        durationMs = 200_000,
        isEpisode = false,
    )

    @Test
    fun modesReadBack() = runBlocking {
        assertNull(store.read())
        for (saved in listOf(
            state.copy(shuffle = true, smartShuffle = true, repeat = RepeatMode.TRACK),
            state.copy(shuffle = true, repeat = RepeatMode.CONTEXT),
            state.copy(contextUri = null, trackUris = listOf("spotify:track:t", "spotify:track:n1", "spotify:episode:e")),
            state,
        )) {
            store.save(saved)
            assertEquals(saved, store.read())
        }
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun aStateStoredByAnOlderVersionReadsWithTheModesOff() = runBlocking {
        // The keys of a state saved before the modes were stored.
        dataStore.edit { p ->
            p[stringPreferencesKey("context")] = "spotify:album:a"
            p[stringPreferencesKey("track")] = "spotify:track:t"
            p[longPreferencesKey("position")] = 5_000
            p[booleanPreferencesKey("episode")] = false
        }
        val read = checkNotNull(store.read())
        assertEquals("spotify:album:a", read.contextUri)
        assertNull(read.trackUris) // the context form, as before
        assertEquals(5_000L, read.positionMs)
        assertFalse(read.shuffle)
        assertFalse(read.smartShuffle)
        assertEquals(RepeatMode.OFF, read.repeat)

        // An unknown repeat value (a newer version, a damaged file) is off too.
        dataStore.edit { it[stringPreferencesKey("repeat")] = "sometimes" }
        assertEquals(RepeatMode.OFF, checkNotNull(store.read()).repeat)
    }

    private fun t(n: Int) = "spotify:track:$n"

    private fun snapshot(provider: TrackProvider, context: String = "spotify:album:a") = PlaybackSnapshot(
        status = PlaybackStatus.PAUSED,
        positionMs = 130_000,
        context = PlaybackContext(context),
        track = PlaybackTrack(uri = t(9), provider = provider),
        nextTracks = listOf(
            PlaybackTrack(uri = t(10), provider = TrackProvider.CONTEXT),
            PlaybackTrack(uri = t(11), provider = TrackProvider.QUEUE),
            PlaybackTrack(uri = t(12), provider = TrackProvider.SUGGESTION),
            PlaybackTrack(uri = t(13), provider = TrackProvider.UNAVAILABLE),
            PlaybackTrack(uri = "spotify:delimiter", provider = TrackProvider.CONTEXT),
            PlaybackTrack(uri = t(14), provider = TrackProvider.AUTOPLAY),
        ),
        shuffle = true,
        repeat = RepeatMode.CONTEXT,
    )

    @Test
    fun aTrackOutsideItsContextResumesAsATrackList() {
        // Queued, autoplay (the context still names the album) and smart-shuffle suggestions.
        for (provider in listOf(TrackProvider.QUEUE, TrackProvider.AUTOPLAY, TrackProvider.SUGGESTION)) {
            val saved = checkNotNull(ResumeState.from(snapshot(provider)))
            assertNull(saved.contextUri)
            assertEquals(listOf(t(9), t(10), t(14)), saved.trackUris)
            assertEquals(t(9), saved.mediaId) // not ctx|album|track: that would start the album's first track
            val request = saved.toPlayRequest()
            assertNull(request.contextUri)
            assertEquals(listOf(t(9), t(10), t(14)), request.trackUris)
            assertEquals(0, request.startIndex)
            assertEquals(t(9), request.startUri)
            assertEquals(130_000L, request.positionMs)
            // The list plays in its saved order; repeat stays.
            assertEquals(false, request.shuffle)
            assertEquals(false, request.smartShuffle)
            assertEquals(RepeatMode.CONTEXT, request.repeat)
        }
    }

    @Test
    fun aContextTrackStillResumesInItsContext() {
        val saved = checkNotNull(ResumeState.from(snapshot(TrackProvider.CONTEXT)))
        assertEquals("spotify:album:a", saved.contextUri)
        assertNull(saved.trackUris)
        assertEquals(MediaIds.inContext("spotify:album:a", t(9)), saved.mediaId)
        val request = saved.toPlayRequest()
        assertEquals("spotify:album:a", request.contextUri)
        assertNull(request.trackUris)
        assertEquals(t(9), request.startUri)
        assertEquals(true, request.shuffle)
        // A plain track list cannot be loaded again: its tracks are kept instead.
        val list = checkNotNull(ResumeState.from(snapshot(TrackProvider.CONTEXT, context = "spotify:web-api")))
        assertNull(list.contextUri)
        assertEquals(listOf(t(9), t(10), t(14)), list.trackUris)
    }

    @Test
    fun theTrackListIsCapped() {
        val long = snapshot(TrackProvider.QUEUE).copy(nextTracks = (100 until 200).map { PlaybackTrack(uri = t(it)) })
        val saved = checkNotNull(ResumeState.from(long))
        assertEquals(ResumeState.RESUME_TRACKS, saved.trackUris?.size)
        assertEquals(t(9), saved.trackUris?.first())
    }

    @Test
    fun theMedia3ResumeItemLoadsTheSameAsTheStoredSession() {
        for (provider in TrackProvider.entries) {
            val saved = checkNotNull(ResumeState.from(snapshot(provider)))
            // What the session player sends for the resume item: its media id, then its extras.
            val plan = checkNotNull(MediaIds.plan(listOf(saved.mediaId), 0) { emptyList() })
            val planned = PlayRequest(
                contextUri = plan.contextUri,
                trackUris = plan.trackUris,
                startUri = plan.startUri,
                startIndex = plan.startIndex,
                positionMs = saved.positionMs,
            )
            val loaded = saved.resumeLoad.applyTo(planned)
            val expected = saved.toPlayRequest()
            assertEquals(expected.contextUri, loaded.contextUri)
            assertEquals(expected.trackUris, loaded.trackUris)
            assertEquals(expected.shuffle, loaded.shuffle)
            assertEquals(expected.smartShuffle, loaded.smartShuffle)
            assertEquals(expected.repeat, loaded.repeat)
            if (expected.trackUris != null) assertEquals(0, loaded.startIndex)
        }
        // Another item (or another start) keeps its own load; only the modes are applied.
        val load = ResumeLoad(listOf(t(1), t(2)), shuffle = false, smartShuffle = false, repeat = RepeatMode.TRACK)
        val other = PlayRequest(trackUris = listOf(t(5)), startIndex = 0)
        assertEquals(other.copy(shuffle = false, smartShuffle = false, repeat = RepeatMode.TRACK), load.applyTo(other))
        val context = PlayRequest(contextUri = "spotify:album:a", startUri = t(1))
        assertTrue(load.applyTo(context).trackUris == null)
    }

    @Test
    fun aStoredListThatDoesNotStartWithTheTrackIsIgnored() {
        val broken = state.copy(contextUri = "spotify:album:a", trackUris = listOf(t(1), t(2)))
        assertEquals(MediaIds.inContext("spotify:album:a", "spotify:track:t"), broken.mediaId)
        assertEquals("spotify:album:a", broken.toPlayRequest().contextUri)
    }
}
