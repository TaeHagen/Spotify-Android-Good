package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.playback.PresenceRestore.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun aRestrictedAppIsAskedToOpenTheAppInsteadOfFailingSilently() {
        // Battery use "Restricted": the system would drop the start or ignore its foreground.
        for (sdk in listOf(28, 31, 34, 35)) {
            assertEquals(
                Decision.NOTIFY,
                PresenceRestore.decide(update, connectPresence = true, loggedIn = true, presenceUp = false, sdk = sdk, backgroundRestricted = true),
            )
        }
        assertEquals(
            Decision.NOTIFY,
            PresenceRestore.decide(boot, connectPresence = true, loggedIn = true, presenceUp = false, sdk = 31, backgroundRestricted = true),
        )
        // Nothing to ask for when presence is off or the account is gone, restricted or not.
        assertEquals(
            Decision.SKIP,
            PresenceRestore.decide(boot, connectPresence = false, loggedIn = true, presenceUp = false, sdk = 31, backgroundRestricted = true),
        )
        assertEquals(
            Decision.SKIP,
            PresenceRestore.decide(update, connectPresence = true, loggedIn = false, presenceUp = false, sdk = 31, backgroundRestricted = true),
        )
    }

    @Test
    fun anIgnoredPresenceForegroundIsNotTakenForOne() {
        val connected = 0x10 // FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val media = 0x2 // FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        // API 29+: the type the system recorded (none after an ignored start).
        assertTrue(PresenceRestore.foregroundTookEffect(34, connected, backgroundRestricted = false, appVisible = false))
        assertTrue(PresenceRestore.foregroundTookEffect(31, connected or media, backgroundRestricted = true, appVisible = true))
        assertFalse(PresenceRestore.foregroundTookEffect(34, 0, backgroundRestricted = true, appVisible = false))
        assertFalse(PresenceRestore.foregroundTookEffect(29, media, backgroundRestricted = false, appVisible = false))
        // API 28: the restriction, unless the app is visible.
        assertFalse(PresenceRestore.foregroundTookEffect(28, 0, backgroundRestricted = true, appVisible = false))
        assertTrue(PresenceRestore.foregroundTookEffect(28, 0, backgroundRestricted = true, appVisible = true))
        assertTrue(PresenceRestore.foregroundTookEffect(28, 0, backgroundRestricted = false, appVisible = false))
        // Before: no such restriction.
        assertTrue(PresenceRestore.foregroundTookEffect(26, 0, backgroundRestricted = false, appVisible = false))
    }
}
