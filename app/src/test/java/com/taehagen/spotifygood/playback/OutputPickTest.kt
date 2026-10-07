package com.taehagen.spotifygood.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputPickTest {
    private val speaker = 1
    private val headphones = 5

    @Test
    fun aNewExternalOutputTakesOverFromAPickedSpeaker() {
        val pick = OutputPick()
        // Headphones connected; the user picks the phone speaker to play out loud.
        pick.pick(speaker, setOf(speaker, headphones))
        // The callback's initial event (engine start) lists every current device: the pick stays.
        assertFalse(pick.onAdded(listOf(speaker to OutputKind.SPEAKER, headphones to OutputKind.BLUETOOTH)))
        assertEquals(speaker, pick.preferredId)
        // The headphones go away: the speaker is still picked.
        assertFalse(pick.onRemoved(setOf(headphones)))
        assertEquals(speaker, pick.preferredId)
        // They come back (a new id), or a car kit connects: the system route again.
        assertTrue(pick.onAdded(listOf(9 to OutputKind.BLUETOOTH)))
        assertNull(pick.preferredId)
        assertFalse(pick.onAdded(listOf(10 to OutputKind.CAR)))
    }

    @Test
    fun onlyExternalOutputsTakeOver() {
        val pick = OutputPick()
        pick.pick(headphones, setOf(speaker, headphones))
        assertFalse(pick.onAdded(listOf(12 to OutputKind.OTHER, 13 to null, speaker to OutputKind.SPEAKER)))
        assertEquals(headphones, pick.preferredId)
        for (kind in listOf(OutputKind.WIRED, OutputKind.USB, OutputKind.HEARING_AID, OutputKind.CAR, OutputKind.HDMI, OutputKind.BLUETOOTH)) {
            pick.pick(headphones, setOf(speaker, headphones))
            assertTrue(kind.name, pick.onAdded(listOf(20 to kind)))
        }
    }

    @Test
    fun aPickEndsWhenItsOutputGoesAwayOrTheEngineStops() {
        val pick = OutputPick()
        pick.pick(headphones, setOf(speaker, headphones))
        assertFalse(pick.onRemoved(setOf(speaker)))
        assertTrue(pick.onRemoved(setOf(headphones)))
        assertNull(pick.preferredId)

        pick.pick(speaker, setOf(speaker))
        assertTrue(pick.clear()) // engine stop / logout
        assertFalse(pick.clear())
        // "Automatic" is no pick: nothing to end.
        pick.pick(null, setOf(speaker))
        assertFalse(pick.onAdded(listOf(9 to OutputKind.BLUETOOTH)))
        assertFalse(pick.onRemoved(setOf(speaker)))
    }
}
