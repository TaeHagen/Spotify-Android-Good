package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.playback.EngineReach

// Pure start of a tap in a plain list of items (Your Episodes): a track-list load, which the
// engine routes to its offline queue when the session isn't ONLINE and something in it is
// downloaded — that queue starts the next download after a start item that isn't one.

/** How a tap in a plain list starts ([planListPlay]). */
sealed interface ListPlay {
    /** Load [uris] from [index]. */
    data class Tracks(val uris: List<String>, val index: Int) : ListPlay

    /** Offline, and the item isn't downloaded: "not available offline", nothing else plays. */
    data object NotDownloaded : ListPlay
}

/**
 * A tap on [uris]`[index]` while the engine has [reach] ([downloaded]: the completed downloads),
 * by the rule of the episode page's Play: ONLINE the whole list from it. Otherwise a downloaded
 * item starts in the list's downloads, exactly there; one that isn't downloaded is sent alone
 * while the session is CONNECTING (the load waits for it, else the engine says it isn't available
 * offline), and OFFLINE it doesn't start: another download would play instead.
 */
fun planListPlay(uris: List<String>, index: Int, reach: EngineReach, downloaded: Set<String>): ListPlay {
    val start = uris.getOrNull(index) ?: return ListPlay.Tracks(uris, 0)
    return when {
        reach == EngineReach.ONLINE -> ListPlay.Tracks(uris, index)
        start in downloaded -> {
            val own = uris.filter { it in downloaded }
            ListPlay.Tracks(own, own.indexOf(start))
        }
        reach == EngineReach.CONNECTING -> ListPlay.Tracks(listOf(start), 0)
        else -> ListPlay.NotDownloaded
    }
}
