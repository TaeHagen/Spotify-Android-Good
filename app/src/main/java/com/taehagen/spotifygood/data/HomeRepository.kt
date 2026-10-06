package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.HomeFeed
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.flow.Flow

/** Home feed (native `catalog.home`, pathfinder with local fallback). */
class HomeRepository(private val rpc: NativeRpc, private val cache: ResponseCache) {
    /** Cached home feed; refreshed at most every 15 minutes unless [force]. */
    fun home(force: Boolean = false): Flow<Resource<HomeFeed>> =
        cache.resource(CacheKeys.HOME, HomeFeed.serializer(), if (force) 0 else CacheKeys.TTL_HOME) {
            rpc.callOffMain<HomeFeed>("catalog.home")
        }
}
