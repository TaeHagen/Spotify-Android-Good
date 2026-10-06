package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Track
import kotlinx.coroutines.Dispatchers
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

/**
 * Downloaded collections, newest first. DownloadManager has no list API for collections yet, so
 * this reads the (public) Room table it maintains.
 */
internal fun AppGraph.downloadedCollectionsFlow(): Flow<List<DownloadedCollection>> =
    database.collections().observeAll()
        .map { list -> list.map { it.toDownloadedCollection(json) } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

/** uri → download state of every known download item (only emits when a state changes). */
internal fun AppGraph.downloadStatesFlow(): Flow<Map<String, DownloadState>> =
    downloads.items
        .map { items -> items.associate { it.uri to it.state } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

/** Plays [track] within its album (so playback continues naturally), else on its own. */
internal fun AppGraph.playTrackInAlbum(track: Track) {
    val albumUri = track.album?.uri
    if (albumUri != null) player.playContext(albumUri, startUri = track.uri) else player.playTracks(listOf(track.uri))
}
