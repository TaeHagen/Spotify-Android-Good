package com.taehagen.spotifygood.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Wall-clock bound of the playback service's paused lifetime (docs/ARCHITECTURE.md §9.4, §10).
 * Media3 leaves the foreground 10 minutes after a pause on a Handler timer, and Handler time is
 * uptime: with the screen off the phone deep-sleeps between the session's keep-alive packets, so
 * those 10 minutes could stretch to hours of wall time, with the service in the foreground and the
 * engine connected. This counts `elapsedRealtime` instead (it advances in deep sleep).
 *
 * [update] with whether anything keeps the service busy (playing or loading, here or on the
 * mirrored device, or Connect presence): it returns the deadline to arm, null to cancel. Once
 * [isDue], the service lets go ([expire]) and the deadline is not armed again until the service
 * becomes busy again, or a playback command ([restart]) starts a new window. Thread-safe.
 */
internal class PausedIdle(private val timeoutMs: Long = TIMEOUT_MS) {
    /** When the service last became idle (elapsedRealtime); null while busy. */
    private var since: Long? = null
    private var expired = false

    @Synchronized fun update(busy: Boolean, now: Long): Long? {
        if (busy) {
            since = null
            expired = false
            return null
        }
        val start = since ?: now.also { since = it }
        return if (expired) null else start + timeoutMs
    }

    @Synchronized fun isDue(busy: Boolean, now: Long): Boolean {
        val start = since ?: return false
        return !busy && !expired && now - start >= timeoutMs
    }

    /** The service let go after the deadline: nothing more to arm while it stays idle. */
    @Synchronized fun expire() {
        expired = true
    }

    /** A playback command (a seek while paused, a resume being set up): idle counts from [now]. */
    @Synchronized fun restart(now: Long) {
        since = now
        expired = false
    }

    companion object {
        /** Media3's own pause timeout, as wall time. */
        const val TIMEOUT_MS = 10 * 60 * 1000L
    }
}

/**
 * The [PausedIdle] deadline as an inexact, non-wakeup `ELAPSED_REALTIME` alarm: it adds no
 * wake-up of its own and is delivered at the next one after the deadline (the session's next
 * packet, any other app's). Allow-while-idle, since a plain alarm waits for a Doze maintenance
 * window, which can be hours away.
 */
internal object PausedIdleAlarm {
    private const val TAG = "PausedIdleAlarm"
    private const val REQUEST = 31
    private const val ACTION = "com.taehagen.spotifygood.playback.action.PAUSED_IDLE"

    fun schedule(context: Context, atElapsedMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, atElapsedMs, pendingIntent(context))
        } catch (e: RuntimeException) {
            // Alarm limits or a dead system service: Media3's own (uptime) timeout remains.
            Log.w(TAG, "Cannot schedule the paused-idle alarm", e)
        }
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        REQUEST,
        Intent(context.applicationContext, PausedIdleAlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Delivers the paused-idle alarm to the running [PlaybackService] (not exported; main thread). */
class PausedIdleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaybackService.onPausedIdleAlarm()
    }
}
