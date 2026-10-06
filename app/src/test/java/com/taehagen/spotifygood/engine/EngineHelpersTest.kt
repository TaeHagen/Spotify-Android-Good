package com.taehagen.spotifygood.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineHelpersTest {
    @Test
    fun connectVolumeIsLinearAndRounded() {
        assertEquals(0, connectVolume(0, 15))
        assertEquals(65_535, connectVolume(15, 15))
        assertEquals(30_583, connectVolume(7, 15))
        assertEquals(2_621, connectVolume(1, 25))
        assertEquals(32_768, connectVolume(1, 2))
        assertEquals(21_845, connectVolume(1, 3))
    }

    @Test
    fun connectVolumeClampsOddInput() {
        assertEquals(0, connectVolume(5, 0))
        assertEquals(65_535, connectVolume(20, 15))
        assertEquals(0, connectVolume(-3, 15))
    }

    @Test
    fun deviceNames() {
        assertEquals("Pixel 9 Pro", deviceNameFrom("Google", "Pixel 9 Pro"))
        assertEquals("Samsung SM-S928B", deviceNameFrom("samsung", "SM-S928B"))
        assertEquals("OnePlus 12", deviceNameFrom("OnePlus", "OnePlus 12"))
        assertEquals("Xiaomi", deviceNameFrom("Xiaomi", ""))
        assertEquals("Android", deviceNameFrom(null, null))
        assertEquals("Moto g", deviceNameFrom("", "moto g"))
    }
}
