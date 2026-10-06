package com.taehagen.spotifygood.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taehagen.spotifygood.App
import kotlinx.coroutines.CancellationException

/**
 * Daily periodic sync of downloaded collections (unique periodic work `download-sync`, network +
 * battery not low). Enqueued while downloaded collections exist and cancelled when the last one is
 * removed (see [DownloadManager]). Plain background work, no foreground service: syncing is a few
 * metadata requests; any newly queued items are downloaded by the regular download work.
 */
class DownloadSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as App).graph.downloads
        return try {
            val synced = !manager.hasCollections() || manager.sync()
            if (synced || runAttemptCount >= MAX_RETRIES) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download sync failed", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val TAG = "DownloadSyncWorker"
        const val MAX_RETRIES = 3
    }
}
