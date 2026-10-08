package com.taehagen.spotifygood.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.taehagen.spotifygood.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Where downloads can be stored (docs §9.7): the app's internal storage, or a mounted removable
 * volume (SD card) through its app-specific directory (`getExternalFilesDirs`: no storage
 * permission; other apps cannot read it on API 30+, and the audio is encrypted anyway). A location
 * is named by its volume UUID ([INTERNAL] for internal storage), its downloads live under
 * `<root>/audio` and `<root>/images`. The data key ([KeyVault]) always stays on internal storage.
 * Blocking methods (StatFs, volume lookups): call off the main thread.
 */
internal class DownloadLocations(private val context: Context) {
    val internalRoot: File = File(context.noBackupFilesDir, "offline")

    /** A location downloads can go to right now, with its space. */
    data class Location(val id: String, val root: File, val label: String, val removable: Boolean, val freeBytes: Long, val totalBytes: Long)

    /** Internal storage and the mounted removable volumes. */
    fun list(): List<Location> {
        val out = ArrayList<Location>()
        out += Location(INTERNAL, internalRoot, context.getString(R.string.data_dl_location_internal), false, freeBytes(internalRoot), totalBytes(internalRoot))
        val storage = context.getSystemService(StorageManager::class.java)
        val dirs = try {
            context.getExternalFilesDirs(null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Listing the storage volumes failed", e)
            emptyArray()
        }
        for (dir in dirs) {
            if (dir == null || !isRemovable(dir) || !isMounted(dir)) continue
            val volume = runCatching { storage?.getStorageVolume(dir) }.getOrNull()
            val id = volume?.uuid ?: dir.path
            val label = volume?.getDescription(context) ?: context.getString(R.string.data_dl_location_card)
            out += Location(id, File(dir, "offline"), label, true, freeBytes(dir), totalBytes(dir))
        }
        return out
    }

    /** The root of location [id] while it is available; null for a volume that is not mounted. */
    fun rootFor(id: String): File? = if (id == INTERNAL) internalRoot else list().firstOrNull { it.id == id }?.root

    /** Whether the files under location root [root] can be read now (internal storage always). */
    fun isAvailable(root: String): Boolean {
        if (root == DownloadRules.normalizeRoot(internalRoot.path)) return true
        return isMounted(File(root))
    }

    /**
     * Emits whenever a volume is mounted, unmounted, ejected or removed (the system's media
     * broadcasts, protected: only the system sends them).
     */
    fun changes(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            MEDIA_ACTIONS.forEach(::addAction)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
        }
    }

    /** Free bytes at [dir] (or the nearest existing parent); unknown → [Long.MAX_VALUE]. */
    fun freeBytes(dir: File): Long = statFs(dir)?.availableBytes ?: Long.MAX_VALUE

    private fun totalBytes(dir: File): Long = statFs(dir)?.totalBytes ?: 0L

    private fun statFs(dir: File): StatFs? {
        var existing: File? = dir
        while (existing != null && !existing.exists()) existing = existing.parentFile
        return try {
            StatFs((existing ?: dir).path)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun isMounted(dir: File): Boolean =
        try {
            Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
        } catch (e: RuntimeException) {
            false
        }

    private fun isRemovable(dir: File): Boolean =
        try {
            Environment.isExternalStorageRemovable(dir)
        } catch (e: IllegalArgumentException) {
            false
        }

    companion object {
        /** Location id of internal storage. */
        const val INTERNAL = ""
        private const val TAG = "DownloadLocations"
        private val MEDIA_ACTIONS = listOf(
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
        )
    }
}
