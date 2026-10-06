package com.taehagen.spotifygood.ui.screens.player

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colours of the player surfaces (dark-first, docs §9.9). */
internal object PlayerDefaults {
    /** Bottom of the now playing gradient and the lyrics/mini player fallback. */
    val Background = Color(0xFF121212)
    /** Used until the artwork colour is known. */
    val FallbackArtwork = Color(0xFF404040)
    val Brand = Color(0xFF1ED760)
    val PrimaryText = Color.White
    val SecondaryText = Color.White.copy(alpha = 0.70f)
    val TrackInactive = Color.White.copy(alpha = 0.24f)
}

/** Darkened variant of an artwork colour suitable behind white text. */
internal fun Color.toned(maxLightness: Float, minLightness: Float = 0.10f): Color =
    Color(toneForBackground(toArgb(), maxLightness = maxLightness, minLightness = minLightness))

/**
 * Dominant artwork colours extracted with Palette on a background dispatcher from a small Coil
 * decode (disk-cached image, software bitmap), cached per image URL for the process lifetime.
 */
internal object ArtworkColorCache {
    private const val PALETTE_SIZE_PX = 112
    private val cache = LruCache<String, Int>(128)

    fun peek(url: String?): Int? = url?.let { cache.get(it) }

    suspend fun dominantColor(context: Context, url: String): Int? {
        cache.get(url)?.let { return it }
        val color = try {
            extract(context.applicationContext, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (color != null) cache.put(url, color)
        return color
    }

    private suspend fun extract(context: Context, url: String): Int? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(PALETTE_SIZE_PX)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return withContext(Dispatchers.Default) {
            val bitmap = result.image.toBitmap()
            pick(Palette.from(bitmap).maximumColorCount(16).generate())
        }
    }

    private fun pick(palette: Palette): Int? {
        val dominant = palette.dominantSwatch
        val vibrant = palette.vibrantSwatch ?: palette.darkVibrantSwatch
        val muted = palette.mutedSwatch ?: palette.darkMutedSwatch
        val swatch = when {
            // Prefer the dominant colour unless it is (nearly) grey and a real colour is present.
            dominant != null && dominant.hsl[1] >= 0.18f -> dominant
            vibrant != null && vibrant.population * 10 >= (dominant?.population ?: 0) -> vibrant
            dominant != null -> dominant
            else -> muted
        }
        return swatch?.rgb
    }
}

/** Animated dominant colour of [url]'s artwork (keeps the previous colour while extracting). */
@Composable
internal fun rememberArtworkColor(url: String?, fallback: Color = PlayerDefaults.FallbackArtwork): State<Color> {
    val context = LocalContext.current
    val initial = remember { ArtworkColorCache.peek(url)?.let(::Color) ?: fallback }
    val target = produceState(initial, url) {
        if (url == null) {
            value = fallback
        } else {
            ArtworkColorCache.dominantColor(context, url)?.let { value = Color(it) }
        }
    }
    return animateColorAsState(target.value, animationSpec = tween(durationMillis = 700), label = "artworkColor")
}

/**
 * Player surfaces always use a dark look with white content. When the app runs a light scheme the
 * player gets a dark scheme with the brand accent so Material components render correctly.
 */
@Composable
internal fun PlayerSurfaceTheme(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (scheme.background.luminance() < 0.5f) {
        CompositionLocalProvider(LocalContentColor provides PlayerDefaults.PrimaryText, content = content)
    } else {
        val dark = remember {
            darkColorScheme(
                primary = PlayerDefaults.Brand,
                onPrimary = Color.Black,
                background = PlayerDefaults.Background,
                surface = PlayerDefaults.Background,
                surfaceContainer = Color(0xFF1F1F1F),
                surfaceContainerHigh = Color(0xFF2A2A2A),
            )
        }
        MaterialTheme(colorScheme = dark, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(LocalContentColor provides PlayerDefaults.PrimaryText, content = content)
        }
    }
}
