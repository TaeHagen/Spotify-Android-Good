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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** A sink whose output takes speeds up to [max] (an AudioTrack falls back as [PodcastSpeeds.fallbacks]). */
    private class FakeSink(var max: Float = PodcastSpeeds.MAX) {
        val applied = mutableListOf<Float>()
        val speed = MutableStateFlow(1f)
        private var requested = 1f

        fun apply(value: Float): Float {
            synchronized(applied) { applied += value }
            requested = value
            return inEffect().also { speed.value = it }
        }

        /** A new track or output checks the speed asked for again. */
        fun recheck() {
            speed.value = inEffect()
        }

        private fun inEffect() = PodcastSpeeds.fallbacks(requested).first { it <= max + 0.001f }
    }

    private fun TestScope.podcastSpeed(snapshots: MutableStateFlow<PlaybackSnapshot>, sink: FakeSink, reported: MutableList<Float>) =
        PodcastSpeed(backgroundScope, snapshots, store, sink::apply, sink.speed) { reported += it }

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
        val sink = FakeSink()
        val reported = mutableListOf<Float>()
        val speed = podcastSpeed(snapshots, sink, reported)
        runCurrent()
        assertEquals(listOf(1f), sink.applied)
        assertTrue("the engine starts at normal speed", reported.isEmpty())
        speed.set(1.5f)
        runCurrent()
        assertEquals("music plays at normal speed", listOf(1f), sink.applied)
        assertEquals(1.5f, speed.inEffect.value)
        // An episode starts: the sink and the engine get the chosen speed...
        snapshots.value = episode
        runCurrent()
        assertEquals(listOf(1f, 1.5f), sink.applied)
        assertEquals(listOf(1.5f), reported)
        assertEquals(1.5f, speed.inEffect.value)
        // ...a paused or offline snapshot of it changes nothing, music or a remote device resets.
        snapshots.value = episode.copy(status = PlaybackStatus.PAUSED)
        runCurrent()
        snapshots.value = music
        runCurrent()
        assertEquals(listOf(1f, 1.5f, 1f), sink.applied)
        assertEquals(listOf(1.5f, 1f), reported)
    }

    @Test
    fun aSpeedTheOutputRefusesIsNeverReported() = runTest {
        val snapshots = MutableStateFlow(episode)
        val sink = FakeSink(max = 2f)
        val reported = mutableListOf<Float>()
        val speed = podcastSpeed(snapshots, sink, reported)
        runCurrent()
        speed.set(3.5f)
        runCurrent()
        // The output takes 2x at most: the engine is told 2x, and that is what is shown.
        assertEquals(listOf(1f, 3.5f), sink.applied)
        assertEquals(listOf(2f), reported)
        assertEquals(2f, speed.inEffect.value)
        assertEquals("the choice stays", 3.5f, speed.speed.value)
        // Another output takes it (a route change re-checks it in the sink): now it is reported.
        sink.max = PodcastSpeeds.MAX
        sink.recheck()
        runCurrent()
        assertEquals(listOf(2f, 3.5f), reported)
        assertEquals(3.5f, speed.inEffect.value)
        // A new track that refuses it again (a Bluetooth headset): back to what it takes.
        sink.max = 1.5f
        sink.recheck()
        runCurrent()
        assertEquals(listOf(2f, 3.5f, 1.5f), reported)
        assertEquals(1.5f, speed.inEffect.value)
        assertEquals("nothing applied anew", listOf(1f, 3.5f), sink.applied)
    }

    @Test
    fun aRefusedSpeedFallsBackToTheHighestStepBelowIt() {
        assertEquals(listOf(3.5f, 3f, 2.5f, 2f, 1.8f, 1.5f, 1.2f, 1f), PodcastSpeeds.fallbacks(3.5f))
        assertEquals(listOf(1.25f, 1.2f, 1f), PodcastSpeeds.fallbacks(1.25f))
        assertEquals(listOf(0.5f, 0.8f, 1f), PodcastSpeeds.fallbacks(0.5f))
        assertEquals(listOf(1f), PodcastSpeeds.fallbacks(1f))
        // 3.5x chosen, 2.5x in effect: 3x and 3.5x were refused, the rest may be chosen.
        assertTrue(PodcastSpeeds.refused(3f, chosen = 3.5f, inEffect = 2.5f))
        assertTrue(PodcastSpeeds.refused(3.5f, chosen = 3.5f, inEffect = 2.5f))
        assertFalse(PodcastSpeeds.refused(2.5f, chosen = 3.5f, inEffect = 2.5f))
        assertFalse(PodcastSpeeds.refused(0.5f, chosen = 3.5f, inEffect = 2.5f))
        // Nothing is known while the chosen speed plays.
        assertFalse(PodcastSpeeds.refused(3.5f, chosen = 2f, inEffect = 2f))
    }

    @Test
    fun theChosenSpeedIsRemembered() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val first = PodcastSpeed(scope, MutableStateFlow(music), store, { it }, MutableStateFlow(1f), {})
            first.set(1.8f)
            // Fresh reads: a collector of the store running alongside the write may miss it (JVM tests).
            withTimeout(5_000) {
                while (store.data.first().asMap().isEmpty()) delay(10)
            }
            // A new instance (process restart) reads it back and applies it to an episode.
            val sink = FakeSink()
            val again = PodcastSpeed(scope, MutableStateFlow(episode), store, sink::apply, sink.speed, {})
            withTimeout(5_000) { again.speed.first { it == 1.8f } }
            withTimeout(5_000) { again.inEffect.first { it == 1.8f } }
            withTimeout(5_000) {
                while (synchronized(sink.applied) { sink.applied.lastOrNull() } != 1.8f) delay(10)
            }
        } finally {
            scope.cancel()
        }
    }
}
