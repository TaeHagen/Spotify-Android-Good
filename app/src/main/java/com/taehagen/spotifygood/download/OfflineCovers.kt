package com.taehagen.spotifygood.download

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import java.io.File

/**
 * Serves downloaded covers for the CDN image URLs the lists show (docs §9.7). Lists built from
 * downloads (and the online pages of downloaded items) name the CDN URLs from the stored metadata;
 * each download keeps its cover in `offline/images` (the largest size ≤ 640 px), which only Coil's
 * purgeable HTTP cache would otherwise stand in for offline.
 *
 * [exact] URLs (every size of a completed download's album / episode / show images: the 64, 300
 * and 640 px variants all show that cover) are always served from the file, also online (no data
 * spent); the network only when the file went. [fallback] URLs (a downloaded collection's own
 * image, which may differ: a playlist mosaic) are tried on the network first and served from a
 * member's cover when that fails. [DownloadManager] keeps the maps current.
 */
internal class OfflineCovers {
    @Volatile private var maps = DownloadRules.CoverMaps(emptyMap(), emptyMap())

    fun update(maps: DownloadRules.CoverMaps) {
        this.maps = maps
    }

    /** The downloaded cover showing [url], if any. */
    fun exact(url: String): String? = maps.exact[url]

    /** A downloaded cover standing in for [url] when it cannot be loaded. */
    fun fallback(url: String): String? = maps.fallback[url]

    /** For the image loader (registered in `AppInitializer`). */
    val interceptor: Interceptor = Interceptor { chain ->
        val url = chain.request.data as? String ?: return@Interceptor chain.proceed()
        exact(url)?.let { path ->
            val local = chain.withRequest(chain.request.newBuilder().data(File(path)).build()).proceed()
            if (local !is ErrorResult) return@Interceptor local
            // The file went meanwhile (the download was removed): the network.
        }
        val result = chain.proceed()
        val stand = if (result is ErrorResult) fallback(url) else null
        if (stand == null) result else chain.withRequest(chain.request.newBuilder().data(File(stand)).build()).proceed()
    }
}
