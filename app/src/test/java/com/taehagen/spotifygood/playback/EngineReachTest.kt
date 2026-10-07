package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineReachTest {
    @Test
    fun aLostNetworkIsOfflineEvenWhileTheSessionStillReadsOnline() {
        assertEquals(EngineReach.OFFLINE, EngineReach.of(offlineMode = false, session = SessionState.ONLINE, networkAvailable = false))
        assertEquals(EngineReach.ONLINE, EngineReach.of(offlineMode = false, session = SessionState.ONLINE, networkAvailable = true))
    }

    @Test
    fun offlineModeAndOfflineSessions() {
        assertEquals(EngineReach.OFFLINE, EngineReach.of(offlineMode = true, session = SessionState.ONLINE, networkAvailable = true))
        assertEquals(EngineReach.OFFLINE, EngineReach.of(offlineMode = false, session = SessionState.OFFLINE, networkAvailable = true))
    }

    @Test
    fun startingSessionsWithANetworkAreConnecting() {
        for (session in listOf(SessionState.STOPPED, SessionState.CONNECTING, SessionState.RECONNECTING, SessionState.ERROR)) {
            assertEquals(EngineReach.CONNECTING, EngineReach.of(offlineMode = false, session = session, networkAvailable = true))
            assertEquals(EngineReach.OFFLINE, EngineReach.of(offlineMode = false, session = session, networkAvailable = false))
        }
    }
}
