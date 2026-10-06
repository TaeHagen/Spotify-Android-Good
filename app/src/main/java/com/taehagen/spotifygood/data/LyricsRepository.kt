package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Lyrics
import com.taehagen.spotifygood.nativebridge.NativeRpc

class LyricsRepository(rpc: NativeRpc) {
    /** Lyrics for a track, or null if Spotify has none. Memory-cached (LRU). */
    suspend fun lyrics(trackUri: String): Lyrics? = TODO()
}
