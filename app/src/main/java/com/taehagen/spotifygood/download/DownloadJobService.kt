package com.taehagen.spotifygood.download

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.util.Log
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * User-initiated data transfer job (API 34+) that drains the download queue with [DownloadRunner].
 *
 * Scheduled by [DownloadManager] only while the app is visible. Not subject to the dataSync FGS
 * 6 h limit nor the Android 16 job quota; the system shows the job notification set in
 * [onStartJob] (updated as the run progresses). [onStopJob] (constraints lost, system pressure)
 * cancels the run cleanly — partial `.part` files are kept and resumed — and asks for a reschedule.
 * A "Stop" from Task Manager kills the process without callbacks; pending rows are picked up again
 * the next time the app starts.
 */
class DownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var run: Job? = null
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val manager = (application as App).graph.downloads
        stopped = false
        manager.jobExecuting = true
        // Must happen right away for user-initiated jobs.
        setNotification(
            params,
            Notifications.ID_DOWNLOADS,
            manager.notifications.progress(null, 0, 0, 0f),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val host = object : DownloadHost {
            override suspend fun updateNotification(notification: Notification) {
                if (!stopped) runCatching { setNotification(params, Notifications.ID_DOWNLOADS, notification, JOB_END_NOTIFICATION_POLICY_REMOVE) }
            }

            override fun reportTransferred(bytes: Long) {
                if (!stopped) runCatching { updateTransferredNetworkBytes(params, bytes, 0) }
            }
        }
        run = scope.launch {
            val outcome = try {
                manager.runner.run(host)
            } catch (e: CancellationException) {
                // Our scope cancelled (onStopJob / onDestroy) → propagate; a run stopped by removeAll /
                // logout while the job itself is still alive → finish without reschedule.
                if (!currentCoroutineContext().isActive) throw e
                RunOutcome.STOPPED
            } catch (e: Exception) {
                Log.e(TAG, "Download run failed", e)
                RunOutcome.RESCHEDULE
            }
            manager.jobExecuting = false
            if (!stopped) jobFinished(params, outcome == RunOutcome.RESCHEDULE)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        (application as App).graph.downloads.jobExecuting = false
        run?.cancel()
        // Remaining items continue when the system runs the job again (with backoff).
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DownloadJobService"
    }
}
