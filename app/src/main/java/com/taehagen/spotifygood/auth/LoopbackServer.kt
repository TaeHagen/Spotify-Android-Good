package com.taehagen.spotifygood.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * One-shot HTTP listener on the IPv4 loopback for the PKCE redirect
 * (`http://127.0.0.1:<port>/login?code=…&state=…`).
 *
 * Binds 127.0.0.1 explicitly (on Android `getLoopbackAddress()` may answer `::1`, which the
 * browser does not reach for a 127.0.0.1 redirect). Every connection is handled independently, so
 * browser pre-connects or `/favicon.ico` requests (answered 404) cannot block or end the flow;
 * requests with a wrong `state` are rejected and ignored. The first valid `/login` request gets a
 * 302 back to the app plus a small HTML page with a "return" link, then the server closes.
 */
internal class LoopbackServer private constructor(private val server: ServerSocket) : Closeable {

    sealed interface Result {
        data class Code(val code: String) : Result {
            override fun toString(): String = "Code(<redacted>)"
        }
        data class Error(val error: String) : Result
    }

    /** User-visible texts of the page shown in the browser (already localised). */
    data class Page(
        val title: String,
        val successMessage: String,
        val failureMessage: String,
        val linkText: String,
        val returnUri: String = APP_RETURN_URI,
    )

    val port: Int get() = server.localPort
    val redirectUri: String get() = "http://127.0.0.1:$port$CALLBACK_PATH"

    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    /**
     * Serves until a valid callback arrives (returned), [timeoutMs] passes
     * ([SocketTimeoutException]) or the coroutine is cancelled. Closes the server in every case.
     */
    suspend fun awaitResult(expectedState: String, timeoutMs: Long, page: Page): Result = try {
        coroutineScope {
            val result = CompletableDeferred<Result>()
            val acceptor = launch(Dispatchers.IO) {
                while (isActive) {
                    val client = try {
                        server.accept()
                    } catch (e: IOException) {
                        result.completeExceptionally(e)
                        break
                    }
                    clients += client
                    launch(Dispatchers.IO) {
                        try {
                            handle(client, expectedState, page)?.let { result.complete(it) }
                        } catch (_: IOException) {
                            // Broken or idle connection: ignore, keep serving.
                        } finally {
                            clients -= client
                            client.closeQuietly()
                        }
                    }
                }
            }
            try {
                withTimeoutOrNull(timeoutMs) { result.await() }
                    ?: throw SocketTimeoutException("No login redirect within ${timeoutMs}ms")
            } finally {
                close()
                acceptor.cancel()
            }
        }
    } finally {
        close()
    }

    /** Stops listening and drops open connections (unblocks [awaitResult]). Idempotent. */
    override fun close() {
        server.closeQuietly()
        clients.forEach { it.closeQuietly() }
    }

    /** Handles one connection; returns a result when this was the redirect we wait for. */
    private fun handle(client: Socket, expectedState: String, page: Page): Result? {
        client.soTimeout = READ_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1), 4096)
        val requestLine = reader.readLine() ?: return null
        // Drain the headers (bounded) so the browser sees a well-behaved server.
        var headers = 0
        while (headers < MAX_HEADERS) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers++
        }
        val out = client.getOutputStream()
        val request = parseRequestLine(requestLine)
        if (request == null || request.path != CALLBACK_PATH) {
            out.write(plainResponse(404, "Not Found"))
            out.flush()
            return null
        }
        if (request.method != "GET") {
            out.write(plainResponse(405, "Method Not Allowed"))
            out.flush()
            return null
        }
        val result = callbackResult(request.query, expectedState)
        if (result == null) {
            // Missing/wrong state: not our redirect (or a forged one). Reject and keep waiting.
            out.write(plainResponse(400, "Bad Request"))
            out.flush()
            return null
        }
        out.write(redirectResponse(result, page))
        out.flush()
        return result
    }

    companion object {
        const val CALLBACK_PATH = "/login"
        private const val BACKLOG = 8
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_HEADERS = 100
        private val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** Binds the first free port of [ports] on 127.0.0.1 (blocking; call on IO). Throws [BindException]. */
        fun bind(ports: List<Int>): LoopbackServer {
            var lastError: IOException? = null
            for (port in ports) {
                val socket = ServerSocket()
                try {
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(IPV4_LOOPBACK, port), BACKLOG)
                    return LoopbackServer(socket)
                } catch (e: IOException) {
                    socket.closeQuietly()
                    lastError = e
                }
            }
            throw (lastError as? BindException) ?: BindException("No loopback port available: ${lastError?.message}")
        }

        /** The callback outcome for a `/login` query, or `null` if [expectedState] does not match. */
        internal fun callbackResult(query: Map<String, String>, expectedState: String): Result? {
            val state = query["state"] ?: return null
            if (!constantTimeEquals(state, expectedState)) return null
            query["code"]?.takeIf { it.isNotEmpty() }?.let { return Result.Code(it) }
            return Result.Error(query["error"]?.takeIf { it.isNotEmpty() } ?: "invalid_request")
        }

        internal fun redirectResponse(result: Result, page: Page): ByteArray {
            val ok = result is Result.Code
            val location = "${page.returnUri}?ok=${if (ok) 1 else 0}"
            val message = if (ok) page.successMessage else page.failureMessage
            val body = """
                <!doctype html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>${page.title.escapeHtml()}</title>
                <style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
                background:#121212;color:#fff;font-family:sans-serif;text-align:center;padding:24px;box-sizing:border-box}
                a{display:inline-block;margin-top:24px;padding:14px 28px;border-radius:24px;background:#1ed760;
                color:#000;font-weight:bold;text-decoration:none}</style></head>
                <body><main><p>${message.escapeHtml()}</p><a href="${location.escapeHtml()}">${page.linkText.escapeHtml()}</a></main></body></html>
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 302 Found\r\n" +
                "Location: $location\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Connection: close\r\n\r\n"
            return head.toByteArray(Charsets.ISO_8859_1) + body
        }

        private fun plainResponse(code: Int, reason: String): ByteArray =
            "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1)

        private fun constantTimeEquals(a: String, b: String): Boolean =
            java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        private fun String.escapeHtml(): String = buildString(length) {
            for (c in this@escapeHtml) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }
    }
}

internal data class HttpRequestLine(val method: String, val path: String, val query: Map<String, String>)

/** Parses `GET /login?code=a&state=b HTTP/1.1`; `null` when malformed. */
internal fun parseRequestLine(line: String): HttpRequestLine? {
    val parts = line.trim().split(' ')
    if (parts.size < 2) return null
    val target = parts[1]
    if (!target.startsWith("/")) return null
    val path = target.substringBefore('?').substringBefore('#')
    val query = if ('?' in target) target.substringAfter('?').substringBefore('#') else ""
    return HttpRequestLine(parts[0].uppercase(), path, parseQuery(query))
}

/** `application/x-www-form-urlencoded` query parsing; first value of a repeated key wins. */
internal fun parseQuery(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (pair in raw.split('&')) {
        if (pair.isEmpty()) continue
        val key = decode(pair.substringBefore('='))
        val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
        if (key != null && value != null && key !in result) result[key] = value
    }
    return result
}

private fun decode(s: String): String? = try {
    URLDecoder.decode(s, "UTF-8")
} catch (e: IllegalArgumentException) {
    null
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
