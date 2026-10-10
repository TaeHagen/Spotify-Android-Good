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
 * A run that ends because the whole queue waits until a known time ([RunOutcome.PAUSED]: Spotify's
 * key limit, a rate limit) schedules one delayed request for then and succeeds; in the background
 * on battery the queue runs in bursts this way. On API 34+ with the app visible the worker hands the
 * queue to a user-initiated job instead ([DownloadManager.handToUserInitiatedJob]).
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val manager: DownloadManager get() = (applicationContext as App).graph.downloads

    override suspend fun getForegroundInfo(): ForegroundInfo =
        manager.notifications.foregroundInfo(manager.notifications.progress(null, 0, 0, 0f))

    override suspend fun doWork(): Result {
        val manager = manager
        // API 34+ with the app visible: a user-initiated job takes the queue (no 10-minute limit, no
        // job quota, pauses waited out in it).
        if (manager.handToUserInitiatedJob()) return Result.success()
        val foreground = try {
            setForeground(getForegroundInfo())
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: started from the background.
            Log.i(TAG, "Running downloads without a foreground service", e)
            false
        }
        val host = object : DownloadHost {
            // An ordinary job (no foreground service) is stopped after about 10 minutes: bursts only.
            override val longRunning = foreground

            override suspend fun updateNotification(notification: Notification) {
                if (foreground) manager.notifications.updateProgress(notification)
            }
        }
        val outcome = try {
            manager.runner.runHosted(host)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e // stopped by WorkManager
            RunOutcome.STOPPED // run stopped by removeAll / logout
        } catch (e: Exception) {
            Log.e(TAG, "Download run failed", e)
            RunOutcome.RESCHEDULE
        }
        // Bounded retries: afterwards the queue waits for the next enqueue / app start / daily sync.
        // A pause until a known time is resumed then, by a new request (no backoff, no retry spent).
        return when (DownloadRules.workerStep(outcome, runAttemptCount, MAX_RETRIES)) {
            DownloadRules.WorkerStep.SUCCESS -> Result.success()
            DownloadRules.WorkerStep.RETRY -> Result.retry()
            DownloadRules.WorkerStep.RESUME -> {
                // The app came to the front during the burst: a user-initiated job goes on instead.
                if (!manager.handToUserInitiatedJob()) manager.scheduleResume()
                Result.success()
            }
        }
    }

    private companion object {
        const val TAG = "DownloadWorker"
        const val MAX_RETRIES = 8
    }
}
