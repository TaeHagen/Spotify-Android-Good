package com.taehagen.spotifygood.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.taehagen.spotifygood.App

/**
 * [SleepWakeups] with AlarmManager (`ELAPSED_REALTIME_WAKEUP`, inexact: no exact-alarm
 * permission) and a short timed partial wake lock.
 *
 * Two alarms target the [SleepTimerAlarmReceiver]: a window from [SleepSchedule.LEAD_MS] before the
 * end up to the end, after which the CPU stays awake until the end (so the pause is on time), and an
 * allow-while-idle alarm at the end, which still fires (with network access) in Doze, where windowed
 * alarms are deferred.
 */
internal class AndroidSleepWakeups(context: Context) : SleepWakeups {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val wakeLock: PowerManager.WakeLock? = app.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpotifyGood:sleep-timer")
        ?.apply { setReferenceCounted(false) }

    override fun schedule(endsAtElapsedMs: Long) {
        val alarms = alarms ?: return
        val now = SystemClock.elapsedRealtime()
        try {
            alarms.setWindow(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SleepSchedule.windowStart(endsAtElapsedMs, now),
                SleepSchedule.windowLength(endsAtElapsedMs, now),
                pendingIntent(REQUEST_WINDOW),
            )
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, endsAtElapsedMs, pendingIntent(REQUEST_IDLE))
        } catch (e: RuntimeException) {
            // SecurityException (alarm limits) or a dead system service: the timer still runs.
            Log.w(TAG, "Cannot schedule the sleep timer wake-up", e)
        }
    }

    override fun cancel() {
        val alarms = alarms ?: return
        runCatching {
            alarms.cancel(pendingIntent(REQUEST_WINDOW))
            alarms.cancel(pendingIntent(REQUEST_IDLE))
        }
    }

    override fun holdAwake(ms: Long) {
        runCatching { wakeLock?.acquire(ms.coerceAtLeast(1)) }.onFailure { Log.w(TAG, "Wake lock failed", it) }
    }

    override fun release() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
    }

    private fun pendingIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        app,
        requestCode,
        Intent(app, SleepTimerAlarmReceiver::class.java).setAction(ACTION_WAKE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val TAG = "SleepTimerAlarms"
        const val ACTION_WAKE = "com.taehagen.spotifygood.playback.action.SLEEP_TIMER_WAKE"
        const val REQUEST_WINDOW = 21
        const val REQUEST_IDLE = 22
    }
}

/**
 * The sleep timer's wake-up alarm (not exported). The system holds a wake lock while this runs;
 * [SleepTimer.onWakeupAlarm] takes the timer's own timed wake lock (until the end plus the pause
 * request) before returning and pokes the timer, so no `goAsync` is needed. A timer that no longer
 * exists (process restarted) ignores it.
 */
class SleepTimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App ?: return
        app.graph.sleepTimer.onWakeupAlarm()
    }
}
