package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.playback.PresenceRestore.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceRestoreTest {
    private val boot = "android.intent.action.BOOT_COMPLETED"
    private val update = "android.intent.action.MY_PACKAGE_REPLACED"

    @Test
    fun presenceComesBackAfterARebootAndAnUpdate() {
        assertEquals(Decision.RESTORE, PresenceRestore.decide(boot, connectPresence = true, loggedIn = true, presenceUp = false, sdk = 34))
        assertEquals(Decision.RESTORE, PresenceRestore.decide(update, connectPresence = true, loggedIn = true, presenceUp = false, sdk = 34))
    }

    @Test
    fun onlyWhatTheUserAskedForAndCanHave() {
        // The setting off, or not read in time: nothing to restore.
        assertEquals(Decision.SKIP, PresenceRestore.decide(boot, connectPresence = false, loggedIn = true, presenceUp = false, sdk = 34))
        assertEquals(Decision.SKIP, PresenceRestore.decide(boot, connectPresence = null, loggedIn = null, presenceUp = false, sdk = 34))
        // Logged out: presence needs the account.
        assertEquals(Decision.SKIP, PresenceRestore.decide(update, connectPresence = true, loggedIn = false, presenceUp = false, sdk = 34))
        // Already up (the app was opened first).
        assertEquals(Decision.SKIP, PresenceRestore.decide(boot, connectPresence = true, loggedIn = true, presenceUp = true, sdk = 34))
        // Any other broadcast, the locked boot included (the credentials are not readable yet).
        assertEquals(Decision.SKIP, PresenceRestore.decide("android.intent.action.LOCKED_BOOT_COMPLETED", true, true, false, 34))
        assertEquals(Decision.SKIP, PresenceRestore.decide(null, true, true, false, 34))
    }

    @Test
    fun aLoginThatCannotBeReadInTimeAsksToOpenTheApp() {
        assertEquals(Decision.NOTIFY, PresenceRestore.decide(boot, connectPresence = true, loggedIn = null, presenceUp = false, sdk = 34))
    }

    @Test
    fun fromBootOnAndroid15AndLaterTheUserOpensTheApp() {
        // A boot-started service is held to the boot types on every later startForeground: a
        // presence started there would refuse Media3's mediaPlayback foreground for good.
        for (sdk in listOf(35, 36, 37)) {
            assertEquals(Decision.NOTIFY, PresenceRestore.decide(boot, connectPresence = true, loggedIn = true, presenceUp = false, sdk = sdk))
            // An update records a reason of its own: restored as before.
            assertEquals(Decision.RESTORE, PresenceRestore.decide(update, connectPresence = true, loggedIn = true, presenceUp = false, sdk = sdk))
            // Nothing to ask for when presence is off or the account is gone.
            assertEquals(Decision.SKIP, PresenceRestore.decide(boot, connectPresence = false, loggedIn = true, presenceUp = false, sdk = sdk))
            assertEquals(Decision.SKIP, PresenceRestore.decide(boot, connectPresence = true, loggedIn = false, presenceUp = false, sdk = sdk))
        }
        // Up to Android 14 there is no such check: restored from boot.
        for (sdk in 26..34) {
            assertEquals(Decision.RESTORE, PresenceRestore.decide(boot, connectPresence = true, loggedIn = true, presenceUp = false, sdk = sdk))
        }
    }
}
