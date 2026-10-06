package com.taehagen.spotifygood.engine

import android.os.Build

/** Default Spotify Connect name of this phone, e.g. "Pixel 9" or "Samsung SM-S928B". */
fun defaultDeviceName(): String = deviceNameFrom(Build.MANUFACTURER, Build.MODEL)

internal fun deviceNameFrom(manufacturer: String?, model: String?): String {
    val maker = manufacturer.orEmpty().trim()
    val product = model.orEmpty().trim()
    val name = when {
        product.isEmpty() -> maker.ifEmpty { "Android" }
        maker.isEmpty() || product.startsWith(maker, ignoreCase = true) -> product
        // Google's models are already branded ("Pixel 9"); repeating the maker adds nothing.
        maker.equals("google", ignoreCase = true) && product.startsWith("Pixel", ignoreCase = true) -> product
        else -> "$maker $product"
    }
    return name.replaceFirstChar { it.titlecase() }
}
