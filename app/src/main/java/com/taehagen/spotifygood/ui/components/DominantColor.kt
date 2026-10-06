package com.taehagen.spotifygood.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** url → tamed dominant ARGB. android.util.LruCache is thread-safe. */
private val dominantColorCache = LruCache<String, Int>(96)

/**
 * Dominant (dark-vibrant) color of the image at [url], extracted with androidx.palette from a
 * small software bitmap off the main thread, darkened so white text stays readable on it.
 * Null until known (or when there is no image). Results are memory-cached per URL.
 */
@Composable
fun rememberDominantColor(url: String?): Color? {
    val context = LocalContext.current.applicationContext
    var color by remember(url) { mutableStateOf(url?.let { dominantColorCache.get(it) }?.let { Color(it) }) }
    LaunchedEffect(url) {
        if (url == null || color != null) return@LaunchedEffect
        val argb = extractDominantColor(context, url) ?: return@LaunchedEffect
        color = Color(argb)
    }
    return color
}

/** Suspending variant (cached); returns null when the image cannot be loaded. */
suspend fun extractDominantColor(context: Context, url: String): Int? {
    dominantColorCache.get(url)?.let { return it }
    return withContext(Dispatchers.Default) {
        try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(PALETTE_SIZE_PX)
                .allowHardware(false)
                .build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                ?: return@withContext null
            val bitmap = result.image.toBitmap()
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val swatch = palette.darkVibrantSwatch
                ?: palette.vibrantSwatch
                ?: palette.dominantSwatch
                ?: palette.mutedSwatch
                ?: return@withContext null
            tame(swatch.rgb).also { dominantColorCache.put(url, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }
}

/** Clamp lightness/saturation so the color works as a background behind white text. */
private fun tame(argb: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    hsl[1] = hsl[1].coerceAtMost(0.75f)
    hsl[2] = hsl[2].coerceIn(0.18f, 0.42f)
    return ColorUtils.HSLToColor(hsl)
}

private const val PALETTE_SIZE_PX = 96
