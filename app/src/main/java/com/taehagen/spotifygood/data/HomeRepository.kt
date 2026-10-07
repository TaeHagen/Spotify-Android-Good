package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.HomeFeed
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.put
import java.util.TimeZone

/** Home feed (native `catalog.home`, pathfinder with local fallback). */
class HomeRepository(private val rpc: NativeRpc, private val cache: ResponseCache) {
    /**
     * Cached home feed; refreshed at most every 15 minutes unless [force]. A partial or empty feed
     * is shown but not cached as fresh, and is refetched while on screen ([ResponseCache.resourceOf]).
     */
    fun home(force: Boolean = false): Flow<Resource<HomeFeed>> =
        cache.resourceOf(CacheKeys.HOME, HomeFeed.serializer(), if (force) 0 else CacheKeys.TTL_HOME) {
            // Spotify picks time-of-day sections from the device time zone (native defaults to UTC).
            fill(rpc.callOffMain<HomeFeed>("catalog.home", rpcArgs { put("timeZone", TimeZone.getDefault().id) }))
        }

    internal companion object {
        /** An empty feed is degraded too: nothing to show is not worth keeping for 15 minutes. */
        fun fill(feed: HomeFeed): CacheFill<HomeFeed> = CacheFill(feed, feed.partial || feed.sections.isEmpty())
    }
}
