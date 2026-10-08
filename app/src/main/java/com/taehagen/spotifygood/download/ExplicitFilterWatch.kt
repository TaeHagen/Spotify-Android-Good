package com.taehagen.spotifygood.download

/**
 * What the downloads bookkeeping knows about the explicit filter behind a catalog answer.
 *
 * While the effective filter is on ("Hide explicit content", or the account's own filter), the
 * catalog returns every explicit track and episode with `playable:false`, whatever its
 * availability. Downloads ignore the filter (display and playback apply it), so such an answer
 * says nothing about an explicit item ([DownloadRules.downloadVerdict]). The engine does not report
 * which filter an answer was computed with, so a lookup takes a [Mark] before it asks ([begin]) and
 * then asks [mayFilter]: the filter may have applied unless it was known to be off, and applied by
 * the engine, from before the lookup until after it. Until the first value is known, it may have.
 */
internal class ExplicitFilterWatch {
    /** The watch when a lookup started. */
    class Mark internal constructor(internal val generation: Long, internal val knownOff: Boolean)

    private data class State(val generation: Long, val filtered: Boolean, val settled: Boolean)

    private val lock = Any()
    @Volatile private var state = State(generation = 0, filtered = true, settled = false)

    /** Taken before a catalog lookup whose `playable` flags are used. */
    fun begin(): Mark = state.let { Mark(it.generation, knownOff = !it.filtered && it.settled) }

    /**
     * Whether the filter may have applied to an answer requested after [mark]: unless it was off and
     * applied then, and has not changed since (a change always starts a new generation).
     */
    fun mayFilter(mark: Mark): Boolean = !mark.knownOff || state.generation != mark.generation

    /** Whether the effective filter is on (or not known yet). */
    val filtered: Boolean get() = state.filtered

    /**
     * The effective filter is now [filtered] (also when unchanged: whatever was asked before cannot
     * count on the engine's filter any more); not applied by the engine yet. Returns the generation
     * to [settle].
     */
    fun update(filtered: Boolean): Long = synchronized(lock) {
        State(state.generation + 1, filtered, settled = false).also { state = it }.generation
    }

    /** The engine runs with the value of [generation] (ignored once a newer value was observed). */
    fun settle(generation: Long) {
        synchronized(lock) {
            if (state.generation == generation) state = state.copy(settled = true)
        }
    }
}
