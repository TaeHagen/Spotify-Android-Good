package com.taehagen.spotifygood.ui.screens.player

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeThrottleTest {
    @Test
    fun sendsFirstValueImmediatelyThenLatestPerInterval() = runTest {
        val sent = mutableListOf<Int>()
        val throttle = VolumeThrottle(backgroundScope, intervalMs = 200) { sent += it }
        runCurrent()

        throttle.offer(100)
        runCurrent()
        assertEquals(listOf(100), sent)

        // A burst during the interval collapses to its latest value.
        throttle.offer(200)
        throttle.offer(300)
        throttle.offer(400)
        runCurrent()
        assertEquals(listOf(100), sent)

        advanceTimeBy(201)
        runCurrent()
        assertEquals(listOf(100, 400), sent)
    }

    @Test
    fun resendsSameValueAfterExternalChange() = runTest {
        val sent = mutableListOf<Int>()
        val throttle = VolumeThrottle(backgroundScope, intervalMs = 200) { sent += it }
        runCurrent()
        throttle.offer(500)
        runCurrent()
        advanceTimeBy(500)
        throttle.offer(500)
        runCurrent()
        assertEquals(listOf(500, 500), sent)
    }

    @Test
    fun clampsToConnectRange() = runTest {
        val sent = mutableListOf<Int>()
        val throttle = VolumeThrottle(backgroundScope, intervalMs = 10) { sent += it }
        runCurrent()
        throttle.offer(-5)
        runCurrent()
        advanceTimeBy(20)
        throttle.offer(1_000_000)
        runCurrent()
        assertEquals(listOf(0, MAX_CONNECT_VOLUME), sent)
    }
}
