package com.taehagen.spotifygood.ui.screens.login

import com.taehagen.spotifygood.auth.LoginState
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginStepTest {
    @Test
    fun aStaleSuccessShowsTheOptions() {
        // Logged out (logout, rejected credentials) after a finished login: never a dead-end
        // "Connecting" card.
        assertEquals(LoginStep.OPTIONS, loginStep(LoginState.Success, loggedIn = false))
        // During the fade from the login screen to the app.
        assertEquals(LoginStep.CONNECTING, loginStep(LoginState.Success, loggedIn = true))
    }

    @Test
    fun flowStates() {
        assertEquals(LoginStep.OPTIONS, loginStep(LoginState.Idle, loggedIn = false))
        assertEquals(LoginStep.OPTIONS, loginStep(LoginState.Failed("x", "BAD_CREDENTIALS"), loggedIn = false))
        assertEquals(LoginStep.CONNECTING, loginStep(LoginState.Connecting, loggedIn = false))
        assertEquals(LoginStep.BROWSER, loginStep(LoginState.WaitingForBrowser, loggedIn = false))
        assertEquals(LoginStep.ZEROCONF, loginStep(LoginState.WaitingForDevice, loggedIn = false))
        assertEquals(
            LoginStep.DEVICE_CODE,
            loginStep(LoginState.AwaitingApproval("ABCD", "https://x", null, 0L), loggedIn = false),
        )
    }
}
