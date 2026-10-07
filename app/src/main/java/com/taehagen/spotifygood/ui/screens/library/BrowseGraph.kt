package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.ui.components.SessionMessenger
import com.taehagen.spotifygood.ui.components.isPlaceholder
import com.taehagen.spotifygood.ui.screens.album.engineReach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

// AppGraph projections shared by the browse ViewModels.

/** True while the app can only use downloaded content (no network or offline mode). */
internal fun AppGraph.offlineFlow(): Flow<Boolean> =
    combine(engine.isNetworkAvailable, settings.settings) { network, settings -> !network || settings.offlineMode }
        .distinctUntilChanged()

internal fun AppGraph.nowPlayingFlow(): Flow<NowPlaying> =
    playback.snapshot.map { it.toNowPlaying() }.distinctUntilChanged()

/** Downloaded collections, newest first. */
internal fun AppGraph.downloadedCollectionsFlow(): Flow<List<DownloadedCollection>> =
    downloads.collections
        .map { list -> list.map { it.toDownloadedCollection() } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

/** uri → download state of every known download item (only emits when a state changes). */
internal fun AppGraph.downloadStatesFlow(): Flow<Map<String, DownloadState>> =
    downloads.items
        .map { items -> items.associate { it.uri to it.state } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

/** Why a single track (tile, link, recent search, search result) can't be started. */
internal enum class TrackStartBlock {
    /**
     * The catalog marks it unplayable (explicit with "Hide explicit content" on, not available in
     * the country): the player refuses it and Spirc starts the album's next track instead.
     */
    UNAVAILABLE,

    /**
     * Not downloaded while the session can't stream: playback routes the album load to its
     * downloads and would start a different, downloaded track.
     */
    NOT_DOWNLOADED,
}

/**
 * [track]: its catalog metadata, null when unknown (a placeholder, i.e. metadata that failed, is
 * unknown too, not unplayable); [online]: the session is (or became) ONLINE.
 */
internal fun trackStartBlock(track: Track?, online: Boolean, downloaded: Boolean): TrackStartBlock? = when {
    track != null && !track.isPlaceholder && !track.playable -> TrackStartBlock.UNAVAILABLE
    !online && !downloaded -> TrackStartBlock.NOT_DOWNLOADED
    else -> null
}

/** How long a single-track start waits for a session that is connecting (cold start, reconnect). */
private const val TRACK_START_SESSION_WAIT_MS = 10_000L

/**
 * Starts [trackUri] within its album when [track] knows it (so playback continues naturally), else
 * on its own, unless [trackStartBlock] says it can't start: then shows why instead of letting a
 * different track play.
 */
internal suspend fun AppGraph.startTrack(trackUri: String, track: Track?) {
    val downloaded = trackUri in downloads.downloadedUris.value
    val unplayable = track != null && !track.isPlaceholder && !track.playable
    val online = downloaded || unplayable || when (engineReach()) {
        EngineReach.ONLINE -> true
        EngineReach.OFFLINE -> false
        // Returns early when the session can't come online (offline, error).
        EngineReach.CONNECTING -> engine.awaitOnline(TRACK_START_SESSION_WAIT_MS)
    }
    when (trackStartBlock(track, online, downloaded)) {
        TrackStartBlock.UNAVAILABLE -> SessionMessenger(app).post(R.string.player_unavailable)
        TrackStartBlock.NOT_DOWNLOADED -> SessionMessenger(app).post(R.string.playback_error_not_available_offline)
        null -> {
            val albumUri = track?.album?.uri
            if (albumUri != null) player.playContext(albumUri, startUri = trackUri) else player.playTracks(listOf(trackUri))
        }
    }
}

/** Plays [track] within its album, else on its own; see [startTrack]. */
internal fun AppGraph.playTrackInAlbum(track: Track) {
    appScope.launch { startTrack(track.uri, track) }
}
