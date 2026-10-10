package com.taehagen.spotifygood.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.taehagen.spotifygood.data.MOSAIC_TILES
import com.taehagen.spotifygood.data.centerSquare
import com.taehagen.spotifygood.data.mosaicTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * A playlist mosaic as one bitmap (docs §9.8), for what needs a single image: Android Auto / media
 * browsers ([ArtworkProvider.mosaicUri]), a home-screen widget. Composed once per set of covers —
 * each loaded downsampled to a tile through the app's image loader (its disk cache, the downloaded
 * covers offline), never decoded full size — and kept as a JPEG in the app cache, keyed by the
 * covers' image ids.
 */
internal object MosaicBitmaps {
    /** Side of a composed mosaic. */
    const val SIZE_PX = 640
    private const val DIR = "playlist_mosaics"
    private const val MAX_FILES = 200
    private const val JPEG_QUALITY = 90

    /** The cache file name of the mosaic of [urls] (their image ids, in order). */
    fun key(urls: List<String>): String {
        val ids = urls.joinToString("|") { it.substringAfterLast('/') }
        return MessageDigest.getInstance("SHA-1").digest(ids.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    /** Forgets every composed mosaic (another account). */
    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /**
     * The composed mosaic of [urls] (the [MOSAIC_TILES] covers, in reading order) as a JPEG file,
     * made now if not kept yet; null when a cover can't be loaded.
     */
    suspend fun file(context: Context, urls: List<String>): File? = withContext(Dispatchers.IO) {
        if (urls.size != MOSAIC_TILES) return@withContext null
        val target = File(dir(context), "${key(urls)}.jpg")
        if (target.isFile) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        val tile = SIZE_PX / 2
        val loader = SingletonImageLoader.get(context)
        val covers = coroutineScope {
            urls.map { url ->
                async {
                    val request = ImageRequest.Builder(context).data(url).size(tile)
                        .memoryCachePolicy(CachePolicy.DISABLED).allowHardware(false).build()
                    (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
                }
            }.awaitAll()
        }
        if (covers.any { it == null }) return@withContext null
        val out = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        mosaicTiles(SIZE_PX, SIZE_PX).forEachIndexed { i, t ->
            val cover = covers[i]!!
            val src = centerSquare(cover.width, cover.height)
            canvas.drawBitmap(cover, Rect(src.left, src.top, src.right, src.bottom), Rect(t.left, t.top, t.right, t.bottom), paint)
        }
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext null
            }
        } catch (e: IOException) {
            return@withContext null
        } finally {
            out.recycle()
        }
        prune(dir(context))
        target
    }

    /** Keeps the [MAX_FILES] most recently used. */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }
}
