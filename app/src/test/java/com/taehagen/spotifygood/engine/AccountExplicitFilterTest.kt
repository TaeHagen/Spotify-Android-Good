package com.taehagen.spotifygood.engine

import com.taehagen.spotifygood.data.settings.Settings
import com.taehagen.spotifygood.data.settings.toEngineSettings
import com.taehagen.spotifygood.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account's own explicit filter outlives the session (a child account offline). */
class AccountExplicitFilterTest {
    private val child = User(username = "kid", product = "premium", country = "SE", explicitFilter = true)

    @Test
    fun aColdStartWithoutANetworkUsesTheLastReportedValue() {
        // knownUser: only the username until a session is online.
        val stub = knownUser(null, null) ?: User(username = "kid")
        assertFalse(stub.reportedOnline)
        assertTrue(accountExplicitFilter(stub, persisted = true))
        assertFalse(accountExplicitFilter(stub, persisted = false))
        assertTrue(accountExplicitFilter(null, persisted = true))
        // An online session's report wins over the stored value.
        assertTrue(accountExplicitFilter(child, persisted = false))
        assertFalse(accountExplicitFilter(child.copy(explicitFilter = false), persisted = true))
    }

    @Test
    fun onlyAReportedValueIsPersisted() {
        assertEquals(true, accountExplicitFilterToPersist(child, persisted = false))
        assertNull("unchanged", accountExplicitFilterToPersist(child, persisted = true))
        assertEquals(false, accountExplicitFilterToPersist(child.copy(explicitFilter = false), persisted = true))
        // The username-only stand-in knows nothing about the filter: the stored value stays.
        assertNull(accountExplicitFilterToPersist(User(username = "kid"), persisted = true))
        assertNull(accountExplicitFilterToPersist(null, persisted = true))
    }

    @Test
    fun theOfflineSessionGetsThePersistedValue() {
        val settings = Settings(accountExplicitFilter = true)
        val engine = settings.toEngineSettings(metered = false, defaultDeviceName = "Pixel")
        assertTrue(engine.accountFilterExplicit)
        assertFalse("not the app setting", engine.filterExplicit)
        assertFalse(Settings().toEngineSettings(false, "Pixel").accountFilterExplicit)
    }
}
