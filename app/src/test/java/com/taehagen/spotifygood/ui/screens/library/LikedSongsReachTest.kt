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

    // Captive portal / reconnect backoff: a network, but the session isn't ONLINE.
    private val connecting = EngineReach.of(offlineMode = false, session = SessionState.RECONNECTING, networkAvailable = true)

    @Test
    fun offlineAlwaysListsTheDownload() {
        assertTrue(likedSongsShowDownloads(EngineReach.OFFLINE, loaded = true, error = null))
        assertTrue(likedSongsShowDownloads(EngineReach.OFFLINE, loaded = false, error = null))
    }

    @Test
    fun connectingListsTheDownloadUntilServerPagesArrive() {
        assertEquals(EngineReach.CONNECTING, connecting)
        assertTrue("nothing loaded yet", likedSongsShowDownloads(connecting, loaded = false, error = null))
        assertTrue(likedSongsShowDownloads(connecting, loaded = false, error = native(NativeErrorCode.NOT_CONNECTED)))
        assertTrue("a page failed for lack of connection", likedSongsShowDownloads(connecting, loaded = true, error = native(NativeErrorCode.NETWORK)))
        assertFalse("loaded pages stay", likedSongsShowDownloads(connecting, loaded = true, error = null))
        assertFalse(likedSongsShowDownloads(connecting, loaded = true, error = native(NativeErrorCode.RATE_LIMITED)))
    }

    @Test
    fun onlineListsTheServerPages() {
        assertFalse(likedSongsShowDownloads(EngineReach.ONLINE, loaded = false, error = native(NativeErrorCode.NETWORK)))
        assertFalse(likedSongsShowDownloads(EngineReach.ONLINE, loaded = true, error = null))
    }

    @Test
    fun fetchesOnceOnlineWhenNothingIsLoadedOrTheLastLoadFailed() {
        val empty = PagedState<String>()
        val failed = PagedState<String>(error = native(NativeErrorCode.NOT_CONNECTED))
        val loaded = PagedState(items = listOf("a"), total = 1, endReached = true)
        assertFalse("not while connecting: it would fail NOT_CONNECTED", likedSongsNeedsFetch(connecting, empty))
        assertFalse(likedSongsNeedsFetch(EngineReach.OFFLINE, failed))
        assertTrue(likedSongsNeedsFetch(EngineReach.ONLINE, empty))
        assertTrue("the request made while reconnecting failed", likedSongsNeedsFetch(EngineReach.ONLINE, failed))
        assertTrue(likedSongsNeedsFetch(EngineReach.ONLINE, loaded.copy(error = native(NativeErrorCode.NETWORK))))
        assertFalse(likedSongsNeedsFetch(EngineReach.ONLINE, loaded))
        assertFalse("already loading", likedSongsNeedsFetch(EngineReach.ONLINE, empty.copy(isLoading = true)))
    }
}
