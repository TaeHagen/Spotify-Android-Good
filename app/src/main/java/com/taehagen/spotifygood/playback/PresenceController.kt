package com.taehagen.spotifygood.playback

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.Notifications
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.engine.EngineHolder
import com.taehagen.spotifygood.engine.HolderType

/**
 * Opt-in "stay available for Spotify Connect" (docs/ARCHITECTURE.md §9.4, research §4.4).
 *
 * While enabled, the engine has a [HolderType.PRESENCE] holder and the [PlaybackService] stays in
 * the foreground: as `connectedDevice` with a min-importance "Available on Spotify Connect"
 * notification while idle, and as `mediaPlayback` (Media3's own notification) while playback is
 * ongoing. The service never leaves the foreground in between, so a remote "play on this phone"
 * can start audio from the background. Main thread only.
 */
internal class PresenceController(private val service: Service, private val graph: AppGraph) {
    private val notifications = NotificationManagerCompat.from(service)
    private var holder: EngineHolder? = null

    /** Presence wanted (setting on and logged in). */
    var isEnabled: Boolean = false
        private set

    /** The presence notification is currently the service's foreground notification. */
    var isForeground: Boolean = false
        private set

    /** Enables presence; [showNow] puts the presence notification in the foreground right away. */
    fun enable(showNow: Boolean) {
        if (!isEnabled) {
            isEnabled = true
            if (holder == null) holder = graph.engine.acquire(HolderType.PRESENCE)
        }
        if (showNow && !isForeground) showForeground()
    }

    /**
     * Disables presence and releases the holder. Returns true if the presence notification was the
     * foreground notification (the caller then lets Media3 decide what to show).
     */
    fun disable(): Boolean {
        isEnabled = false
        holder?.release()
        holder = null
        if (!isForeground) return false
        isForeground = false
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifications.cancel(Notifications.ID_PRESENCE)
        PlaybackService.isPresenceForeground = false
        return true
    }

    /**
     * Makes the presence notification the foreground notification (`connectedDevice`). Returns
     * false if the system refused (background start restrictions); presence then waits for the
     * next time the app is visible.
     */
    fun showForeground(): Boolean {
        if (!isEnabled) return false
        ensureChannel()
        return try {
            ServiceCompat.startForeground(
                service,
                Notifications.ID_PRESENCE,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
            isForeground = true
            PlaybackService.isPresenceForeground = true
            // The media notification (if any) is replaced while idle.
            notifications.cancel(Notifications.ID_PLAYBACK)
            // Keep the service started: it must outlive unbinding controllers.
            runCatching { service.startService(PlaybackService.internalIntent(service, null)) }
                .onFailure { Log.w(TAG, "Could not keep the service started", it) }
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
            Log.w(TAG, "Presence foreground not allowed now", e)
            isForeground = false
            PlaybackService.isPresenceForeground = false
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Presence foreground refused", e)
            isForeground = false
            PlaybackService.isPresenceForeground = false
            false
        }
    }

    /** Media3 took the foreground with the media notification (`mediaPlayback`). */
    fun onMediaForeground() {
        if (!isForeground) return
        isForeground = false
        PlaybackService.isPresenceForeground = false
        notifications.cancel(Notifications.ID_PRESENCE)
    }

    /** Service destroyed. */
    fun release() {
        disable()
        PlaybackService.isPresenceForeground = false
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getBroadcast(
            service,
            REQUEST_STOP,
            PlaybackActionReceiver.intent(service, PlaybackActionReceiver.ACTION_STOP_PRESENCE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = service.packageManager.getLaunchIntentForPackage(service.packageName)?.let { launch ->
            PendingIntent.getActivity(service, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(service, Notifications.CHANNEL_CONNECT_PRESENCE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(service.getString(R.string.playback_presence_title))
            .setContentText(service.getString(R.string.playback_presence_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.pb_ic_stop, service.getString(R.string.playback_presence_stop), stop)
            .build()
    }

    private fun ensureChannel() {
        if (notifications.getNotificationChannelCompat(Notifications.CHANNEL_CONNECT_PRESENCE) != null) return
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(Notifications.CHANNEL_CONNECT_PRESENCE, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(service.getString(R.string.playback_presence_channel_name))
                .setShowBadge(false)
                .build(),
        )
    }

    private companion object {
        const val TAG = "ConnectPresence"
        const val REQUEST_STOP = 11
        const val REQUEST_OPEN = 12
    }
}
