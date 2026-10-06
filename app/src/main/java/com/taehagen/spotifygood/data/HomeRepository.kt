package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.HomeFeed
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.put
import java.util.TimeZone

/** Home feed (native `catalog.home`, pathfinder with local fallback). */
class HomeRepository(private val rpc: NativeRpc, private val cache: ResponseCache) {
    /** Cached home feed; refreshed at most every 15 minutes unless [force]. */
    fun home(force: Boolean = false): Flow<Resource<HomeFeed>> =
        cache.resource(CacheKeys.HOME, HomeFeed.serializer(), if (force) 0 else CacheKeys.TTL_HOME) {
            // Spotify picks time-of-day sections from the device time zone (native defaults to UTC).
            rpc.callOffMain<HomeFeed>("catalog.home", rpcArgs { put("timeZone", TimeZone.getDefault().id) })
        }
}
