package com.taehagen.spotifygood.ui

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.withTimeoutOrNull

// Pure helpers of the UI shell (no Android framework calls; covered by JVM unit tests).

/**
 * Whether the user is logged in. On a cold start [isLoggedIn] reads false until the engine has
 * loaded the stored credentials, so a false answer is only trusted after [awaitReady] returned
 * (waiting at most [timeoutMs]; a timeout counts as logged out).
 */
internal suspend fun awaitLoginState(
    isLoggedIn: () -> Boolean,
    awaitReady: suspend () -> Unit,
    timeoutMs: Long,
): Boolean {
    if (isLoggedIn()) return true
    return withTimeoutOrNull(timeoutMs) { awaitReady() } != null && isLoggedIn()
}

/**
 * ViewModel stores of the signed-in UI (the main scaffold's navigation entries, player surfaces and
 * sheets). Held by the activity-scoped shell, so a session survives configuration changes; a
 * session is [release]d when the signed-in UI is left for good (logout, Premium gate, activity
 * finishing), which clears all its ViewModels. Without this the NavController's entries live in the
 * activity's store, and disposing the scaffold without popping them leaves every ViewModel of the
 * old session running (collectors, refetches) until the activity finishes. Main thread only.
 */
internal class MainSessionStore {
    private class Session : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private var current: Session? = null

    /** The current session (a new one once the previous one was released). */
    fun acquire(): ViewModelStoreOwner = current ?: Session().also { current = it }

    /** Clears every ViewModel of [session]; the next [acquire] starts a new session. */
    fun release(session: ViewModelStoreOwner) {
        if (current === session) current = null
        session.viewModelStore.clear()
    }

    /** The shell itself is cleared (activity finished). */
    fun clear() {
        current?.viewModelStore?.clear()
        current = null
    }
}
