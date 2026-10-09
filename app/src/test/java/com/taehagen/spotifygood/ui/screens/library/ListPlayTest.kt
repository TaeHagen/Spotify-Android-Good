package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.playback.EngineReach
import org.junit.Assert.assertEquals
import org.junit.Test

class ListPlayTest {
    private fun e(n: Int) = "spotify:episode:$n"

    /** Your Episodes: 30 saved episodes, #12 and #20 downloaded. */
    private val saved = (1..30).map(::e)
    private val downloaded = setOf(e(12), e(20))

    @Test
    fun onlineTheWholeListPlaysFromTheTap() {
        assertEquals(ListPlay.Tracks(saved, 4), planListPlay(saved, 4, EngineReach.ONLINE, downloaded))
    }

    @Test
    fun offlineAnEpisodeThatIsntDownloadedDoesntStartAnother() {
        assertEquals(ListPlay.NotDownloaded, planListPlay(saved, 4, EngineReach.OFFLINE, downloaded))
    }

    @Test
    fun whileConnectingAnEpisodeThatIsntDownloadedIsSentAloneToWaitForTheSession() {
        // Not the list: the engine would route it to the offline queue, which starts #12 at 0:00.
        assertEquals(ListPlay.Tracks(listOf(e(5)), 0), planListPlay(saved, 4, EngineReach.CONNECTING, downloaded))
    }

    @Test
    fun aDownloadedEpisodeStartsExactlyThereAmongTheDownloads() {
        val plan = ListPlay.Tracks(listOf(e(12), e(20)), 1)
        assertEquals(plan, planListPlay(saved, 19, EngineReach.OFFLINE, downloaded))
        assertEquals(plan, planListPlay(saved, 19, EngineReach.CONNECTING, downloaded))
        assertEquals(ListPlay.Tracks(listOf(e(12), e(20)), 0), planListPlay(saved, 11, EngineReach.OFFLINE, downloaded))
    }

    @Test
    fun aTapOutsideTheLoadedListPlaysItAlone() {
        assertEquals(ListPlay.Tracks(listOf(e(99)), 0), planListPlay(listOf(e(99)), 0, EngineReach.ONLINE, downloaded))
        assertEquals(ListPlay.NotDownloaded, planListPlay(listOf(e(99)), 0, EngineReach.OFFLINE, downloaded))
    }
}
