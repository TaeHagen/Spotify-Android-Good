package com.taehagen.spotifygood.download

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.taehagen.spotifygood.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * WorkManager host of [DownloadRunner] (unique work `downloads`): used below API 34, and on API 34+
 * when downloads must be (re)scheduled while the app is not visible.
 *
 * Runs as a dataSync foreground service when allowed; if the app is in the background and the FGS
 * start is refused, it continues as ordinary work within the job window. When WorkManager stops
 * the worker (constraints lost, Android 15 dataSync `onTimeout` after 6 h, quota) the coroutine is
 * cancelled, the runner requeues its item (partial files are kept) and WorkManager reschedules.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val manager: DownloadManager get() = (applicationContext as App).graph.downloads

    override suspend fun getForegroundInfo(): ForegroundInfo =
        manager.notifications.foregroundInfo(manager.notifications.progress(null, 0, 0, 0f))

    override suspend fun doWork(): Result {
        val manager = manager
        val foreground = try {
            setForeground(getForegroundInfo())
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: started from the background.
            Log.i(TAG, "Running downloads without a foreground service", e)
            false
        }
        val host = object : DownloadHost {
            override suspend fun updateNotification(notification: Notification) {
                if (foreground) manager.notifications.updateProgress(notification)
            }
        }
        val outcome = try {
            manager.runner.run(host)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e // stopped by WorkManager
            RunOutcome.STOPPED // run stopped by removeAll / logout
        } catch (e: Exception) {
            Log.e(TAG, "Download run failed", e)
            RunOutcome.RESCHEDULE
        }
        return when (outcome) {
            RunOutcome.FINISHED, RunOutcome.STOPPED -> Result.success()
            // Bounded: afterwards the queue waits for the next enqueue / app start / daily sync.
            RunOutcome.RESCHEDULE -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val TAG = "DownloadWorker"
        const val MAX_RETRIES = 8
    }
}
