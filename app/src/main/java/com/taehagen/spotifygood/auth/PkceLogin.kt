package com.taehagen.spotifygood.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// Fallback login: OAuth Authorization Code + PKCE (RFC 7636) in a Custom Tab with a
// http://127.0.0.1:<port>/login loopback redirect (RFC 8252 §7.3), docs/ARCHITECTURE.md §9.3.

/** PKCE parameters of one browser login. [state] is the CSRF token verified on the redirect. */
internal class Pkce(val verifier: String, val challenge: String, val state: String) {
    override fun toString(): String = "Pkce(<redacted>)"

    companion object {
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        /** 64 random bytes → 86-char verifier (RFC 7636 allows 43..128), S256 challenge, 16-byte state. */
        fun generate(random: SecureRandom = SecureRandom()): Pkce {
            val verifier = encoder.encodeToString(ByteArray(64).also(random::nextBytes))
            val state = encoder.encodeToString(ByteArray(16).also(random::nextBytes))
            return Pkce(verifier, challengeFor(verifier), state)
        }

        /** `BASE64URL(SHA256(ASCII(verifier)))` without padding (RFC 7636 §4.2). */
        fun challengeFor(verifier: String): String =
            encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}

/** Ports registered as `http://127.0.0.1:<port>/login` redirects for the desktop client id. */
internal val LOOPBACK_PORTS = listOf(5588, 8898)

/** Where the loopback server sends the browser after the redirect (brings the app forward). */
internal const val APP_RETURN_URI = "spotifygood://auth"
