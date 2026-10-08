package com.taehagen.spotifygood.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * On-disk layout of downloads (docs/ARCHITECTURE.md §9.7): under the root of a download location
 * ([DownloadLocations]: internal `noBackupFilesDir/offline`, or `<SD card app dir>/offline`),
 * encrypted audio in `audio/<fileIdHex>` (+ `.part` while incomplete, resumed natively) and cover
 * art in `images/<imageHex>.jpg`. Both directories belong to downloads only. New downloads go to
 * the chosen location ([target]); rows name their files by absolute path, so downloads stay
 * playable wherever they are until they are moved. `noBackupFilesDir/offline/datakey.bin` holds the
 * data key that seals their audio keys ([KeyVault]) and never moves. Files can be shared by several
 * downloads (see [DownloadRules.audioToDelete]). All methods do blocking file I/O: call them off
 * the main thread.
 */
internal class DownloadStorage(context: Context) {
    val locations = DownloadLocations(context)

    /** The downloads' data key, sealed with the Keystore ([KeyVault]); goes with [deleteAll]. */
    val keyFile = File(locations.internalRoot, "datakey.bin")

    /** Where new downloads go. */
    sealed interface Target {
        /** The setting was not read yet. */
        data object Unresolved : Target

        /** The chosen location, available at [root]. */
        data class Ready(val id: String, val root: File) : Target

        /** The chosen location [id] (a card) is not mounted: nothing downloads until it is back. */
        data class Missing(val id: String) : Target
    }

    private val _target = MutableStateFlow<Target>(Target.Unresolved)
    val target: StateFlow<Target> = _target.asStateFlow()

    /** Downloads are being moved between locations: garbage collection waits (copies are not referenced yet). */
    @Volatile var relocating = false

    fun setTarget(target: Target) {
        _target.value = target
    }

    /**
     * The root new downloads go to, once the setting was read; null while the chosen card is
     * missing. Internal storage if it was not resolved in time (never expected: the manager resolves
     * it at start); a download there is moved later.
     */
    suspend fun awaitRoot(): File? {
        val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { target.first { it !is Target.Unresolved } }
            ?: return locations.internalRoot
        return (resolved as? Target.Ready)?.root
    }

    fun audioDir(root: File) = File(root, AUDIO)

    fun imageDir(root: File) = File(root, IMAGES)

    fun ensureDirs(root: File) {
        audioDir(root).mkdirs()
        imageDir(root).mkdirs()
    }

    /** Free bytes on the volume holding [root] (unknown → [Long.MAX_VALUE]). */
    fun freeBytes(root: File): Long = locations.freeBytes(root)

    /**
     * Availability of location roots ([DownloadRules.rootOf]) for one pass over many rows: each
     * root is looked up once.
     */
    fun availability(): (String) -> Boolean {
        val known = HashMap<String, Boolean>()
        return { root -> known.getOrPut(root) { locations.isAvailable(root) } }
    }

    /**
     * Deletes a completed audio file; the caller checked that no remaining download uses it. A
     * `.part` of the same file belongs to whichever download is (or was) writing it and is left to
     * [collectGarbage]. A file on a card that is not mounted stays (collected once it is back).
     */
    fun deleteAudio(path: String?) {
        if (!path.isNullOrEmpty()) delete(File(path))
    }

    fun deleteImage(path: String?) {
        if (!path.isNullOrEmpty()) delete(File(path))
    }

    /** Every location's downloads and the data key (a card that is not mounted is cleaned when back). */
    fun deleteAll() {
        val roots = (locations.list().map { it.root } + locations.internalRoot).distinct()
        roots.forEach { root ->
            if (root.exists() && !root.deleteRecursively()) Log.w(TAG, "Could not delete all of $root")
        }
    }

    /**
     * Removes files no download row refers to, on every available location: audio no row has as
     * its path there ([paths]), files and `.part` files an unfinished row may resume
     * ([unfinishedFileIds]: failed and cancelled rows resume theirs when retried) and unreferenced
     * images ([DownloadRules.audioGarbage]). Only call this while no download can be in flight and
     * nothing is [relocating] (a file being written, copied or just reused is not referenced yet).
     * A card that is not mounted is left alone. Returns the number of bytes freed.
     */
    fun collectGarbage(paths: Collection<String>, unfinishedFileIds: Collection<String>, referencedImages: Collection<String>): Long {
        var freed = 0L
        for (location in locations.list()) {
            val root = DownloadRules.normalizeRoot(location.root.path)
            val audio = audioDir(location.root).listFiles()?.filter { it.isFile }.orEmpty()
            val here = DownloadRules.pathsUnder(root, paths)
            val garbage = DownloadRules.audioGarbage(audio.map { it.name }, here, unfinishedFileIds, unfinishedFileIds).toHashSet()
            audio.forEach { file ->
                if (file.name in garbage) freed += sizeAndDelete(file)
            }
            val imageNames = DownloadRules.pathsUnder(root, referencedImages).mapTo(HashSet()) { File(it).name }
            imageDir(location.root).listFiles()?.forEach { file ->
                if (file.isFile && file.name !in imageNames) freed += sizeAndDelete(file)
            }
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

    companion object {
        private const val TAG = "DownloadStorage"
        private const val RESOLVE_TIMEOUT_MS = 10_000L
        const val AUDIO = "audio"
        const val IMAGES = "images"
    }
}
