package com.taehagen.spotifygood.connect

import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProviderSwitchTest {
    private class FakeSetting(var state: Int = COMPONENT_ENABLED_STATE_DEFAULT, val failing: Boolean = false) : ComponentSetting {
        /** Every write: each one is a package-wide PACKAGE_CHANGED on a device. */
        val writes = mutableListOf<Int>()

        override fun get(): Int = state

        override fun set(state: Int) {
            if (failing) throw SecurityException("no")
            writes += state
            this.state = state
        }
    }

    private fun TestScope.switch(setting: FakeSetting, sdk: Int = 34, credentials: () -> Boolean) = RouteProviderSwitch(
        scope = backgroundScope,
        setting = setting,
        storedCredentials = credentials,
        sdk = sdk,
        settleMs = SETTLE,
        io = StandardTestDispatcher(testScheduler),
        log = { _, _ -> },
    )

    private fun TestScope.settle() {
        advanceTimeBy(SETTLE + 1)
        runCurrent()
    }

    // ---- the rule ------------------------------------------------------------------------------

    @Test
    fun enabledWhileLoggedInFromAndroid12() {
        assertTrue(RouteProviderRule.wanted(31, loggedIn = true, storedCredentials = false))
        assertTrue("credentials not read yet", RouteProviderRule.wanted(35, loggedIn = false, storedCredentials = true))
        assertFalse(RouteProviderRule.wanted(36, loggedIn = false, storedCredentials = false))
        // Android 11 would keep the process bound for good.
        assertFalse(RouteProviderRule.wanted(30, loggedIn = true, storedCredentials = true))
    }

    @Test
    fun onlyADifferingStateIsWritten() {
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, RouteProviderRule.change(COMPONENT_ENABLED_STATE_DEFAULT, wanted = true))
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, RouteProviderRule.change(COMPONENT_ENABLED_STATE_DISABLED, wanted = true))
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, RouteProviderRule.change(COMPONENT_ENABLED_STATE_ENABLED, wanted = false))
        assertNull(RouteProviderRule.change(COMPONENT_ENABLED_STATE_ENABLED, wanted = true))
        assertNull("the manifest's default is off", RouteProviderRule.change(COMPONENT_ENABLED_STATE_DEFAULT, wanted = false))
        assertNull(RouteProviderRule.change(COMPONENT_ENABLED_STATE_DISABLED, wanted = false))
    }

    // ---- the switch ----------------------------------------------------------------------------

    @Test
    fun anOrdinaryProcessStartWritesNothing() = runTest {
        val enabled = FakeSetting(COMPONENT_ENABLED_STATE_ENABLED)
        switch(enabled) { true }.apply { reconcile() }
        settle()
        assertEquals(emptyList<Int>(), enabled.writes)
        val off = FakeSetting(COMPONENT_ENABLED_STATE_DEFAULT)
        switch(off) { false }.apply { reconcile() }
        settle()
        assertEquals(emptyList<Int>(), off.writes)
    }

    @Test
    fun theFirstStartAfterAnUpdateWithStoredCredentialsEnablesItOnce() = runTest {
        val setting = FakeSetting()
        val switch = switch(setting) { true }
        switch.reconcile()
        settle()
        assertEquals(listOf(COMPONENT_ENABLED_STATE_ENABLED), setting.writes)
        // The engine read the credentials, the playback service and the app come and go: nothing more.
        repeat(5) { switch.update(true) }
        settle()
        switch.reconcile()
        settle()
        assertEquals(listOf(COMPONENT_ENABLED_STATE_ENABLED), setting.writes)
    }

    @Test
    fun loginAndLogoutWriteOnceEach() = runTest {
        var stored = false
        val setting = FakeSetting()
        val switch = switch(setting) { stored }
        switch.reconcile()
        settle()
        assertEquals(emptyList<Int>(), setting.writes)
        // The engine says logged in before the credentials are written.
        switch.update(true)
        settle()
        stored = true
        assertEquals(listOf(COMPONENT_ENABLED_STATE_ENABLED), setting.writes)
        stored = false
        switch.update(false)
        settle()
        assertEquals(listOf(COMPONENT_ENABLED_STATE_ENABLED, COMPONENT_ENABLED_STATE_DEFAULT), setting.writes)
    }

    @Test
    fun aLogoutAndALoginWithinTheSettleWriteNothing() = runTest {
        var stored = true
        val setting = FakeSetting(COMPONENT_ENABLED_STATE_ENABLED)
        val switch = switch(setting) { stored }
        switch.update(true)
        settle()
        stored = false
        switch.update(false)
        advanceTimeBy(SETTLE / 2)
        stored = true
        switch.update(true)
        settle()
        assertEquals(emptyList<Int>(), setting.writes)
    }

    @Test
    fun credentialsTheEngineCouldNotReadKeepItEnabled() = runTest {
        // A Keystore briefly unavailable: logged out for this process, the credentials stay stored.
        val setting = FakeSetting(COMPONENT_ENABLED_STATE_ENABLED)
        switch(setting) { true }.update(false)
        settle()
        assertEquals(emptyList<Int>(), setting.writes)
    }

    @Test
    fun android11NeverEnablesItAndDisablesOneLeftEnabled() = runTest {
        val off = FakeSetting()
        switch(off, sdk = 30) { true }.apply {
            reconcile()
            update(true)
        }
        settle()
        assertEquals(emptyList<Int>(), off.writes)
        val leftOn = FakeSetting(COMPONENT_ENABLED_STATE_ENABLED)
        switch(leftOn, sdk = 30) { true }.reconcile()
        settle()
        assertEquals(listOf(COMPONENT_ENABLED_STATE_DEFAULT), leftOn.writes)
    }

    @Test
    fun aRefusedWriteIsOnlyLogged() = runTest {
        val setting = FakeSetting(failing = true)
        val switch = switch(setting) { true }
        switch.reconcile()
        settle()
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, setting.state)
        // Still working: the next wish is tried again.
        switch.update(true)
        settle()
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, setting.state)
    }

    @Test
    fun theProviderIsNamedWithoutLoadingItsClass() {
        // Provider builds its ComponentName from this name, so App.onCreate never loads the API 30
        // class below Android 11; the name must still be the service's.
        assertEquals(ConnectRouteProviderService::class.java.name, RouteProviderSwitch.PROVIDER_CLASS)
    }

    private companion object {
        const val SETTLE = 2_000L
    }
}
