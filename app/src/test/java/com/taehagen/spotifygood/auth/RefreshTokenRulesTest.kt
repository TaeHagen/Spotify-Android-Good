package com.taehagen.spotifygood.auth

import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshTokenRulesTest {
    @Test
    fun aRefusedAccountForgetsItsRefreshToken() {
        // A Free account (or a refused / rejected one) that stays logged out: the next "Log in"
        // must reach the device-code request instead of silently retrying that account.
        assertFalse(keepsRefreshToken(NativeErrorCode.PREMIUM_REQUIRED, loggedIn = false))
        assertFalse(keepsRefreshToken(NativeErrorCode.PLAYBACK_REFUSED, loggedIn = false))
        assertFalse(keepsRefreshToken(NativeErrorCode.BAD_CREDENTIALS, loggedIn = false))
    }

    @Test
    fun transientFailuresKeepIt() {
        // Retried without a new approval.
        assertTrue(keepsRefreshToken(NativeErrorCode.NETWORK, loggedIn = false))
        assertTrue(keepsRefreshToken(NativeErrorCode.CANCELLED, loggedIn = false))
        assertTrue(keepsRefreshToken(null, loggedIn = false))
        // Logged in after all (reusable credentials exist): the account screens explain it.
        assertTrue(keepsRefreshToken(NativeErrorCode.PREMIUM_REQUIRED, loggedIn = true))
    }

    @Test
    fun accountRejections() {
        assertTrue(isAccountRejection(NativeErrorCode.PREMIUM_REQUIRED))
        assertFalse(isAccountRejection(NativeErrorCode.NETWORK))
        assertFalse(isAccountRejection(NativeErrorCode.NOT_CONNECTED))
    }
}
