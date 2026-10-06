package com.taehagen.spotifygood.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.taehagen.spotifygood.Notifications
import com.taehagen.spotifygood.R

/**
 * The "Tap to resume" alert (docs/ARCHITECTURE.md §9.4) shown when local playback had to be paused
 * because the app could not start its media foreground service from the background. Tapping it
 * starts [PlaybackService] with [PlaybackService.ACTION_RESUME]; the tap's temporary allowlist lets
 * the service go foreground. Posted by the service or by [PlaybackCoordinator] (also while the
 * service does not exist); cancelled when playback runs again.
 */
internal object ResumeAlert {
    private const val TAG = "ResumeAlert"
    private const val REQUEST_RESUME = 2

    /** The alert is (probably) showing. */
    @Volatile var isPosted: Boolean = false
        private set

    fun post(context: Context, title: String?) {
        val app = context.applicationContext
        val notifications = NotificationManagerCompat.from(app)
        if (!notifications.areNotificationsEnabled()) return
        if (notifications.getNotificationChannelCompat(Notifications.CHANNEL_ALERTS) == null) {
            notifications.createNotificationChannel(
                NotificationChannelCompat.Builder(Notifications.CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(app.getString(R.string.playback_alerts_channel_name))
                    .build(),
            )
        }
        val resume = PendingIntent.getService(
            app,
            REQUEST_RESUME,
            Intent(app, PlaybackService::class.java).setAction(PlaybackService.ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: app.getString(R.string.playback_resume_title))
            .setContentText(app.getString(R.string.playback_resume_text))
            .setContentIntent(resume)
            .addAction(R.drawable.pb_ic_play, app.getString(R.string.playback_resume_text), resume)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try {
            notifications.notify(Notifications.ID_RESUME_ALERT, notification)
            isPosted = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post the resume notification", e)
        }
    }

    fun cancel(context: Context) {
        isPosted = false
        NotificationManagerCompat.from(context.applicationContext).cancel(Notifications.ID_RESUME_ALERT)
    }
}
