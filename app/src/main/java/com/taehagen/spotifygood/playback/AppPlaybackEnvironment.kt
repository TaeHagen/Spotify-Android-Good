package com.taehagen.spotifygood.playback

import android.util.Log
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.download.DownloadItem
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.SessionState
import com.taehagen.spotifygood.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PlaybackEnvironment] backed by the engine, the settings and the downloads (read only: Room
 * membership through [com.taehagen.spotifygood.download.DownloadManager]'s flows).
 */
internal class AppPlaybackEnvironment(private val graph: AppGraph) : PlaybackEnvironment {

    override fun reach(): EngineReach {
        val s = graph.engine.state.value
        return EngineReach.of(graph.settings.settings.value.offlineMode, s.session, s.networkAvailable)
    }

    override suspend fun awaitSessionStart() {
        val engine = graph.engine
        if (reach() != EngineReach.CONNECTING || engine.holderCount == 0) return
        val s = engine.state.value
        // A failed session is retried by the engine's own backoff (or a "Retry"): do not hold every
        // command back for it; only a session that is starting (or about to) is worth the wait.
        val starting = when (s.session) {
            SessionState.STOPPED, SessionState.CONNECTING -> true
            SessionState.RECONNECTING -> (s.nextRetryMs ?: 0) <= START_WAIT_MS
            else -> false
        }
        if (!starting) return
        withTimeoutOrNull(START_WAIT_MS) {
            coroutineScope {
                // awaitOnline also returns for offline mode, logged out and fatal errors.
                val online = async { engine.awaitOnline(START_WAIT_MS) }
                val offline = async {
                    combine(engine.state, graph.settings.settings) { st, prefs ->
                        prefs.offlineMode || !st.networkAvailable || st.session == SessionState.OFFLINE
                    }.first { it }
                }
                select {
                    online.onAwait {}
                    offline.onAwait {}
                }
                online.cancel()
                offline.cancel()
            }
        }
    }

    override suspend fun downloadedMembers(contextUri: String, startUri: String?): OfflineMembers? {
        return try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                val downloads = graph.downloads
                val completed = downloads.items.first().filter { it.state == DownloadState.COMPLETED }
                val collections = downloads.collections.first()
                withContext(Dispatchers.Default) {
                    OfflineLoads.members(contextUri, startUri, collections, completed.mapTo(HashSet()) { it.uri }) {
                        completed.mapNotNull(::entryOf)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download lookup for $contextUri failed", e)
            null
        }
    }

    private fun entryOf(item: DownloadItem): DownloadedEntry? {
        val json = item.metadataJson ?: return null
        return runCatching {
            if (item.uri.startsWith("spotify:episode:")) {
                val episode = graph.json.decodeFromString<Episode>(json)
                DownloadedEntry(item.uri, showUri = episode.show?.uri, releaseDate = episode.releaseDate)
            } else {
                val track = graph.json.decodeFromString<Track>(json)
                DownloadedEntry(item.uri, albumUri = track.album?.uri, discNumber = track.discNumber, trackNumber = track.trackNumber)
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "PlaybackEnvironment"
        /** How long a playback command waits for a starting session (the engine's own wait is similar). */
        const val START_WAIT_MS = 15_000L
        const val LOOKUP_TIMEOUT_MS = 5_000L
    }
}
