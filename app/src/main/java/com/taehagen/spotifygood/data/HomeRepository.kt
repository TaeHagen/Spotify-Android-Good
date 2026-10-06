package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.HomeFeed
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.flow.Flow

class HomeRepository(rpc: NativeRpc, cache: ResponseCache) {
    /** Cached home feed; refreshed at most every 15 minutes unless [force]. */
    fun home(force: Boolean = false): Flow<Resource<HomeFeed>> = TODO()
}
