package com.taehagen.spotifygood.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.taehagen.spotifygood.Notifications
import com.taehagen.spotifygood.R

/**
 * Download notifications: the ongoing progress notification (foreground / user-initiated job
 * notification, [Notifications.ID_DOWNLOADS]) with a Cancel action, and the completion / failure
 * notification ([Notifications.ID_DOWNLOADS_DONE]).
 *
 * The Cancel action is a broadcast to a receiver registered only while a download run is active
 * ([registerCancelReceiver]); it works the same for the JobScheduler and the WorkManager path.
 */
internal class DownloadNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private val cancelAction = "${context.packageName}.action.CANCEL_DOWNLOADS"

    /** Creates the downloads channel if the app initializer has not (idempotent, cheap). */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(Notifications.CHANNEL_DOWNLOADS) != null) return
        system.createNotificationChannel(
            NotificationChannel(
                Notifications.CHANNEL_DOWNLOADS,
                context.getString(R.string.data_dl_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.data_dl_channel_description)
                setShowBadge(false)
            },
        )
    }

    /**
     * Ongoing progress: "[title]" plus "[done] of [total]"; the bar advances per item and within the
     * current item by [itemFraction]. `total <= 0` shows an indeterminate "Preparing" notification.
     */
    fun progress(title: String?, done: Int, total: Int, itemFraction: Float): Notification {
        ensureChannel()
        val builder = base(Notifications.CHANNEL_DOWNLOADS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .addAction(0, context.getString(R.string.data_dl_notif_cancel), cancelIntent())
        if (total <= 0) {
            builder.setContentTitle(context.getString(R.string.data_dl_notif_preparing)).setProgress(0, 0, true)
        } else {
            val shown = (done + 1).coerceAtMost(total)
            val scaled = ((done + itemFraction.coerceIn(0f, 1f)) * PROGRESS_SCALE).toInt()
            builder
                .setContentTitle(title ?: context.getString(R.string.data_dl_notif_title))
                .setContentText(context.getString(R.string.data_dl_notif_progress, shown, total))
                .setSubText(context.getString(R.string.data_dl_notif_title))
                .setProgress(total * PROGRESS_SCALE, scaled.coerceAtMost(total * PROGRESS_SCALE), false)
        }
        return builder.build()
    }

    /** WorkManager foreground info (dataSync type on API 29+). */
    fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_DOWNLOADS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_DOWNLOADS, notification)
        }

    /** Updates the ongoing notification in place (it is owned by the FGS / job). */
    fun updateProgress(notification: Notification) = notify(Notifications.ID_DOWNLOADS, notification)

    /** "N downloads complete" (and failures), shown when a run drained the queue. */
    fun showSummary(completed: Int, failed: Int) {
        if (completed <= 0 && failed <= 0) return
        ensureChannel()
        val res = context.resources
        val title = if (completed > 0) {
            res.getQuantityString(R.plurals.data_dl_done_title, completed, completed)
        } else {
            res.getQuantityString(R.plurals.data_dl_done_failed, failed, failed)
        }
        val builder = base(Notifications.CHANNEL_DOWNLOADS).setContentTitle(title).setAutoCancel(true)
        if (completed > 0 && failed > 0) builder.setContentText(res.getQuantityString(R.plurals.data_dl_done_failed, failed, failed))
        notify(Notifications.ID_DOWNLOADS_DONE, builder.build())
    }

    /** Downloads stopped for a reason the user has to act on (storage, account, …). */
    fun showStopped(message: String) {
        ensureChannel()
        val notification = base(Notifications.CHANNEL_DOWNLOADS)
            .setContentTitle(context.getString(R.string.data_dl_stopped_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        notify(Notifications.ID_DOWNLOADS_DONE, notification)
    }

    fun cancelAll() {
        manager.cancel(Notifications.ID_DOWNLOADS)
        manager.cancel(Notifications.ID_DOWNLOADS_DONE)
    }

    /** Registers the Cancel-action receiver for the duration of a run; pair with [unregister]. */
    fun registerCancelReceiver(onCancel: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == cancelAction) onCancel()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(cancelAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }

    fun unregister(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private fun base(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(contentIntent())
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CANCEL,
        Intent(cancelAction).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun contentIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification not allowed", e)
        }
    }

    private companion object {
        const val TAG = "DownloadNotifications"
        const val PROGRESS_SCALE = 100
        const val REQUEST_CANCEL = 3101
        const val REQUEST_OPEN = 3102
    }
}
