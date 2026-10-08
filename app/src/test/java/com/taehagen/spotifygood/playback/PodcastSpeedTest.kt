package com.taehagen.spotifygood.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

class PodcastSpeedTest {
    private val dir: File = Files.createTempDirectory("podcast-speed").toFile()
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = PreferenceDataStoreFactory.create(scope = io, produceFile = { File(dir, "speed.preferences_pb") })

    @After
    fun tearDown() {
        io.cancel()
        dir.deleteRecursively()
    }

    private val episode = PlaybackSnapshot(
        source = PlaybackSource.LOCAL,
        status = PlaybackStatus.PLAYING,
        track = PlaybackTrack(uri = "spotify:episode:e", isEpisode = true),
    )
    private val music = episode.copy(track = PlaybackTrack(uri = "spotify:track:t"))

    @Test
    fun theSpeedAppliesToEpisodesPlayedHereOnly() {
        assertEquals(1.5f, PodcastSpeeds.effective(1.5f, episode))
        assertEquals(1.5f, PodcastSpeeds.effective(1.5f, episode.copy(status = PlaybackStatus.PAUSED, offline = true)))
        assertEquals(1f, PodcastSpeeds.effective(1.5f, music))
        // Another device plays it: Spotify Connect has no speed command.
        assertEquals(1f, PodcastSpeeds.effective(1.5f, episode.copy(source = PlaybackSource.REMOTE)))
        assertEquals(1f, PodcastSpeeds.effective(1.5f, PlaybackSnapshot.EMPTY))
    }

    @Test
    fun speedsStayInSpotifysRange() {
        assertEquals(0.5f, PodcastSpeeds.clamp(0.1f))
        assertEquals(3.5f, PodcastSpeeds.clamp(8f))
        assertEquals(1.25f, PodcastSpeeds.clamp(1.26f))
        assertEquals(1f, PodcastSpeeds.clamp(Float.NaN))
        assertEquals(listOf(0.5f, 0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f, 2.5f, 3f, 3.5f), PodcastSpeeds.STEPS)
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("1×", PodcastSpeeds.label(1f))
            assertEquals("1.5×", PodcastSpeeds.label(1.5f))
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0,8×", PodcastSpeeds.label(0.8f))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun theChosenSpeedFollowsPlayback() = runTest {
        val snapshots = MutableStateFlow(music)
        val sink = mutableListOf<Float>()
        val reported = mutableListOf<Float>()
        val speed = PodcastSpeed(backgroundScope, snapshots, store, { sink += it }, { reported += it })
        runCurrent()
        assertEquals(listOf(1f), sink)
        assertTrue("the engine starts at normal speed", reported.isEmpty())
        speed.set(1.5f)
        runCurrent()
        assertEquals("music plays at normal speed", listOf(1f), sink)
        // An episode starts: the sink and the engine get the chosen speed...
        snapshots.value = episode
        runCurrent()
        assertEquals(listOf(1f, 1.5f), sink)
        assertEquals(listOf(1.5f), reported)
        // ...a paused or offline snapshot of it changes nothing, music or a remote device resets.
        snapshots.value = episode.copy(status = PlaybackStatus.PAUSED)
        runCurrent()
        snapshots.value = music
        runCurrent()
        assertEquals(listOf(1f, 1.5f, 1f), sink)
        assertEquals(listOf(1.5f, 1f), reported)
    }

    @Test
    fun theChosenSpeedIsRemembered() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val first = PodcastSpeed(scope, MutableStateFlow(music), store, {}, {})
            first.set(1.8f)
            withTimeout(5_000) { store.data.first { it.asMap().isNotEmpty() } }
            // A new instance (process restart) reads it back and applies it to an episode.
            val sink = mutableListOf<Float>()
            val again = PodcastSpeed(scope, MutableStateFlow(episode), store, { synchronized(sink) { sink += it } }, {})
            withTimeout(5_000) { again.speed.first { it == 1.8f } }
            withTimeout(5_000) {
                while (synchronized(sink) { sink.lastOrNull() } != 1.8f) delay(10)
            }
        } finally {
            scope.cancel()
        }
    }
}
