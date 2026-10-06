package com.taehagen.spotifygood.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun challengeMatchesRfc7636AppendixB() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun generatedValuesAreWellFormed() {
        val pkce = Pkce.generate()
        val unreserved = Regex("[A-Za-z0-9_-]+")
        assertEquals(86, pkce.verifier.length)
        assertTrue(pkce.verifier.length in 43..128)
        assertTrue(unreserved.matches(pkce.verifier))
        assertTrue(unreserved.matches(pkce.state))
        assertEquals(22, pkce.state.length)
        assertEquals(Pkce.challengeFor(pkce.verifier), pkce.challenge)
        assertEquals(43, pkce.challenge.length)
        assertFalse(pkce.challenge.contains('='))
    }

    @Test
    fun generatedValuesAreRandom() {
        val a = Pkce.generate()
        val b = Pkce.generate()
        assertNotEquals(a.verifier, b.verifier)
        assertNotEquals(a.state, b.state)
    }

    @Test
    fun toStringDoesNotLeakSecrets() {
        val pkce = Pkce.generate()
        assertFalse(pkce.toString().contains(pkce.verifier))
    }

    @Test
    fun authorizeUrlCarriesAllParameters() {
        val redirect = "http://127.0.0.1:5588/login"
        val raw = SpotifyOAuth.authorizeUrl(redirect, "challenge123", "state456")
        val url = raw.toHttpUrl()
        assertEquals("accounts.spotify.com", url.host)
        assertEquals("/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("65b708073fc0480ea92a077233ca87bd", url.queryParameter("client_id"))
        assertEquals(redirect, url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("challenge123", url.queryParameter("code_challenge"))
        assertEquals("state456", url.queryParameter("state"))
        val scopes = url.queryParameter("scope")!!.split(' ')
        assertEquals(SpotifyOAuth.SCOPES, scopes)
        assertTrue("streaming" in scopes)
        // Spaces must be percent-encoded, never '+', in the query.
        assertTrue(raw.contains("app-remote-control%20playlist-modify"))
    }
}
