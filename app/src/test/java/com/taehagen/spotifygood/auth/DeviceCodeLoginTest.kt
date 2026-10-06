package com.taehagen.spotifygood.auth

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DeviceCodeLoginTest {

    // ---- parsing ----------------------------------------------------------------------------

    @Test
    fun parsesDeviceAuthorization() {
        val body = """
            {"device_code":"dc-1","user_code":"ABCD-EFGH","verification_uri":"https://www.spotify.com/pair",
             "verification_uri_complete":"https://www.spotify.com/pair?code=ABCD-EFGH","expires_in":900,"interval":5,
             "something_new":true}
        """.trimIndent()
        val login = parseDeviceAuthorization(HttpResult(200, body), nowMs = 1_000)
        assertEquals("dc-1", login.deviceCode)
        assertEquals("ABCD-EFGH", login.userCode)
        assertEquals("https://www.spotify.com/pair", login.verificationUri)
        assertEquals("https://www.spotify.com/pair?code=ABCD-EFGH", login.verificationUriComplete)
        assertEquals(901_000, login.expiresAtMs)
        assertEquals(5, login.intervalSec)
    }

    @Test
    fun deviceAuthorizationDefaultsOptionalFields() {
        val body = """{"device_code":"dc","user_code":"UC","verification_uri":"https://spotify.com/pair","expires_in":600}"""
        val login = parseDeviceAuthorization(HttpResult(200, body), nowMs = 0)
        assertNull(login.verificationUriComplete)
        assertEquals(PendingDeviceLogin.DEFAULT_INTERVAL_SEC, login.intervalSec)
        assertEquals(600_000, login.expiresAtMs)
    }

    @Test
    fun deviceAuthorizationErrors() {
        try {
            parseDeviceAuthorization(HttpResult(400, """{"error":"unauthorized_client","error_description":"nope"}"""), 0)
            fail("expected OAuthException")
        } catch (e: OAuthException) {
            assertEquals("unauthorized_client", e.error)
            assertEquals("nope", e.description)
        }
        try {
            parseDeviceAuthorization(HttpResult(502, "<html>bad gateway</html>"), 0)
            fail("expected IOException")
        } catch (_: IOException) {
        }
        try {
            parseDeviceAuthorization(HttpResult(200, "not json"), 0)
            fail("expected OAuthException")
        } catch (e: OAuthException) {
            assertEquals(OAuthException.INVALID_RESPONSE, e.error)
        }
    }

    @Test
    fun parsesTokenPollOutcomes() {
        assertEquals(TokenPollResult.Pending, parseTokenPoll(HttpResult(400, """{"error":"authorization_pending"}""")))
        assertEquals(TokenPollResult.SlowDown, parseTokenPoll(HttpResult(400, """{"error":"slow_down"}""")))
        assertEquals(TokenPollResult.SlowDown, parseTokenPoll(HttpResult(429, "")))
        assertEquals(
            TokenPollResult.Failure("expired_token", "Device code expired"),
            parseTokenPoll(HttpResult(400, """{"error":"expired_token","error_description":"Device code expired"}""")),
        )
        assertEquals(TokenPollResult.Failure("access_denied"), parseTokenPoll(HttpResult(400, """{"error":"access_denied"}""")))
        assertEquals(TokenPollResult.Failure("http_400"), parseTokenPoll(HttpResult(400, "garbage")))
        assertTrue(parseTokenPoll(HttpResult(503, "")) is TokenPollResult.Retryable)
    }

    @Test
    fun parsesTokenSuccess() {
        val body = """{"access_token":"AT","token_type":"Bearer","expires_in":3600,"refresh_token":"RT","scope":"streaming"}"""
        val result = parseTokenPoll(HttpResult(200, body))
        result as TokenPollResult.Success
        assertEquals("AT", result.token.accessToken)
        assertEquals("RT", result.token.refreshToken)
        assertEquals(3600, result.token.expiresIn)
        // Tokens never end up in logs via toString().
        val printed = result.token.copy(accessToken = "secret-access", refreshToken = "secret-refresh").toString()
        assertTrue(!printed.contains("secret"))
    }

    @Test
    fun tokenWithoutAccessTokenIsInvalid() {
        val result = parseTokenPoll(HttpResult(200, """{"token_type":"Bearer"}"""))
        assertEquals(TokenPollResult.Failure(OAuthException.INVALID_RESPONSE), result)
    }

    // ---- poller state machine (virtual time) --------------------------------------------------

    private val token = TokenResponse(accessToken = "AT", expiresIn = 3600)

    private fun login(expiresAtMs: Long = 600_000, intervalSec: Int = 5) =
        PendingDeviceLogin("dc", "UC", "https://www.spotify.com/pair", null, expiresAtMs, intervalSec)

    private fun TestScope.poller(results: List<TokenPollResult>, polls: MutableList<Long>, intervals: MutableList<Int> = mutableListOf()): DeviceCodePoller {
        val queue = ArrayDeque(results)
        return DeviceCodePoller(
            poll = { code ->
                assertEquals("dc", code)
                polls += currentTime
                queue.removeFirstOrNull() ?: TokenPollResult.Pending
            },
            clock = { currentTime },
            onIntervalChanged = { intervals += it },
        )
    }

    @Test
    fun pollsAtIntervalAndHonoursSlowDown() = runTest {
        val polls = mutableListOf<Long>()
        val intervals = mutableListOf<Int>()
        val p = poller(
            listOf(TokenPollResult.Pending, TokenPollResult.SlowDown, TokenPollResult.Pending, TokenPollResult.Success(token)),
            polls,
            intervals,
        )
        val result = p.awaitToken(login())
        assertEquals("AT", result.accessToken)
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L), polls)
        assertEquals(listOf(10), intervals)
    }

    @Test
    fun stopsWhenTheCodeExpires() = runTest {
        val polls = mutableListOf<Long>()
        val p = poller(emptyList(), polls)
        try {
            p.awaitToken(login(expiresAtMs = 12_000))
            fail("expected expiry")
        } catch (e: OAuthException) {
            assertEquals(OAuthException.EXPIRED_TOKEN, e.error)
        }
        assertEquals(listOf(5_000L, 10_000L), polls)
        assertEquals(12_000L, currentTime)
    }

    @Test
    fun alreadyExpiredCodeFailsWithoutPolling() = runTest {
        val polls = mutableListOf<Long>()
        try {
            poller(emptyList(), polls).awaitToken(login(expiresAtMs = 0))
            fail("expected expiry")
        } catch (e: OAuthException) {
            assertEquals(OAuthException.EXPIRED_TOKEN, e.error)
        }
        assertTrue(polls.isEmpty())
    }

    @Test
    fun terminalErrorsStopPolling() = runTest {
        val polls = mutableListOf<Long>()
        val p = poller(listOf(TokenPollResult.Pending, TokenPollResult.Failure("access_denied")), polls)
        try {
            p.awaitToken(login())
            fail("expected denial")
        } catch (e: OAuthException) {
            assertEquals(OAuthException.ACCESS_DENIED, e.error)
        }
        assertEquals(2, polls.size)
    }

    @Test
    fun networkErrorsBackOffExponentially() = runTest {
        val polls = mutableListOf<Long>()
        val p = poller(
            listOf(TokenPollResult.Retryable("io"), TokenPollResult.Retryable("io"), TokenPollResult.Success(token)),
            polls,
        )
        p.awaitToken(login())
        assertEquals(listOf(5_000L, 15_000L, 35_000L), polls)
    }

    @Test
    fun networkErrorsAreBounded() = runTest {
        val polls = mutableListOf<Long>()
        val p = poller(List(100) { TokenPollResult.Retryable("io") }, polls)
        try {
            p.awaitToken(login(expiresAtMs = Long.MAX_VALUE / 2))
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(DeviceCodePoller.MAX_RETRYABLE_FAILURES + 1, polls.size)
    }

    @Test
    fun pendingResetsBackoff() = runTest {
        val polls = mutableListOf<Long>()
        val p = poller(
            listOf(TokenPollResult.Retryable("io"), TokenPollResult.Pending, TokenPollResult.Success(token)),
            polls,
        )
        p.awaitToken(login())
        // 5 s, then 10 s backoff, then back to the 5 s interval.
        assertEquals(listOf(5_000L, 15_000L, 20_000L), polls)
    }
}
