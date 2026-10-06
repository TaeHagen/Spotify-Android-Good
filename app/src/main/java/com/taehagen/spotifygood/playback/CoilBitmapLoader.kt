package com.taehagen.spotifygood.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.Util
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import java.io.IOException

/**
 * Media3 [BitmapLoader] backed by the app's Coil ImageLoader (memory + disk cache), used for the
 * notification / session artwork. Our own `content://…artwork` uris are resolved to their source
 * directly, so the in-process path never goes through the content provider.
 */
internal class CoilBitmapLoader(context: Context, private val scope: CoroutineScope) : BitmapLoader {
    private val context = context.applicationContext

    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IOException("Undecodable artwork")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future(Dispatchers.IO) {
        val data: Any = ArtworkProvider.sourceOf(context, uri) ?: uri
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(SIZE_PX)
            // Notifications are parcelled: no hardware bitmaps.
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        (result as? SuccessResult)?.image?.toBitmap() ?: throw IOException("Artwork unavailable: $uri")
    }

    private companion object {
        const val SIZE_PX = 512
    }
}
