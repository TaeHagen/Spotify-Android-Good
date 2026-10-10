package com.taehagen.spotifygood.widget

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** When the widget is pushed (docs/ARCHITECTURE.md §9.4, §10); pure, apart from [WidgetIds]'s read. */
internal object WidgetUpdates {
    /**
     * The pushes for [models]: none while no widget is placed ([placed] false), when [models] is
     * not even collected; a model equal to the last pushed one is dropped; at most one push per
     * [intervalMs] (a track change brings several snapshots), the latest model winning.
     */
    fun <T> pushes(models: Flow<T>, placed: Flow<Boolean>, intervalMs: Long): Flow<T> =
        placed.distinctUntilChanged().flatMapLatest { any -> if (any) models.throttleLatest(intervalMs) else emptyFlow() }
}

/**
 * The first value at once, then at most one per [periodMs]: the latest of those that arrived
 * meanwhile, unless it equals the last one emitted. The period counts from the end of an emission
 * (a push in progress is never overtaken by the next one).
 */
internal fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = flow {
    coroutineScope {
        val latest = produce(capacity = Channel.CONFLATED) { collect { send(it) } }
        var emitted = false
        var last: T? = null
        for (value in latest) {
            if (emitted && value == last) continue
            emit(value)
            emitted = true
            last = value
            delay(periodMs)
        }
    }
}

/**
 * The ids of the placed widgets: read from the system once per process ([read]: a binder call),
 * then only again when the set may have changed ([refresh]: the provider's update, delete,
 * enable, disable and restore broadcasts).
 */
internal class WidgetIds(private val read: () -> IntArray) {
    private val ids = MutableStateFlow<IntArray?>(null)

    /** Whether a widget is placed; the first collection reads the ids if nothing did yet. */
    val placed: Flow<Boolean> = flow {
        current()
        emitAll(ids.map { it != null && it.isNotEmpty() })
    }.distinctUntilChanged()

    fun current(): IntArray = ids.value ?: refresh()

    fun refresh(): IntArray = runCatching(read).getOrElse { IntArray(0) }.also { ids.value = it }
}
