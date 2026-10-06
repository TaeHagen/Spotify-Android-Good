package com.taehagen.spotifygood.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteVolumeTargetTest {
    private var time = 10_000L
    private val target = RemoteVolumeTarget({ time })

    /** What SpotifyPlayer does on a volume-up key press. */
    private fun press(device: String, snapshotPercent: Int, step: Int = 5): Int {
        val next = (target.base(device, snapshotPercent) + step).coerceIn(0, 100)
        target.set(next, device)
        return next
    }

    @Test
    fun quickPressesAccumulateWhileTheSnapshotIsStale() {
        val sent = (1..4).map { time += 80; press("tv", snapshotPercent = 50) }
        assertEquals(listOf(55, 60, 65, 70), sent)
        assertEquals(70, target.reported("tv", 50))
    }

    @Test
    fun echoesOfOurOwnEarlierTargetsDoNotRollTheBaseBack() {
        press("tv", 50) // 55
        press("tv", 50) // 60
        // The debounced PUT of 55 lands and is echoed while the user keeps pressing.
        assertEquals(60, target.reported("tv", 55))
        assertEquals(65, press("tv", 55))
    }

    @Test
    fun confirmationHandsBackToTheSnapshot() {
        press("tv", 50)
        assertEquals(55, target.reported("tv", 55))
        assertNull(target.pending("tv"))
        // Someone changes it on the speaker afterwards: that value is the base now.
        assertEquals(35, press("tv", 30))
    }

    @Test
    fun expiresAndIsPerDevice() {
        press("tv", 50)
        assertEquals(50, target.reported("speaker", 50))
        assertNull(target.pending("tv"))

        press("tv", 50)
        time += RemoteVolumeTarget.DEFAULT_VALID_MS
        assertEquals(40, target.reported("tv", 40))
        assertEquals(45, press("tv", 40))
    }

    @Test
    fun clampsToPercent() {
        target.set(130, "tv")
        assertEquals(100, target.pending("tv"))
        assertEquals(RemoteVolumeTarget.DEFAULT_VALID_MS, target.remainingMs())
    }
}
