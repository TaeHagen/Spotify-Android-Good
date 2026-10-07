package com.taehagen.spotifygood.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectVisibilityTest {
    private val grace = 20_000L

    @Test
    fun showsAtOnceAndHidesAfterTheGrace() = runTest {
        val v = ConnectVisibility(backgroundScope, grace)
        v.update(true)
        assertEquals(true, v.visible.value)
        v.update(false)
        advanceTimeBy(grace - 1)
        runCurrent()
        assertEquals("still a target during the grace", true, v.visible.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(false, v.visible.value)
    }

    @Test
    fun aQuickSwitchBackNeverHides() = runTest {
        val v = ConnectVisibility(backgroundScope, grace)
        v.update(true)
        v.update(false) // the app left the foreground
        advanceTimeBy(10_000)
        v.update(true) // and came back
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(true, v.visible.value)
        // A later hide gets a full grace of its own.
        v.update(false)
        advanceTimeBy(grace - 1)
        runCurrent()
        assertEquals(true, v.visible.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(false, v.visible.value)
    }

    @Test
    fun repeatedHidesKeepTheFirstDeadline() = runTest {
        val v = ConnectVisibility(backgroundScope, grace)
        v.update(true)
        v.update(false)
        advanceTimeBy(15_000)
        v.update(false)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(false, v.visible.value)
    }

    @Test
    fun aFreshStartUsesTheHoldersAsTheyAre() = runTest {
        val v = ConnectVisibility(backgroundScope, grace)
        v.update(true)
        v.update(false) // hide pending
        // A start for downloads alone: hidden at once, and the pending hide is moot.
        assertEquals(false, v.snap())
        assertEquals(false, v.visible.value)
        v.update(true)
        assertEquals(true, v.snap())
        assertEquals(true, v.visible.value)
    }
}
