package com.taehagen.spotifygood.download

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyCacheTest {
    @Test
    fun aKeyDecryptedOutsideTheLockDoesNotReplaceANewerCommit() {
        val keys = KeyCache()
        // The track was removed and downloaded again while its old key was being decrypted.
        keys["spotify:track:1"] = "new"
        assertEquals("new", keys.remember("spotify:track:1", "old"))
        assertEquals("new", keys["spotify:track:1"])
        // Nothing cached yet: remembered.
        assertEquals("only", keys.remember("spotify:track:2", "only"))
        assertEquals("only", keys["spotify:track:2"])
    }
}
