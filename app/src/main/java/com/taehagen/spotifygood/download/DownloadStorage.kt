package com.taehagen.spotifygood.download

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File

/**
 * On-disk layout of downloads (docs/ARCHITECTURE.md §9.7): encrypted audio in
 * `noBackupFilesDir/offline/audio/<fileIdHex>` (+ `.part` while incomplete, resumed natively) and
 * cover art in `noBackupFilesDir/offline/images/<imageHex>`. Both directories belong to downloads
 * only. All methods do blocking file I/O: call them off the main thread.
 */
internal class DownloadStorage(context: Context) {
    private val root = File(context.noBackupFilesDir, "offline")
    val audioDir = File(root, "audio")
    val imageDir = File(root, "images")

    fun ensureDirs() {
        audioDir.mkdirs()
        imageDir.mkdirs()
    }

    /** Free bytes on the partition holding downloads (unknown → [Long.MAX_VALUE]). */
    fun freeBytes(): Long = try {
        val dir = if (audioDir.exists()) audioDir else root.parentFile ?: root
        StatFs(dir.path).availableBytes
    } catch (e: IllegalArgumentException) {
        Long.MAX_VALUE
    }

    /** Deletes a downloaded file and its partial download. */
    fun deleteAudio(path: String?) {
        if (path.isNullOrEmpty()) return
        delete(File(path))
        delete(File("$path.part"))
    }

    fun deleteImage(path: String?) {
        if (!path.isNullOrEmpty()) delete(File(path))
    }

    fun deleteAll() {
        if (root.exists() && !root.deleteRecursively()) Log.w(TAG, "Could not delete all of $root")
    }

    /**
     * Removes files no download row refers to: unreferenced audio and images, plus every `.part`
     * file. Only call this while no download can be in flight and none is pending (nothing could
     * resume the partial files). Matching is by file name, so path aliases (`/data/user/0` vs
     * `/data/data`) do not matter. Returns the number of bytes freed.
     */
    fun collectGarbage(referencedAudio: Collection<String>, referencedImages: Collection<String>): Long {
        val audioNames = referencedAudio.mapTo(HashSet()) { File(it).name }
        val imageNames = referencedImages.mapTo(HashSet()) { File(it).name }
        var freed = 0L
        audioDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(PART_SUFFIX) || file.name !in audioNames)) freed += sizeAndDelete(file)
        }
        imageDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in imageNames) freed += sizeAndDelete(file)
        }
        if (freed > 0) Log.i(TAG, "Freed $freed bytes of orphaned download files")
        return freed
    }

    private fun sizeAndDelete(file: File): Long {
        val size = file.length()
        return if (delete(file)) size else 0
    }

    private fun delete(file: File): Boolean = !file.exists() || file.delete().also { ok ->
        if (!ok) Log.w(TAG, "Could not delete $file")
    }

    private companion object {
        const val TAG = "DownloadStorage"
        const val PART_SUFFIX = ".part"
    }
}
