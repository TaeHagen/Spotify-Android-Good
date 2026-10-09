package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.playback.PresenceRestore.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceRestoreTest {
    private val boot = "android.intent.action.BOOT_COMPLETED"
    private val update = "android.intent.action.MY_PACKAGE_REPLACED"

    @Test
    fun presenceComesBackAfterARebootAndAnUpdate() {
        assertEquals(Decision.RESTORE, PresenceRestore.decide(boot, connectPresence = true, loggedIn = true, presenceUp = false))
        assertEquals(Decision.RESTORE, PresenceRestore.decide(update, connectPresence = true, loggedIn = true, presenceUp = false))
    }

    @Test
    fun onlyWhatTheUserAskedForAndCanHave() {
        // The setting off, or not read in time: nothing to restore.
        assertEquals(Decision.SKIP, PresenceRestore.decide(boot, connectPresence = false, loggedIn = true, presenceUp = false))
        assertEquals(Decision.SKIP, PresenceRestore.decide(boot, connectPresence = null, loggedIn = null, presenceUp = false))
        // Logged out: presence needs the account.
        assertEquals(Decision.SKIP, PresenceRestore.decide(update, connectPresence = true, loggedIn = false, presenceUp = false))
        // Already up (the app was opened first).
        assertEquals(Decision.SKIP, PresenceRestore.decide(boot, connectPresence = true, loggedIn = true, presenceUp = true))
        // Any other broadcast, the locked boot included (the credentials are not readable yet).
        assertEquals(Decision.SKIP, PresenceRestore.decide("android.intent.action.LOCKED_BOOT_COMPLETED", true, true, false))
        assertEquals(Decision.SKIP, PresenceRestore.decide(null, true, true, false))
    }

    @Test
    fun aLoginThatCannotBeReadInTimeAsksToOpenTheApp() {
        assertEquals(Decision.NOTIFY, PresenceRestore.decide(boot, connectPresence = true, loggedIn = null, presenceUp = false))
    }
}
