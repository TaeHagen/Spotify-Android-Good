package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Page
import com.taehagen.spotifygood.model.Rootlist
import com.taehagen.spotifygood.model.SavedAlbum
import com.taehagen.spotifygood.model.SavedArtist
import com.taehagen.spotifygood.model.SavedEpisode
import com.taehagen.spotifygood.model.SavedShow
import com.taehagen.spotifygood.model.SavedTrack
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The user's library (native `library.*`): playlists (rootlist), Liked Songs, saved albums,
 * followed artists, saved podcasts and episodes. Saved state is optimistic and cached.
 */
class LibraryRepository(scope: CoroutineScope, rpc: NativeRpc, cache: ResponseCache) {
    fun playlists(): Flow<Resource<Rootlist>> = TODO()
    suspend fun likedTracks(offset: Int, limit: Int = 100): Page<SavedTrack> = TODO()
    /** All Liked Songs URIs (newest first) for play/shuffle/download. */
    suspend fun allLikedTrackUris(): List<String> = TODO()
    fun albums(): Flow<Resource<List<SavedAlbum>>> = TODO()
    fun artists(): Flow<Resource<List<SavedArtist>>> = TODO()
    fun shows(): Flow<Resource<List<SavedShow>>> = TODO()
    suspend fun episodes(offset: Int, limit: Int = 50): Page<SavedEpisode> = TODO()

    /** Live saved/followed state of [uri] (track/album/artist/show/episode/playlist). */
    fun isSaved(uri: String): Flow<Boolean> = TODO()
    suspend fun setSaved(uris: List<String>, saved: Boolean): Unit = TODO()
    suspend fun toggleSaved(uri: String): Unit = TODO()

    /** Emits after any library mutation (lists can refresh). */
    val changes: SharedFlow<Unit> get() = TODO()
    suspend fun refresh(): Unit = TODO()
}
