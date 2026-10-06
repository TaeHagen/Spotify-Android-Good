package com.taehagen.spotifygood.ui

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
