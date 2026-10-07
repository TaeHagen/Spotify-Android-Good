package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.SessionState
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.EngineReach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LikedSongsReachTest {
    private fun native(code: String) = NativeException(NativeErrorInfo(code, "boom"))

    // Cold start, reconnect, captive portal: a network, but the session isn't ONLINE.
    private val connecting = EngineReach.of(offlineMode = false, session = SessionState.RECONNECTING, networkAvailable = true)

    private fun view(
        reach: EngineReach,
        loaded: Boolean = false,
        error: Throwable? = null,
        hasDownload: Boolean = false,
        awaiting: Boolean = true,
    ) = likedSongsView(reach, loaded, error, hasDownload, awaiting)

    @Test
    fun offlineAlwaysListsTheDownload() {
        assertEquals(LikedView.DOWNLOADS, view(EngineReach.OFFLINE, loaded = true))
        assertEquals("the offline empty state", LikedView.DOWNLOADS, view(EngineReach.OFFLINE, hasDownload = false))
    }

    @Test
    fun connectingWithNothingDownloadedWaitsInsteadOfClaimingOffline() {
        assertEquals(EngineReach.CONNECTING, connecting)
        assertEquals(LikedView.WAITING, view(connecting))
        // Waiting stopped being worth it (retry backoff, captive portal): the server view answers.
        assertEquals(LikedView.SERVER, view(connecting, awaiting = false))
        assertEquals(LikedView.SERVER, view(connecting, error = native(NativeErrorCode.NOT_CONNECTED)))
    }

    @Test
    fun connectingListsTheDownloadWhenThereIsOne() {
        assertEquals(LikedView.DOWNLOADS, view(connecting, hasDownload = true))
        assertEquals("a page failed for lack of connection", LikedView.DOWNLOADS, view(connecting, loaded = true, error = native(NativeErrorCode.NETWORK), hasDownload = true))
        assertEquals("loaded pages stay", LikedView.SERVER, view(connecting, loaded = true, hasDownload = true))
        assertEquals(LikedView.SERVER, view(connecting, loaded = true, error = native(NativeErrorCode.RATE_LIMITED), hasDownload = true))
    }

    @Test
    fun onlineListsTheServerPages() {
        assertEquals(LikedView.SERVER, view(EngineReach.ONLINE, error = native(NativeErrorCode.NETWORK), hasDownload = true))
        assertEquals(LikedView.SERVER, view(EngineReach.ONLINE, loaded = true, hasDownload = true))
    }

    @Test
    fun fetchesOnceOnlineWhenNothingIsLoadedOrTheLastLoadFailed() {
        val empty = PagedState<String>()
        val failed = PagedState<String>(error = native(NativeErrorCode.NOT_CONNECTED))
        val loaded = PagedState(items = listOf("a"), total = 1, endReached = true)
        assertFalse("not while waiting for the session: it would fail NOT_CONNECTED", likedSongsNeedsFetch(connecting, empty, awaitingSession = true))
        assertTrue("one attempt once waiting isn't worth it", likedSongsNeedsFetch(connecting, empty, awaitingSession = false))
        assertFalse("no retry loop while connecting", likedSongsNeedsFetch(connecting, failed, awaitingSession = false))
        assertFalse(likedSongsNeedsFetch(EngineReach.OFFLINE, failed))
        assertTrue(likedSongsNeedsFetch(EngineReach.ONLINE, empty))
        assertTrue("the request made while reconnecting failed", likedSongsNeedsFetch(EngineReach.ONLINE, failed))
        assertTrue(likedSongsNeedsFetch(EngineReach.ONLINE, loaded.copy(error = native(NativeErrorCode.NETWORK))))
        assertFalse(likedSongsNeedsFetch(EngineReach.ONLINE, loaded))
        assertFalse("already loading", likedSongsNeedsFetch(EngineReach.ONLINE, empty.copy(isLoading = true)))
    }
}
