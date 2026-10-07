package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.auth.KeystoreUnavailableException
import com.taehagen.spotifygood.download.DownloadRules.KeyFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

class KeyFailureTest {
    @Test
    fun aBusyKeystoreOnlyPostponesTheDownload() {
        // Retried, then reported as unavailable: the key and the data are intact.
        val busy = KeystoreUnavailableException("decrypt: Keystore unavailable", ProviderException("BACKEND_BUSY"))
        assertEquals(KeyFailure.RETRY_LATER, DownloadRules.keyFailure(busy))
    }

    @Test
    fun badDataMarksTheDownload() {
        assertEquals(KeyFailure.UNREADABLE, DownloadRules.keyFailure(AEADBadTagException("Tag mismatch")))
        // A permanently invalid key: what it sealed cannot be read any more.
        assertEquals(KeyFailure.UNREADABLE, DownloadRules.keyFailure(GeneralSecurityException("Key permanently invalidated")))
        assertEquals(KeyFailure.UNREADABLE, DownloadRules.keyFailure(IllegalArgumentException("Odd hex length")))
    }
}
