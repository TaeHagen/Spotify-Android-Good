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
import com.taehagen.spotifygood.MainActivity
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
internal class PresenceController(
    private val service: Service,
    private val graph: AppGraph,
    /** The app is visible (a visible app may go foreground although restricted). */
    private val isAppVisible: () -> Boolean = { false },
) {
    private val notifications = NotificationManagerCompat.from(service)
    private var holder: EngineHolder? = null

    /** Presence wanted (setting on and logged in). */
    var isEnabled: Boolean = false
        private set

    /** The presence notification is currently the service's foreground notification. */
    var isForeground: Boolean = false
        private set

    /**
     * The system ignored the last presence foreground without a word (a background-restricted
     * app): the notification updates do not ask again ([showForeground]`(retry = false)`); the
     * next start of presence ([enable]: the app visible again, the setting) does.
     */
    private var ignored = false

    /** Enables presence; [showNow] puts the presence notification in the foreground right away. */
    fun enable(showNow: Boolean) {
        if (!isEnabled) {
            isEnabled = true
            if (holder == null) holder = graph.engine.acquire(HolderType.PRESENCE)
        }
        if (showNow && !isForeground) showForeground(retry = true)
    }

    /**
     * Disables presence and releases the holder. Returns true if the presence notification was the
     * foreground notification (the caller then lets Media3 decide what to show).
     */
    fun disable(): Boolean {
        isEnabled = false
        ignored = false
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
     * false if the system refused (background start restrictions, with an exception or, for a
     * background-restricted app, silently: [PresenceRestore.foregroundTookEffect]); presence then
     * waits for the next time the app is visible, and holds no engine meanwhile. After a silent
     * refusal only a [retry] (a new start of presence) asks again, not every notification update.
     */
    fun showForeground(retry: Boolean = false): Boolean {
        if (!isEnabled) return false
        if (ignored && !retry) return false
        ensureChannel()
        return try {
            ServiceCompat.startForeground(
                service,
                Notifications.ID_PRESENCE,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
            if (!tookEffect()) {
                // Ignored (battery use "Restricted"): no foreground, nothing advertised on Connect
                // for the minute until the system stops the service. Not up, so the app starts it
                // again when it is visible.
                Log.w(TAG, "Presence foreground ignored (background restricted)")
                ignored = true
                notForeground()
                return false
            }
            ignored = false
            if (holder == null) holder = graph.engine.acquire(HolderType.PRESENCE)
            isForeground = true
            PlaybackService.isPresenceForeground = true
            // Up again: the "open the app" notice of a refused restore is moot.
            PresenceRestore.cancelNotice(service)
            // The media notification (if any) is replaced while idle.
            notifications.cancel(Notifications.ID_PLAYBACK)
            // Keep the service started: it must outlive unbinding controllers.
            runCatching { service.startService(PlaybackService.internalIntent(service, null)) }
                .onFailure { Log.w(TAG, "Could not keep the service started", it) }
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
            Log.w(TAG, "Presence foreground not allowed now", e)
            notForeground()
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Presence foreground refused", e)
            notForeground()
            false
        }
    }

    /** The system recorded the presence foreground ([PresenceRestore.foregroundTookEffect]). */
    private fun tookEffect(): Boolean {
        val recorded = if (Build.VERSION.SDK_INT >= 29) runCatching { service.foregroundServiceType }.getOrDefault(0) else 0
        return PresenceRestore.foregroundTookEffect(
            Build.VERSION.SDK_INT,
            recorded,
            PresenceRestore.isBackgroundRestricted(service),
            isAppVisible(),
        )
    }

    /**
     * Presence is not in the foreground: the flags say so, and no engine is held for it unless
     * the app is visible (the next start of presence takes it again).
     */
    private fun notForeground() {
        isForeground = false
        PlaybackService.isPresenceForeground = false
        if (!isAppVisible()) {
            holder?.release()
            holder = null
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
        val open = PendingIntent.getActivity(
            service,
            REQUEST_OPEN,
            MainActivity.launchIntent(service),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
