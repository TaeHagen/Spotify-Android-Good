package com.taehagen.spotifygood.playback

import androidx.media3.common.PlaybackException
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerErrorsTest {
    private val messages = PlaybackErrorMessages { kind, _ -> "msg:${kind.name}" }
    private val idle = PlaybackSnapshot()
    private val paused = PlaybackSnapshot(source = PlaybackSource.LOCAL, status = PlaybackStatus.PAUSED, track = PlaybackTrack("spotify:track:1"))

    private fun select(
        ready: Boolean = true,
        loggedIn: Boolean = true,
        account: String? = null,
        snapshot: PlaybackSnapshot = idle,
        failure: PlaybackFailure? = null,
    ) = PlayerErrors.select(ready, loggedIn, account, snapshot, failure, messages)

    @Test
    fun loggedOutAsksToSignInOnceCredentialsWereRead() {
        val e = select(loggedIn = false)!!
        assertEquals(PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED, e.code)
        assertTrue(e.signIn)
        assertEquals("msg:NOT_LOGGED_IN", e.message)
        assertNull("credentials not read yet", select(ready = false, loggedIn = false))
    }

    @Test
    fun accountErrors() {
        assertEquals(PlaybackException.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED, select(account = NativeErrorCode.PREMIUM_REQUIRED)?.code)
        val refused = paused.copy(lastError = "refused")
        assertEquals(PlaybackException.ERROR_CODE_PERMISSION_DENIED, select(account = NativeErrorCode.PLAYBACK_REFUSED, snapshot = refused)?.code)
        // The sticky engine error outlives the refusal: only shown while the snapshot reports it.
        assertNull(select(account = NativeErrorCode.PLAYBACK_REFUSED, snapshot = paused))
    }

    @Test
    fun failedStartIsShownWhileNothingPlays() {
        val failure = PlaybackFailure(PlaybackErrorKind.NOT_AVAILABLE_OFFLINE, "not downloaded")
        val e = select(snapshot = paused, failure = failure)!!
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, e.code)
        assertEquals("not downloaded", e.message)
    }

    @Test
    fun nothingWhilePlayingOrLoading() {
        val playing = paused.copy(status = PlaybackStatus.PLAYING)
        assertNull(select(loggedIn = false, snapshot = playing, failure = PlaybackFailure(PlaybackErrorKind.GENERIC, "x")))
        assertNull(select(account = NativeErrorCode.PREMIUM_REQUIRED, snapshot = paused.copy(status = PlaybackStatus.LOADING)))
        assertNull(select(snapshot = paused))
    }

    @Test
    fun everyKindHasACode() {
        PlaybackErrorKind.entries.forEach { PlayerErrors.codeOf(it) }
        assertEquals(PlaybackException.ERROR_CODE_TIMEOUT, PlayerErrors.codeOf(PlaybackErrorKind.TIMEOUT))
    }
}
