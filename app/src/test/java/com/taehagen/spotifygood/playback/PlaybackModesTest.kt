package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.ContextType
import com.taehagen.spotifygood.model.PlaybackContext
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.RepeatMode
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModesTest {
    @Test
    fun shuffleCyclesThroughSmartShuffleWhenAvailable() {
        var mode = ShuffleMode.OFF
        val seen = (1..3).map { mode = PlaybackModes.nextShuffle(mode, smartAvailable = true); mode }
        assertEquals(listOf(ShuffleMode.SHUFFLE, ShuffleMode.SMART, ShuffleMode.OFF), seen)
    }

    @Test
    fun shuffleSkipsSmartShuffleWhenUnavailable() {
        assertEquals(ShuffleMode.SHUFFLE, PlaybackModes.nextShuffle(ShuffleMode.OFF, smartAvailable = false))
        assertEquals(ShuffleMode.OFF, PlaybackModes.nextShuffle(ShuffleMode.SHUFFLE, smartAvailable = false))
        assertEquals(ShuffleMode.OFF, PlaybackModes.nextShuffle(ShuffleMode.SMART, smartAvailable = false))
    }

    @Test
    fun repeatCycles() {
        assertEquals(RepeatMode.CONTEXT, PlaybackModes.nextRepeat(RepeatMode.OFF))
        assertEquals(RepeatMode.TRACK, PlaybackModes.nextRepeat(RepeatMode.CONTEXT))
        assertEquals(RepeatMode.OFF, PlaybackModes.nextRepeat(RepeatMode.TRACK))
        assertEquals("context", PlaybackModes.wire(RepeatMode.CONTEXT))
    }

    @Test
    fun smartShuffleOnlyForLocalPlaylistLikeContexts() {
        fun snapshot(source: PlaybackSource, type: ContextType) =
            PlaybackSnapshot(source = source, context = PlaybackContext("spotify:x", type = type))
        assertTrue(snapshot(PlaybackSource.LOCAL, ContextType.PLAYLIST).isSmartShuffleAvailable)
        assertTrue(snapshot(PlaybackSource.LOCAL, ContextType.COLLECTION).isSmartShuffleAvailable)
        assertFalse(snapshot(PlaybackSource.LOCAL, ContextType.ALBUM).isSmartShuffleAvailable)
        assertFalse(snapshot(PlaybackSource.REMOTE, ContextType.PLAYLIST).isSmartShuffleAvailable)
        assertFalse(PlaybackSnapshot(source = PlaybackSource.LOCAL).isSmartShuffleAvailable)
    }

    @Test
    fun smartShuffleIsNotOfferedByTheOfflineQueue() {
        val offline = PlaybackSnapshot(
            source = PlaybackSource.LOCAL,
            offline = true,
            shuffle = true,
            context = PlaybackContext("spotify:playlist:p", type = ContextType.PLAYLIST),
        )
        assertFalse(offline.isSmartShuffleAvailable)
        // The cycle therefore goes shuffle → off instead of asking for smart shuffle again.
        assertEquals(ShuffleMode.OFF, PlaybackModes.nextShuffle(offline.shuffleMode, offline.isSmartShuffleAvailable))
        assertEquals(ShuffleMode.SHUFFLE, PlaybackModes.nextShuffle(ShuffleMode.OFF, offline.isSmartShuffleAvailable))
    }

    @Test
    fun shuffleModeOfSnapshot() {
        assertEquals(ShuffleMode.OFF, PlaybackSnapshot().shuffleMode)
        assertEquals(ShuffleMode.SHUFFLE, PlaybackSnapshot(shuffle = true).shuffleMode)
        assertEquals(ShuffleMode.SMART, PlaybackSnapshot(shuffle = true, smartShuffle = true).shuffleMode)
    }

    @Test
    fun errorCodesMapToUserFacingKinds() {
        assertNull(PlaybackErrorKind.fromCode(NativeErrorCode.CANCELLED))
        assertEquals(PlaybackErrorKind.NOT_ACTIVE_DEVICE, PlaybackErrorKind.fromCode(NativeErrorCode.NOT_ACTIVE_DEVICE))
        assertEquals(PlaybackErrorKind.NETWORK, PlaybackErrorKind.fromCode(NativeErrorCode.NOT_CONNECTED))
        assertEquals(PlaybackErrorKind.PREMIUM_REQUIRED, PlaybackErrorKind.fromCode(NativeErrorCode.PREMIUM_REQUIRED))
        assertEquals(PlaybackErrorKind.PLAYBACK_REFUSED, PlaybackErrorKind.fromCode(NativeErrorCode.PLAYBACK_REFUSED))
        assertEquals(PlaybackErrorKind.UNAVAILABLE, PlaybackErrorKind.fromCode(NativeErrorCode.UNAVAILABLE))
        assertEquals(PlaybackErrorKind.GENERIC, PlaybackErrorKind.fromCode("SOMETHING_NEW"))
        assertEquals("boom", PlaybackErrorMessages.Fallback.message(PlaybackErrorKind.GENERIC, "boom"))
        assertEquals("NETWORK", PlaybackErrorMessages.Fallback.message(PlaybackErrorKind.NETWORK, null))
    }

    @Test
    fun sleepTimerFadeIsMonotonicAndBounded() {
        val fade = SleepTimer.FADE_MS
        assertEquals(1f, SleepTimer.fadeGain(fade, fade), 0f)
        assertEquals(1f, SleepTimer.fadeGain(fade * 2, fade), 0f)
        assertEquals(0f, SleepTimer.fadeGain(0, fade), 0f)
        assertEquals(0.25f, SleepTimer.fadeGain(fade / 2, fade), 1e-6f)
        val gains = (fade downTo 0 step 500).map { SleepTimer.fadeGain(it, fade) }
        assertEquals(gains.sortedDescending(), gains)
    }

    @Test
    fun repeatWireValuesReadBack() {
        for (mode in RepeatMode.entries) assertEquals(mode, PlaybackModes.parseRepeat(PlaybackModes.wire(mode)))
        assertEquals(RepeatMode.OFF, PlaybackModes.parseRepeat(null))
        assertEquals(RepeatMode.OFF, PlaybackModes.parseRepeat("TRACK"))
        assertEquals(RepeatMode.OFF, PlaybackModes.parseRepeat(""))
    }
}
