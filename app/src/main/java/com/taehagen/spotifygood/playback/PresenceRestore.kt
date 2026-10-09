package com.taehagen.spotifygood.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.taehagen.spotifygood.App
import com.taehagen.spotifygood.MainActivity
import com.taehagen.spotifygood.Notifications
import com.taehagen.spotifygood.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Brings the opt-in Connect presence back after the process was gone with no way of its own to
 * return (docs/ARCHITECTURE.md §9.4): a reboot (`BOOT_COMPLETED`, after the first unlock) and an
 * app update (`MY_PACKAGE_REPLACED`). Both broadcasts exempt the app from the background
 * foreground-service start ban, and presence is a `connectedDevice` foreground service, which
 * Android 15 still allows from boot (it refuses `mediaPlayback` and `dataSync` there).
 * `LOCKED_BOOT_COMPLETED` is not used: the credentials and settings live in credential-encrypted
 * storage, readable only after the first unlock, which `BOOT_COMPLETED` follows. Nothing is held
 * awake: the receiver reads the setting and the stored login (bounded, inside its broadcast) and
 * starts the service; presence itself then holds the engine, as when started from the app. When
 * the start is refused (a restricted app, an OEM's own limits) a notification asks the user to
 * open the app once; it goes away when presence is up again.
 */
internal object PresenceRestore {
    /** The broadcasts presence is restored after. */
    val RESTORE_POINTS: Set<String> = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)

    enum class Decision {
        /** Start the service for presence. */
        RESTORE,

        /** Nothing to restore (not one of [RESTORE_POINTS], presence off, logged out, already up). */
        SKIP,

        /** Presence is on but the stored login could not be read in time: ask the user to open the app. */
        NOTIFY,
    }

    /**
     * What to do after [action]: [connectPresence] the setting, [loggedIn] the stored login (null:
     * not read in time), [presenceUp] presence already in the foreground.
     */
    fun decide(action: String?, connectPresence: Boolean?, loggedIn: Boolean?, presenceUp: Boolean): Decision = when {
        action !in RESTORE_POINTS || presenceUp -> Decision.SKIP
        connectPresence != true -> Decision.SKIP
        loggedIn == null -> Decision.NOTIFY
        loggedIn -> Decision.RESTORE
        else -> Decision.SKIP
    }

    /** "Open SpotifyGood to stay available for Spotify Connect", opening the app on a tap. */
    fun postNotice(context: Context) {
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
        val open = PendingIntent.getActivity(
            app,
            REQUEST_OPEN,
            MainActivity.launchIntent(app),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, Notifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.playback_presence_restore_title))
            .setContentText(app.getString(R.string.playback_presence_restore_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(R.string.playback_presence_restore_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            notifications.notify(Notifications.ID_PRESENCE_RESTORE, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post the presence notice", e)
        }
    }

    /** Presence is up again (or no longer wanted). */
    fun cancelNotice(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(Notifications.ID_PRESENCE_RESTORE)
    }

    private const val TAG = "PresenceRestore"
    private const val REQUEST_OPEN = 5
}

/** Restores Connect presence after a reboot or an app update ([PresenceRestore]); not exported. */
class PresenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in PresenceRestore.RESTORE_POINTS) return
        val app = context.applicationContext
        val graph = (app as App).graph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                // Well inside the broadcast's window (its foreground-start exemption lasts ~10 s).
                val presence = withTimeoutOrNull(READ_TIMEOUT_MS) { graph.settings.awaitLoaded().connectPresence }
                val loggedIn = if (presence == true) {
                    withTimeoutOrNull(READ_TIMEOUT_MS) {
                        graph.engine.awaitReady()
                        graph.engine.isLoggedIn.value
                    }
                } else {
                    null
                }
                when (PresenceRestore.decide(action, presence, loggedIn, PlaybackService.isPresenceForeground)) {
                    PresenceRestore.Decision.RESTORE -> start(app)
                    PresenceRestore.Decision.NOTIFY -> PresenceRestore.postNotice(app)
                    PresenceRestore.Decision.SKIP -> Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "Presence restore failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun start(app: Context) {
        try {
            ContextCompat.startForegroundService(
                app,
                PlaybackService.internalIntent(app, PlaybackService.ACTION_START_PRESENCE)
                    .putExtra(PlaybackService.EXTRA_FOREGROUND_START, true),
            )
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+): a restricted app, OEM limits.
            Log.w(TAG, "Presence could not be restored", e)
            PresenceRestore.postNotice(app)
        } catch (e: SecurityException) {
            Log.w(TAG, "Presence could not be restored", e)
            PresenceRestore.postNotice(app)
        }
    }

    private companion object {
        const val TAG = "PresenceRestore"
        const val READ_TIMEOUT_MS = 3_000L
    }
}
