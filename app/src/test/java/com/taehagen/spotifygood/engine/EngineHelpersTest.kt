package com.taehagen.spotifygood.engine

import com.taehagen.spotifygood.model.StoredCredentials
import com.taehagen.spotifygood.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun theStoredCredentialsNameTheUserBeforeASessionWasOnline() {
        val creds = StoredCredentials(username = "alice", authType = 1, authData = "YWJj")
        // Cold start without a network: only the username is known.
        val seeded = knownUser(null, creds)
        assertEquals(User(username = "alice"), seeded)
        assertEquals(true, seeded?.isPremium) // no Premium screen for an unknown product
        assertNull(seeded?.country) // downloads still wait for the online user's country
        // The online user (from the session) is kept for the same account ...
        val online = User(username = "alice", displayName = "Alice", product = "premium", country = "SE")
        assertSame(online, knownUser(online, creds))
        // ... and replaced for another one (a LAN login of another account).
        assertEquals(User(username = "bob"), knownUser(online, creds.copy(username = "bob")))
        // Nothing usable stored: as it is.
        assertNull(knownUser(null, null))
        assertSame(online, knownUser(online, null))
        assertNull(knownUser(null, creds.copy(username = " ")))
    }

    @Test
    fun networkArgsCarryTheDefaultNetwork() {
        assertEquals(
            """{"available":true,"metered":true,"network":432902426637}""",
            networkArgs(NetworkStatus(available = true, metered = true, handle = 432_902_426_637L)).toString(),
        )
        assertEquals(
            """{"available":false,"metered":false}""",
            networkArgs(NetworkStatus(available = false, metered = false)).toString(),
        )
    }

    @Test
    fun connectVisibilityFollowsTheHolders() {
        assertEquals(false, connectVisibleFor(ui = 0, playback = 0, presence = 0))
        assertEquals(true, connectVisibleFor(ui = 1, playback = 0, presence = 0))
        assertEquals(true, connectVisibleFor(ui = 0, playback = 1, presence = 0))
        assertEquals(true, connectVisibleFor(ui = 0, playback = 0, presence = 1))
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
