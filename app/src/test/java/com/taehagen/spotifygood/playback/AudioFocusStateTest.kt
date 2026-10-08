package com.taehagen.spotifygood.playback

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusStateTest {
    private class Fakes : AudioFocusController.Callbacks, AudioFocusState.Platform {
        var speech = false
        var playing = true
        var result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        var time = 1_000L
        val events = mutableListOf<String>()
        var requests = 0
        val duck = mutableListOf<Boolean>()

        override fun pauseForFocus() { events += "pause" }
        override fun resumeAfterFocus() { events += "resume" }
        override fun setDuck(ducked: Boolean) { duck += ducked }
        override fun isSpeech() = speech
        override fun isPlayingLocally() = playing
        override fun requestFocus(): Int { requests++; return result }
        override fun abandonFocus() { events += "abandon" }
        override fun now() = time
    }

    private val f = Fakes()
    private val state = AudioFocusState(f, f)

    @Test
    fun duckDoesNotLoseFocusSoASinkRestartDoesNotStealItBack() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(listOf(true), f.duck)
        assertTrue(state.hasFocus)
        // Skip / pause+resume / watchdog restart during the navigation prompt.
        assertTrue(state.request())
        assertEquals("no second requestAudioFocus while ducked", 1, f.requests)
        // The prompt ends: the ducking app abandons and we get GAIN.
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(true, false), f.duck)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun grantedRequestClearsAStaleDuck() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        // The ducking app escalates to a transient loss (we pause), then playback is restarted
        // by the user before any GAIN: the request is granted synchronously, no GAIN follows.
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(state.hasFocus)
        assertTrue(state.request())
        assertEquals(2, f.requests)
        assertEquals("gain restored after the granted request", listOf(true, false), f.duck)
        assertFalse(state.isDucked)
    }

    @Test
    fun speechPausesInsteadOfDucking() {
        f.speech = true
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(f.duck.isEmpty())
        assertEquals(listOf("pause"), f.events)
        assertTrue(state.isWaitingForGain)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause", "resume"), f.events)
    }

    @Test
    fun transientLossResumesOnlyWithinTheWindowAndOnlyWhatWasPlaying() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        f.time += AudioFocusState.RESUME_WINDOW_MS + 1
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause"), f.events)

        f.events.clear()
        f.playing = false
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("a paused player is not resumed", f.events.isEmpty())
    }

    @Test
    fun lossPausesAndAbandonsAndClearsTheDuck() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(listOf("pause", "abandon"), f.events)
        assertEquals(listOf(true, false), f.duck)
        assertFalse(state.isRequested)
    }

    @Test
    fun failedAndDelayedRequestsPause() {
        f.result = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        assertFalse(state.request())
        assertEquals(listOf("pause"), f.events)
        f.result = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        assertFalse(state.request())
        assertTrue(state.isRequested)
        assertTrue(state.isWaitingForGain)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause", "pause", "resume"), f.events)
    }

    @Test
    fun aNoisyEventDuringAFocusPauseCancelsTheResume() {
        // A call (or a spoken navigation prompt over a podcast) pauses with a resume pending...
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(state.isWaitingForGain)
        // ...the earbuds go into their case (or the car is turned off): becoming noisy.
        state.cancelPendingResume()
        assertFalse(state.isWaitingForGain)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals("no resume on the speaker", listOf("pause"), f.events)
    }

    @Test
    fun aNoisyEventWhileFocusIsDelayedCancelsTheResumeToo() {
        f.result = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        assertFalse(state.request())
        assertTrue(state.isWaitingForGain)
        state.cancelPendingResume()
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause"), f.events)
    }
}
