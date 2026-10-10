package com.taehagen.spotifygood.widget

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUpdatesTest {
    private val interval = 500L

    /** Collects [flow] in the background, with the virtual time of each value. */
    private class Pushes<T>(scope: TestScope, flow: Flow<T>) {
        val values = mutableListOf<Pair<Long, T>>()

        init {
            scope.backgroundScope.launch { flow.collect { values += scope.testScheduler.currentTime to it } }
            scope.runCurrent()
        }

        fun models(): List<T> = values.map { it.second }
    }

    /** [source] as a flow that counts its collections and cancellations. */
    private class Counted<T>(source: Flow<T>) {
        var collections = 0
        var completions = 0
        val flow: Flow<T> = flow {
            collections++
            emitAll(source)
        }.onCompletion { completions++ }
    }

    @Test
    fun theFirstStateIsPushedAtOnceAndABurstOnceItsIntervalIsOver() = runTest {
        val models = MutableStateFlow("a")
        val pushes = Pushes(this, models.throttleLatest(interval))
        assertEquals(listOf(0L to "a"), pushes.values)
        // A track change: several snapshots within the interval; only the last one is pushed.
        advanceTimeBy(100)
        models.value = "b"
        runCurrent()
        advanceTimeBy(100)
        models.value = "c"
        runCurrent()
        assertEquals(listOf("a"), pushes.models())
        advanceTimeBy(interval)
        runCurrent()
        assertEquals(listOf(0L to "a", interval to "c"), pushes.values)
    }

    @Test
    fun atMostOnePushPerInterval() = runTest {
        val models = MutableStateFlow(0)
        val pushes = Pushes(this, models.throttleLatest(interval))
        repeat(40) {
            advanceTimeBy(50)
            models.value = it + 1
            runCurrent()
        }
        advanceTimeBy(interval * 2)
        runCurrent()
        val times = pushes.values.map { it.first }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= interval })
        // The final state always arrives.
        assertEquals(40, pushes.models().last())
    }

    @Test
    fun aBurstThatEndsWhereItStartedPushesNothingMore() = runTest {
        val models = MutableStateFlow("playing")
        val pushes = Pushes(this, models.throttleLatest(interval))
        models.value = "paused"
        runCurrent()
        models.value = "playing"
        runCurrent()
        advanceTimeBy(interval * 2)
        runCurrent()
        assertEquals(listOf("playing"), pushes.models())
    }

    @Test
    fun withoutAWidgetNothingIsPushedOrEvenCollected() = runTest {
        val models = Counted(MutableStateFlow("a"))
        val placed = MutableStateFlow(false)
        val pushes = Pushes(this, WidgetUpdates.pushes(models.flow, placed, interval))
        advanceTimeBy(interval * 4)
        runCurrent()
        assertEquals(emptyList<String>(), pushes.models())
        assertEquals(0, models.collections)
    }

    @Test
    fun aPlacedWidgetGetsTheCurrentStateAndRemovingTheLastOneStopsCollecting() = runTest {
        val state = MutableStateFlow("a")
        val models = Counted(state)
        val placed = MutableStateFlow(false)
        val pushes = Pushes(this, WidgetUpdates.pushes(models.flow, placed, interval))
        placed.value = true
        runCurrent()
        assertEquals(listOf("a"), pushes.models())
        assertEquals(1, models.collections)
        placed.value = false
        runCurrent()
        assertEquals(1, models.completions)
        state.value = "b"
        advanceTimeBy(interval * 2)
        runCurrent()
        assertEquals(listOf("a"), pushes.models())
        // Placed again: the state as it is now.
        placed.value = true
        runCurrent()
        assertEquals(listOf("a", "b"), pushes.models())
    }

    @Test
    fun theIdsAreReadOnceAndAgainOnlyWhenTheSetMayHaveChanged() = runTest {
        var reads = 0
        var placed = intArrayOf()
        val ids = WidgetIds {
            reads++
            placed
        }
        val presence = Pushes(this, ids.placed)
        assertEquals(listOf(false), presence.models())
        assertEquals(1, reads)
        ids.current()
        ids.current()
        assertEquals(1, reads)
        // A widget placed: the provider's broadcast re-reads.
        placed = intArrayOf(7)
        ids.refresh()
        runCurrent()
        assertEquals(2, reads)
        assertEquals(listOf(false, true), presence.models())
        assertEquals(listOf(7), ids.current().toList())
        // Another refresh with the same set changes nothing downstream.
        ids.refresh()
        runCurrent()
        assertEquals(listOf(false, true), presence.models())
    }

    @Test
    fun idsThatCannotBeReadCountAsNone() = runTest {
        val ids = WidgetIds { throw SecurityException("no widgets service") }
        assertEquals(0, ids.current().size)
        val presence = Pushes(this, ids.placed)
        assertEquals(listOf(false), presence.models())
    }
}
