package com.taehagen.spotifygood.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Spotify Connect visibility sent to the engine (`EngineSettings.connectVisible`), with
 * hysteresis: becoming visible applies at once, becoming hidden only once the holders stayed
 * "hidden" for [hideGraceMs]. Hiding shuts Spirc down, and becoming visible again then costs a
 * full re-login (docs/ARCHITECTURE.md §8), so a quick switch to another app and back must not
 * hide the phone. Thread-safe.
 *
 * The grace is wall time on [clock] (`elapsedRealtime`): it usually starts right before the
 * phone sleeps (the app left, the paused service let go), and coroutine delays count only awake
 * time. The wait looks at the clock at least every [DEADLINE_CHECK_MS] of awake time, and the
 * engine's alarm ([hideDeadline], [expireDue]) applies a hide that came due during deep sleep.
 */
internal class ConnectVisibility(
    private val scope: CoroutineScope,
    private val hideGraceMs: Long,
    private val clock: () -> Long,
) {
    private val lock = Any()
    private val _visible = MutableStateFlow(false)

    /** What the engine is told. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    // Guarded by [lock].
    private var wanted = false
    private var pendingHide: Job? = null
    @Volatile private var hideAt: Long? = null
    /** Bumped by every change, so a hide that was overtaken never applies. */
    private var version = 0L

    /** When a pending hide is due (on [clock]); null without one. */
    val hideDeadline: Long? get() = hideAt

    /**
     * The holders say [visibleNow]: shown at once, hidden after the grace. [immediate]: the grace
     * was already served (the paused service let go after its paused lifetime), hidden at once.
     */
    fun update(visibleNow: Boolean, immediate: Boolean = false) {
        synchronized(lock) {
            // No change: a pending hide keeps its original deadline, unless it is due now.
            if (visibleNow == wanted && !(immediate && !visibleNow && hideAt != null)) return
            wanted = visibleNow
            val current = ++version
            cancelHideLocked()
            if (visibleNow) {
                _visible.value = true
            } else if (_visible.value) {
                if (immediate) {
                    _visible.value = false
                    return
                }
                val deadline = clock() + hideGraceMs
                hideAt = deadline
                pendingHide = scope.launch {
                    delayUntil(deadline, clock)
                    synchronized(lock) {
                        if (version == current) hideLocked()
                    }
                }
            }
        }
    }

    /** Whether a pending hide is due. */
    fun isHideDue(): Boolean = hideAt?.let { clock() >= it } == true

    /** Applies a pending hide whose deadline passed (the engine's alarm, an event that woke the process). */
    fun expireDue() {
        synchronized(lock) {
            if (isHideDue()) hideLocked()
        }
    }

    /**
     * A fresh engine start: no grace, the holders as they are now (a start for downloads alone
     * is hidden from the beginning). Returns the value to start with.
     */
    fun snap(): Boolean = synchronized(lock) {
        version++
        cancelHideLocked()
        _visible.value = wanted
        wanted
    }

    private fun hideLocked() {
        _visible.value = false
        cancelHideLocked()
    }

    private fun cancelHideLocked() {
        pendingHide?.cancel()
        pendingHide = null
        hideAt = null
    }
}
