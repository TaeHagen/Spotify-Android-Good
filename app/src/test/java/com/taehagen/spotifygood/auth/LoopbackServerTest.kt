package com.taehagen.spotifygood.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException

class LoopbackServerTest {
    private val page = LoopbackServer.Page(
        title = "SpotifyGood",
        successMessage = "Done <ok>",
        failureMessage = "Failed",
        linkText = "Return",
    )

    @Test
    fun parsesRequestLines() {
        val request = parseRequestLine("GET /login?code=abc&state=x%2By%3D HTTP/1.1")!!
        assertEquals("GET", request.method)
        assertEquals("/login", request.path)
        assertEquals("abc", request.query["code"])
        assertEquals("x+y=", request.query["state"])
        assertEquals("/favicon.ico", parseRequestLine("GET /favicon.ico HTTP/1.1")!!.path)
        assertTrue(parseRequestLine("GET /login HTTP/1.1")!!.query.isEmpty())
        assertNull(parseRequestLine("garbage"))
        assertNull(parseRequestLine("GET http://evil/ HTTP/1.1"))
    }

    @Test
    fun parsesQueries() {
        assertEquals(mapOf("a" to "1", "b" to "", "c" to "x y"), parseQuery("a=1&b&c=x+y&a=2"))
        assertTrue(parseQuery("").isEmpty())
        // Malformed escapes are dropped instead of failing the request.
        assertEquals(mapOf("ok" to "1"), parseQuery("bad=%zz&ok=1"))
    }

    @Test
    fun verifiesState() {
        assertNull(LoopbackServer.callbackResult(mapOf("code" to "c"), "s"))
        assertNull(LoopbackServer.callbackResult(mapOf("code" to "c", "state" to "other"), "s"))
        assertEquals(LoopbackServer.Result.Code("c"), LoopbackServer.callbackResult(mapOf("code" to "c", "state" to "s"), "s"))
        assertEquals(
            LoopbackServer.Result.Error("access_denied"),
            LoopbackServer.callbackResult(mapOf("error" to "access_denied", "state" to "s"), "s"),
        )
    }

    @Test
    fun redirectResponseEscapesHtmlAndPointsBackToTheApp() {
        val response = String(LoopbackServer.redirectResponse(LoopbackServer.Result.Code("c"), page), Charsets.UTF_8)
        assertTrue(response.startsWith("HTTP/1.1 302 Found\r\n"))
        assertTrue(response.contains("Location: spotifygood://auth?ok=1\r\n"))
        assertTrue(response.contains("Done &lt;ok&gt;"))
        assertTrue(response.contains("href=\"spotifygood://auth?ok=1\""))
        val failure = String(LoopbackServer.redirectResponse(LoopbackServer.Result.Error("access_denied"), page), Charsets.UTF_8)
        assertTrue(failure.contains("Location: spotifygood://auth?ok=0\r\n"))
    }

    @Test
    fun servesUntilTheValidRedirectArrives() = runBlocking {
        val server = LoopbackServer.bind(listOf(0))
        assertTrue(server.redirectUri.startsWith("http://127.0.0.1:"))
        val result = async(Dispatchers.IO) { server.awaitResult("good-state", 10_000, page) }
        withTimeout(10_000) {
            assertTrue(request(server.port, "/favicon.ico").startsWith("HTTP/1.1 404"))
            assertTrue(request(server.port, "/login?code=x&state=forged").startsWith("HTTP/1.1 400"))
            val idle = withContext(Dispatchers.IO) { Socket(InetAddress.getByName("127.0.0.1"), server.port) }
            // An idle (pre-)connection must not block the real redirect.
            val response = request(server.port, "/login?code=the-code&state=good-state")
            assertTrue(response.startsWith("HTTP/1.1 302"))
            assertTrue(response.contains("Location: spotifygood://auth?ok=1"))
            assertEquals(LoopbackServer.Result.Code("the-code"), result.await())
            idle.close()
        }
        // The server is closed afterwards.
        try {
            withContext(Dispatchers.IO) { Socket(InetAddress.getByName("127.0.0.1"), server.port).close() }
            fail("server still listening")
        } catch (_: IOException) {
        }
    }

    @Test
    fun timesOut() = runBlocking {
        val server = LoopbackServer.bind(listOf(0))
        try {
            server.awaitResult("s", 200, page)
            fail("expected timeout")
        } catch (_: SocketTimeoutException) {
        }
    }

    @Test
    fun fallsBackToTheNextPort() {
        val first = LoopbackServer.bind(listOf(0))
        try {
            val second = LoopbackServer.bind(listOf(first.port, 0))
            assertTrue(second.port != first.port)
            second.close()
        } finally {
            first.close()
        }
    }

    private suspend fun request(port: Int, target: String): String = withContext(Dispatchers.IO) {
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
}
