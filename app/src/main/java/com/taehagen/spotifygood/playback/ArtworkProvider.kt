package com.taehagen.spotifygood.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Serves artwork as `content://<applicationId>.artwork/img?u=<url-encoded image url>` for
 * Android Auto / AAOS (which require local URIs) and other media controllers
 * (docs/ARCHITECTURE.md §9.4).
 *
 * Only Spotify CDN images (https, hosts `scdn.co` / `spotifycdn.com` and their subdomains) and files inside the
 * app's offline images directory are served, so the exported provider cannot be abused to read
 * other files or fetch arbitrary URLs. Images come from the Coil disk cache when present, from the
 * offline image store when downloaded, otherwise they are fetched through the app's ImageLoader
 * (blocking a binder thread of the provider, never the main thread).
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        val context = context ?: throw FileNotFoundException(uri.toString())
        return when (val source = sourceOf(context, uri)) {
            is File -> openLocal(source)
            is String -> openRemote(context, source)
            else -> throw FileNotFoundException("Unsupported artwork uri $uri")
        }
    }

    private fun openLocal(file: File): ParcelFileDescriptor {
        if (!file.isFile) throw FileNotFoundException(file.name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun openRemote(context: Context, url: String): ParcelFileDescriptor {
        offlineImageFor(context, url)?.let { return openLocal(it) }
        cachedFile(context, url)?.let { return it }

        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(FETCH_SIZE_PX)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .allowHardware(false)
            .build()
        val result = runBlocking(Dispatchers.IO) { withTimeoutOrNull(FETCH_TIMEOUT_MS) { loader.execute(request) } }
        // The fetch populated the disk cache: serve the original bytes.
        cachedFile(context, url)?.let { return it }
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: throw FileNotFoundException(url)
        return pipeOf(bitmap)
    }

    private fun cachedFile(context: Context, url: String): ParcelFileDescriptor? {
        val cache = SingletonImageLoader.get(context).diskCache ?: return null
        return try {
            cache.openSnapshot(url)?.use { snapshot ->
                // The descriptor keeps the file readable even if Coil evicts it afterwards.
                ParcelFileDescriptor.open(File(snapshot.data.toString()), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Disk cache read failed", e)
            null
        }
    }

    /** Last resort when no disk cache is configured: stream a re-encoded copy through a pipe. */
    private fun pipeOf(bitmap: Bitmap): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "artwork-pipe", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Artwork pipe closed early", e)
            }
        }
        return read
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val TAG = "ArtworkProvider"
        private const val PATH = "img"
        private const val PARAM_URL = "u"
        private const val MIME_TYPE = "image/jpeg"
        private const val FETCH_SIZE_PX = 640
        private const val FETCH_TIMEOUT_MS = 15_000L
        private const val JPEG_QUALITY = 90
        private val OFFLINE_IMAGES = listOf("offline", "images")

        fun authority(context: Context): String = "${context.packageName}.artwork"

        /**
         * `content://` uri serving [url] (an https Spotify CDN image or a local offline image path),
         * or null if [url] is not servable.
         */
        fun artworkUri(context: Context, url: String?): Uri? {
            if (url.isNullOrBlank()) return null
            val normalized = when {
                url.startsWith("/") -> Uri.fromFile(File(url)).toString()
                else -> url
            }
            if (!isAllowedUrl(normalized)) return null
            return Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(PATH)
                .appendQueryParameter(PARAM_URL, normalized)
                .build()
        }

        /**
         * What an artwork uri points to: a [File] (offline image) or an https URL [String];
         * null when the uri is not one of ours or not allowed.
         */
        internal fun sourceOf(context: Context, uri: Uri): Any? {
            if (uri.scheme != "content" || uri.authority != authority(context)) return null
            if (uri.pathSegments.firstOrNull() != PATH) return null
            val url = uri.getQueryParameter(PARAM_URL) ?: return null
            val parsed = Uri.parse(url)
            return when (parsed.scheme) {
                "https" -> url.takeIf { isAllowedHost(parsed.host) }
                "file" -> parsed.path?.let { File(it) }?.takeIf { isInsideOfflineImages(context, it) }
                else -> null
            }
        }

        internal fun isAllowedUrl(url: String): Boolean {
            val parsed = Uri.parse(url)
            return when (parsed.scheme) {
                "https" -> isAllowedHost(parsed.host)
                "file" -> parsed.path?.contains("/offline/images/") == true
                else -> false
            }
        }

        /** Spotify's image CDNs only. */
        internal fun isAllowedHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return h == "scdn.co" || h.endsWith(".scdn.co") || h == "spotifycdn.com" || h.endsWith(".spotifycdn.com")
        }

        private fun offlineDirs(context: Context): List<File> = listOf(context.noBackupFilesDir, context.filesDir)
            .map { base -> OFFLINE_IMAGES.fold(base) { dir, name -> File(dir, name) } }

        private fun isInsideOfflineImages(context: Context, file: File): Boolean {
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
            return offlineDirs(context).any { dir ->
                val root = runCatching { dir.canonicalFile }.getOrNull() ?: return@any false
                canonical.parentFile?.let { it == root || it.path.startsWith(root.path + File.separator) } == true
            }
        }

        /** Downloaded copy of a CDN image (`offline/images/<image id>`), if any. */
        private fun offlineImageFor(context: Context, url: String): File? {
            val id = Uri.parse(url).lastPathSegment?.takeIf { it.matches(Regex("[0-9a-fA-F]{16,64}")) } ?: return null
            return offlineDirs(context).map { File(it, id) }.firstOrNull { it.isFile }
        }
    }
}

/** `content://` artwork uri for [url] (see [ArtworkProvider.artworkUri]). */
fun artworkUri(context: Context, url: String?): Uri? = ArtworkProvider.artworkUri(context, url)
