package com.taehagen.spotifygood.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceLineTest {
    private fun join(artist: String?, device: String?) = DeviceLine.join(artist, device) { a, d -> "$a • $d" }?.toString()

    @Test
    fun appendsTheDeviceToTheArtist() {
        assertEquals("Artist • Playing on Laptop", join("Artist", "Playing on Laptop"))
    }

    @Test
    fun deviceOnlyWithoutArtist() {
        assertEquals("Playing on Laptop", join(null, "Playing on Laptop"))
        assertEquals("Playing on Laptop", join(" ", "Playing on Laptop"))
    }

    @Test
    fun localPlaybackKeepsTheArtist() {
        assertEquals("Artist", join("Artist", null))
        assertNull(join(null, null))
    }

    @Test
    fun neverAddedTwice() {
        // The notification provider sees an artist that already carries the device line (API 30+),
        // or a browse item whose subtitle is the artist itself.
        assertEquals("Artist • Playing on Laptop", join("Artist • Playing on Laptop", "Playing on Laptop"))
        assertEquals("Artist", join("Artist", "Artist"))
    }
}
