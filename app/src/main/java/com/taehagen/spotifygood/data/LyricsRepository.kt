package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Lyrics
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.serialization.json.put

/** Lyrics (native `catalog.lyrics`) with an in-memory LRU of the last 50 tracks. */
class LyricsRepository(private val rpc: NativeRpc) {
    private val cache = LruMap<String, CachedLyrics>(MAX_ENTRIES)

    /** Lyrics for a track, or null if Spotify has none. Memory-cached (LRU). */
    suspend fun lyrics(trackUri: String): Lyrics? {
        cache[trackUri]?.let { return it.lyrics }
        val lyrics = try {
            rpc.callOffMain<Lyrics>("catalog.lyrics", rpcArgs { put("uri", trackUri) }).takeIf { it.lines.isNotEmpty() }
        } catch (e: NativeException) {
            // "No lyrics" is an answer worth remembering; other errors (offline …) are retried next time.
            if (e.code != NativeErrorCode.NOT_FOUND) throw e
            null
        }
        cache[trackUri] = CachedLyrics(lyrics)
        return lyrics
    }

    /** Wrapper so a cached "no lyrics" (null) is distinguishable from a cache miss. */
    private class CachedLyrics(val lyrics: Lyrics?)

    private companion object {
        const val MAX_ENTRIES = 50
    }
}

/** Small thread-safe LRU map (access order). */
internal class LruMap<K : Any, V : Any>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxEntries
    }

    operator fun get(key: K): V? = synchronized(map) { map[key] }

    operator fun set(key: K, value: V) {
        synchronized(map) { map[key] = value }
    }

    val size: Int get() = synchronized(map) { map.size }

    fun clear() = synchronized(map) { map.clear() }
}
