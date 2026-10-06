package com.taehagen.spotifygood.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeMathTest {
    @Test
    fun indexRoundTripsForCommonStepCounts() {
        for (max in listOf(15, 16, 25, 30, 150)) {
            for (index in 0..max) {
                val connect = VolumeMath.indexToConnect(index, max)
                assertEquals("max=$max index=$index", index, VolumeMath.connectToIndex(connect, 0, max))
            }
        }
    }

    @Test
    fun extremesMapToExtremes() {
        assertEquals(0, VolumeMath.connectToIndex(0, 0, 15))
        assertEquals(15, VolumeMath.connectToIndex(VolumeMath.CONNECT_MAX, 0, 15))
        assertEquals(VolumeMath.CONNECT_MAX, VolumeMath.indexToConnect(15, 15))
        assertEquals(0, VolumeMath.indexToConnect(0, 15))
    }

    @Test
    fun clampsOutOfRangeValues() {
        assertEquals(15, VolumeMath.connectToIndex(1_000_000, 0, 15))
        assertEquals(1, VolumeMath.connectToIndex(0, 1, 15))
        assertEquals(0, VolumeMath.connectToIndex(-5, 0, 15))
        assertEquals(VolumeMath.CONNECT_MAX, VolumeMath.indexToConnect(99, 15))
        assertEquals(0, VolumeMath.indexToConnect(3, 0))
        assertEquals(0, VolumeMath.connectToIndex(30_000, 0, 0))
    }

    @Test
    fun nearbyConnectVolumesQuantiseToTheSameStep() {
        // Avoids the Android-step ping-pong: tiny Connect changes do not move the stream index.
        val center = VolumeMath.indexToConnect(7, 15)
        assertEquals(7, VolumeMath.connectToIndex(center, 0, 15))
        assertEquals(7, VolumeMath.connectToIndex(center + 500, 0, 15))
        assertEquals(7, VolumeMath.connectToIndex(center - 500, 0, 15))
    }

    @Test
    fun percentConversions() {
        assertEquals(0, VolumeMath.connectToPercent(0))
        assertEquals(100, VolumeMath.connectToPercent(VolumeMath.CONNECT_MAX))
        assertEquals(50, VolumeMath.connectToPercent(VolumeMath.percentToConnect(50)))
        for (p in 0..100) assertEquals(p, VolumeMath.connectToPercent(VolumeMath.percentToConnect(p)))
        assertEquals(VolumeMath.CONNECT_MAX, VolumeMath.percentToConnect(150))
    }
}
