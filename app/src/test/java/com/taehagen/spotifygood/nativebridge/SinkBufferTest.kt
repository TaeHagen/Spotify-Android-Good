package com.taehagen.spotifygood.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SinkBufferTest {
    @Test
    fun onlyASpeedAbove1xGetsTheLargeCapacity() {
        assertEquals(1, SinkBuffer.capacityScale(1f))
        assertEquals(1, SinkBuffer.capacityScale(0.5f))
        assertEquals(1, SinkBuffer.capacityScale(0.8f))
        // Room for 3.5x, whatever speed above 1x asked for it (built once, then it stays large).
        assertEquals(4, SinkBuffer.capacityScale(1.2f))
        assertEquals(4, SinkBuffer.capacityScale(3.5f))
        // Music and slow speeds play on a 1x track, which a re-route cannot enlarge.
        assertFalse(SinkBuffer.needsLargerTrack(scale = 1, speed = 1f))
        assertFalse(SinkBuffer.needsLargerTrack(scale = 1, speed = 0.5f))
        assertTrue(SinkBuffer.needsLargerTrack(scale = 1, speed = 1.5f))
        assertFalse(SinkBuffer.needsLargerTrack(scale = 4, speed = 3.5f))
        // Back to 1x: the large track stays (no second drop), its fill goes back to 1x.
        assertFalse(SinkBuffer.needsLargerTrack(scale = 4, speed = 1f))
    }

    @Test
    fun theFillHoldsTheSameWallClockTimeAtAnySpeed() {
        assertEquals(11_025, SinkBuffer.fillFrames(11_025, 1f))
        assertEquals(11_025, SinkBuffer.fillFrames(11_025, 0.5f))
        assertEquals(22_050, SinkBuffer.fillFrames(11_025, 2f))
        assertEquals(38_588, SinkBuffer.fillFrames(11_025, 3.5f))
    }

    @Test
    fun aRestoreThatEnlargedTheBufferIsNoticed() {
        // A re-route rebuilt the server track at its capacity: 4x the 1x fill.
        assertTrue(SinkBuffer.grew(current = 44_100, expected = 11_025))
        assertFalse(SinkBuffer.grew(current = 11_025, expected = 11_025))
        // The track rounded the fill down, or no fill was set: nothing to put back.
        assertFalse(SinkBuffer.grew(current = 11_000, expected = 11_025))
        assertFalse(SinkBuffer.grew(current = 44_100, expected = 0))
    }
}
