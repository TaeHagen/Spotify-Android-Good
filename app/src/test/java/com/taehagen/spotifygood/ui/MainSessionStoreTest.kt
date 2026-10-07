package com.taehagen.spotifygood.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSessionStoreTest {
    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = Probe() as T
    }

    private fun ViewModelStoreOwner.probe(): Probe = ViewModelProvider(this, factory)[Probe::class.java]

    @Test
    fun aConfigurationChangeGetsTheSameSessionBack() {
        val store = MainSessionStore()
        val owner = store.acquire()
        val probe = owner.probe()
        val again = store.acquire()
        assertSame(owner, again)
        assertSame(probe, again.probe())
        assertFalse(probe.cleared)
    }

    @Test
    fun releasingClearsTheSessionsViewModels() {
        // Logout / Premium gate: MainScaffold leaves the composition for good.
        val store = MainSessionStore()
        val owner = store.acquire()
        val probe = owner.probe()
        store.release(owner)
        assertTrue(probe.cleared)
        val next = store.acquire()
        assertNotSame(owner, next)
        assertNotSame(probe, next.probe())
    }

    @Test
    fun releasingAnOldSessionKeepsTheCurrentOne() {
        // Content that kept an old session (quick MAIN → LOGIN → MAIN) is disposed later.
        val store = MainSessionStore()
        val old = store.acquire()
        store.release(old)
        val current = store.acquire()
        val probe = current.probe()
        store.release(old)
        assertFalse(probe.cleared)
        assertSame(current, store.acquire())
    }

    @Test
    fun aSessionStillShownIsNotReleasedWhenIdle() {
        // The root already left MAIN, but the signed-in UI is still fading out.
        val store = MainSessionStore()
        val probe = store.acquire().probe()
        store.enter()
        assertFalse(store.releaseIfIdle())
        assertFalse(probe.cleared)
    }

    @Test
    fun aSessionDisposedByAConfigurationChangeMidFadeIsReleasedWhenIdle() {
        // MAIN was fading out to Login when the activity was recreated: its own release was
        // skipped (configuration change) and the new activity never shows it again.
        val store = MainSessionStore()
        val owner = store.acquire()
        val probe = owner.probe()
        store.enter()
        store.exit()
        assertTrue(store.releaseIfIdle())
        assertTrue(probe.cleared)
        assertNotSame(owner, store.acquire())
        // Nothing left to release.
        store.releaseIfIdle()
        assertFalse(MainSessionStore().releaseIfIdle())
    }

    @Test
    fun aConfigurationChangeWhileSignedInKeepsTheSession() {
        val store = MainSessionStore()
        val owner = store.acquire()
        val probe = owner.probe()
        store.enter()
        store.exit() // old activity's composition disposed
        store.enter() // recreated activity shows MAIN again
        assertSame(owner, store.acquire())
        assertFalse(store.releaseIfIdle())
        assertFalse(probe.cleared)
    }

    @Test
    fun clearingTheShellClearsTheCurrentSession() {
        val store = MainSessionStore()
        val probe = store.acquire().probe()
        store.clear()
        assertTrue(probe.cleared)
    }
}
