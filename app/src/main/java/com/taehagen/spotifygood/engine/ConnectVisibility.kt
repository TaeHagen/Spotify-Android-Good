package com.taehagen.spotifygood.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 */
internal class ConnectVisibility(private val scope: CoroutineScope, private val hideGraceMs: Long) {
    private val lock = Any()
    private val _visible = MutableStateFlow(false)

    /** What the engine is told. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    // Guarded by [lock].
    private var wanted = false
    private var pendingHide: Job? = null
    /** Bumped by every change, so a hide that was overtaken never applies. */
    private var version = 0L

    /** The holders say [visibleNow]: shown at once, hidden after the grace. */
    fun update(visibleNow: Boolean) {
        synchronized(lock) {
            // No change: a pending hide keeps its original deadline.
            if (visibleNow == wanted) return
            wanted = visibleNow
            val current = ++version
            pendingHide?.cancel()
            pendingHide = null
            if (visibleNow) {
                _visible.value = true
            } else if (_visible.value) {
                pendingHide = scope.launch {
                    delay(hideGraceMs)
                    synchronized(lock) {
                        if (version == current) {
                            _visible.value = false
                            pendingHide = null
                        }
                    }
                }
            }
        }
    }

    /**
     * A fresh engine start: no grace, the holders as they are now (a start for downloads alone
     * is hidden from the beginning). Returns the value to start with.
     */
    fun snap(): Boolean = synchronized(lock) {
        version++
        pendingHide?.cancel()
        pendingHide = null
        _visible.value = wanted
        wanted
    }
}
